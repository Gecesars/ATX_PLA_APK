package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.BandwidthMHz
import com.gecesars.atxplan.domain.model.FrequencyMHz
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.TiltDegrees

enum class RfEntityKind(val idPrefix: String) {
    NETWORK("network"),
    SITE("site"),
    SECTOR("sector"),
    RECEIVER("receiver"),
}

fun interface RfEntityIdGenerator {
    fun nextId(kind: RfEntityKind): String
}

fun interface EpochMillisClock {
    fun nowEpochMillis(): Long
}

data class NewRfNetwork(
    val name: String,
    val system: RadioSystem,
    val downlinkFrequencyMHz: FrequencyMHz,
    val bandwidthMHz: BandwidthMHz,
) {
    init {
        requireValidEntityName("Network", name)
    }
}

data class NewTransmitterSite(
    val name: String,
    val location: GeoCoordinate,
    val notes: String = "",
) {
    init {
        requireValidEntityName("Site", name)
        require(notes.length <= MAX_NOTES_LENGTH) {
            "Site notes cannot exceed $MAX_NOTES_LENGTH characters."
        }
    }
}

data class NewTransmitterSector(
    val name: String,
    val active: Boolean = true,
    val azimuthDegrees: AzimuthDegrees,
    val electricalTiltDegrees: TiltDegrees = TiltDegrees(0.0),
    val antennaHeightM: HeightM,
    val transmitPowerDbm: PowerDbm,
    val antennaGainDbi: GainDbi,
    val feederLossDb: LossDb,
) {
    init {
        requireValidEntityName("Sector", name)
    }
}

data class NewReceiver(
    val name: String,
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
        requireValidEntityName("Receiver", name)
        require(notes.length <= MAX_NOTES_LENGTH) {
            "Receiver notes cannot exceed $MAX_NOTES_LENGTH characters."
        }
    }
}

data class AddRfPathCommand(
    val projectId: String,
    val network: NewRfNetwork,
    val site: NewTransmitterSite,
    val sector: NewTransmitterSector,
    val receiver: NewReceiver,
) {
    init {
        require(projectId.isNotBlank() && projectId == projectId.trim()) {
            "The RF path command requires a non-blank, trimmed project ID."
        }
    }
}

data class AddRfPathResult(
    val catalog: ProjectCatalog,
    val project: PlannerProject,
    val network: RfNetwork,
    val site: RadioSite,
    val sector: Sector,
    val receiver: Receiver,
) {
    init {
        require(catalog.projects.singleOrNull { it.id == project.id } == project) {
            "The result project must be present exactly once in the result catalog."
        }
        require(network in project.networks) {
            "The result network must belong to the result project."
        }
        require(site in project.sites && sector in site.sectors) {
            "The result site and sector must belong to the result project."
        }
        require(receiver in project.receivers) {
            "The result receiver must belong to the result project."
        }
        require(sector.networkId == network.id && receiver.networkId == network.id) {
            "The result sector and receiver must reference the result network."
        }
    }
}

/**
 * Builds a complete RF path as one immutable catalog transition.
 *
 * No caller-visible state changes until every input, generated ID, timestamp,
 * entity, and aggregate reference has passed validation.
 */
