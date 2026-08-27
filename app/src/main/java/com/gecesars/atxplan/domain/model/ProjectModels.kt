package com.gecesars.atxplan.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

const val PROJECT_CATALOG_SCHEMA_VERSION = 4

@Serializable
data class ProjectCatalog(
    val schemaVersion: Int = PROJECT_CATALOG_SCHEMA_VERSION,
    val selectedProjectId: String? = null,
    val projects: List<PlannerProject> = emptyList(),
    val archivedProjects: List<ArchivedProject> = emptyList(),
) {
    init {
        require(schemaVersion >= 1) { "Invalid catalog version: $schemaVersion" }
        val activeProjectIds = projects.map(PlannerProject::id)
        val archivedProjectIds = archivedProjects.map { archived -> archived.project.id }
        val allProjectIds = activeProjectIds + archivedProjectIds
        require(allProjectIds.distinct().size == allProjectIds.size) {
            "Active and archived project IDs must be unique across the catalog."
        }
    }

    val selectedProject: PlannerProject?
        get() = projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()
}

/**
 * A project aggregate moved out of the active workspace list without changing its engineering
 * content. The original index provides a deterministic, bounded insertion hint for restoration.
 */
@Serializable
data class ArchivedProject(
    val project: PlannerProject,
    val archivedAtEpochMillis: Long,
    val originalProjectIndex: Int,
) {
    init {
        require(archivedAtEpochMillis >= 0L) {
            "An archived project requires a non-negative archive timestamp."
        }
        require(originalProjectIndex >= 0) {
            "An archived project requires a non-negative original index."
        }
    }
}

