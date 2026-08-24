package com.gecesars.atxplan.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

const val PROJECT_CATALOG_SCHEMA_VERSION = 2

@Serializable
data class ProjectCatalog(
    val schemaVersion: Int = PROJECT_CATALOG_SCHEMA_VERSION,
    val selectedProjectId: String? = null,
    val projects: List<PlannerProject> = emptyList(),
) {
    init {
        require(schemaVersion >= 1) { "Invalid catalog version: $schemaVersion" }
        require(projects.map(PlannerProject::id).distinct().size == projects.size) {
            "The catalog contains duplicate project IDs."
        }
    }

    val selectedProject: PlannerProject?
        get() = projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()
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
    }
}

@Serializable
data class RfNetwork(
    val id: String,
    val name: String,
    val system: RadioSystem,
    val downlinkFrequencyMHz: Double,
    val bandwidthMHz: Double,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid network." }
        require(downlinkFrequencyMHz > 0.0 && downlinkFrequencyMHz.isFinite()) {
            "The network frequency must be positive."
        }
        require(bandwidthMHz > 0.0 && bandwidthMHz.isFinite()) {
            "The bandwidth must be positive."
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
    val notes: String = "",
    val sectors: List<Sector> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Invalid site." }
        require(groundElevationM == null || groundElevationM.isFinite()) {
            "The site elevation must be finite."
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
) {
    init {
        require(id.isNotBlank()) { "The receiver requires an ID." }
        require(name.isNotBlank()) { "The receiver requires a name." }
        require(networkId.isNotBlank()) { "The receiver requires a network reference." }
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
    }
}

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
