package com.gecesars.atxplan.data.regulatory

import android.content.Context
import com.gecesars.atxplan.data.anatel.AndroidAnatelBasicPlanCatalog
import com.gecesars.atxplan.data.dataset.BundledIbgeDatasetRepository
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogLimits
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.contour.BrazilBroadcastRegulatoryStudyPlanner
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.BrazilBroadcastRegulatoryContext
import com.gecesars.atxplan.domain.contour.RegulatoryMunicipalityContext
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

/** Android composition boundary for a bounded, local Brazil FM or digital-TV regulatory study. */
class AndroidBrazilDigitalTvStudyRunner(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val catalog = AndroidAnatelBasicPlanCatalog(applicationContext)
    private val ibgeAttributes = BundledIbgeDatasetRepository(applicationContext)
    private val regulatoryRoot = File(applicationContext.noBackupFilesDir, REGULATORY_INPUT_DIRECTORY)

    suspend fun run(
        project: PlannerProject,
        radiusKm: Double,
        municipality: RegulatoryMunicipalityContext,
        onPreparation: (RegulatoryArtifactProgress) -> Unit = {},
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BrazilDigitalTvRegulatoryStudyResult {
        val projectService = projectBroadcastServiceAndChannel(project)
        val prepared = withContext(Dispatchers.IO) {
            ibgeAttributes.prepare()
            val censusGeometry = IbgeCensusGeometryRepository(
                root = regulatoryRoot,
                ibgeAttributes = ibgeAttributes,
            ).prepareMunicipality(
                municipality = municipality,
                transmitter = projectService.center,
                onProgress = onPreparation,
            )
            val licensedBaseline = McomLicensedBroadcastRepository(regulatoryRoot).prepareAndQuery(
                service = projectService.service,
                channel = projectService.channel,
                center = projectService.center,
                maximumDistanceKm = MAXIMUM_LICENSED_QUERY_DISTANCE_KM,
                onProgress = onPreparation,
            )
            val status = catalog.status()
            val snapshot = status.snapshot
            val referenceRecords = if (snapshot == null) {
                emptyList()
            } else {
                buildList {
                    for (candidateChannel in (projectService.channel - 1)..(projectService.channel + 1)) {
                        addAll(queryChannel(projectService.service, candidateChannel))
                    }
                }.distinctBy { recordItem ->
                    listOf(
                        recordItem.origin.name,
                        recordItem.sourceRowId.orEmpty(),
                        recordItem.provenance.entryName,
                        recordItem.provenance.sourceRowNumber.toString(),
                    ).joinToString("\u0000")
                }
            }
            val terrainMosaic = AnademTerrainMosaic.open(
                bounds = terrainBounds(projectService.center, MAXIMUM_TERRAIN_DISTANCE_KM),
                cacheRoot = regulatoryRoot,
            )
            PreparedInput(
                terrainMosaic = terrainMosaic,
                catalogSnapshot = snapshot,
                referenceRecords = referenceRecords,
                regulatoryContext = BrazilBroadcastRegulatoryContext(
                    municipality = municipality,
                    censusGeometry = censusGeometry,
                    licensedBaseline = licensedBaseline,
                ),
            )
        }

        return withContext(Dispatchers.Default) {
            try {
                BrazilBroadcastRegulatoryStudyPlanner.calculate(
                    project = project,
                    radiusKm = radiusKm,
                    terrain = { latitude, longitude ->
                        coroutineContext.ensureActive()
                        prepared.terrainMosaic.elevationMeters(latitude, longitude)
                    },
                    terrainProvenance = prepared.terrainMosaic.provenance(),
                    referenceRecords = prepared.referenceRecords,
                    catalogSnapshot = prepared.catalogSnapshot,
                    regulatoryContext = prepared.regulatoryContext,
                    isCancelled = { !coroutineContext.isActive },
                    onProgress = onProgress,
                ).copy(terrainProvenance = prepared.terrainMosaic.provenance())
            } finally {
                prepared.terrainMosaic.close()
            }
        }
    }

    private fun queryChannel(service: BroadcastService, channel: Int): List<AnatelBasicPlanRecord> {
        if (channel !in 1..999) return emptyList()
        val records = mutableListOf<AnatelBasicPlanRecord>()
        var offset = 0
        repeat(MAXIMUM_QUERY_PAGES) {
            val page = catalog.query(
                AnatelBasicPlanQuery(
                    service = when (service) {
                        BroadcastService.FM -> AnatelBroadcastService.FM
                        BroadcastService.DIGITAL_TV -> AnatelBroadcastService.TELEVISION
                    },
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

    private fun projectBroadcastServiceAndChannel(project: PlannerProject): ProjectBroadcastSelection {
        val networks = project.networks.associateBy { it.id }
        val sectors = project.sites.flatMap { site ->
            site.sectors.mapNotNull { sector ->
                val network = sector.networkId?.let(networks::get)
                val service = when (network?.system) {
                    com.gecesars.atxplan.domain.model.RadioSystem.FM_BROADCAST -> BroadcastService.FM
                    com.gecesars.atxplan.domain.model.RadioSystem.TV_BROADCAST -> BroadcastService.DIGITAL_TV
                    else -> null
                }
                Triple(site.location, sector, service).takeIf {
                    sector.active && network?.active == true && service != null
                }
            }
        }
        require(sectors.size == 1) {
            "A regulatory broadcast study requires exactly one active FM or TV project sector."
        }
        val (center, sector, service) = sectors.single()
        val resolvedService = checkNotNull(service)
        val channel = com.gecesars.atxplan.domain.contour.BrazilBroadcastRules
            .protectedProfile(
                resolvedService,
                sector.frequencyMHz,
            )?.channel
            ?: throw IllegalArgumentException(
                "The active broadcast sector frequency does not resolve to a supported current Brazilian channel.",
            )
        return ProjectBroadcastSelection(resolvedService, channel, center)
    }

    private fun terrainBounds(center: GeoPoint, radiusKm: Double): RegionalBounds {
        val latitudeDelta = radiusKm / 110.574
        val longitudeDelta = radiusKm /
            (111.320 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.05))
        return RegionalBounds(
            west = (center.longitude - longitudeDelta).coerceAtLeast(-180.0),
            south = (center.latitude - latitudeDelta).coerceAtLeast(-79.999999),
            east = (center.longitude + longitudeDelta).coerceAtMost(180.0),
            north = (center.latitude + latitudeDelta).coerceAtMost(83.999999),
        )
    }

    private data class PreparedInput(
        val terrainMosaic: AnademTerrainMosaic,
        val catalogSnapshot: com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot?,
        val referenceRecords: List<AnatelBasicPlanRecord>,
        val regulatoryContext: BrazilBroadcastRegulatoryContext,
    )

    private data class ProjectBroadcastSelection(
        val service: BroadcastService,
        val channel: Int,
        val center: GeoPoint,
    )

    private companion object {
        const val MAXIMUM_QUERY_PAGES = 256
        const val MAXIMUM_LICENSED_QUERY_DISTANCE_KM = 500.0
        const val MAXIMUM_TERRAIN_DISTANCE_KM = 620.0
        const val REGULATORY_INPUT_DIRECTORY = "regulatory-inputs-v1"
    }
}
