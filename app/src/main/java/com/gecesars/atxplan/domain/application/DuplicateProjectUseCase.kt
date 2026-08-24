package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog

fun interface ProjectDuplicationIdGenerator {
    fun nextId(): String
}

data class DuplicateProjectCommand(
    val sourceProjectId: String,
    val newName: String,
) {
    init {
        require(sourceProjectId.isNotBlank()) {
            "The duplicate command requires a non-blank source project ID."
        }
        require(newName.trim().length in MIN_PROJECT_NAME_LENGTH..MAX_PROJECT_NAME_LENGTH) {
            "Use a project name between $MIN_PROJECT_NAME_LENGTH and " +
                "$MAX_PROJECT_NAME_LENGTH characters."
        }
    }
}

data class DuplicateProjectResult(
    val catalog: ProjectCatalog,
    val sourceProject: PlannerProject,
    val duplicatedProject: PlannerProject,
) {
    init {
        require(catalog.projects.singleOrNull { project -> project.id == sourceProject.id } == sourceProject) {
            "The source project must be present exactly once in the result catalog."
        }
        require(
            duplicatedProject.id != sourceProject.id &&
                catalog.projects.singleOrNull { project ->
                    project.id == duplicatedProject.id
                } == duplicatedProject,
        ) {
            "The duplicated project must have a fresh identity and be present exactly once."
        }
        require(catalog.selectedProjectId == duplicatedProject.id) {
            "The duplicated project must be selected in the result catalog."
        }
    }
}

/**
 * Copies the latest durable source aggregate as one immutable catalog transition.
 *
 * RF and study identities are project-scoped, so their values and internal references remain
 * unchanged inside the new project. Only the root project receives a fresh identity and creation
 * timestamps. The duplicate is appended and selected explicitly.
 */
class DuplicateProjectUseCase(
    private val idGenerator: ProjectDuplicationIdGenerator,
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: DuplicateProjectCommand,
    ): DuplicateProjectResult {
        val sourceProject = catalog.projects.singleOrNull { project ->
            project.id == command.sourceProjectId
        } ?: throw IllegalArgumentException(
            "Project '${command.sourceProjectId}' was not found.",
        )
        val duplicatedProjectId = idGenerator.nextId()
        require(
            duplicatedProjectId.isNotBlank() &&
                duplicatedProjectId == duplicatedProjectId.trim() &&
                duplicatedProjectId.length <= MAX_PROJECT_ID_LENGTH &&
                duplicatedProjectId.none(Char::isISOControl),
        ) {
            "Generated project ID must be non-blank, trimmed, and no longer than " +
                "$MAX_PROJECT_ID_LENGTH characters, with no control characters."
        }
        require(catalog.projects.none { project -> project.id == duplicatedProjectId }) {
            "Generated project ID '$duplicatedProjectId' already exists in the catalog."
        }

        val nowEpochMillis = clock.nowEpochMillis()
        val duplicatedProject = sourceProject.copy(
            id = duplicatedProjectId,
            name = command.newName.trim(),
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            networks = sourceProject.networks.map { network -> network.copy() },
            sites = sourceProject.sites.map { site ->
                site.copy(
                    sectors = site.sectors.map { sector -> sector.copy() },
                )
            },
            studies = sourceProject.studies.map { study -> study.copy() },
            receivers = sourceProject.receivers.map { receiver -> receiver.copy() },
        )
        val updatedCatalog = catalog.copy(
            selectedProjectId = duplicatedProject.id,
            projects = catalog.projects + duplicatedProject,
        )

        return DuplicateProjectResult(
            catalog = updatedCatalog,
            sourceProject = sourceProject,
            duplicatedProject = duplicatedProject,
        )
    }
}

private const val MIN_PROJECT_NAME_LENGTH = 2
private const val MAX_PROJECT_NAME_LENGTH = 80
private const val MAX_PROJECT_ID_LENGTH = 128
