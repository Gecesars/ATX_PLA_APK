package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.StudyStatus
import com.gecesars.atxplan.domain.model.StudySummary
import com.gecesars.atxplan.domain.model.StudyType
import com.gecesars.atxplan.domain.study.ProjectLinkStudyEngine
import com.gecesars.atxplan.domain.study.ProjectLinkStudyRecord

data class RunProjectLinkStudyCommand(
    val requestId: String,
    val expectedProject: PlannerProject,
    val name: String,
    val siteId: String,
    val sectorId: String,
    val receiverId: String,
) {
    init {
        require(requestId.isNotBlank()) { "A project link-study request requires an ID." }
        require(name.trim().length in 2..80) { "Use a study name between 2 and 80 characters." }
        require(siteId.isNotBlank() && sectorId.isNotBlank() && receiverId.isNotBlank()) {
            "A project link study requires a site, sector, and receiver selection."
        }
    }
}

enum class RunProjectLinkStudyStatus {
    CREATED,
    PROJECT_NOT_FOUND,
    STALE,
    ENDPOINT_NOT_FOUND,
    INCOMPATIBLE_NETWORK,
    ID_COLLISION,
}

data class RunProjectLinkStudyResult(
    val catalog: ProjectCatalog,
    val status: RunProjectLinkStudyStatus,
    val record: ProjectLinkStudyRecord? = null,
)

fun interface LinkStudyRecordIdGenerator {
    fun newId(): String
}

class RunProjectLinkStudyUseCase(
    private val idGenerator: LinkStudyRecordIdGenerator,
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: RunProjectLinkStudyCommand,
    ): RunProjectLinkStudyResult {
        val latestProject = catalog.projects.firstOrNull { project ->
            project.id == command.expectedProject.id
        } ?: return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.PROJECT_NOT_FOUND)
        if (latestProject != command.expectedProject) {
            return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.STALE)
        }

        val site = latestProject.sites.firstOrNull { it.id == command.siteId }
        val sector = site?.sectors?.firstOrNull { it.id == command.sectorId }
        val receiver = latestProject.receivers.firstOrNull { it.id == command.receiverId }
        if (site == null || sector == null || receiver == null) {
            return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.ENDPOINT_NOT_FOUND)
        }
        val networkId = sector.networkId
            ?: return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.INCOMPATIBLE_NETWORK)
        val network = latestProject.networks.firstOrNull { it.id == networkId }
            ?: return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.INCOMPATIBLE_NETWORK)
        val receiverIsCompatible = receiver.networkId == network.id ||
            receiver.networkProfiles.any { profile -> profile.networkId == network.id }
        if (!receiverIsCompatible) {
            return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.INCOMPATIBLE_NETWORK)
        }

        val recordId = idGenerator.newId()
        require(recordId.isNotBlank()) { "The link-study ID generator returned a blank ID." }
        if (latestProject.linkStudies.any { it.id == recordId } || latestProject.studies.any { it.id == recordId }) {
            return RunProjectLinkStudyResult(catalog, RunProjectLinkStudyStatus.ID_COLLISION)
        }
        val createdAt = maxOf(clock.nowEpochMillis(), latestProject.updatedAtEpochMillis)
        val record = ProjectLinkStudyEngine.calculate(
            id = recordId,
            name = command.name,
            createdAtEpochMillis = createdAt,
            projectId = latestProject.id,
            projectName = latestProject.name,
            network = network,
            site = site,
            sector = sector,
            receiver = receiver,
        )
        val updatedProject = latestProject.copy(
            updatedAtEpochMillis = createdAt,
            studies = latestProject.studies + StudySummary(
                id = record.id,
                name = record.name,
                type = StudyType.POINT_TO_POINT,
                status = StudyStatus.COMPLETED,
                updatedAtEpochMillis = createdAt,
            ),
            linkStudies = latestProject.linkStudies + record,
        )
        val updatedCatalog = catalog.copy(
            projects = catalog.projects.map { project ->
                if (project.id == updatedProject.id) updatedProject else project
            },
        )
        return RunProjectLinkStudyResult(
            catalog = updatedCatalog,
            status = RunProjectLinkStudyStatus.CREATED,
            record = record,
        )
    }
}
