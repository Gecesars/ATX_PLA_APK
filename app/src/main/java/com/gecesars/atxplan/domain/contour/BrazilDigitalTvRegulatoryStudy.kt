package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.coverage.BroadcastCoverageSurface
import com.gecesars.atxplan.domain.coverage.BrazilDigitalTvCoverageSurfacePlanner
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSystem
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun interface TerrainElevationProvider {
    fun elevationMeters(latitude: Double, longitude: Double): Double?
}

data class RegulatoryTerrainProvenance(
    val datasetId: String,
    val datasetTitle: String,
    val dataType: String,
    val relativePath: String,
    val sha256: String,
    val acquiredAt: String?,
    val sourceUrl: String,
    val licenseTitle: String,
    val attribution: String,
    val nominalResolutionM: Double,
    val sampleMethod: String,
    val additionalArtifacts: List<RegulatoryTerrainArtifactProvenance> = emptyList(),
) {
    init {
        require(datasetId.isNotBlank() && datasetTitle.isNotBlank() && dataType.isNotBlank())
        require(relativePath.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceUrl.startsWith("https://") && licenseTitle.isNotBlank() && attribution.isNotBlank())
        require(nominalResolutionM.isFinite() && nominalResolutionM > 0.0 && sampleMethod.isNotBlank())
        require(additionalArtifacts.size <= 31) { "Terrain provenance cannot exceed 32 artifacts." }
        require(
            (listOf(relativePath) + additionalArtifacts.map { it.relativePath }).distinct().size ==
                additionalArtifacts.size + 1,
        ) { "Terrain provenance cannot repeat artifact paths." }
    }

    val allArtifacts: List<RegulatoryTerrainArtifactProvenance>
        get() = listOf(
            RegulatoryTerrainArtifactProvenance(relativePath, sha256, acquiredAt, sourceUrl),
        ) + additionalArtifacts
}