@Serializable
data class PlannerProject(
    val id: String,
    val name: String,
    val customer: String = "",
    val notes: String = "",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val isDemonstration: Boolean = false,
    val networks: List<RfNetwork> = emptyList(),
    val sites: List<RadioSite> = emptyList(),
    val studies: List<StudySummary> = emptyList(),
    val receivers: List<Receiver> = emptyList(),
    val antennaPatterns: List<AntennaPatternRecord> = emptyList(),
    val gisLayers: List<GisLayerRecord> = emptyList(),
    val studyScenarios: List<StudyScenarioRecord> = emptyList(),
    val activeStudyScenarioId: String? = null,
    val coverageSnapshots: List<CoverageSnapshotRecord> = emptyList(),
    val regulatoryStudies: List<RegulatoryStudyRecord> = emptyList(),
    val artifacts: List<ProjectArtifactReference> = emptyList(),
    val importProvenance: ImportProvenance? = null,
) {
    init {
        require(id.isNotBlank()) { "The project requires an ID." }
        require(name.isNotBlank()) { "The project requires a name." }
        require(networks.map(RfNetwork::id).distinct().size == networks.size) {
            "The project contains duplicate networks."
        }
        require(sites.map(RadioSite::id).distinct().size == sites.size) {
            "The project contains duplicate sites."
        }
        val networkIds = networks.map(RfNetwork::id).toSet()
        val sectorsWithMissingNetworks = sites.flatMap { site ->
            site.sectors
                .filter { sector ->
                    sector.networkId != null && sector.networkId !in networkIds
                }
                .map { sector -> "${site.id}/${sector.id}" }
        }.sorted()
        require(sectorsWithMissingNetworks.isEmpty()) {
            "Sectors reference networks outside this project: " +
                "${sectorsWithMissingNetworks.joinToString()}."
        }
        val duplicateReceiverIds = receivers
            .groupingBy(Receiver::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
        require(duplicateReceiverIds.isEmpty()) {
            "The project contains duplicate receiver IDs: ${duplicateReceiverIds.joinToString()}."
        }
        val receiversWithMissingNetworks = receivers
            .filterNot { receiver -> receiver.networkId in networkIds }
            .map(Receiver::id)
            .sorted()
        require(receiversWithMissingNetworks.isEmpty()) {
            "Receivers reference networks outside this project: " +
                "${receiversWithMissingNetworks.joinToString()}."
        }
        val receiverProfilesWithMissingNetworks = receivers.flatMap { receiver ->
            receiver.networkProfiles
                .filterNot { profile -> profile.networkId in networkIds }
                .map { profile -> "${receiver.id}/${profile.networkId}" }
        }.sorted()
        require(receiverProfilesWithMissingNetworks.isEmpty()) {
            "Receiver profiles reference networks outside this project: " +
                "${receiverProfilesWithMissingNetworks.joinToString()}."
        }
        requireUniqueIds("antenna pattern", antennaPatterns.map(AntennaPatternRecord::id))
        requireUniqueIds("GIS layer", gisLayers.map(GisLayerRecord::id))
        requireUniqueIds("study scenario", studyScenarios.map(StudyScenarioRecord::id))
        requireUniqueIds("coverage snapshot", coverageSnapshots.map(CoverageSnapshotRecord::id))
        requireUniqueIds("regulatory study", regulatoryStudies.map(RegulatoryStudyRecord::id))
        requireUniqueIds("artifact", artifacts.map(ProjectArtifactReference::id))

        val antennaPatternIds = antennaPatterns.map(AntennaPatternRecord::id).toSet()
        val missingAntennaReferences = sites.flatMap { site ->
            site.sectors.flatMap { sector ->
                listOfNotNull(
                    sector.transmitAntennaPatternId,
                    sector.receiveAntennaPatternId,
                ).filterNot(antennaPatternIds::contains).map { patternId ->
                    "${site.id}/${sector.id}/$patternId"
                }
            }
        }.sorted()
        require(missingAntennaReferences.isEmpty()) {
            "Sectors reference antenna patterns outside this project: " +
                "${missingAntennaReferences.joinToString()}."
        }

        val scenarioIds = studyScenarios.map(StudyScenarioRecord::id).toSet()
        require(activeStudyScenarioId == null || activeStudyScenarioId in scenarioIds) {
            "The active study scenario must belong to this project."
        }
        val snapshotsWithMissingScenarios = coverageSnapshots
            .filter { snapshot -> snapshot.scenarioId != null && snapshot.scenarioId !in scenarioIds }
            .map(CoverageSnapshotRecord::id)
            .sorted()
        require(snapshotsWithMissingScenarios.isEmpty()) {
            "Coverage snapshots reference missing study scenarios: " +
                "${snapshotsWithMissingScenarios.joinToString()}."
        }
        val artifactIds = artifacts.map(ProjectArtifactReference::id).toSet()
        val missingArtifactReferences = buildList {
            antennaPatterns.mapNotNullTo(this) { it.dataArtifactId }
            gisLayers.mapNotNullTo(this) { it.dataArtifactId }
            coverageSnapshots.mapNotNullTo(this) { it.dataArtifactId }
            regulatoryStudies.mapNotNullTo(this) { it.dataArtifactId }
        }.filterNot(artifactIds::contains).distinct().sorted()
        require(missingArtifactReferences.isEmpty()) {
            "Project records reference missing artifacts: ${missingArtifactReferences.joinToString()}."
        }
    }
}

@Serializable
data class RfNetwork(
    val id: String,
    val name: String,
    val system: RadioSystem,
    val downlinkFrequencyMHz: Double,
    val bandwidthMHz: Double,
    val active: Boolean = true,
    val uplinkFrequencyMHz: Double? = null,
    val duplexMode: DuplexMode = DuplexMode.UNSPECIFIED,
    val downlinkThresholdDbm: Double? = null,
    val uplinkThresholdDbm: Double? = null,
    val channelPlan: List<ChannelPlanPoint> = emptyList(),
    val technologyProfile: RadioTechnologyProfile? = null,
    val legacyParametersJson: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid network." }
        require(downlinkFrequencyMHz > 0.0 && downlinkFrequencyMHz.isFinite()) {
            "The network frequency must be positive."
        }
        require(bandwidthMHz > 0.0 && bandwidthMHz.isFinite()) {
            "The bandwidth must be positive."
        }
        require(uplinkFrequencyMHz == null || uplinkFrequencyMHz > 0.0 && uplinkFrequencyMHz.isFinite()) {
            "The uplink frequency must be positive when available."
        }
        require(downlinkThresholdDbm == null || downlinkThresholdDbm.isFinite()) {
            "The downlink threshold must be finite when available."
        }
        require(uplinkThresholdDbm == null || uplinkThresholdDbm.isFinite()) {
            "The uplink threshold must be finite when available."
        }
        require(channelPlan.map(ChannelPlanPoint::id).distinct().size == channelPlan.size) {
            "The network contains duplicate channel-plan IDs."
        }
        require(legacyParametersJson == null || legacyParametersJson.length <= MAX_OPAQUE_JSON_CHARS) {
            "The network legacy payload exceeds the safe character limit."
        }
    }
}

@Serializable
enum class DuplexMode {
    UNSPECIFIED,
    SIMPLEX,
    FDD,
    TDD,
}

