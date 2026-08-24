package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog

data class RenameProjectCommand(
    val projectId: String,
    val expectedName: String,
    val newName: String,
) {
    init {
        require(projectId.isNotBlank()) {
            "The rename command requires a non-blank project ID."
        }
        require(expectedName.isNotBlank()) {
            "The rename command requires the expected project name."
        }
        require(newName.trim().length in MIN_PROJECT_NAME_LENGTH..MAX_PROJECT_NAME_LENGTH) {
            "Use a project name between $MIN_PROJECT_NAME_LENGTH and " +
                "$MAX_PROJECT_NAME_LENGTH characters."
        }
    }
}

enum class RenameProjectStatus {
    CHANGED,
    UNCHANGED,
    STALE_NAME,
}

data class RenameProjectResult(
    val catalog: ProjectCatalog,
    val project: PlannerProject,
    val status: RenameProjectStatus,
) {
    init {
        require(catalog.projects.singleOrNull { candidate -> candidate.id == project.id } == project) {
            "The renamed project must be present exactly once in the result catalog."
        }
    }

    val didChange: Boolean
        get() = status == RenameProjectStatus.CHANGED
}

/**
 * Renames exactly one existing project as an immutable catalog transition.
 *
 * The project ID is matched exactly so schema-valid imported IDs remain usable.
 * The expected name is checked inside the catalog transaction so a stale editor
 * cannot overwrite a competing rename. A successful rename preserves the project
 * graph and never moves the wall-clock timestamp backwards when an imported project
 * is dated in the future.
 */
class RenameProjectUseCase(
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: RenameProjectCommand,
    ): RenameProjectResult {
        val projectIndex = catalog.projects.indexOfFirst { project ->
            project.id == command.projectId
        }
        require(projectIndex >= 0) {
            "Project '${command.projectId}' was not found."
        }

        val originalProject = catalog.projects[projectIndex]
        val cleanName = command.newName.trim()
        if (cleanName == originalProject.name.trim()) {
            return RenameProjectResult(
                catalog = catalog,
                project = originalProject,
                status = RenameProjectStatus.UNCHANGED,
            )
        }

        if (originalProject.name != command.expectedName) {
            return RenameProjectResult(
                catalog = catalog,
                project = originalProject,
                status = RenameProjectStatus.STALE_NAME,
            )
        }

        val updatedProject = originalProject.copy(
            name = cleanName,
            updatedAtEpochMillis = maxOf(
                clock.nowEpochMillis(),
                originalProject.updatedAtEpochMillis,
            ),
        )
        val updatedProjects = catalog.projects.toMutableList().apply {
            this[projectIndex] = updatedProject
        }
        val updatedCatalog = catalog.copy(projects = updatedProjects)

        return RenameProjectResult(
            catalog = updatedCatalog,
            project = updatedProject,
            status = RenameProjectStatus.CHANGED,
        )
    }
}

private const val MIN_PROJECT_NAME_LENGTH = 2
private const val MAX_PROJECT_NAME_LENGTH = 80
