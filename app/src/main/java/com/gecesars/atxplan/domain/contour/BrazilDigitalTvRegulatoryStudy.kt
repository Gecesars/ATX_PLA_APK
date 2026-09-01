package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.coverage.BroadcastCoverageSurface
import com.gecesars.atxplan.domain.coverage.BrazilDigitalTvCoverageSurfacePlanner
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Sector
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
    val integrityScope: RegulatoryTerrainIntegrityScope = RegulatoryTerrainIntegrityScope.FULL_ARTIFACT_SHA256,
    val integrityEvidenceDescription: String = "SHA-256 identifies the complete local artifact content.",
    val rangeCacheEvidence: List<RegulatoryTerrainRangeCacheProvenance> = emptyList(),
    val additionalArtifacts: List<RegulatoryTerrainArtifactProvenance> = emptyList(),
) {
    init {
        require(datasetId.isNotBlank() && datasetTitle.isNotBlank() && dataType.isNotBlank())
        require(relativePath.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceUrl.startsWith("https://") && licenseTitle.isNotBlank() && attribution.isNotBlank())
        require(nominalResolutionM.isFinite() && nominalResolutionM > 0.0 && sampleMethod.isNotBlank())
        require(integrityEvidenceDescription.isNotBlank() && rangeCacheEvidence.size <= 32)
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

data class RegulatoryTerrainRangeCacheProvenance(
    val sourceId: String,
    val sourceIdentitySha256: String,
    val rangeManifestSha256: String,
    val cachedRangeCount: Int,
    val cachedByteCount: Long,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceIdentitySha256.matches(Regex("[0-9a-f]{64}")))
        require(rangeManifestSha256.matches(Regex("[0-9a-f]{64}")))
        require(cachedRangeCount >= 0 && cachedByteCount >= 0L)
    }
}

enum class RegulatoryTerrainIntegrityScope {
    FULL_ARTIFACT_SHA256,
    SOURCE_IDENTITY_AND_RANGE_SHA256,
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
    val erpKw: Double? = null,
)

data class RegulatoryReferenceStation(
    val sourceRowId: String,
    val basicPlanId: String?,
    val origin: AnatelBasicPlanOrigin?,
    val entityName: String?,
    val municipalityName: String?,
    val channel: Int,
    val frequencyMHz: Double,
    val latitude: Double,
    val longitude: Double,
    /** ERP used after the mandatory ideal-reference fallback, when applicable. */
    val erpKw: Double,
    /** Transmit height used on P.526 paths. */
    val antennaHeightM: Double,
    val generationDate: String?,
    val sourceRowNumber: Long,
    val service: BroadcastService = BroadcastService.DIGITAL_TV,
    val rawService: String = "",
    val stationClassRaw: String = "",
    val sourceErpKw: Double? = null,
    val sourceAntennaHeightM: Double? = null,
    val protectedReferenceHnmtM: Double? = null,
    val protectedMaximumDistanceKm: Double? = null,
    val idealReferenceFallbackApplied: Boolean = false,
    val idealReferenceFallbackResolved: Boolean = true,
    val sourceKind: RegulatoryReferenceSourceKind = RegulatoryReferenceSourceKind.BASIC_PLAN,
)

enum class RegulatoryReferenceSourceKind {
    BASIC_PLAN,
    LICENSED_MCOM,
}

enum class RegulatoryDuDirection(val displayName: String) {
    REFERENCE_TO_PROJECT("Basic Plan station → project protected contour"),
    PROJECT_TO_REFERENCE("Project station → Basic Plan protected contour"),
}

enum class RegulatoryDuMethod(val displayName: String) {
    POINT_TO_POINT_FIELD("P.526 + Deygout–Assis point-to-point field"),
    COLOCATED_ERP_RATIO("Colocated adjacent-channel ERP ratio"),
}

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
    val direction: RegulatoryDuDirection = RegulatoryDuDirection.REFERENCE_TO_PROJECT,
    val method: RegulatoryDuMethod = RegulatoryDuMethod.POINT_TO_POINT_FIELD,
    val minimumMarginDb: Double? = worstDuDb?.minus(requiredDuDb),
    val separationKm: Double? = null,
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
    val marginDb: Double? = duDb?.minus(requiredDuDb),
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

data class RegulatoryReferenceContourEvidence(
    val station: RegulatoryReferenceStation,
    val contour: ServiceContourOverlay,
    val radialEvidence: List<RegulatoryContourRadialEvidence>,
)

data class BrazilDigitalTvRegulatoryStudyResult(
    val projectId: String,
    val projectName: String,
    val siteId: String,
    val siteName: String,
    val sectorId: String,
    val sectorName: String,
    val center: GeoPoint,
    val radiusKm: Double,
    val service: BroadcastService,
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
    val applicableReferenceRecordCount: Int = referenceStationCount,
    val unevaluatedReferenceRecordCount: Int = 0,
    val referenceContours: List<RegulatoryReferenceContourEvidence> = emptyList(),
    val coverageRequirementPercent: Int = if (service == BroadcastService.FM) 50 else 70,
    val regulatoryScope: String = "Current same-service FM/FM or digital-TV/digital-TV viability",
    val coverageGate: RegulatoryCoverageGateEvidence? = null,
    val licensedBaseline: LicensedBroadcastBaselineSnapshot? = null,
    val scenarioComparisons: List<RegulatoryScenarioComparison> = emptyList(),
    val censusGeometry: RegulatoryCensusGeometrySnapshot? = null,
    val engineeringReady: Boolean = filingReady,
)

class RegulatoryStudyCancelled : RuntimeException("The regulatory study was cancelled.")

/** Current Brazilian same-service FM and digital-TV viability workflow. */
object BrazilBroadcastRegulatoryStudyPlanner {
    const val P1546_MODEL_ID = "itu-r-p1546-6-land-tables-hnmt-v1"
    const val TERRAIN_SPACING_M = 30.0
    const val RECEIVER_HEIGHT_AGL_M = 10.0
    const val EARTH_RADIUS_FACTOR = 4.0 / 3.0
    const val RADIAL_STEP_DEGREES = 5
    const val RADIAL_COUNT = 72
    const val TVD_COCHANNEL_DU_DB = 19.0
    const val TVD_ADJACENT_DU_DB = -36.0
    const val FM_COCHANNEL_DU_DB = 30.0
    const val FM_ADJACENT_DU_DB = 6.0
    const val MIN_RADIUS_KM = 1.0
    const val MAX_RADIUS_KM = 100.0
    private const val HNMT_START_M = 3_000.0
    private const val HNMT_END_M = 15_000.0
    private const val EARTH_MEAN_RADIUS_M = 6_371_008.8
    private const val FIELD_SCAN_STEP_KM = 0.25
    private const val MAX_REFERENCE_PATH_M = 500_000.0
    private const val DIGITAL_TV_COLOCATION_M = 5_000.0