@Serializable
data class ChannelPlanPoint(
    val id: String,
    val label: String,
    val downlinkFrequencyMHz: Double,
    val uplinkFrequencyMHz: Double? = null,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank()) { "Invalid channel-plan point." }
        require(downlinkFrequencyMHz > 0.0 && downlinkFrequencyMHz.isFinite()) {
            "The channel downlink frequency must be positive."
        }
        require(uplinkFrequencyMHz == null || uplinkFrequencyMHz > 0.0 && uplinkFrequencyMHz.isFinite()) {
            "The channel uplink frequency must be positive when available."
        }
    }
}

@Serializable
data class RadioTechnologyProfile(
    val variant: String = "",
    val adaptiveModulation: Boolean = false,
    val mimoLayers: Int? = null,
    val downlinkLoadPercent: Double? = null,
    val uplinkLoadPercent: Double? = null,
) {
    init {
        require(variant.length <= 120) { "The technology variant cannot exceed 120 characters." }
        require(mimoLayers == null || mimoLayers in 1..64) {
            "MIMO layers must be between 1 and 64 when available."
        }
        require(downlinkLoadPercent == null || downlinkLoadPercent.isFinite() && downlinkLoadPercent in 0.0..100.0) {
            "The downlink load must be between 0% and 100% when available."
        }
        require(uplinkLoadPercent == null || uplinkLoadPercent.isFinite() && uplinkLoadPercent in 0.0..100.0) {
            "The uplink load must be between 0% and 100% when available."
        }
    }
}

@Serializable
enum class RadioSystem {
    GENERIC,
    FM_BROADCAST,
    TV_BROADCAST,
    LTE,
    NR_5G,
    LAND_MOBILE,
    FWA,
    AIR_TO_GROUND,
}

@Serializable
data class RadioSite(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val groundElevationM: Double? = null,
    val towerHeightM: Double? = null,
    val notes: String = "",
    val sectors: List<Sector> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid site." }
        require(groundElevationM == null || groundElevationM.isFinite()) {
            "The site elevation must be finite."
        }
        require(towerHeightM == null || towerHeightM >= 0.0 && towerHeightM.isFinite()) {
            "The tower height cannot be negative."
        }
        require(sectors.map(Sector::id).distinct().size == sectors.size) {
            "The site contains duplicate sectors."
        }
    }
}

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude." }
    }
}

/**
 * A receive endpoint or customer-premises equipment profile.
 *
 * Unit-bearing value objects keep RF assumptions explicit while retaining
 * primitive numeric values in serialized JSON.
 */
@Serializable
data class Receiver(
    val id: String,
    val name: String,
    val networkId: String,
    val location: GeoCoordinate,
    val antennaHeightM: HeightM,
    val antennaGainDbi: GainDbi = GainDbi(0.0),
    val systemLossDb: LossDb = LossDb(0.0),
    val sensitivityDbm: PowerDbm,
    val noiseFigureDb: LossDb = LossDb(0.0),
    val azimuthDegrees: AzimuthDegrees = AzimuthDegrees(0.0),
    val electricalTiltDegrees: TiltDegrees = TiltDegrees(0.0),
    val notes: String = "",
    val equipmentModel: String = "",
    val networkProfiles: List<ReceiverNetworkProfile> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "The receiver requires an ID." }
        require(name.isNotBlank()) { "The receiver requires a name." }
        require(networkId.isNotBlank()) { "The receiver requires a network reference." }
        require(equipmentModel.length <= 160) {
            "The receiver equipment model cannot exceed 160 characters."
        }
        require(networkProfiles.map(ReceiverNetworkProfile::networkId).distinct().size == networkProfiles.size) {
            "The receiver contains duplicate per-network profiles."
        }
    }
}

@Serializable
data class ReceiverNetworkProfile(
    val networkId: String,
    val antennaGainDbi: Double? = null,
    val systemLossDb: Double? = null,
    val sensitivityDbm: Double? = null,
) {
    init {
        require(networkId.isNotBlank()) { "A receiver network profile requires a network ID." }
        require(antennaGainDbi == null || antennaGainDbi.isFinite()) {
            "Receiver profile gain must be finite when available."
        }
        require(systemLossDb == null || systemLossDb >= 0.0 && systemLossDb.isFinite()) {
            "Receiver profile loss cannot be negative."
        }
        require(sensitivityDbm == null || sensitivityDbm.isFinite()) {
            "Receiver profile sensitivity must be finite when available."
        }
    }
}

