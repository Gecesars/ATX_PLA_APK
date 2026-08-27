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
import java.util.UUID

enum class RfAssetKind {
    NETWORK,
    SITE,
    SECTOR,
    RECEIVER,
}

enum class RfAssetMutationStatus {
    CREATED,
    UPDATED,
    DELETED,
    UNCHANGED,
    STALE,
    NOT_FOUND,
    BLOCKED_REFERENCES,
}

data class RfDeletionImpact(
    val sectorCount: Int = 0,
    val receiverCount: Int = 0,
) {
    init {
        require(sectorCount >= 0 && receiverCount >= 0) {
            "RF deletion impact counts cannot be negative."
        }
    }

    val hasReferences: Boolean
        get() = sectorCount > 0 || receiverCount > 0
}

data class RfAssetMutationReceipt(
    val requestId: String,
    val kind: RfAssetKind,
    val status: RfAssetMutationStatus,
    val entityId: String?,
    val impact: RfDeletionImpact = RfDeletionImpact(),
)

data class RfNetworkInput(
    val name: String,
    val system: RadioSystem,
    val downlinkFrequencyMHz: FrequencyMHz,
    val bandwidthMHz: BandwidthMHz,
    val active: Boolean = true,
) {
    init {
        require(name.trim().length in MIN_RF_NAME_LENGTH..MAX_RF_NAME_LENGTH) {
            "Network name must contain between $MIN_RF_NAME_LENGTH and $MAX_RF_NAME_LENGTH characters."
        }
    }
}

data class RfSiteInput(
    val name: String,
    val location: GeoPoint,
    val groundElevationM: Double? = null,
    val towerHeightM: Double? = null,
    val notes: String = "",
) {
    init {
        require(name.trim().length in MIN_RF_NAME_LENGTH..MAX_RF_NAME_LENGTH) {
            "Site name must contain between $MIN_RF_NAME_LENGTH and $MAX_RF_NAME_LENGTH characters."
        }
        require(notes.length <= MAX_RF_NOTES_LENGTH) {
            "Site notes cannot exceed $MAX_RF_NOTES_LENGTH characters."
        }
    }
}

data class RfSectorInput(
    val name: String,
    val active: Boolean,
    val networkId: String?,
    val frequencyMHz: FrequencyMHz,
    val azimuthDegrees: AzimuthDegrees,
    val electricalTiltDegrees: TiltDegrees,
    val antennaHeightM: HeightM,
    val transmitPowerDbm: PowerDbm,
    val antennaGainDbi: GainDbi,
    val feederLossDb: LossDb,
) {
    init {
        require(name.trim().length in MIN_RF_NAME_LENGTH..MAX_RF_NAME_LENGTH) {
            "Sector name must contain between $MIN_RF_NAME_LENGTH and $MAX_RF_NAME_LENGTH characters."
        }
        require(networkId == null || networkId.isNotBlank()) {
            "A sector network reference cannot be blank."
        }
    }
}

data class RfReceiverInput(
    val name: String,
    val networkId: String,
    val location: GeoCoordinate,
    val antennaHeightM: HeightM,
    val antennaGainDbi: GainDbi,
    val systemLossDb: LossDb,
    val sensitivityDbm: PowerDbm,
    val noiseFigureDb: LossDb,
    val azimuthDegrees: AzimuthDegrees,
    val electricalTiltDegrees: TiltDegrees,
    val notes: String = "",
) {
    init {
        require(name.trim().length in MIN_RF_NAME_LENGTH..MAX_RF_NAME_LENGTH) {
            "Receiver name must contain between $MIN_RF_NAME_LENGTH and $MAX_RF_NAME_LENGTH characters."
        }
        require(networkId.isNotBlank()) { "A receiver requires a network reference." }
        require(notes.length <= MAX_RF_NOTES_LENGTH) {
            "Receiver notes cannot exceed $MAX_RF_NOTES_LENGTH characters."
        }
    }
}

sealed interface RfAssetMutationCommand {
    val requestId: String
    val projectId: String