class AddRfPathUseCase(
    private val idGenerator: RfEntityIdGenerator,
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: AddRfPathCommand,
    ): AddRfPathResult {
        val projectIndex = catalog.projects.indexOfFirst { project ->
            project.id == command.projectId
        }
        require(projectIndex >= 0) {
            "Project '${command.projectId}' was not found."
        }
        val originalProject = catalog.projects[projectIndex]
        val generatedIds = RfEntityKind.entries.associateWith { kind ->
            idGenerator.nextId(kind).also { id -> requireValidGeneratedId(kind, id) }
        }
        val newIds = generatedIds.values.toList()
        require(newIds.distinct().size == newIds.size) {
            "The ID generator returned duplicate IDs for this RF path."
        }
        val existingIds = buildSet {
            addAll(originalProject.networks.map(RfNetwork::id))
            addAll(originalProject.sites.map(RadioSite::id))
            addAll(originalProject.sites.flatMap { site -> site.sectors }.map(Sector::id))
            addAll(originalProject.receivers.map(Receiver::id))
        }
        val collisions = newIds.filter { id -> id in existingIds }.sorted()
        require(collisions.isEmpty()) {
            "Generated IDs already exist in the project: ${collisions.joinToString()}."
        }

        val networkId = generatedIds.getValue(RfEntityKind.NETWORK)
        val network = RfNetwork(
            id = networkId,
            name = command.network.name.trim(),
            system = command.network.system,
            downlinkFrequencyMHz = command.network.downlinkFrequencyMHz.value,
            bandwidthMHz = command.network.bandwidthMHz.value,
        )
        val sector = Sector(
            id = generatedIds.getValue(RfEntityKind.SECTOR),
            name = command.sector.name.trim(),
            active = command.sector.active,
            azimuthDegrees = command.sector.azimuthDegrees.value,
            electricalTiltDegrees = command.sector.electricalTiltDegrees.value,
            antennaHeightM = command.sector.antennaHeightM.value,
            transmitPowerDbm = command.sector.transmitPowerDbm.value,
            antennaGainDbi = command.sector.antennaGainDbi.value,
            feederLossDb = command.sector.feederLossDb.value,
            frequencyMHz = command.network.downlinkFrequencyMHz.value,
            networkId = networkId,
        )
        val site = RadioSite(
            id = generatedIds.getValue(RfEntityKind.SITE),
            name = command.site.name.trim(),
            location = command.site.location.toLegacyGeoPoint(),
            notes = command.site.notes.trim(),
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = generatedIds.getValue(RfEntityKind.RECEIVER),
            name = command.receiver.name.trim(),
            networkId = networkId,
            location = command.receiver.location,
            antennaHeightM = command.receiver.antennaHeightM,
            antennaGainDbi = command.receiver.antennaGainDbi,
            systemLossDb = command.receiver.systemLossDb,
            sensitivityDbm = command.receiver.sensitivityDbm,
            noiseFigureDb = command.receiver.noiseFigureDb,
            azimuthDegrees = command.receiver.azimuthDegrees,
            electricalTiltDegrees = command.receiver.electricalTiltDegrees,
            notes = command.receiver.notes.trim(),
        )
        val updatedAtEpochMillis = maxOf(
            clock.nowEpochMillis(),
            originalProject.updatedAtEpochMillis,
        )
        val updatedProject = originalProject.copy(
            updatedAtEpochMillis = updatedAtEpochMillis,
            networks = originalProject.networks + network,
            sites = originalProject.sites + site,
            receivers = originalProject.receivers + receiver,
        )
        val updatedProjects = catalog.projects.toMutableList().apply {
            this[projectIndex] = updatedProject
        }
        val updatedCatalog = catalog.copy(projects = updatedProjects)

        return AddRfPathResult(
            catalog = updatedCatalog,
            project = updatedProject,
            network = network,
            site = site,
            sector = sector,
            receiver = receiver,
        )
    }

    private fun requireValidGeneratedId(kind: RfEntityKind, id: String) {
        require(id.isNotBlank() && id == id.trim() && id.length <= MAX_ID_LENGTH) {
            "Generated ${kind.idPrefix} ID must be non-blank, trimmed, and no longer than " +
                "$MAX_ID_LENGTH characters."
        }
    }
}

private fun GeoCoordinate.toLegacyGeoPoint() = GeoPoint(
    latitude = latitude.value,
    longitude = longitude.value,
)

private fun requireValidEntityName(entityLabel: String, name: String) {
    require(name.trim().length in MIN_ENTITY_NAME_LENGTH..MAX_ENTITY_NAME_LENGTH) {
        "$entityLabel name must contain between $MIN_ENTITY_NAME_LENGTH and " +
            "$MAX_ENTITY_NAME_LENGTH characters."
    }
}

private const val MIN_ENTITY_NAME_LENGTH = 2
private const val MAX_ENTITY_NAME_LENGTH = 80
private const val MAX_NOTES_LENGTH = 2_000
private const val MAX_ID_LENGTH = 160