@Serializable
data class Sector(
    val id: String,
    val name: String,
    val active: Boolean = true,
    val azimuthDegrees: Double,
    val electricalTiltDegrees: Double = 0.0,
    val antennaHeightM: Double,
    val transmitPowerDbm: Double,
    val antennaGainDbi: Double,
    val feederLossDb: Double,
    val frequencyMHz: Double,
    val networkId: String? = null,
    val transmitAntennaPatternId: String? = null,
    val receiveAntennaPatternId: String? = null,
    val receiveAntennaHeightM: Double? = null,
    val receiveAntennaGainDbi: Double? = null,
    val receiveSystemLossDb: Double? = null,
    val cableType: String = "",
    val cableLengthM: Double? = null,
    val equipmentModel: String = "",
    val mimoIndex: Int? = null,
    val simulcastDelayMicros: Double? = null,
    val legacyParametersJson: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid sector." }
        require(azimuthDegrees.isFinite() && azimuthDegrees in 0.0..360.0) {
            "The azimuth must be between 0° and 360°."
        }
        require(electricalTiltDegrees.isFinite() && electricalTiltDegrees in -90.0..90.0) {
            "The electrical tilt must be between -90° and 90°."
        }
        require(antennaHeightM >= 0.0 && antennaHeightM.isFinite()) {
            "The antenna height cannot be negative."
        }
        require(transmitPowerDbm.isFinite() && antennaGainDbi.isFinite()) {
            "Power and gain must be finite."
        }
        require(feederLossDb >= 0.0 && feederLossDb.isFinite()) {
            "The feeder loss cannot be negative."
        }
        require(frequencyMHz > 0.0 && frequencyMHz.isFinite()) {
            "The frequency must be positive."
        }
        require(networkId == null || networkId.isNotBlank()) {
            "A sector network reference cannot be blank."
        }
        require(transmitAntennaPatternId == null || transmitAntennaPatternId.isNotBlank()) {
            "A transmit antenna pattern reference cannot be blank."
        }
        require(receiveAntennaPatternId == null || receiveAntennaPatternId.isNotBlank()) {
            "A receive antenna pattern reference cannot be blank."
        }
        require(receiveAntennaHeightM == null || receiveAntennaHeightM >= 0.0 && receiveAntennaHeightM.isFinite()) {
            "The receive antenna height cannot be negative."
        }
        require(receiveAntennaGainDbi == null || receiveAntennaGainDbi.isFinite()) {
            "The receive antenna gain must be finite when available."
        }
        require(receiveSystemLossDb == null || receiveSystemLossDb >= 0.0 && receiveSystemLossDb.isFinite()) {
            "The receive system loss cannot be negative."
        }
        require(cableType.length <= 120 && equipmentModel.length <= 160) {
            "Sector equipment labels exceed their safe character limits."
        }
        require(cableLengthM == null || cableLengthM >= 0.0 && cableLengthM.isFinite()) {
            "The cable length cannot be negative."
        }
        require(mimoIndex == null || mimoIndex >= 0) { "The MIMO index cannot be negative." }
        require(simulcastDelayMicros == null || simulcastDelayMicros.isFinite()) {
            "The simulcast delay must be finite when available."
        }
        require(legacyParametersJson == null || legacyParametersJson.length <= MAX_OPAQUE_JSON_CHARS) {
            "The sector legacy payload exceeds the safe character limit."
        }
    }
}

@Serializable
data class ProjectArtifactReference(
    val id: String,
    val role: ProjectArtifactRole,
    val fileName: String,
    val mediaType: String,
    val sha256: String,
    val byteCount: Long,
    val createdAtEpochMillis: Long,
) {
    init {
        requireValidProjectArtifactMetadata(id, fileName, mediaType, createdAtEpochMillis)
        require(SHA256_PATTERN.matches(sha256)) { "An artifact requires a lowercase SHA-256 digest." }
        require(byteCount >= 0L) { "An artifact byte count cannot be negative." }
    }
}

private fun requireValidProjectArtifactMetadata(
    id: String,
    fileName: String,
    mediaType: String,
    createdAtEpochMillis: Long,
) {
    require(id.isNotBlank()) { "An artifact reference requires an ID." }
    require(fileName.isNotBlank() && fileName.length <= 240 && fileName.none(Char::isISOControl)) {
        "An artifact filename must be non-blank, bounded, and contain no control characters."
    }
    require(mediaType.isNotBlank() && mediaType.length <= 120) {
        "An artifact media type must be non-blank and bounded."
    }
    require(createdAtEpochMillis >= 0L) { "An artifact timestamp cannot be negative." }
}