data class RegulatoryTerrainArtifactProvenance(
    val relativePath: String,
    val sha256: String,
    val acquiredAt: String?,
    val artifactUrl: String,
) {
    init {
        require(relativePath.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(artifactUrl.startsWith("https://"))
    }
}

data class RegulatoryContourRadialEvidence(
    val azimuthDegrees: Double,
    val distanceKm: Double?,
    val hnmtM: Double?,
    val desiredFieldDbuvPerM: Double?,
    val status: ContourRadialStatus,
    val warning: String? = null,
)

data class RegulatoryReferenceStation(
    val sourceRowId: String,
    val basicPlanId: String?,
    val origin: AnatelBasicPlanOrigin,
    val entityName: String?,
    val municipalityName: String?,
    val channel: Int,
    val frequencyMHz: Double,
    val latitude: Double,
    val longitude: Double,
    val erpKw: Double,
    val antennaHeightM: Double,
    val generationDate: String?,
    val sourceRowNumber: Long,
)

data class RegulatoryDuAssessment(
    val station: RegulatoryReferenceStation,
    val channelRelation: String,
    val requiredDuDb: Double,
    val evaluatedPointCount: Int,
    val noDataPointCount: Int,
    val passingPointCount: Int,
    val failingPointCount: Int,
    val worstDuDb: Double?,
    val maximumUndesiredFieldDbuvPerM: Double?,
    val maximumDiffractionLossDb: Double?,
    val status: ContourStatus,
    val points: List<RegulatoryDuPointEvidence>,
    val warnings: List<String>,
)

enum class RegulatoryDuPointStatus {
    PASS,
    FAIL,
    NO_DATA,
}

data class RegulatoryDuPointEvidence(
    val radialIndex: Int,
    val location: GeoPoint,
    val desiredFieldDbuvPerM: Double?,
    val undesiredFieldDbuvPerM: Double?,
    val diffractionLossDb: Double?,
    val duDb: Double?,
    val requiredDuDb: Double,
    val status: RegulatoryDuPointStatus,
) {
    init {
        require(radialIndex >= 0)
        require(desiredFieldDbuvPerM == null || desiredFieldDbuvPerM.isFinite())
        require(undesiredFieldDbuvPerM == null || undesiredFieldDbuvPerM.isFinite())
        require(diffractionLossDb == null || diffractionLossDb.isFinite())
        require(duDb == null || duDb.isFinite())
        require(requiredDuDb.isFinite())
        require((status == RegulatoryDuPointStatus.NO_DATA) == (duDb == null))
    }
}

data class BrazilDigitalTvRegulatoryStudyResult(
    val projectId: String,
    val projectName: String,
    val siteId: String,
    val siteName: String,
    val sectorId: String,
    val sectorName: String,
    val center: GeoPoint,
    val radiusKm: Double,
    val channel: Int,
    val frequencyMHz: Double,
    val peakErpKw: Double,
    val antennaHeightAglM: Double,
    val receiverHeightAglM: Double,
    val terrainSpacingM: Double,
    val protectedThresholdDbuvPerM: Double,
    val protectedStatisticalBasis: String,
    val p1546ModelId: String,
    val diffractionModelId: String,
    val contour: ServiceContourOverlay,
    val radialEvidence: List<RegulatoryContourRadialEvidence>,
    val coverageSurface: BroadcastCoverageSurface,
    val referenceStationCount: Int,
    val duAssessments: List<RegulatoryDuAssessment>,
    val terrainProvenance: RegulatoryTerrainProvenance,
    val anatelArchiveSha256: String?,
    val anatelArchiveAcquiredAtEpochMillis: Long?,
    val anatelIndexArtifactName: String?,
    val inputFingerprint: String,
    val filingReady: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
)

class RegulatoryStudyCancelled : RuntimeException("The regulatory study was cancelled.")

/**
 * CPU-only Brazil first-generation digital-TV workflow. The project station is authoritative and
 * independent; Anatel catalog records are read-only external references for viability and D/U.
 */
object BrazilDigitalTvRegulatoryStudyPlanner {
    const val P1546_MODEL_ID = "itu-r-p1546-6-land-tables-hnmt-v1"
    const val TERRAIN_SPACING_M = 30.0
    const val RECEIVER_HEIGHT_AGL_M = 10.0
    const val EARTH_RADIUS_FACTOR = 4.0 / 3.0
    const val RADIAL_STEP_DEGREES = 5
    const val RADIAL_COUNT = 72
    const val TVD_COCHANNEL_DU_DB = 19.0
    const val TVD_ADJACENT_DU_DB = -36.0
    const val MIN_RADIUS_KM = 1.0
    const val MAX_RADIUS_KM = 100.0
    private const val HNMT_START_M = 3_000.0
    private const val HNMT_END_M = 15_000.0
    private const val EARTH_MEAN_RADIUS_M = 6_371_008.8
    private const val FIELD_SCAN_STEP_KM = 0.25

    fun calculate(
        project: PlannerProject,
        radiusKm: Double,
        terrain: TerrainElevationProvider,
        terrainProvenance: RegulatoryTerrainProvenance,
        referenceRecords: List<AnatelBasicPlanRecord>,
        catalogSnapshot: AnatelBasicPlanCatalogSnapshot?,
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BrazilDigitalTvRegulatoryStudyResult {
        require(radiusKm.isFinite() && radiusKm in MIN_RADIUS_KM..MAX_RADIUS_KM) {
            "The regulatory study radius must be between 1 and 100 km."
        }
        val networks = project.networks.associateBy { it.id }
        val candidates = project.sites.flatMap { site ->
            site.sectors.mapNotNull { sector ->
                val network = sector.networkId?.let(networks::get)
                Triple(site, sector, network).takeIf {
                    sector.active && network?.active == true && network.system == RadioSystem.TV_BROADCAST
                }
            }
        }
        require(candidates.size == 1) {
            "A regulatory TV study requires exactly one active project TV sector."
        }
        val (site, sector, network) = candidates.single()
        checkNotNull(network)
        val profile = BrazilBroadcastRules.protectedProfile(
            BroadcastService.DIGITAL_TV,
            sector.frequencyMHz,
        ) ?: throw IllegalArgumentException(
            "The active TV sector frequency does not resolve to a supported digital channel 7–51.",
        )
        val channel = checkNotNull(profile.channel)
        val peakErpKw = erpKw(
            transmitPowerDbm = sector.transmitPowerDbm,
            antennaGainDbi = sector.antennaGainDbi,
            feederLossDb = sector.feederLossDb,
        )
        require(peakErpKw.isFinite() && peakErpKw > 0.0) {
            "The active TV sector does not provide a positive finite ERP."
        }
        require(sector.antennaHeightM > 0.0) {
            "The active TV sector requires a positive antenna height AGL."
        }

        val references = referenceRecords.asSequence()
            .filter { record -> record.origin == AnatelBasicPlanOrigin.BASIC_PLAN }
            .mapNotNull { it.toReferenceStationOrNull(channel) }
            .filter { station ->
                greatCircleDistanceM(site.location, GeoPoint(station.latitude, station.longitude)) <=
                    MAX_REFERENCE_DISTANCE_M
            }
            .sortedWith(
                compareBy<RegulatoryReferenceStation> { kotlin.math.abs(it.channel - channel) }
                    .thenBy { greatCircleDistanceM(site.location, GeoPoint(it.latitude, it.longitude)) }
                    .thenBy(RegulatoryReferenceStation::sourceRowId),
            )
            .take(MAXIMUM_REFERENCE_STATIONS)
            .toList()
        val totalProgress =
            RADIAL_COUNT + references.size * RADIAL_COUNT + BrazilDigitalTvCoverageSurfacePlanner.GRID_SIZE
        var progress = 0
        val radialEvidence = mutableListOf<RegulatoryContourRadialEvidence>()
        val contourRadials = mutableListOf<ContourRadial>()
        val contourPoints = mutableListOf<GeoPoint>()
        repeat(RADIAL_COUNT) { radialIndex ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val azimuth = (radialIndex * RADIAL_STEP_DEGREES).toDouble()
            val radial = protectedRadial(
                center = site.location,
                azimuthDegrees = azimuth,
                radiusKm = radiusKm,
                antennaHeightAglM = sector.antennaHeightM,
                frequencyMHz = sector.frequencyMHz,
                erpKw = peakErpKw,
                thresholdDbuvPerM = profile.thresholdDbuvPerM,
                terrain = terrain,
            )
            radialEvidence += radial
            contourRadials += ContourRadial(
                azimuthDegrees = azimuth,
                distanceKm = radial.distanceKm,
                erpKw = if (radial.distanceKm == null) 0.0 else peakErpKw,
                effectiveHeightM = radial.hnmtM ?: 0.0,
                status = radial.status,
                warnings = listOfNotNull(radial.warning),
            )
            radial.distanceKm?.let { distance ->
                contourPoints += destination(site.location, azimuth, distance * 1_000.0)
            }
            progress += 1
            onProgress(progress, totalProgress)
        }
        val allDrawable = radialEvidence.all { it.distanceKm != null }
        val allCrossings = radialEvidence.all { it.status == ContourRadialStatus.COMPLETE }
        val contourStatus = when {
            !allDrawable -> ContourStatus.NO_DATA
            allCrossings -> ContourStatus.COMPLETE
            else -> ContourStatus.INCOMPLETE
        }
        val closedPoints = if (allDrawable) contourPoints + contourPoints.first() else emptyList()
        val terrainBlocker = if (terrainProvenance.dataType == "DIGITAL_TERRAIN_MODEL") null else {
            "The available Copernicus source is a digital surface model, not a bare-earth DTM."
        }
        val contourWarnings = buildList {
            add("The project transmitter is independent; no station parameter was copied from the Anatel catalog.")
            add("Radial HNMT is transmitter AMSL minus mean sampled terrain from 3 to 15 km.")
            add("The 30 m nearest-pixel profile uses effective Earth radius k=4/3 for P.526 paths.")
            terrainBlocker?.let(::add)
            if (!allCrossings && allDrawable) {
                add("At least one protected-field crossing lies outside the requested study radius; the boundary radial is explicit.")
            }
            if (!allDrawable) add("At least one radial contains terrain NoData and has no contour point.")
        }
        val inputFingerprint = fingerprint(
            project.id,
            site.id,
            sector.id,
            radiusKm.toString(),
            channel.toString(),
            sector.frequencyMHz.toString(),
            peakErpKw.toString(),
            sector.antennaHeightM.toString(),
            terrainProvenance.allArtifacts
                .sortedBy(RegulatoryTerrainArtifactProvenance::relativePath)
                .joinToString("|") { artifact -> artifact.sha256 },
            catalogSnapshot?.report?.verifiedArchiveSha256.orEmpty(),
        )
        val overlay = ServiceContourOverlay(
            id = "${project.id}:${site.id}:${sector.id}:tvd-regulatory-protected",
            siteId = site.id,
            sectorId = sector.id,
            service = BroadcastService.DIGITAL_TV,
            purpose = ContourPurpose.PROTECTED,
            statisticalBasis = profile.statisticalBasis,
            thresholdDbuvPerM = profile.thresholdDbuvPerM,
            points = closedPoints,
            status = contourStatus,
            model = "ITU-R P.1546-6 land tables with radial HNMT",
            rulesetId = profile.rulesetId,
            warnings = contourWarnings,
            sourceUrl = profile.sourceUrl,
            regulatory = terrainBlocker == null && contourStatus == ContourStatus.COMPLETE,
            radials = contourRadials,
            inputFingerprint = inputFingerprint,
        )

        val assessments = references.map { station ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            assessReferenceStation(
                station = station,
                wantedChannel = channel,
                radialEvidence = radialEvidence,
                contourPoints = if (allDrawable) contourPoints else emptyList(),
                terrain = terrain,
                isCancelled = isCancelled,
                onPointComplete = {
                    progress += 1
                    onProgress(progress, totalProgress)
                },
            )
        }
        val assignedPattern = sector.transmitAntennaPatternId?.let { patternId ->
            project.antennaPatterns.firstOrNull { pattern -> pattern.id == patternId }
        }
        val coverageSurface = BrazilDigitalTvCoverageSurfacePlanner.calculate(
            center = site.location,
            radiusKm = radiusKm,
            frequencyMHz = sector.frequencyMHz,
            peakErpKw = peakErpKw,
            antennaHeightAglM = sector.antennaHeightM,
            sector = sector,
            assignedPattern = assignedPattern,
            radialEvidence = radialEvidence,
            terrain = terrain,
            inputFingerprint = inputFingerprint,
            isCancelled = isCancelled,
            onRowComplete = {
                progress += 1
                onProgress(progress, totalProgress)
            },
        )
        val blockers = buildList {
            terrainBlocker?.let(::add)
            if (contourStatus != ContourStatus.COMPLETE) {
                add("The protected contour is not complete inside the requested radius.")
            }
            if (catalogSnapshot == null) {
                add("The Anatel reference catalog has no verified current snapshot.")
            }
            if (assessments.any { it.status != ContourStatus.COMPLETE }) {
                add("At least one applicable reference-station D/U assessment is incomplete or contains NoData.")
            }
            if (assessments.any { it.failingPointCount > 0 }) {
                add("At least one reference-station D/U assessment fails its applicable threshold.")
            }
        }.distinct()
        val warnings = buildList {
            addAll(contourWarnings)
            if (references.isEmpty()) {
                add("No calculation-ready Basic Plan channel 41, 42, or 43 record was found within 60 km.")
            }
            if (referenceRecords.size > references.size) {
                add("Non-Basic-Plan records and records outside the bounded distance or without complete RF coordinates were retained as unevaluated catalog references.")
            }
            add("Reference-station ERP is applied uniformly by radial because catalog pattern semantics are not normalized for this calculation.")
        }.distinct()
        return BrazilDigitalTvRegulatoryStudyResult(
            projectId = project.id,
            projectName = project.name,
            siteId = site.id,
            siteName = site.name,
            sectorId = sector.id,
            sectorName = sector.name,
            center = site.location,
            radiusKm = radiusKm,
            channel = channel,
            frequencyMHz = sector.frequencyMHz,
            peakErpKw = peakErpKw,
            antennaHeightAglM = sector.antennaHeightM,
            receiverHeightAglM = RECEIVER_HEIGHT_AGL_M,
            terrainSpacingM = TERRAIN_SPACING_M,
            protectedThresholdDbuvPerM = profile.thresholdDbuvPerM,
            protectedStatisticalBasis = profile.statisticalBasis,
            p1546ModelId = P1546_MODEL_ID,
            diffractionModelId = P526DeygoutAssis.MODEL_ID,
            contour = overlay,
            radialEvidence = radialEvidence,
            coverageSurface = coverageSurface,
            referenceStationCount = references.size,
            duAssessments = assessments,
            terrainProvenance = terrainProvenance,
            anatelArchiveSha256 = catalogSnapshot?.report?.verifiedArchiveSha256,
            anatelArchiveAcquiredAtEpochMillis =
                catalogSnapshot?.report?.provenance?.acquiredAtEpochMillis,
            anatelIndexArtifactName = catalogSnapshot?.indexArtifactName,
            inputFingerprint = inputFingerprint,
            filingReady = blockers.isEmpty(),
            blockers = blockers,
            warnings = warnings,
        )
    }

    private fun protectedRadial(
        center: GeoPoint,
        azimuthDegrees: Double,
        radiusKm: Double,
        antennaHeightAglM: Double,
        frequencyMHz: Double,
        erpKw: Double,
        thresholdDbuvPerM: Double,
        terrain: TerrainElevationProvider,
    ): RegulatoryContourRadialEvidence {
        val siteGroundM = terrain.elevationMeters(center.latitude, center.longitude)
            ?: return noDataRadial(azimuthDegrees, "The transmitter DEM sample is NoData.")
        val hnmtSamples = profileSamples(
            start = center,
            azimuthDegrees = azimuthDegrees,
            endDistanceM = HNMT_END_M,
            spacingM = TERRAIN_SPACING_M,
            terrain = terrain,
        ).filter { (distanceM, _) -> distanceM >= HNMT_START_M }
        if (hnmtSamples.isEmpty() || hnmtSamples.any { it.second == null }) {
            return noDataRadial(azimuthDegrees, "The 3–15 km HNMT segment contains terrain NoData.")
        }
        val meanTerrainM = hnmtSamples.map { checkNotNull(it.second) }.average()
        val hnmtM = siteGroundM + antennaHeightAglM - meanTerrainM
        if (hnmtM !in P1546LandReference.MIN_EFFECTIVE_HEIGHT_M..P1546LandReference.MAX_EFFECTIVE_HEIGHT_M) {
            return RegulatoryContourRadialEvidence(
                azimuthDegrees = azimuthDegrees,
                distanceKm = null,
                hnmtM = hnmtM,
                desiredFieldDbuvPerM = null,
                status = ContourRadialStatus.NO_DATA,
                warning = "Radial HNMT is outside the packaged P.1546 range of 10–3000 m.",
            )
        }
        fun field(distanceKm: Double): Double {
            val e50 = P1546LandReference.fieldStrengthDbuvPerM(
                frequencyMHz = frequencyMHz,
                timePercent = 50,
                effectiveHeightM = hnmtM,
                distanceKm = distanceKm,
                erpKw = erpKw,
            )
            val e10 = P1546LandReference.fieldStrengthDbuvPerM(
                frequencyMHz = frequencyMHz,
                timePercent = 10,
                effectiveHeightM = hnmtM,
                distanceKm = distanceKm,
                erpKw = erpKw,
            )
            return 2.0 * e50 - e10
        }
        val firstField = field(MIN_RADIUS_KM)
        if (firstField < thresholdDbuvPerM) {
            return RegulatoryContourRadialEvidence(
                azimuthDegrees,
                null,
                hnmtM,
                firstField,
                ContourRadialStatus.NO_DATA,
                "The protected-field crossing is below the 1 km P.1546 table boundary.",
            )
        }
        var lowerKm = MIN_RADIUS_KM
        var lowerField = firstField
        var upperKm = MIN_RADIUS_KM + FIELD_SCAN_STEP_KM
        while (upperKm <= radiusKm + 1e-9) {
            val boundedUpper = min(upperKm, radiusKm)
            val upperField = field(boundedUpper)
            if (lowerField >= thresholdDbuvPerM && upperField < thresholdDbuvPerM) {
                var low = lowerKm
                var high = boundedUpper
                repeat(28) {
                    val middle = (low + high) / 2.0
                    if (field(middle) >= thresholdDbuvPerM) low = middle else high = middle
                }
                val distanceKm = (low + high) / 2.0
                return RegulatoryContourRadialEvidence(
                    azimuthDegrees,
                    distanceKm,
                    hnmtM,
                    field(distanceKm),
                    ContourRadialStatus.COMPLETE,
                )
            }
            lowerKm = boundedUpper
            lowerField = upperField
            if (boundedUpper == radiusKm) break
            upperKm += FIELD_SCAN_STEP_KM
        }
        return RegulatoryContourRadialEvidence(
            azimuthDegrees,
            radiusKm,
            hnmtM,
            field(radiusKm),
            ContourRadialStatus.MODEL_BOUNDARY,
            "The protected threshold remains exceeded at the requested ${canonical(radiusKm)} km boundary.",
        )
    }

    private fun assessReferenceStation(
        station: RegulatoryReferenceStation,
        wantedChannel: Int,
        radialEvidence: List<RegulatoryContourRadialEvidence>,
        contourPoints: List<GeoPoint>,
        terrain: TerrainElevationProvider,
        isCancelled: () -> Boolean,
        onPointComplete: () -> Unit,
    ): RegulatoryDuAssessment {
        val delta = station.channel - wantedChannel
        val relation = when (delta) {
            0 -> "cochannel"
            -1 -> "lower-adjacent"
            1 -> "upper-adjacent"
            else -> throw IllegalArgumentException("The reference station is not cochannel or adjacent.")
        }
        val requiredDuDb = if (delta == 0) TVD_COCHANNEL_DU_DB else TVD_ADJACENT_DU_DB
        val points = contourPoints.mapIndexed { index, receiver ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val propagation = referenceFieldAt(station, receiver, terrain)
            val desired = radialEvidence.getOrNull(index)?.desiredFieldDbuvPerM
            val point = if (propagation == null || desired == null) {
                RegulatoryDuPointEvidence(
                    radialIndex = index,
                    location = receiver,
                    desiredFieldDbuvPerM = desired,
                    undesiredFieldDbuvPerM = propagation?.first,
                    diffractionLossDb = propagation?.second,
                    duDb = null,
                    requiredDuDb = requiredDuDb,
                    status = RegulatoryDuPointStatus.NO_DATA,
                )
            } else {
                val duDb = desired - propagation.first
                RegulatoryDuPointEvidence(
                    radialIndex = index,
                    location = receiver,
                    desiredFieldDbuvPerM = desired,
                    undesiredFieldDbuvPerM = propagation.first,
                    diffractionLossDb = propagation.second,
                    duDb = duDb,
                    requiredDuDb = requiredDuDb,
                    status = if (duDb >= requiredDuDb) {
                        RegulatoryDuPointStatus.PASS
                    } else {
                        RegulatoryDuPointStatus.FAIL
                    },
                )
            }
            onPointComplete()
            point
        }
        val unavailableBoundaryPointCount = (radialEvidence.size - points.size).coerceAtLeast(0)
        repeat(unavailableBoundaryPointCount) { onPointComplete() }
        val evaluatedPoints = points.filter { point -> point.status != RegulatoryDuPointStatus.NO_DATA }
        val failing = points.count { point -> point.status == RegulatoryDuPointStatus.FAIL }
        val passing = points.count { point -> point.status == RegulatoryDuPointStatus.PASS }
        val noData = points.count { point -> point.status == RegulatoryDuPointStatus.NO_DATA } +
            unavailableBoundaryPointCount
        val status = when {
            contourPoints.isEmpty() || evaluatedPoints.isEmpty() -> ContourStatus.NO_DATA
            noData > 0 -> ContourStatus.INCOMPLETE
            else -> ContourStatus.COMPLETE
        }
        return RegulatoryDuAssessment(
            station = station,
            channelRelation = relation,
            requiredDuDb = requiredDuDb,
            evaluatedPointCount = evaluatedPoints.size,
            noDataPointCount = noData,
            passingPointCount = passing,
            failingPointCount = failing,
            worstDuDb = evaluatedPoints.mapNotNull(RegulatoryDuPointEvidence::duDb).minOrNull(),
            maximumUndesiredFieldDbuvPerM =
                evaluatedPoints.mapNotNull(RegulatoryDuPointEvidence::undesiredFieldDbuvPerM).maxOrNull(),
            maximumDiffractionLossDb =
                evaluatedPoints.mapNotNull(RegulatoryDuPointEvidence::diffractionLossDb).maxOrNull(),
            status = status,
            points = points,
            warnings = buildList {
                add("Point-to-point field uses free-space ERP field minus P.526-15 Deygout–Assis diffraction.")
                add("Catalog ERP is treated as uniform by radial; no normalized directional pattern was applied.")
                if (noData > 0) {
                    add("$noData protected-contour point(s) are unavailable because boundary or terrain evidence is NoData.")
                }
                if (failing > 0) add("$failing evaluated point(s) fail the ${canonical(requiredDuDb)} dB D/U threshold.")
            },
        )
    }

    private fun referenceFieldAt(
        station: RegulatoryReferenceStation,
        receiver: GeoPoint,
        terrain: TerrainElevationProvider,
    ): Pair<Double, Double>? {
        val transmitter = GeoPoint(station.latitude, station.longitude)
        val distanceM = greatCircleDistanceM(transmitter, receiver)
        if (distanceM <= 0.0 || distanceM > MAX_REFERENCE_PATH_M) return null
        val sampleCount = ceil(distanceM / TERRAIN_SPACING_M).toInt().coerceAtLeast(1)
        val distances = ArrayList<Double>(sampleCount + 1)
        val ground = ArrayList<Double>(sampleCount + 1)
        repeat(sampleCount + 1) { index ->
            val nodeDistanceM = distanceM * index / sampleCount
            val bearing = initialBearingDegrees(transmitter, receiver)
            val point = destination(transmitter, bearing, nodeDistanceM)
            val elevation = terrain.elevationMeters(point.latitude, point.longitude) ?: return null
            distances += nodeDistanceM
            ground += elevation
        }
        val effectiveEarthRadiusM = EARTH_MEAN_RADIUS_M * EARTH_RADIUS_FACTOR
        val effectiveHeights = ground.mapIndexed { index, elevation ->
            when (index) {
                0 -> elevation + station.antennaHeightM
                ground.lastIndex -> elevation + RECEIVER_HEIGHT_AGL_M
                else -> {
                    val d = distances[index]
                    elevation + d * (distanceM - d) / (2.0 * effectiveEarthRadiusM)
                }
            }
        }
        val diffraction = P526DeygoutAssis.calculate(
            distancesM = distances,
            effectiveHeightsM = effectiveHeights,
            frequencyMHz = station.frequencyMHz,
        )
        val freeSpaceField = freeSpaceErpFieldDbuvPerM(station.erpKw * 1_000.0, distanceM)
        return freeSpaceField - diffraction.lossDb to diffraction.lossDb
    }

    private fun profileSamples(
        start: GeoPoint,
        azimuthDegrees: Double,
        endDistanceM: Double,
        spacingM: Double,
        terrain: TerrainElevationProvider,
    ): List<Pair<Double, Double?>> {
        val segments = ceil(endDistanceM / spacingM).toInt().coerceAtLeast(1)
        return List(segments + 1) { index ->
            val distanceM = endDistanceM * index / segments
            val point = destination(start, azimuthDegrees, distanceM)
            distanceM to terrain.elevationMeters(point.latitude, point.longitude)
        }
    }

    private fun AnatelBasicPlanRecord.toReferenceStationOrNull(wantedChannel: Int): RegulatoryReferenceStation? {
        val resolvedChannel = channel ?: return null
        if (kotlin.math.abs(resolvedChannel - wantedChannel) > 1) return null
        val frequencyMHz = frequency.frequencyMHz ?: return null
        val latitude = latitudeDegrees ?: return null
        val longitude = longitudeDegrees ?: return null
        val erp = erpKw?.takeIf { it > 0.0 } ?: return null
        val height = antennaHeightMeters?.takeIf { it > 0.0 } ?: return null
        val stableSourceId = sourceRowId ?: "${provenance.entryName}:${provenance.sourceRowNumber}"
        return RegulatoryReferenceStation(
            sourceRowId = stableSourceId,
            basicPlanId = basicPlanId,
            origin = origin,
            entityName = entityName,
            municipalityName = municipalityName,
            channel = resolvedChannel,
            frequencyMHz = frequencyMHz,
            latitude = latitude,
            longitude = longitude,
            erpKw = erp,
            antennaHeightM = height,
            generationDate = provenance.generationDate,
            sourceRowNumber = provenance.sourceRowNumber,
        )
    }

    private fun noDataRadial(azimuthDegrees: Double, warning: String) =
        RegulatoryContourRadialEvidence(
            azimuthDegrees,
            null,
            null,
            null,
            ContourRadialStatus.NO_DATA,
            warning,
        )

    private fun erpKw(transmitPowerDbm: Double, antennaGainDbi: Double, feederLossDb: Double): Double =
        10.0.pow((transmitPowerDbm + antennaGainDbi - feederLossDb - 2.15 - 60.0) / 10.0)

    private fun freeSpaceErpFieldDbuvPerM(erpW: Double, distanceM: Double): Double {
        require(erpW.isFinite() && erpW > 0.0 && distanceM.isFinite() && distanceM > 0.0)
        val eirpW = 1.64 * erpW
        val fieldVPerM = sqrt(30.0 * eirpW) / distanceM
        return 20.0 * log10(fieldVPerM * 1_000_000.0)
    }

    private fun destination(start: GeoPoint, bearingDegrees: Double, distanceM: Double): GeoPoint {
        val angularDistance = distanceM / EARTH_MEAN_RADIUS_M
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        val normalizedLongitude = Math.toDegrees(destinationLongitude)
            .let { ((it + 540.0) % 360.0) - 180.0 }
        return GeoPoint(Math.toDegrees(destinationLatitude), normalizedLongitude)
    }

    private fun greatCircleDistanceM(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        return 2.0 * EARTH_MEAN_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
    }

    private fun initialBearingDegrees(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun canonical(value: Double): String =
        if (value == 0.0) "0" else java.lang.Double.toString(value)

    private const val MAX_REFERENCE_DISTANCE_M = 60_000.0
    private const val MAX_REFERENCE_PATH_M = 120_000.0
    private const val MAXIMUM_REFERENCE_STATIONS = 24
}