    fun calculate(
        project: PlannerProject,
        radiusKm: Double,
        terrain: TerrainElevationProvider,
        terrainProvenance: RegulatoryTerrainProvenance,
        referenceRecords: List<AnatelBasicPlanRecord>,
        catalogSnapshot: AnatelBasicPlanCatalogSnapshot?,
        regulatoryContext: BrazilBroadcastRegulatoryContext? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BrazilDigitalTvRegulatoryStudyResult {
        require(radiusKm.isFinite() && radiusKm in MIN_RADIUS_KM..MAX_RADIUS_KM) {
            "The regulatory study radius must be between 1 and 100 km."
        }
        val selection = selectProjectBroadcastSector(project)
        val service = selection.service
        val site = selection.site
        val sector = selection.sector
        val profile = BrazilBroadcastRules.protectedProfile(service, sector.frequencyMHz)
            ?: throw IllegalArgumentException(
                if (service == BroadcastService.FM) {
                    "The active FM sector does not resolve to a supported channel."
                } else {
                    "The active TV sector frequency does not resolve to a supported digital channel 7–51."
                },
            )
        val channel = checkNotNull(profile.channel)
        val peakErpKw = erpKw(sector.transmitPowerDbm, sector.antennaGainDbi, sector.feederLossDb)
        require(peakErpKw.isFinite() && peakErpKw > 0.0) {
            "The active broadcast sector does not provide a positive finite ERP."
        }
        require(sector.antennaHeightM > 0.0) {
            "The active broadcast sector requires a positive antenna height AGL."
        }
        val assignedPattern = sector.transmitAntennaPatternId?.let { patternId ->
            project.antennaPatterns.firstOrNull { pattern -> pattern.id == patternId }
        }
        val verifiedPattern = assignedPattern?.takeIf(AntennaPatternRecord::hasVerifiedNormalizedContentIdentity)
        val applicableRecords = referenceRecords.filter { record ->
            record.origin == AnatelBasicPlanOrigin.BASIC_PLAN && record.matches(service) &&
                record.channel?.let { kotlin.math.abs(it - channel) <= 1 } == true
        }
        val calculationReady = applicableRecords.mapNotNull { record ->
            record.toReferenceStationOrNull(service, channel)
        }.sortedWith(
            compareBy<RegulatoryReferenceStation> {
                greatCircleDistanceM(site.location, GeoPoint(it.latitude, it.longitude))
            }.thenBy { kotlin.math.abs(it.channel - channel) }
                .thenBy(RegulatoryReferenceStation::sourceRowId),
        )
        val licensedCalculationReady = regulatoryContext?.licensedBaseline?.stations.orEmpty()
            .mapNotNull { station -> station.toReferenceStationOrNull(service, channel) }
        val licensedPlanIds = licensedCalculationReady.mapNotNull(RegulatoryReferenceStation::basicPlanId).toSet()
        val references = (
            licensedCalculationReady + calculationReady.filter { reference ->
                reference.basicPlanId == null || reference.basicPlanId !in licensedPlanIds
            }
        ).sortedWith(
            compareBy<RegulatoryReferenceStation> {
                greatCircleDistanceM(site.location, GeoPoint(it.latitude, it.longitude))
            }.thenBy { kotlin.math.abs(it.channel - channel) }
                .thenBy(RegulatoryReferenceStation::sourceRowId),
        )
        val unevaluatedCount = applicableRecords.size - calculationReady.size +
            (regulatoryContext?.licensedBaseline?.stations?.size.orZero() - licensedCalculationReady.size)
        val colocated = references.associateWith { station ->
            service == BroadcastService.DIGITAL_TV && station.channel != channel &&
                greatCircleDistanceM(site.location, station.location) <= DIGITAL_TV_COLOCATION_M
        }
        val licensedIds = licensedCalculationReady.map(RegulatoryReferenceStation::sourceRowId).toSet()
        val baselineProgress = licensedCalculationReady.sumOf { wanted ->
            licensedCalculationReady.filter { interferer ->
                interferer.sourceRowId != wanted.sourceRowId &&
                    kotlin.math.abs(interferer.channel - wanted.channel) <= 1
            }.sumOf { interferer ->
                if (service == BroadcastService.DIGITAL_TV && wanted.channel != interferer.channel &&
                    greatCircleDistanceM(wanted.location, interferer.location) <= DIGITAL_TV_COLOCATION_M
                ) 1 else RADIAL_COUNT
            }
        } + references.count { station -> station.sourceRowId in licensedIds && colocated.getValue(station) } *
            RADIAL_COUNT
        val totalProgress = RADIAL_COUNT + BrazilDigitalTvCoverageSurfacePlanner.GRID_SIZE +
            references.sumOf { station -> if (colocated.getValue(station)) 2 else RADIAL_COUNT * 3 } +
            baselineProgress
        var progress = 0
        fun progressed() {
            progress += 1
            onProgress(progress, totalProgress)
        }
        val projectErpAtAzimuth: (Double) -> Double = { azimuth ->
            directionalErpKw(peakErpKw, verifiedPattern, azimuth - sector.azimuthDegrees)
        }
        val projectGeometry = protectedGeometry(
            id = "${project.id}:${site.id}:${sector.id}:${service.name.lowercase()}-regulatory-protected",
            siteId = site.id,
            sectorId = sector.id,
            service = service,
            center = site.location,
            radiusKm = radiusKm,
            frequencyMHz = sector.frequencyMHz,
            antennaHeightAglM = sector.antennaHeightM,
            fixedHnmtM = null,
            erpAtAzimuth = projectErpAtAzimuth,
            profile = profile,
            terrain = terrain,
            inputFingerprint = "pending",
            externalReference = false,
            isCancelled = isCancelled,
            onRadialComplete = ::progressed,
        )
        val inputFingerprint = fingerprint(
            project.id,
            site.id,
            sector.id,
            service.name,
            radiusKm.toString(),
            channel.toString(),
            sector.frequencyMHz.toString(),
            peakErpKw.toString(),
            sector.antennaHeightM.toString(),
            verifiedPattern?.normalizedContentSha256.orEmpty(),
            terrainProvenance.allArtifacts.sortedBy { it.relativePath }.joinToString("|") { it.sha256 },
            catalogSnapshot?.report?.verifiedArchiveSha256.orEmpty(),
            regulatoryContext?.censusGeometry?.sourceSha256.orEmpty(),
            regulatoryContext?.licensedBaseline?.sourceSha256.orEmpty(),
            regulatoryContext?.municipality?.ibgeCode.orEmpty(),
            references.joinToString("|") { it.sourceRowId },
        )
        val terrainBlocker = if (terrainProvenance.dataType == "DIGITAL_TERRAIN_MODEL") null else {
            "The available terrain source is ${terrainProvenance.dataType}, not a verified bare-earth digital terrain model."
        }
        val projectPatternBlocker = when {
            assignedPattern == null ->
                "Project radial ERP is unavailable: assign a verified HRP or provide a regulatory station class for the ideal-reference fallback."
            verifiedPattern == null -> "The assigned project antenna pattern failed normalized-content verification."
            else -> null
        }
        val projectContour = projectGeometry.contour.copy(
            inputFingerprint = inputFingerprint,
            warnings = buildList {
                addAll(projectGeometry.contour.warnings)
                add("The project transmitter is independent; no project parameter was copied from the Anatel catalog.")
                add("Protected contours use 72 true-north radials at 5° spacing and the largest P.1546 threshold crossing.")
                add("P.526 paths use 30 m terrain samples, receiver height 10 m AGL, and effective Earth radius k=4/3.")
                terrainBlocker?.let(::add)
                projectPatternBlocker?.let(::add)
                if (verifiedPattern != null) add("Verified HRP field amplitude shapes radial ERP once through (E/Emax)².")
            }.distinct(),
            regulatory = terrainBlocker == null && projectPatternBlocker == null &&
                projectGeometry.contour.status == ContourStatus.COMPLETE,
        )
        val assessments = mutableListOf<RegulatoryDuAssessment>()
        val referenceContours = mutableListOf<RegulatoryReferenceContourEvidence>()
        val referenceGeometries = linkedMapOf<String, ProtectedGeometry>()
        references.forEach { station ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val delta = station.channel - channel
            val relation = relation(delta)
            val requiredDuDb = protectionRatio(service, delta)
            val separationM = greatCircleDistanceM(site.location, station.location)
            if (colocated.getValue(station)) {
                assessments += erpRatioAssessment(
                    station, relation, requiredDuDb, RegulatoryDuDirection.REFERENCE_TO_PROJECT,
                    peakErpKw, station.erpKw, separationM,
                )
                progressed()
                assessments += erpRatioAssessment(
                    station, relation, requiredDuDb, RegulatoryDuDirection.PROJECT_TO_REFERENCE,
                    station.erpKw, peakErpKw, separationM,
                )
                progressed()
            } else {
                assessments += pointAssessment(
                    station, relation, requiredDuDb, RegulatoryDuDirection.REFERENCE_TO_PROJECT,
                    projectGeometry,
                    PropagationSource(station.location, station.frequencyMHz, station.antennaHeightM) { station.erpKw },
                    separationM / 1_000.0, terrain, isCancelled, ::progressed,
                )
                val referenceProfile = checkNotNull(
                    BrazilBroadcastRules.protectedProfile(service, station.frequencyMHz),
                )
                val referenceGeometry = protectedGeometry(
                    id = "reference:${station.sourceRowId}:protected",
                    siteId = "reference:${station.sourceRowId}",
                    sectorId = "reference:${station.sourceRowId}",
                    service = service,
                    center = station.location,
                    radiusKm = max(radiusKm, station.protectedMaximumDistanceKm ?: radiusKm)
                        .coerceAtMost(MAX_RADIUS_KM),
                    frequencyMHz = station.frequencyMHz,
                    antennaHeightAglM = station.antennaHeightM,
                    fixedHnmtM = station.protectedReferenceHnmtM,
                    erpAtAzimuth = { station.erpKw },
                    profile = referenceProfile,
                    terrain = terrain,
                    inputFingerprint = inputFingerprint,
                    externalReference = true,
                    isCancelled = isCancelled,
                    onRadialComplete = ::progressed,
                )
                referenceContours += RegulatoryReferenceContourEvidence(
                    station, referenceGeometry.contour, referenceGeometry.radialEvidence,
                )
                referenceGeometries[station.sourceRowId] = referenceGeometry
                assessments += pointAssessment(
                    station, relation, requiredDuDb, RegulatoryDuDirection.PROJECT_TO_REFERENCE,
                    referenceGeometry,
                    PropagationSource(site.location, sector.frequencyMHz, sector.antennaHeightM, projectErpAtAzimuth),
                    separationM / 1_000.0, terrain, isCancelled, ::progressed,
                )
            }
        }
        val coverageSurface = BrazilDigitalTvCoverageSurfacePlanner.calculate(
            center = site.location,
            radiusKm = radiusKm,
            frequencyMHz = sector.frequencyMHz,
            peakErpKw = peakErpKw,
            antennaHeightAglM = sector.antennaHeightM,
            sector = sector,
            assignedPattern = assignedPattern,
            radialEvidence = projectGeometry.radialEvidence,
            terrain = terrain,
            inputFingerprint = inputFingerprint,
            service = service,
            isCancelled = isCancelled,
            onRowComplete = ::progressed,
        )
        val coverageGate = regulatoryContext?.let { context ->
            BrazilUrbanCoverageGate.calculate(
                context = context,
                service = service,
                protectedContour = projectContour.points,
                transmitter = site.location,
                frequencyMHz = sector.frequencyMHz,
                antennaHeightAglM = sector.antennaHeightM,
                thresholdDbuvPerM = profile.thresholdDbuvPerM,
                erpAtAzimuthKw = projectErpAtAzimuth,
                terrain = terrain,
                isCancelled = isCancelled,
            )
        }
        val scenarioComparisons = licensedCalculationReady.map { wanted ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val wantedGeometry = referenceGeometries[wanted.sourceRowId] ?: run {
                val wantedProfile = checkNotNull(BrazilBroadcastRules.protectedProfile(service, wanted.frequencyMHz))
                protectedGeometry(
                    id = "licensed:${wanted.sourceRowId}:protected",
                    siteId = "licensed:${wanted.sourceRowId}",
                    sectorId = "licensed:${wanted.sourceRowId}",
                    service = service,
                    center = wanted.location,
                    radiusKm = max(radiusKm, wanted.protectedMaximumDistanceKm ?: radiusKm)
                        .coerceAtMost(MAX_RADIUS_KM),
                    frequencyMHz = wanted.frequencyMHz,
                    antennaHeightAglM = wanted.antennaHeightM,
                    fixedHnmtM = wanted.protectedReferenceHnmtM,
                    erpAtAzimuth = { wanted.erpKw },
                    profile = wantedProfile,
                    terrain = terrain,
                    inputFingerprint = inputFingerprint,
                    externalReference = true,
                    isCancelled = isCancelled,
                    onRadialComplete = ::progressed,
                ).also { geometry ->
                    referenceGeometries[wanted.sourceRowId] = geometry
                    if (referenceContours.none { it.station.sourceRowId == wanted.sourceRowId }) {
                        referenceContours += RegulatoryReferenceContourEvidence(
                            wanted,
                            geometry.contour,
                            geometry.radialEvidence,
                        )
                    }
                }
            }
            val baselineAssessments = licensedCalculationReady.mapNotNull { interferer ->
                if (interferer.sourceRowId == wanted.sourceRowId ||
                    kotlin.math.abs(interferer.channel - wanted.channel) > 1
                ) return@mapNotNull null
                val delta = interferer.channel - wanted.channel
                val requiredDuDb = protectionRatio(service, delta)
                val separationM = greatCircleDistanceM(wanted.location, interferer.location)
                if (service == BroadcastService.DIGITAL_TV && delta != 0 &&
                    separationM <= DIGITAL_TV_COLOCATION_M
                ) {
                    progressed()
                    erpRatioAssessment(
                        interferer,
                        relation(delta),
                        requiredDuDb,
                        RegulatoryDuDirection.REFERENCE_TO_PROJECT,
                        wanted.erpKw,
                        interferer.erpKw,
                        separationM,
                    )
                } else {
                    pointAssessment(
                        interferer,
                        relation(delta),
                        requiredDuDb,
                        RegulatoryDuDirection.REFERENCE_TO_PROJECT,
                        wantedGeometry,
                        PropagationSource(
                            interferer.location,
                            interferer.frequencyMHz,
                            interferer.antennaHeightM,
                        ) { interferer.erpKw },
                        separationM / 1_000.0,
                        terrain,
                        isCancelled,
                        ::progressed,
                    )
                }
            }
            val projectAssessment = assessments.firstOrNull { assessment ->
                assessment.direction == RegulatoryDuDirection.PROJECT_TO_REFERENCE &&
                    assessment.station.sourceRowId == wanted.sourceRowId
            }
            scenarioComparison(wanted, baselineAssessments, projectAssessment)
        }
        val incompleteAssessments = assessments.count { it.status != ContourStatus.COMPLETE }
        val failedAssessments = assessments.count { it.failingPointCount > 0 }
        val aggravatedScenarios = scenarioComparisons.count { comparison ->
            comparison.status in setOf(RegulatoryScenarioStatus.AGGRAVATED, RegulatoryScenarioStatus.NEW_CONFLICT)
        }
        val noDataScenarios = scenarioComparisons.count {
            it.status == RegulatoryScenarioStatus.NO_DATA
        }
        val licensedAdjacentForColocation = regulatoryContext?.licensedBaseline?.stations.orEmpty()
            .filter { station ->
                service == BroadcastService.DIGITAL_TV &&
                    station.technology == BroadcastTechnology.DIGITAL &&
                    kotlin.math.abs(station.channel - channel) == 1
            }
        val engineeringBlockers = buildList {
            terrainBlocker?.let(::add)
            projectPatternBlocker?.let(::add)
            if (projectContour.status != ContourStatus.COMPLETE) {
                add("The project protected contour is incomplete inside the requested analysis boundary.")
            }
            val incompleteReferenceContours = referenceContours.count {
                it.contour.status != ContourStatus.COMPLETE
            }
            if (incompleteReferenceContours > 0) {
                add("$incompleteReferenceContours Basic Plan protected contour(s) are incomplete inside their class-aware search boundary.")
            }
            if (catalogSnapshot == null) {
                add("The Anatel Basic Plan catalog has no verified current snapshot for allocation-reference screening.")
            }
            if (unevaluatedCount > 0) {
                add("$unevaluatedCount applicable official reference record(s) were not evaluated because required RF fields were missing.")
            }
            val unresolvedFallbackCount = references.count { !it.idealReferenceFallbackResolved }
            if (unresolvedFallbackCount > 0) {
                add("$unresolvedFallbackCount reference station(s) lack a resolvable class for the mandatory ideal-reference fallback.")
            }
            if (incompleteAssessments > 0) add("$incompleteAssessments bidirectional D/U assessment(s) are incomplete or contain NoData.")
            if (failedAssessments > 0) add("$failedAssessments bidirectional D/U assessment(s) fail the applicable threshold.")
            when (coverageGate?.status) {
                RegulatoryGateStatus.PASS -> Unit
                RegulatoryGateStatus.FAIL -> add(
                    "The official urban census-sector coverage gate fails the ${coverageGate.requirementPercent}% area requirement.",
                )
                RegulatoryGateStatus.NO_DATA -> add(
                    "The official urban census-sector coverage gate contains unresolved NoData.",
                )
                null -> add(
                    "The official urban census-sector coverage gate is NoData because project municipality geometry is not attached.",
                )
            }
            if (regulatoryContext == null) {
                add("Existing-versus-proposed interference aggravation is NoData because no licensed MCom baseline is attached.")
            } else {
                if (regulatoryContext.licensedBaseline.unlocatedSameChannelStationCount > 0) {
                    add(
                        "${regulatoryContext.licensedBaseline.unlocatedSameChannelStationCount} licensed same-service cochannel/adjacent record(s) have neither licensed nor discovery coordinates and cannot be spatially excluded.",
                    )
                }
                if (aggravatedScenarios > 0) {
                    add("$aggravatedScenarios licensed existing-versus-proposed scenario(s) introduce or aggravate interference.")
                }
                if (noDataScenarios > 0) {
                    add("$noDataScenarios licensed existing-versus-proposed scenario(s) contain unresolved NoData.")
                }
            }
            if (service == BroadcastService.DIGITAL_TV && regulatoryContext == null) {
                add("Same-municipality mandatory adjacent-channel colocation cannot be decided without an authoritative project IBGE municipality code.")
            } else if (service == BroadcastService.DIGITAL_TV && regulatoryContext != null) {
                val unresolvedMunicipalityOrLocation = licensedAdjacentForColocation.count { station ->
                    station.municipalityCode == null ||
                        station.locationBasis != LicensedBroadcastLocationBasis.LICENSED_COORDINATES
                }
                if (unresolvedMunicipalityOrLocation > 0) {
                    add("$unresolvedMunicipalityOrLocation adjacent digital licensed record(s) lack an authoritative municipality code or licensed coordinates for the colocation decision.")
                }
                val nonColocated = licensedAdjacentForColocation.count { station ->
                    station.municipalityCode == regulatoryContext.municipality.ibgeCode &&
                        station.locationBasis == LicensedBroadcastLocationBasis.LICENSED_COORDINATES &&
                        greatCircleDistanceM(site.location, station.location) > DIGITAL_TV_COLOCATION_M
                }
                if (nonColocated > 0) {
                    add("$nonColocated same-municipality adjacent digital station(s) exceed the mandatory 5 km colocation distance.")
                }
            }
        }.distinct()
        val blockers = (
            engineeringBlockers + listOf(
                "Independent numerical parity against an accepted regulatory reference implementation has not been signed off.",
                "Qualified Brazilian broadcast-engineering review and current filing/source-license review remain required outside the app.",
            )
        ).distinct()
        val warnings = buildList {
            addAll(projectContour.warnings)
            add(
                if (service == BroadcastService.FM) {
                    "Current FM/FM ratios are 30 dB cochannel and 6 dB for ±200 kHz first-adjacent channels."
                } else {
                    "Current digital-to-digital ratios are +19 dB cochannel and −36 dB for upper or lower first-adjacent channels."
                },
            )
            add("Every evaluated non-colocated pair is checked in both interference directions at the wanted protected boundary.")
            add("Reference rows without normalized radial ERP use the Act's ideal-reference fallback: class maximum ERP and at least 40 m transmit height.")
            if (service == BroadcastService.DIGITAL_TV) {
                add("Adjacent digital stations separated by at most 5 km use the desired/interfering ERP ratio instead of boundary field strength.")
                add("Only Basic Plan rows explicitly identified as GTVD, PBTVD, or RTVD are digital-TV references; analog TV/RTV rows are excluded.")
            }
            if (references.isEmpty()) add("No calculation-ready applicable Basic Plan reference was found in the complete query result.")
            if (regulatoryContext != null) {
                add("Licensed existing stations use the verified MCom/Mosaico snapshot; analog TV and RTV records are excluded by product scope.")
                add("Existing-versus-proposed status compares the minimum individual-signal D/U margin; it does not represent composite-field aggregation.")
            }
        }.distinct()
        return BrazilDigitalTvRegulatoryStudyResult(
            project.id, project.name, site.id, site.name, sector.id, sector.name, site.location,
            radiusKm, service, channel, sector.frequencyMHz, peakErpKw, sector.antennaHeightM,
            RECEIVER_HEIGHT_AGL_M, TERRAIN_SPACING_M, profile.thresholdDbuvPerM,
            profile.statisticalBasis, P1546_MODEL_ID, P526DeygoutAssis.MODEL_ID, projectContour,
            projectGeometry.radialEvidence, coverageSurface, references.size, assessments,
            terrainProvenance, catalogSnapshot?.report?.verifiedArchiveSha256,
            catalogSnapshot?.report?.provenance?.acquiredAtEpochMillis, catalogSnapshot?.indexArtifactName,
            inputFingerprint, blockers.isEmpty(), blockers, warnings, applicableRecords.size,
            unevaluatedCount, referenceContours,
            coverageGate = coverageGate,
            licensedBaseline = regulatoryContext?.licensedBaseline,
            scenarioComparisons = scenarioComparisons,
            censusGeometry = regulatoryContext?.censusGeometry,
            engineeringReady = engineeringBlockers.isEmpty(),
        )
    }

    private fun protectedGeometry(
        id: String,
        siteId: String,
        sectorId: String,
        service: BroadcastService,
        center: GeoPoint,
        radiusKm: Double,
        frequencyMHz: Double,
        antennaHeightAglM: Double,
        fixedHnmtM: Double?,
        erpAtAzimuth: (Double) -> Double,
        profile: BrazilProtectedContourProfile,
        terrain: TerrainElevationProvider,
        inputFingerprint: String,
        externalReference: Boolean,
        isCancelled: () -> Boolean,
        onRadialComplete: () -> Unit,
    ): ProtectedGeometry {
        val evidence = mutableListOf<RegulatoryContourRadialEvidence>()
        val contourRadials = mutableListOf<ContourRadial>()
        repeat(RADIAL_COUNT) { radialIndex ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val azimuth = (radialIndex * RADIAL_STEP_DEGREES).toDouble()
            val erpKw = erpAtAzimuth(azimuth)
            val radial = protectedRadial(
                service, center, azimuth, radiusKm, antennaHeightAglM, fixedHnmtM,
                frequencyMHz, erpKw, profile.thresholdDbuvPerM, terrain,
            )
            evidence += radial
            contourRadials += ContourRadial(
                azimuth, radial.distanceKm, radial.erpKw ?: 0.0, radial.hnmtM ?: 0.0,
                radial.status, listOfNotNull(radial.warning),
            )
            onRadialComplete()
        }
        val allDrawable = evidence.all { it.distanceKm != null }
        val allCrossings = evidence.all { it.status == ContourRadialStatus.COMPLETE }
        val status = when {
            !allDrawable -> ContourStatus.NO_DATA
            allCrossings -> ContourStatus.COMPLETE
            else -> ContourStatus.INCOMPLETE
        }
        val openPoints = if (allDrawable) evidence.map { radial ->
            destination(center, radial.azimuthDegrees, checkNotNull(radial.distanceKm) * 1_000.0)
        } else emptyList()
        val warnings = buildList {
            if (!allCrossings && allDrawable) {
                add("At least one threshold crossing lies outside the requested ${canonical(radiusKm)} km analysis boundary.")
            }
            if (!allDrawable) add("At least one protected radial is NoData.")
            if (externalReference && fixedHnmtM != null) {
                add("The external protected contour uses the class ideal-reference HNMT because normalized radial ERP was unavailable.")
            }
        }
        return ProtectedGeometry(
            ServiceContourOverlay(
                id, siteId, sectorId, service, ContourPurpose.PROTECTED, profile.statisticalBasis,
                profile.thresholdDbuvPerM,
                if (openPoints.isEmpty()) emptyList() else openPoints + openPoints.first(),
                status, "ITU-R P.1546-6 land tables with radial HNMT", profile.rulesetId, warnings,
                profile.sourceUrl, status == ContourStatus.COMPLETE, contourRadials, inputFingerprint,
            ),
            evidence,
            openPoints,
        )
    }

    private fun protectedRadial(
        service: BroadcastService,
        center: GeoPoint,
        azimuthDegrees: Double,
        radiusKm: Double,
        antennaHeightAglM: Double,
        fixedHnmtM: Double?,
        frequencyMHz: Double,
        erpKw: Double,
        thresholdDbuvPerM: Double,
        terrain: TerrainElevationProvider,
    ): RegulatoryContourRadialEvidence {
        if (!erpKw.isFinite() || erpKw <= 0.0) return noDataRadial(azimuthDegrees, "Radial ERP is NoData.")
        val hnmtM = fixedHnmtM ?: run {
            val siteGroundM = terrain.elevationMeters(center.latitude, center.longitude)
                ?: return noDataRadial(azimuthDegrees, "The transmitter terrain sample is NoData.", erpKw)
            val samples = profileSamples(center, azimuthDegrees, HNMT_END_M, TERRAIN_SPACING_M, terrain)
                .filter { it.first >= HNMT_START_M }
            if (samples.isEmpty() || samples.any { it.second == null }) {
                return noDataRadial(azimuthDegrees, "The 3–15 km HNMT segment contains terrain NoData.", erpKw)
            }
            siteGroundM + antennaHeightAglM - samples.map { checkNotNull(it.second) }.average()
        }
        if (hnmtM !in P1546LandReference.MIN_EFFECTIVE_HEIGHT_M..P1546LandReference.MAX_EFFECTIVE_HEIGHT_M) {
            return noDataRadial(
                azimuthDegrees, "Radial HNMT is outside the packaged P.1546 range of 10–3000 m.",
                erpKw, hnmtM,
            )
        }
        fun field(distanceKm: Double): Double {
            val e50 = P1546LandReference.fieldStrengthDbuvPerM(
                frequencyMHz, 50, hnmtM, distanceKm, erpKw,
            )
            return if (service == BroadcastService.FM) e50 else {
                val e10 = P1546LandReference.fieldStrengthDbuvPerM(
                    frequencyMHz, 10, hnmtM, distanceKm, erpKw,
                )
                2.0 * e50 - e10
            }
        }
        var lowerKm = MIN_RADIUS_KM
        var lowerField = field(lowerKm)
        var lastCrossingKm: Double? = null
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
                lastCrossingKm = (low + high) / 2.0
            }
            lowerKm = boundedUpper
            lowerField = upperField
            if (boundedUpper == radiusKm) break
            upperKm += FIELD_SCAN_STEP_KM
        }
        if (lowerField >= thresholdDbuvPerM) {
            return RegulatoryContourRadialEvidence(
                azimuthDegrees, radiusKm, hnmtM, lowerField, ContourRadialStatus.MODEL_BOUNDARY,
                "The protected threshold remains exceeded at the requested ${canonical(radiusKm)} km boundary.",
                erpKw,
            )
        }
        val distanceKm = lastCrossingKm ?: return noDataRadial(
            azimuthDegrees,
            "No protected-field crossing was found inside the packaged 1 km P.1546 boundary.",
            erpKw,
            hnmtM,
        )
        return RegulatoryContourRadialEvidence(
            azimuthDegrees, distanceKm, hnmtM, field(distanceKm),
            ContourRadialStatus.COMPLETE, erpKw = erpKw,
        )
    }

    private fun pointAssessment(
        station: RegulatoryReferenceStation,
        relation: String,
        requiredDuDb: Double,
        direction: RegulatoryDuDirection,
        wanted: ProtectedGeometry,
        source: PropagationSource,
        separationKm: Double,
        terrain: TerrainElevationProvider,
        isCancelled: () -> Boolean,
        onPointComplete: () -> Unit,
    ): RegulatoryDuAssessment {
        val points = wanted.openPoints.mapIndexed { index, receiver ->
            if (isCancelled()) throw RegulatoryStudyCancelled()
            val propagation = sourceFieldAt(source, receiver, terrain)
            val desired = wanted.radialEvidence.getOrNull(index)?.desiredFieldDbuvPerM
            val evidence = if (propagation == null || desired == null) {
                RegulatoryDuPointEvidence(
                    index, receiver, desired, propagation?.fieldDbuvPerM,
                    propagation?.diffractionLossDb, null, requiredDuDb, RegulatoryDuPointStatus.NO_DATA,
                )
            } else {
                val duDb = desired - propagation.fieldDbuvPerM
                RegulatoryDuPointEvidence(
                    index, receiver, desired, propagation.fieldDbuvPerM,
                    propagation.diffractionLossDb, duDb, requiredDuDb,
                    if (duDb >= requiredDuDb) RegulatoryDuPointStatus.PASS else RegulatoryDuPointStatus.FAIL,
                )
            }
            onPointComplete()
            evidence
        }
        val missingBoundaryPoints = (RADIAL_COUNT - points.size).coerceAtLeast(0)
        repeat(missingBoundaryPoints) { onPointComplete() }
        val evaluated = points.filter { it.status != RegulatoryDuPointStatus.NO_DATA }
        val noData = points.count { it.status == RegulatoryDuPointStatus.NO_DATA } + missingBoundaryPoints
        val failing = points.count { it.status == RegulatoryDuPointStatus.FAIL }
        val passing = points.count { it.status == RegulatoryDuPointStatus.PASS }
        val status = when {
            evaluated.isEmpty() -> ContourStatus.NO_DATA
            noData > 0 -> ContourStatus.INCOMPLETE
            else -> ContourStatus.COMPLETE
        }
        val worstDu = evaluated.mapNotNull(RegulatoryDuPointEvidence::duDb).minOrNull()
        return RegulatoryDuAssessment(
            station, relation, requiredDuDb, evaluated.size, noData, passing, failing, worstDu,
            evaluated.mapNotNull { it.undesiredFieldDbuvPerM }.maxOrNull(),
            evaluated.mapNotNull { it.diffractionLossDb }.maxOrNull(), status, points,
            buildList {
                add("${direction.displayName}; ${RegulatoryDuMethod.POINT_TO_POINT_FIELD.displayName}.")
                add("P.526-15 uses the Deygout–Assis decomposition with at most three dominant obstacles.")
                if (noData > 0) add("$noData wanted-boundary point(s) are NoData.")
                if (failing > 0) add("$failing point(s) fail the ${canonical(requiredDuDb)} dB D/U threshold.")
            },
            direction,
            RegulatoryDuMethod.POINT_TO_POINT_FIELD,
            worstDu?.minus(requiredDuDb),
            separationKm,
        )
    }

    private fun erpRatioAssessment(
        station: RegulatoryReferenceStation,
        relation: String,
        requiredDuDb: Double,
        direction: RegulatoryDuDirection,
        desiredErpKw: Double,
        undesiredErpKw: Double,
        separationM: Double,
    ): RegulatoryDuAssessment {
        val duDb = 10.0 * log10(desiredErpKw / undesiredErpKw)
        val point = RegulatoryDuPointEvidence(
            0, station.location, null, null, null, duDb, requiredDuDb,
            if (duDb >= requiredDuDb) RegulatoryDuPointStatus.PASS else RegulatoryDuPointStatus.FAIL,
        )
        return RegulatoryDuAssessment(
            station, relation, requiredDuDb, 1, 0,
            if (point.status == RegulatoryDuPointStatus.PASS) 1 else 0,
            if (point.status == RegulatoryDuPointStatus.FAIL) 1 else 0,
            duDb, null, null, ContourStatus.COMPLETE, listOf(point),
            listOf(
                "${direction.displayName}; adjacent digital stations are colocated within 5 km, so the Act requires desired/interfering ERP comparison.",
            ),
            direction, RegulatoryDuMethod.COLOCATED_ERP_RATIO, duDb - requiredDuDb,
            separationM / 1_000.0,
        )
    }

    private fun scenarioComparison(
        wanted: RegulatoryReferenceStation,
        baseline: List<RegulatoryDuAssessment>,
        project: RegulatoryDuAssessment?,
    ): RegulatoryScenarioComparison {
        val baselineNoData = baseline.count { assessment ->
            assessment.status != ContourStatus.COMPLETE || assessment.noDataPointCount > 0
        }
        val baselineWorst = baseline.mapNotNull(RegulatoryDuAssessment::minimumMarginDb).minOrNull()
        val projectMargin = project?.minimumMarginDb
        val proposedWorst = listOfNotNull(baselineWorst, projectMargin).minOrNull()
        val noData = baselineNoData + if (
            project == null || project.status != ContourStatus.COMPLETE || project.noDataPointCount > 0
        ) 1 else 0
        val status = when {
            project == null || projectMargin == null || project.noDataPointCount > 0 || baselineNoData > 0 ->
                RegulatoryScenarioStatus.NO_DATA
            baselineWorst == null && projectMargin >= 0.0 -> RegulatoryScenarioStatus.UNCHANGED_COMPLIANT
            baselineWorst == null -> RegulatoryScenarioStatus.NEW_CONFLICT
            baselineWorst >= 0.0 && projectMargin < 0.0 -> RegulatoryScenarioStatus.NEW_CONFLICT
            baselineWorst < 0.0 && projectMargin < baselineWorst - SCENARIO_MARGIN_TOLERANCE_DB ->
                RegulatoryScenarioStatus.AGGRAVATED
            baselineWorst < 0.0 -> RegulatoryScenarioStatus.UNCHANGED_EXISTING_CONFLICT
            else -> RegulatoryScenarioStatus.UNCHANGED_COMPLIANT
        }
        return RegulatoryScenarioComparison(
            wantedStationId = wanted.sourceRowId,
            wantedStationLabel = wanted.entityName ?: wanted.municipalityName ?: wanted.sourceRowId,
            baselineWorstMarginDb = baselineWorst,
            proposedWorstMarginDb = proposedWorst,
            proposedProjectMarginDb = projectMargin,
            status = status,
            baselineInterfererCount = baseline.size,
            noDataAssessmentCount = noData,
        )
    }

    private fun sourceFieldAt(
        source: PropagationSource,
        receiver: GeoPoint,
        terrain: TerrainElevationProvider,
    ): PropagationField? {
        val distanceM = greatCircleDistanceM(source.center, receiver)
        if (distanceM <= 0.0 || distanceM > MAX_REFERENCE_PATH_M) return null
        val bearing = initialBearingDegrees(source.center, receiver)
        val erpKw = source.erpAtAzimuth(bearing)
        if (!erpKw.isFinite() || erpKw <= 0.0) return null
        val sampleCount = ceil(distanceM / TERRAIN_SPACING_M).toInt().coerceAtLeast(1)
        val distances = ArrayList<Double>(sampleCount + 1)
        val ground = ArrayList<Double>(sampleCount + 1)
        repeat(sampleCount + 1) { index ->
            val nodeDistanceM = distanceM * index / sampleCount
            val point = destination(source.center, bearing, nodeDistanceM)
            val elevation = terrain.elevationMeters(point.latitude, point.longitude) ?: return null
            distances += nodeDistanceM
            ground += elevation
        }
        val effectiveEarthRadiusM = EARTH_MEAN_RADIUS_M * EARTH_RADIUS_FACTOR
        val effectiveHeights = ground.mapIndexed { index, elevation ->
            when (index) {
                0 -> elevation + source.antennaHeightAglM
                ground.lastIndex -> elevation + RECEIVER_HEIGHT_AGL_M
                else -> {
                    val d = distances[index]
                    elevation + d * (distanceM - d) / (2.0 * effectiveEarthRadiusM)
                }
            }
        }
        val diffraction = P526DeygoutAssis.calculate(distances, effectiveHeights, source.frequencyMHz)
        return PropagationField(
            freeSpaceErpFieldDbuvPerM(erpKw * 1_000.0, distanceM) - diffraction.lossDb,
            diffraction.lossDb,
        )
    }

    private fun selectProjectBroadcastSector(project: PlannerProject): ProjectSelection {
        val networks = project.networks.associateBy { it.id }
        val candidates = project.sites.flatMap { site ->
            site.sectors.mapNotNull { sector ->
                val network = sector.networkId?.let(networks::get)
                val service = when (network?.system) {
                    RadioSystem.FM_BROADCAST -> BroadcastService.FM
                    RadioSystem.TV_BROADCAST -> BroadcastService.DIGITAL_TV
                    else -> null
                }
                ProjectSelection(site, sector, service ?: return@mapNotNull null)
                    .takeIf { sector.active && network?.active == true }
            }
        }
        require(candidates.size == 1) {
            "A regulatory broadcast study requires exactly one active FM or TV project sector."
        }
        return candidates.single()
    }

    private fun AnatelBasicPlanRecord.matches(service: BroadcastService): Boolean = when (service) {
        BroadcastService.FM -> this.service == AnatelBroadcastService.FM
        BroadcastService.DIGITAL_TV ->
            this.service == AnatelBroadcastService.TELEVISION &&
                rawService.trim().uppercase(Locale.ROOT) in DIGITAL_TV_BASIC_PLAN_SERVICE_CODES
    }

    private fun AnatelBasicPlanRecord.toReferenceStationOrNull(
        service: BroadcastService,
        wantedChannel: Int,
    ): RegulatoryReferenceStation? {
        val resolvedChannel = channel ?: return null
        if (kotlin.math.abs(resolvedChannel - wantedChannel) > 1) return null
        val resolvedFrequency = frequency.frequencyMHz ?: return null
        if (BrazilBroadcastRules.protectedProfile(service, resolvedFrequency)?.channel != resolvedChannel) {
            return null
        }
        val latitude = latitudeDegrees ?: return null
        val longitude = longitudeDegrees ?: return null
        val classParameters = referenceClassParameters(service, resolvedChannel, stationClassRaw)
        val sourceErp = erpKw?.takeIf { it > 0.0 }
        val sourceHeight = antennaHeightMeters?.takeIf { it > 0.0 }
        // Raw archive text is preserved, but it is not a normalized 72-radial calculation cut.
        val idealFallback = true
        val calculationErp = if (idealFallback) {
            listOfNotNull(classParameters?.maximumErpKw, sourceErp).maxOrNull()
        } else {
            sourceErp
        }
        val calculationHeight = if (idealFallback) max(40.0, sourceHeight ?: 40.0) else sourceHeight
        if (calculationErp == null || calculationHeight == null) return null
        val stableSourceId = sourceRowId ?: "${provenance.entryName}:${provenance.sourceRowNumber}"
        return RegulatoryReferenceStation(
            stableSourceId, basicPlanId, origin, entityName, municipalityName, resolvedChannel,
            resolvedFrequency, latitude, longitude, calculationErp, calculationHeight,
            provenance.generationDate, provenance.sourceRowNumber, service, rawService,
            stationClassRaw, sourceErp, sourceHeight,
            if (idealFallback) classParameters?.referenceHnmtM else null,
            if (idealFallback) classParameters?.maximumProtectedDistanceKm else null,
            idealFallback, !idealFallback || classParameters != null,
        )
    }

    private fun LicensedBroadcastStation.toReferenceStationOrNull(
        service: BroadcastService,
        wantedChannel: Int,
    ): RegulatoryReferenceStation? {
        if (kotlin.math.abs(channel - wantedChannel) > 1) return null
        if (service == BroadcastService.DIGITAL_TV && technology != BroadcastTechnology.DIGITAL) return null
        if (service == BroadcastService.FM && role != LicensedBroadcastRole.FM_STATION) return null
        if (locationBasis != LicensedBroadcastLocationBasis.LICENSED_COORDINATES) return null
        if (BrazilBroadcastRules.protectedProfile(service, frequencyMHz)?.channel != channel) return null
        val classParameters = referenceClassParameters(service, channel, stationClassRaw)
        val sourceErp = erpKw?.takeIf { it > 0.0 }
        val sourceHeight = antennaHeightAglM?.takeIf { it > 0.0 }
        val calculationErp = listOfNotNull(classParameters?.maximumErpKw, sourceErp).maxOrNull()
            ?: return null
        val calculationHeight = max(40.0, sourceHeight ?: 40.0)
        return RegulatoryReferenceStation(
            sourceRowId = sourceId,
            basicPlanId = basicPlanId,
            origin = null,
            entityName = licensee,
            municipalityName = municipalityName,
            channel = channel,
            frequencyMHz = frequencyMHz,
            latitude = location.latitude,
            longitude = location.longitude,
            erpKw = calculationErp,
            antennaHeightM = calculationHeight,
            generationDate = licensedOn,
            sourceRowNumber = 0L,
            service = service,
            rawService = rawService,
            stationClassRaw = stationClassRaw,
            sourceErpKw = sourceErp,
            sourceAntennaHeightM = sourceHeight,
            protectedReferenceHnmtM = classParameters?.referenceHnmtM,
            protectedMaximumDistanceKm = classParameters?.maximumProtectedDistanceKm,
            idealReferenceFallbackApplied = horizontalPattern == null,
            idealReferenceFallbackResolved = horizontalPattern != null || classParameters != null,
            sourceKind = RegulatoryReferenceSourceKind.LICENSED_MCOM,
        )
    }

    private fun referenceClassParameters(
        service: BroadcastService,
        channel: Int,
        rawClass: String,
    ): ClassParameters? {
        val normalized = rawClass.trim().uppercase(Locale.ROOT)
            .replace("CLASSE", "").replace("SPECIAL", "E").replace("ESPECIAL", "E").trim()
        return when (service) {
            BroadcastService.FM -> when (normalized) {
                "E1" -> ClassParameters(100.0, 600.0, 78.5)
                "E2" -> ClassParameters(75.0, 450.0, 67.5)
                "E3" -> ClassParameters(60.0, 300.0, 54.5)
                "A1" -> ClassParameters(50.0, 150.0, 38.5)
                "A2" -> ClassParameters(30.0, 150.0, 35.0)
                "A3" -> ClassParameters(15.0, 150.0, 30.0)
                "A4" -> ClassParameters(5.0, 150.0, 24.0)
                "B1" -> ClassParameters(3.0, 90.0, 16.5)
                "B2" -> ClassParameters(1.0, 90.0, 12.5)
                "C" -> ClassParameters(0.3, 60.0, 7.5)
                else -> null
            }
            BroadcastService.DIGITAL_TV -> {
                val highVhf = channel in 7..13
                val maximumErp = when (normalized) {
                    "E", "ESP" -> when {
                        highVhf -> 16.0
                        channel in 14..46 -> 80.0
                        channel in 47..51 -> 100.0
                        else -> return null
                    }
                    "A" -> if (highVhf) 1.6 else 8.0
                    "B" -> if (highVhf) 0.16 else 0.8
                    "C" -> if (highVhf) 0.016 else 0.08
                    else -> return null
                }
                val maximumDistanceKm = when (normalized) {
                    "E", "ESP" -> if (highVhf) 65.6 else 58.0
                    "A" -> if (highVhf) 47.9 else 42.5
                    "B" -> if (highVhf) 32.3 else 29.1
                    else -> if (highVhf) 20.2 else 18.1
                }
                ClassParameters(maximumErp, 150.0, maximumDistanceKm)
            }
        }
    }

    private fun protectionRatio(service: BroadcastService, delta: Int): Double = when (service) {
        BroadcastService.FM -> if (delta == 0) FM_COCHANNEL_DU_DB else FM_ADJACENT_DU_DB
        BroadcastService.DIGITAL_TV -> if (delta == 0) TVD_COCHANNEL_DU_DB else TVD_ADJACENT_DU_DB
    }

    private fun relation(delta: Int): String = when (delta) {
        0 -> "cochannel"
        -1 -> "lower-adjacent"
        1 -> "upper-adjacent"
        else -> throw IllegalArgumentException("The reference station is not cochannel or first-adjacent.")
    }

    private val DIGITAL_TV_BASIC_PLAN_SERVICE_CODES = setOf("GTVD", "PBTVD", "RTVD")

    private fun directionalErpKw(
        peakErpKw: Double,
        pattern: AntennaPatternRecord?,
        relativeAzimuthDegrees: Double,
    ): Double {
        val values = pattern?.horizontalCut?.normalizedField ?: return peakErpKw
        val position = wrap360(relativeAzimuthDegrees)
        val lower = floor(position).toInt()
        val fraction = position - lower
        val field = values[Math.floorMod(lower, values.size)] * (1.0 - fraction) +
            values[(lower + 1).mod(values.size)] * fraction
        return peakErpKw * field.pow(2.0)
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

    private fun noDataRadial(
        azimuthDegrees: Double,
        warning: String,
        erpKw: Double? = null,
        hnmtM: Double? = null,
    ) = RegulatoryContourRadialEvidence(
        azimuthDegrees, null, hnmtM, null, ContourRadialStatus.NO_DATA, warning, erpKw,
    )

    private fun erpKw(transmitPowerDbm: Double, antennaGainDbi: Double, feederLossDb: Double): Double =
        10.0.pow((transmitPowerDbm + antennaGainDbi - feederLossDb - 2.15 - 60.0) / 10.0)

    private fun freeSpaceErpFieldDbuvPerM(erpW: Double, distanceM: Double): Double =
        20.0 * log10(sqrt(30.0 * 1.64 * erpW) / distanceM * 1_000_000.0)

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
        return GeoPoint(
            Math.toDegrees(destinationLatitude),
            ((Math.toDegrees(destinationLongitude) + 540.0) % 360.0) - 180.0,
        )
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

    private fun wrap360(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private val RegulatoryReferenceStation.location: GeoPoint
        get() = GeoPoint(latitude, longitude)

    private data class ProjectSelection(val site: RadioSite, val sector: Sector, val service: BroadcastService)
    private data class ClassParameters(
        val maximumErpKw: Double,
        val referenceHnmtM: Double,
        val maximumProtectedDistanceKm: Double,
    )
    private data class ProtectedGeometry(
        val contour: ServiceContourOverlay,
        val radialEvidence: List<RegulatoryContourRadialEvidence>,
        val openPoints: List<GeoPoint>,
    )
    private data class PropagationSource(
        val center: GeoPoint,
        val frequencyMHz: Double,
        val antennaHeightAglM: Double,
        val erpAtAzimuth: (Double) -> Double,
    )
    private data class PropagationField(val fieldDbuvPerM: Double, val diffractionLossDb: Double)

    private const val SCENARIO_MARGIN_TOLERANCE_DB = 0.01
}

/** Compatibility facade retained for existing digital-TV callers and artifacts. */
object BrazilDigitalTvRegulatoryStudyPlanner {
    const val P1546_MODEL_ID = BrazilBroadcastRegulatoryStudyPlanner.P1546_MODEL_ID
    const val TERRAIN_SPACING_M = BrazilBroadcastRegulatoryStudyPlanner.TERRAIN_SPACING_M
    const val RECEIVER_HEIGHT_AGL_M = BrazilBroadcastRegulatoryStudyPlanner.RECEIVER_HEIGHT_AGL_M
    const val EARTH_RADIUS_FACTOR = BrazilBroadcastRegulatoryStudyPlanner.EARTH_RADIUS_FACTOR
    const val RADIAL_STEP_DEGREES = BrazilBroadcastRegulatoryStudyPlanner.RADIAL_STEP_DEGREES
    const val RADIAL_COUNT = BrazilBroadcastRegulatoryStudyPlanner.RADIAL_COUNT
    const val TVD_COCHANNEL_DU_DB = BrazilBroadcastRegulatoryStudyPlanner.TVD_COCHANNEL_DU_DB
    const val TVD_ADJACENT_DU_DB = BrazilBroadcastRegulatoryStudyPlanner.TVD_ADJACENT_DU_DB
    const val MIN_RADIUS_KM = BrazilBroadcastRegulatoryStudyPlanner.MIN_RADIUS_KM
    const val MAX_RADIUS_KM = BrazilBroadcastRegulatoryStudyPlanner.MAX_RADIUS_KM

    fun calculate(
        project: PlannerProject,
        radiusKm: Double,
        terrain: TerrainElevationProvider,
        terrainProvenance: RegulatoryTerrainProvenance,
        referenceRecords: List<AnatelBasicPlanRecord>,
        catalogSnapshot: AnatelBasicPlanCatalogSnapshot?,
        regulatoryContext: BrazilBroadcastRegulatoryContext? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BrazilDigitalTvRegulatoryStudyResult = BrazilBroadcastRegulatoryStudyPlanner.calculate(
        project, radiusKm, terrain, terrainProvenance, referenceRecords, catalogSnapshot,
        regulatoryContext,
        isCancelled, onProgress,
    ).also { result ->
        require(result.service == BroadcastService.DIGITAL_TV) {
            "The digital-TV compatibility planner cannot calculate an FM project."
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