    data class CreateNetwork(
        override val projectId: String,
        val input: RfNetworkInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class UpdateNetwork(
        override val projectId: String,
        val expected: RfNetwork,
        val input: RfNetworkInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class DeleteNetwork(
        override val projectId: String,
        val expected: RfNetwork,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class CreateSite(
        override val projectId: String,
        val input: RfSiteInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class UpdateSite(
        override val projectId: String,
        val expected: RadioSite,
        val input: RfSiteInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    /** Changes only a site's geographic position and preserves every other stored field verbatim. */
    data class MoveSite(
        override val projectId: String,
        val expected: RadioSite,
        val location: GeoPoint,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class DeleteSite(
        override val projectId: String,
        val expected: RadioSite,
        val deleteContainedSectors: Boolean,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class CreateSector(
        override val projectId: String,
        val siteId: String,
        val input: RfSectorInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class UpdateSector(
        override val projectId: String,
        val siteId: String,
        val expected: Sector,
        val input: RfSectorInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class DeleteSector(
        override val projectId: String,
        val siteId: String,
        val expected: Sector,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class CreateReceiver(
        override val projectId: String,
        val input: RfReceiverInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class UpdateReceiver(
        override val projectId: String,
        val expected: Receiver,
        val input: RfReceiverInput,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    data class DeleteReceiver(
        override val projectId: String,
        val expected: Receiver,
        override val requestId: String = newRequestId(),
    ) : RfAssetMutationCommand

    companion object {
        private fun newRequestId(): String = "rf-mutation-${UUID.randomUUID()}"
    }
}

data class RfAssetMutationResult(
    val catalog: ProjectCatalog,
    val project: PlannerProject?,
    val receipt: RfAssetMutationReceipt,
)

/** Pure, optimistic RF entity transitions applied inside the latest durable catalog transaction. */
class RfAssetCrudUseCase(
    private val idGenerator: RfEntityIdGenerator,
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: RfAssetMutationCommand,
    ): RfAssetMutationResult {
        require(command.requestId.isNotBlank() && command.projectId.isNotBlank()) {
            "An RF mutation requires request and project IDs."
        }
        val projectIndex = catalog.projects.indexOfFirst { it.id == command.projectId }
        if (projectIndex < 0) {
            return result(catalog, null, command, RfAssetMutationStatus.NOT_FOUND, null)
        }
        val project = catalog.projects[projectIndex]
        return when (command) {
            is RfAssetMutationCommand.CreateNetwork -> createNetwork(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.UpdateNetwork -> updateNetwork(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.DeleteNetwork -> deleteNetwork(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.CreateSite -> createSite(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.UpdateSite -> updateSite(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.MoveSite -> moveSite(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.DeleteSite -> deleteSite(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.CreateSector -> createSector(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.UpdateSector -> updateSector(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.DeleteSector -> deleteSector(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.CreateReceiver -> createReceiver(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.UpdateReceiver -> updateReceiver(catalog, projectIndex, project, command)
            is RfAssetMutationCommand.DeleteReceiver -> deleteReceiver(catalog, projectIndex, project, command)
        }
    }

    private fun createNetwork(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.CreateNetwork,
    ): RfAssetMutationResult {
        val id = newEntityId(project, RfEntityKind.NETWORK)
        val network = command.input.toNetwork(id)
        return changed(catalog, projectIndex, project.copy(networks = project.networks + network), command, id)
    }

    private fun updateNetwork(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.UpdateNetwork,
    ): RfAssetMutationResult {
        val index = project.networks.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (project.networks[index] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val replacement = command.input.toNetwork(command.expected.id, command.expected)
        if (replacement == command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.UNCHANGED, replacement.id)
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(networks = project.networks.replacedAt(index, replacement)),
            command,
            replacement.id,
            RfAssetMutationStatus.UPDATED,
        )
    }

    private fun deleteNetwork(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.DeleteNetwork,
    ): RfAssetMutationResult {
        val index = project.networks.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (project.networks[index] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val impact = RfDeletionImpact(
            sectorCount = project.sites.sumOf { site -> site.sectors.count { it.networkId == command.expected.id } },
            receiverCount = project.receivers.count { receiver ->
                receiver.networkId == command.expected.id ||
                    receiver.networkProfiles.any { profile -> profile.networkId == command.expected.id }
            },
        )
        if (impact.hasReferences) {
            return result(
                catalog,
                project,
                command,
                RfAssetMutationStatus.BLOCKED_REFERENCES,
                command.expected.id,
                impact,
            )
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(networks = project.networks.removedAt(index)),
            command,
            command.expected.id,
            RfAssetMutationStatus.DELETED,
            impact,
        )
    }

    private fun createSite(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.CreateSite,
    ): RfAssetMutationResult {
        val id = newEntityId(project, RfEntityKind.SITE)
        val site = command.input.toSite(id)
        return changed(catalog, projectIndex, project.copy(sites = project.sites + site), command, id)
    }

    private fun updateSite(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.UpdateSite,
    ): RfAssetMutationResult {
        val index = project.sites.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (project.sites[index] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val replacement = command.input.toSite(command.expected.id, command.expected.sectors)
        if (replacement == command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.UNCHANGED, replacement.id)
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(sites = project.sites.replacedAt(index, replacement)),
            command,
            replacement.id,
            RfAssetMutationStatus.UPDATED,
        )
    }

    private fun moveSite(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.MoveSite,
    ): RfAssetMutationResult {
        val index = project.sites.indexOfFirst { it.id == command.expected.id }
        if (index < 0) {
            return result(
                catalog,
                project,
                command,
                RfAssetMutationStatus.NOT_FOUND,
                command.expected.id,
            )
        }
        val current = project.sites[index]
        if (current.location == command.location) {
            return result(
                catalog,
                project,
                command,
                RfAssetMutationStatus.UNCHANGED,
                current.id,
            )
        }
        if (current != command.expected) {
            return result(
                catalog,
                project,
                command,
                RfAssetMutationStatus.STALE,
                command.expected.id,
            )
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(
                sites = project.sites.replacedAt(
                    index,
                    current.copy(location = command.location),
                ),
            ),
            command,
            current.id,
            RfAssetMutationStatus.UPDATED,
        )
    }

    private fun deleteSite(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.DeleteSite,
    ): RfAssetMutationResult {
        val index = project.sites.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        val current = project.sites[index]
        if (current != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val impact = RfDeletionImpact(sectorCount = current.sectors.size)
        if (impact.sectorCount > 0 && !command.deleteContainedSectors) {
            return result(
                catalog,
                project,
                command,
                RfAssetMutationStatus.BLOCKED_REFERENCES,
                current.id,
                impact,
            )
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(sites = project.sites.removedAt(index)),
            command,
            current.id,
            RfAssetMutationStatus.DELETED,
            impact,
        )
    }

    private fun createSector(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.CreateSector,
    ): RfAssetMutationResult {
        requireNetworkExists(project, command.input.networkId)
        val siteIndex = project.sites.indexOfFirst { it.id == command.siteId }
        if (siteIndex < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, null)
        val id = newEntityId(project, RfEntityKind.SECTOR)
        val sector = command.input.toSector(id)
        val site = project.sites[siteIndex]
        val updatedSite = site.copy(sectors = site.sectors + sector)
        return changed(
            catalog,
            projectIndex,
            project.copy(sites = project.sites.replacedAt(siteIndex, updatedSite)),
            command,
            id,
        )
    }

    private fun updateSector(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.UpdateSector,
    ): RfAssetMutationResult {
        requireNetworkExists(project, command.input.networkId)
        val siteIndex = project.sites.indexOfFirst { it.id == command.siteId }
        if (siteIndex < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        val site = project.sites[siteIndex]
        val sectorIndex = site.sectors.indexOfFirst { it.id == command.expected.id }
        if (sectorIndex < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (site.sectors[sectorIndex] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val replacement = command.input.toSector(command.expected.id, command.expected)
        if (replacement == command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.UNCHANGED, replacement.id)
        }
        val updatedSite = site.copy(sectors = site.sectors.replacedAt(sectorIndex, replacement))
        return changed(
            catalog,
            projectIndex,
            project.copy(sites = project.sites.replacedAt(siteIndex, updatedSite)),
            command,
            replacement.id,
            RfAssetMutationStatus.UPDATED,
        )
    }

    private fun deleteSector(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.DeleteSector,
    ): RfAssetMutationResult {
        val siteIndex = project.sites.indexOfFirst { it.id == command.siteId }
        if (siteIndex < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        val site = project.sites[siteIndex]
        val sectorIndex = site.sectors.indexOfFirst { it.id == command.expected.id }
        if (sectorIndex < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (site.sectors[sectorIndex] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val updatedSite = site.copy(sectors = site.sectors.removedAt(sectorIndex))
        return changed(
            catalog,
            projectIndex,
            project.copy(sites = project.sites.replacedAt(siteIndex, updatedSite)),
            command,
            command.expected.id,
            RfAssetMutationStatus.DELETED,
        )
    }

    private fun createReceiver(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.CreateReceiver,
    ): RfAssetMutationResult {
        requireNetworkExists(project, command.input.networkId)
        val id = newEntityId(project, RfEntityKind.RECEIVER)
        val receiver = command.input.toReceiver(id)
        return changed(
            catalog,
            projectIndex,
            project.copy(receivers = project.receivers + receiver),
            command,
            id,
        )
    }

    private fun updateReceiver(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.UpdateReceiver,
    ): RfAssetMutationResult {
        requireNetworkExists(project, command.input.networkId)
        val index = project.receivers.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (project.receivers[index] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        val replacement = command.input.toReceiver(command.expected.id, command.expected)
        if (replacement == command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.UNCHANGED, replacement.id)
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(receivers = project.receivers.replacedAt(index, replacement)),
            command,
            replacement.id,
            RfAssetMutationStatus.UPDATED,
        )
    }

    private fun deleteReceiver(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
        command: RfAssetMutationCommand.DeleteReceiver,
    ): RfAssetMutationResult {
        val index = project.receivers.indexOfFirst { it.id == command.expected.id }
        if (index < 0) return result(catalog, project, command, RfAssetMutationStatus.NOT_FOUND, command.expected.id)
        if (project.receivers[index] != command.expected) {
            return result(catalog, project, command, RfAssetMutationStatus.STALE, command.expected.id)
        }
        return changed(
            catalog,
            projectIndex,
            project.copy(receivers = project.receivers.removedAt(index)),
            command,
            command.expected.id,
            RfAssetMutationStatus.DELETED,
        )
    }

    private fun changed(
        catalog: ProjectCatalog,
        projectIndex: Int,
        changedProject: PlannerProject,
        command: RfAssetMutationCommand,
        entityId: String,
        status: RfAssetMutationStatus = RfAssetMutationStatus.CREATED,
        impact: RfDeletionImpact = RfDeletionImpact(),
    ): RfAssetMutationResult {
        val original = catalog.projects[projectIndex]
        val updated = changedProject.copy(
            updatedAtEpochMillis = maxOf(clock.nowEpochMillis(), original.updatedAtEpochMillis),
        )
        val updatedCatalog = catalog.copy(projects = catalog.projects.replacedAt(projectIndex, updated))
        return result(updatedCatalog, updated, command, status, entityId, impact)
    }

    private fun result(
        catalog: ProjectCatalog,
        project: PlannerProject?,
        command: RfAssetMutationCommand,
        status: RfAssetMutationStatus,
        entityId: String?,
        impact: RfDeletionImpact = RfDeletionImpact(),
    ) = RfAssetMutationResult(
        catalog = catalog,
        project = project,
        receipt = RfAssetMutationReceipt(
            requestId = command.requestId,
            kind = command.kind,
            status = status,
            entityId = entityId,
            impact = impact,
        ),
    )

    private fun newEntityId(project: PlannerProject, kind: RfEntityKind): String {
        val id = idGenerator.nextId(kind)
        require(id.isNotBlank() && id == id.trim() && id.length <= MAX_RF_ID_LENGTH) {
            "Generated ${kind.idPrefix} ID must be non-blank, trimmed, and bounded."
        }
        require(id !in project.allRfIds()) { "Generated RF entity ID '$id' already exists." }
        return id
    }

    private fun requireNetworkExists(project: PlannerProject, networkId: String?) {
        require(networkId == null || project.networks.any { it.id == networkId }) {
            "The selected RF network no longer exists in this project."
        }
    }
}

private val RfAssetMutationCommand.kind: RfAssetKind
    get() = when (this) {
        is RfAssetMutationCommand.CreateNetwork,
        is RfAssetMutationCommand.UpdateNetwork,
        is RfAssetMutationCommand.DeleteNetwork,
        -> RfAssetKind.NETWORK
        is RfAssetMutationCommand.CreateSite,
        is RfAssetMutationCommand.UpdateSite,
        is RfAssetMutationCommand.MoveSite,
        is RfAssetMutationCommand.DeleteSite,
        -> RfAssetKind.SITE
        is RfAssetMutationCommand.CreateSector,
        is RfAssetMutationCommand.UpdateSector,
        is RfAssetMutationCommand.DeleteSector,
        -> RfAssetKind.SECTOR
        is RfAssetMutationCommand.CreateReceiver,
        is RfAssetMutationCommand.UpdateReceiver,
        is RfAssetMutationCommand.DeleteReceiver,
        -> RfAssetKind.RECEIVER
    }

private fun RfNetworkInput.toNetwork(id: String, source: RfNetwork? = null) = RfNetwork(
    id = id,
    name = name.trim(),
    system = system,
    downlinkFrequencyMHz = downlinkFrequencyMHz.value,
    bandwidthMHz = bandwidthMHz.value,
    active = active,
    uplinkFrequencyMHz = source?.uplinkFrequencyMHz,
    duplexMode = source?.duplexMode ?: com.gecesars.atxplan.domain.model.DuplexMode.UNSPECIFIED,
    downlinkThresholdDbm = source?.downlinkThresholdDbm,
    uplinkThresholdDbm = source?.uplinkThresholdDbm,
    channelPlan = source?.channelPlan.orEmpty(),
    technologyProfile = source?.technologyProfile,
    legacyParametersJson = source?.legacyParametersJson,
)

private fun RfSiteInput.toSite(id: String, sectors: List<Sector> = emptyList()) = RadioSite(
    id = id,
    name = name.trim(),
    location = location,
    groundElevationM = groundElevationM,
    towerHeightM = towerHeightM,
    notes = notes.trim(),
    sectors = sectors,
)

private fun RfSectorInput.toSector(id: String, source: Sector? = null) = Sector(
    id = id,
    name = name.trim(),
    active = active,
    azimuthDegrees = azimuthDegrees.value,
    electricalTiltDegrees = electricalTiltDegrees.value,
    antennaHeightM = antennaHeightM.value,
    transmitPowerDbm = transmitPowerDbm.value,
    antennaGainDbi = antennaGainDbi.value,
    feederLossDb = feederLossDb.value,
    frequencyMHz = frequencyMHz.value,
    networkId = networkId,
    transmitAntennaPatternId = source?.transmitAntennaPatternId,
    receiveAntennaPatternId = source?.receiveAntennaPatternId,
    receiveAntennaHeightM = source?.receiveAntennaHeightM,
    receiveAntennaGainDbi = source?.receiveAntennaGainDbi,
    receiveSystemLossDb = source?.receiveSystemLossDb,
    cableType = source?.cableType.orEmpty(),
    cableLengthM = source?.cableLengthM,
    equipmentModel = source?.equipmentModel.orEmpty(),
    mimoIndex = source?.mimoIndex,
    simulcastDelayMicros = source?.simulcastDelayMicros,
    legacyParametersJson = source?.legacyParametersJson,
)

private fun RfReceiverInput.toReceiver(id: String, source: Receiver? = null) = Receiver(
    id = id,
    name = name.trim(),
    networkId = networkId,
    location = location,
    antennaHeightM = antennaHeightM,
    antennaGainDbi = antennaGainDbi,
    systemLossDb = systemLossDb,
    sensitivityDbm = sensitivityDbm,
    noiseFigureDb = noiseFigureDb,
    azimuthDegrees = azimuthDegrees,
    electricalTiltDegrees = electricalTiltDegrees,
    notes = notes.trim(),
    equipmentModel = source?.equipmentModel.orEmpty(),
    networkProfiles = source?.networkProfiles.orEmpty(),
)

private fun PlannerProject.allRfIds(): Set<String> = buildSet {
    addAll(networks.map(RfNetwork::id))
    addAll(sites.map(RadioSite::id))
    addAll(sites.flatMap(RadioSite::sectors).map(Sector::id))
    addAll(receivers.map(Receiver::id))
}

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> = toMutableList().apply {
    this[index] = value
}

private fun <T> List<T>.removedAt(index: Int): List<T> = filterIndexed { itemIndex, _ ->
    itemIndex != index
}

private const val MIN_RF_NAME_LENGTH = 2
private const val MAX_RF_NAME_LENGTH = 80
private const val MAX_RF_NOTES_LENGTH = 2_000
private const val MAX_RF_ID_LENGTH = 160