@Serializable
enum class ProjectArtifactRole {
    IMPORT_SOURCE,
    ANTENNA_PATTERN,
    GIS_LAYER,
    DATASET_REFERENCE,
    COVERAGE_RESULT,
    REGULATORY_RESULT,
    STUDY_REPORT,
    OTHER,
}

@Serializable
data class AntennaPatternRecord(
    val id: String,
    val name: String,
    val nominalFrequencyHz: Double? = null,
    val peakGainDbi: Double? = null,
    val sourceFormat: String = "",
    val sourceSha256: String? = null,
    val dataArtifactId: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid antenna pattern record." }
        require(nominalFrequencyHz == null || nominalFrequencyHz > 0.0 && nominalFrequencyHz.isFinite()) {
            "The nominal antenna frequency must be positive when available."
        }
        require(peakGainDbi == null || peakGainDbi.isFinite()) {
            "The antenna gain must be finite when available."
        }
        require(sourceSha256 == null || SHA256_PATTERN.matches(sourceSha256)) {
            "The antenna source hash must be a lowercase SHA-256 digest."
        }
        require(dataArtifactId == null || dataArtifactId.isNotBlank()) {
            "An antenna data artifact reference cannot be blank."
        }
    }
}

@Serializable
enum class GisGeometryType {
    POINT,
    LINE,
    POLYGON,
    RASTER,
    MIXED,
    UNKNOWN,
}

@Serializable
data class GisLayerRecord(
    val id: String,
    val name: String,
    val geometryType: GisGeometryType,
    val coordinateReferenceSystem: String = "EPSG:4326",
    val featureCount: Long? = null,
    val dataArtifactId: String? = null,
    val sourceSha256: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid GIS layer record." }
        require(coordinateReferenceSystem.isNotBlank() && coordinateReferenceSystem.length <= 120) {
            "A GIS layer requires a bounded coordinate reference system."
        }
        require(featureCount == null || featureCount >= 0L) {
            "A GIS layer feature count cannot be negative."
        }
        require(dataArtifactId == null || dataArtifactId.isNotBlank()) {
            "A GIS layer artifact reference cannot be blank."
        }
        require(sourceSha256 == null || SHA256_PATTERN.matches(sourceSha256)) {
            "The GIS source hash must be a lowercase SHA-256 digest."
        }
    }
}

@Serializable
data class StudyScenarioRecord(
    val id: String,
    val name: String,
    val modelId: String,
    val modelEdition: String = "",
    val settingsJson: String = "{}",
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && modelId.isNotBlank()) {
            "Invalid study scenario record."
        }
        require(settingsJson.length <= MAX_OPAQUE_JSON_CHARS) {
            "The study scenario settings exceed the safe character limit."
        }
    }
}

@Serializable
data class CoverageSnapshotRecord(
    val id: String,
    val name: String,
    val scenarioId: String? = null,
    val metricId: String,
    val unit: String,
    val noDataMeaning: String,
    val dataArtifactId: String? = null,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && metricId.isNotBlank() && unit.isNotBlank()) {
            "Invalid coverage snapshot record."
        }
        require(noDataMeaning.isNotBlank()) { "A coverage snapshot must define its NoData meaning." }
        require(createdAtEpochMillis >= 0L) { "A snapshot timestamp cannot be negative." }
    }
}

@Serializable
data class RegulatoryStudyRecord(
    val id: String,
    val name: String,
    val serviceId: String,
    val status: RegulatoryRecordStatus = RegulatoryRecordStatus.DRAFT,
    val rulesetId: String,
    val dataArtifactId: String? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && serviceId.isNotBlank() && rulesetId.isNotBlank()) {
            "Invalid regulatory study record."
        }
        require(updatedAtEpochMillis >= 0L) { "A regulatory study timestamp cannot be negative." }
    }
}

@Serializable
enum class RegulatoryRecordStatus {
    DRAFT,
    SCREENING,
    INCONCLUSIVE,
    COMPLIANT,
    CONFLICT,
}

