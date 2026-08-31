package com.gecesars.atxplan.data.regulatory

import android.content.Context
import com.gecesars.atxplan.data.anatel.AndroidAnatelBasicPlanCatalog
import com.gecesars.atxplan.data.dataset.CopernicusGeoTiffTerrainSource
import com.gecesars.atxplan.data.dataset.REGIONAL_DATA_DIRECTORY
import com.gecesars.atxplan.data.dataset.RegionalDataComposition
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogLimits
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyPlanner
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainProvenance
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainArtifactProvenance
import com.gecesars.atxplan.domain.dataset.RegionalDataType
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import com.gecesars.atxplan.domain.model.PlannerProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Android composition boundary for a bounded, local Brazil digital-TV regulatory study. */
class AndroidBrazilDigitalTvStudyRunner(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val regionalRepository = RegionalDataComposition.datasetRepository(applicationContext)
    private val catalog = AndroidAnatelBasicPlanCatalog(applicationContext)
    private val regionalRoot = File(applicationContext.noBackupFilesDir, REGIONAL_DATA_DIRECTORY)

    suspend fun run(
        project: PlannerProject,
        radiusKm: Double,
        referenceStateCode: String,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BrazilDigitalTvRegulatoryStudyResult {
        val normalizedState = referenceStateCode.trim().uppercase(Locale.ROOT)
        require(normalizedState.matches(Regex("[A-Z]{2}"))) {
            "The reference-state code must contain two letters."
        }
        val prepared = withContext(Dispatchers.IO) {
            val inventory = regionalRepository.loadInventory()
            val terrainRecords = inventory.artifacts.values
                .filter(::isReadyCopernicusTerrain)
                .sortedWith(
                    compareBy<RegionalInventoryRecord>(RegionalInventoryRecord::relativePath)
                        .thenByDescending { it.acquiredAt.orEmpty() },
                )
                .distinctBy(RegionalInventoryRecord::relativePath)
                .take(MAXIMUM_TERRAIN_ARTIFACTS)
            if (terrainRecords.isEmpty()) {
                throw IOException(
                    "No processed Copernicus GLO-30 terrain tile is ready. Use Data > Regional Data first.",
                )
            }
            val terrains = terrainRecords.map { record ->
                PreparedTerrain(record, resolveVerifiedArtifact(record))
            }
            val status = catalog.status()
            val snapshot = status.snapshot
                ?: throw IOException(
                    "No verified Anatel Basic Plan snapshot is ready. Use Data > Anatel Basic Plan first.",
                )
            val channel = projectDigitalTvChannel(project)
            val referenceRecords = buildList {
                for (candidateChannel in (channel - 1)..(channel + 1)) {
                    addAll(queryChannel(normalizedState, candidateChannel))
                }
            }.distinctBy { recordItem ->
                listOf(
                    recordItem.origin.name,
                    recordItem.sourceRowId.orEmpty(),
                    recordItem.provenance.entryName,
                    recordItem.provenance.sourceRowNumber.toString(),
                ).joinToString("\u0000")
            }
            PreparedInput(terrains, snapshot, referenceRecords)
        }

        return withContext(Dispatchers.Default) {
            val sources = prepared.terrains.map { terrain ->
                terrain to CopernicusGeoTiffTerrainSource(terrain.file)
            }
            try {
                val primary = sources.first()
                val record = primary.first.record
                val snapshot = record.sourceSnapshot
                BrazilDigitalTvRegulatoryStudyPlanner.calculate(
                    project = project,
                    radiusKm = radiusKm,
                    terrain = { latitude, longitude ->
                        coroutineContext.ensureActive()
                        sources.firstNotNullOfOrNull { (_, source) ->
                            source.elevationMeters(latitude, longitude)
                        }
                    },
                    terrainProvenance = RegulatoryTerrainProvenance(
                        datasetId = record.datasetId,
                        datasetTitle = snapshot.title,
                        dataType = snapshot.dataType.name,
                        relativePath = record.relativePath,
                        sha256 = checkNotNull(record.sha256),
                        acquiredAt = record.acquiredAt,
                        sourceUrl = record.effectiveUrl ?: record.requestedUrl,
                        licenseTitle = snapshot.license.title,
                        attribution = snapshot.license.attribution,
                        nominalResolutionM = checkNotNull(snapshot.nominalResolutionMeters),
                        sampleMethod = "nearest source pixel across a verified tile mosaic",
                        additionalArtifacts = sources.drop(1).map { (terrain, _) ->
                            val additional = terrain.record
                            RegulatoryTerrainArtifactProvenance(
                                relativePath = additional.relativePath,
                                sha256 = checkNotNull(additional.sha256),
                                acquiredAt = additional.acquiredAt,
                                artifactUrl = additional.effectiveUrl ?: additional.requestedUrl,
                            )
                        },
                    ),
                    referenceRecords = prepared.referenceRecords,
                    catalogSnapshot = prepared.catalogSnapshot,
                    isCancelled = { !coroutineContext.isActive },
                    onProgress = onProgress,
                )
            } finally {
                sources.asReversed().forEach { (_, source) -> source.close() }
            }
        }
    }

    private fun queryChannel(stateCode: String, channel: Int): List<AnatelBasicPlanRecord> {
        if (channel !in 1..999) return emptyList()
        val records = mutableListOf<AnatelBasicPlanRecord>()
        var offset = 0
        repeat(MAXIMUM_QUERY_PAGES) {
            val page = catalog.query(
                AnatelBasicPlanQuery(
                    service = AnatelBroadcastService.TELEVISION,
                    stateCode = stateCode,
                    channel = channel,
                    pageSize = AnatelBasicPlanCatalogLimits.MAX_PAGE_SIZE,
                    offset = offset,
                ),
            )
            records += page.records
            offset = page.nextOffset ?: return records
        }
        throw IOException("The bounded Anatel reference query exceeded $MAXIMUM_QUERY_PAGES pages.")
    }

    private fun resolveVerifiedArtifact(record: RegionalInventoryRecord): File {
        val normalizedRoot = regionalRoot.canonicalFile
        val candidate = File(normalizedRoot, record.relativePath).canonicalFile
        val rootPrefix = normalizedRoot.path.trimEnd(File.separatorChar) + File.separator
        if (candidate.parentFile == null || !candidate.path.startsWith(rootPrefix)) {
            throw IOException("The terrain inventory path escapes private regional storage.")
        }
        if (!candidate.isFile || candidate.length() != record.bytes) {
            throw IOException("The committed terrain file is missing or its byte count changed.")
        }
        val expectedHash = record.sha256
            ?: throw IOException("The committed terrain file has no SHA-256 evidence.")
        if (sha256(candidate) != expectedHash) {
            throw IOException("The committed terrain file failed SHA-256 verification.")
        }
        return candidate
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun isReadyCopernicusTerrain(record: RegionalInventoryRecord): Boolean =
        record.sourceSnapshot.dataType == RegionalDataType.DIGITAL_SURFACE_MODEL &&
            record.sourceSnapshot.datasetFamily == COPERNICUS_DATASET_FAMILY &&
            record.processingState == RegionalProcessingState.READY &&
            record.status in setOf(RegionalTransferStatus.READY, RegionalTransferStatus.EXISTING) &&
            record.bytes != null && record.sha256 != null

    private fun projectDigitalTvChannel(project: PlannerProject): Int {
        val networks = project.networks.associateBy { it.id }
        val sectors = project.sites.flatMap { site ->
            site.sectors.filter { sector ->
                val network = sector.networkId?.let(networks::get)
                sector.active && network?.active == true &&
                    network.system == com.gecesars.atxplan.domain.model.RadioSystem.TV_BROADCAST
            }
        }
        require(sectors.size == 1) {
            "A regulatory TV study requires exactly one active project TV sector."
        }
        return com.gecesars.atxplan.domain.contour.BrazilBroadcastRules
            .protectedProfile(
                com.gecesars.atxplan.domain.contour.BroadcastService.DIGITAL_TV,
                sectors.single().frequencyMHz,
            )?.channel
            ?: throw IllegalArgumentException(
                "The active TV sector frequency does not resolve to a supported digital channel 7–51.",
            )
    }

    private data class PreparedInput(
        val terrains: List<PreparedTerrain>,
        val catalogSnapshot: com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot,
        val referenceRecords: List<AnatelBasicPlanRecord>,
    )

    private data class PreparedTerrain(
        val record: RegionalInventoryRecord,
        val file: File,
    )

    private companion object {
        const val COPERNICUS_DATASET_FAMILY = "copernicus-dem-glo30"
        const val MAXIMUM_QUERY_PAGES = 5
        const val MAXIMUM_TERRAIN_ARTIFACTS = 32
    }
}