@Serializable
data class ImportProvenance(
    val sourceFormat: String,
    val sourceSha256: String,
    val sourceVersion: String? = null,
    val importedAtEpochMillis: Long,
    val warnings: List<String> = emptyList(),
    val losses: List<String> = emptyList(),
) {
    init {
        require(sourceFormat.isNotBlank()) { "Import provenance requires a source format." }
        require(SHA256_PATTERN.matches(sourceSha256)) {
            "Import provenance requires a lowercase SHA-256 digest."
        }
        require(importedAtEpochMillis >= 0L) { "An import timestamp cannot be negative." }
        require(warnings.size <= MAX_IMPORT_NOTICES && losses.size <= MAX_IMPORT_NOTICES) {
            "Import provenance contains too many notices."
        }
        require((warnings + losses).all { it.length <= MAX_IMPORT_NOTICE_CHARS }) {
            "An import provenance notice exceeds the safe character limit."
        }
    }
}

private fun requireUniqueIds(label: String, ids: List<String>) {
    require(ids.all(String::isNotBlank)) { "Every $label requires a non-blank ID." }
    val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
    require(duplicates.isEmpty()) {
        "The project contains duplicate $label IDs: ${duplicates.joinToString()}."
    }
}

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private const val MAX_OPAQUE_JSON_CHARS = 1_000_000
private const val MAX_IMPORT_NOTICES = 2_000
private const val MAX_IMPORT_NOTICE_CHARS = 2_000

@Serializable
data class StudySummary(
    val id: String,
    val name: String,
    val type: StudyType,
    val status: StudyStatus,
    val updatedAtEpochMillis: Long,
)

@Serializable
enum class StudyType {
    POINT_TO_POINT,
    COVERAGE,
    INTERFERENCE,
    POPULATION,
    REGULATORY,
    ROUTE,
}

@Serializable
enum class StudyStatus {
    DRAFT,
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
}

object ProjectFactory {
    fun create(
        name: String,
        customer: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PlannerProject {
        val cleanName = name.trim()
        require(cleanName.length in 2..80) { "Use a project name between 2 and 80 characters." }
        require(customer.trim().length <= 80) { "The customer name cannot exceed 80 characters." }
        return PlannerProject(
            id = newId("project"),
            name = cleanName,
            customer = customer.trim(),
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    fun demonstration(nowEpochMillis: Long = System.currentTimeMillis()): PlannerProject {
        val network = RfNetwork(
            id = "network-demo-fm",
            name = "Demonstration FM Network",
            system = RadioSystem.FM_BROADCAST,
            downlinkFrequencyMHz = 99.5,
            bandwidthMHz = 0.2,
        )
        val sites = listOf(
            demoSite(
                id = "site-demo-se",
                name = "Downtown Site",
                latitude = -23.55052,
                longitude = -46.63331,
                azimuth = 320.0,
            ),
            demoSite(
                id = "site-demo-jaragua",
                name = "Northwest Site",
                latitude = -23.45910,
                longitude = -46.75550,
                azimuth = 120.0,
            ),
            demoSite(
                id = "site-demo-paulista",
                name = "Paulista Site",
                latitude = -23.56141,
                longitude = -46.65588,
                azimuth = 35.0,
            ),
        )
        return PlannerProject(
            id = "project-demo-sao-paulo",
            name = "São Paulo — Synthetic Demo",
            customer = "Evaluation Environment",
            notes = "Public coordinates and fictional parameters; do not use for engineering work.",
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            isDemonstration = true,
            networks = listOf(network),
            sites = sites,
            studies = listOf(
                StudySummary(
                    id = "study-demo-link",
                    name = "Downtown → Northwest Link",
                    type = StudyType.POINT_TO_POINT,
                    status = StudyStatus.READY,
                    updatedAtEpochMillis = nowEpochMillis,
                ),
                StudySummary(
                    id = "study-demo-coverage",
                    name = "Initial FM Coverage",
                    type = StudyType.COVERAGE,
                    status = StudyStatus.DRAFT,
                    updatedAtEpochMillis = nowEpochMillis,
                ),
            ),
        )
    }

    private fun demoSite(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        azimuth: Double,
    ) = RadioSite(
        id = id,
        name = name,
        location = GeoPoint(latitude, longitude),
        groundElevationM = null,
        sectors = listOf(
            Sector(
                id = "$id-sector-a",
                name = "TX A",
                azimuthDegrees = azimuth,
                antennaHeightM = 45.0,
                transmitPowerDbm = 43.0,
                antennaGainDbi = 8.0,
                feederLossDb = 2.0,
                frequencyMHz = 99.5,
            ),
        ),
    )

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
