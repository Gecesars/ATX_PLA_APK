package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog

/** Captures the complete archive record reviewed before a restore request. */
data class RestoreProjectCommand(
    val expectedArchivedProject: ArchivedProject,
)

enum class RestoreProjectStatus {
    RESTORED,
    STALE_ARCHIVE,
    ALREADY_ACTIVE,
    NOT_FOUND,
}

data class RestoreProjectResult(
    val catalog: ProjectCatalog,
    val expectedArchivedProject: ArchivedProject,
    val currentArchivedProject: ArchivedProject?,
    val currentActiveProject: PlannerProject?,
    val restoredProject: PlannerProject?,
    val status: RestoreProjectStatus,
) {
    init {
        val projectId = expectedArchivedProject.project.id
        when (status) {
            RestoreProjectStatus.RESTORED -> require(
                currentArchivedProject == expectedArchivedProject &&
                    currentActiveProject == null &&
                    restoredProject == expectedArchivedProject.project &&
                    catalog.projects.singleOrNull { project -> project.id == projectId } ==
                    restoredProject &&
                    catalog.archivedProjects.none { archived -> archived.project.id == projectId } &&
                    catalog.selectedProjectId == projectId,
            ) {
                "A restored project must move unchanged into the active selected catalog."
            }

            RestoreProjectStatus.STALE_ARCHIVE -> require(
                currentArchivedProject != null &&
                    currentArchivedProject.project.id == projectId &&
                    currentArchivedProject != expectedArchivedProject &&
                    currentActiveProject == null &&
                    restoredProject == null &&
                    catalog.projects.none { project -> project.id == projectId } &&
                    catalog.archivedProjects.singleOrNull { archived ->
                        archived.project.id == projectId
                    } == currentArchivedProject,
            ) {
                "A stale restore request must expose the conflicting archive record."
            }

            RestoreProjectStatus.ALREADY_ACTIVE -> require(
                currentActiveProject != null &&
                    currentActiveProject.id == projectId &&
                    currentArchivedProject == null &&
                    restoredProject == null &&
                    catalog.projects.singleOrNull { project -> project.id == projectId } ==
                    currentActiveProject &&
                    catalog.archivedProjects.none { archived -> archived.project.id == projectId },
            ) {
                "An already active project must remain outside the archive."
            }

            RestoreProjectStatus.NOT_FOUND -> require(
                currentArchivedProject == null &&
                    currentActiveProject == null &&
                    restoredProject == null &&
                    catalog.projects.none { project -> project.id == projectId } &&
                    catalog.archivedProjects.none { archived -> archived.project.id == projectId },
            ) {
                "A missing restore target must be absent from active and archived projects."
            }
        }
    }

    val didRestore: Boolean
        get() = status == RestoreProjectStatus.RESTORED
}

/**
 * Restores one exact archive record as an immutable catalog transition.
 *
 * The active insertion index is clamped against the latest catalog so intervening project changes
 * cannot make restoration invalid. A successful restore selects the restored project. Stale,
 * repeated, and missing requests return the exact supplied catalog instance without mutation.
 */
class RestoreProjectUseCase {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: RestoreProjectCommand,
    ): RestoreProjectResult {
        val expectedArchive = command.expectedArchivedProject
        val projectId = expectedArchive.project.id
        val activeProject = catalog.projects.firstOrNull { project -> project.id == projectId }
        if (activeProject != null) {
            return RestoreProjectResult(
                catalog = catalog,
                expectedArchivedProject = expectedArchive,
                currentArchivedProject = null,
                currentActiveProject = activeProject,
                restoredProject = null,
                status = RestoreProjectStatus.ALREADY_ACTIVE,
            )
        }

        val archiveIndex = catalog.archivedProjects.indexOfFirst { archived ->
            archived.project.id == projectId
        }
        if (archiveIndex < 0) {
            return RestoreProjectResult(
                catalog = catalog,
                expectedArchivedProject = expectedArchive,
                currentArchivedProject = null,
                currentActiveProject = null,
                restoredProject = null,
                status = RestoreProjectStatus.NOT_FOUND,
            )
        }

        val currentArchive = catalog.archivedProjects[archiveIndex]
        if (currentArchive != expectedArchive) {
            return RestoreProjectResult(
                catalog = catalog,
                expectedArchivedProject = expectedArchive,
                currentArchivedProject = currentArchive,
                currentActiveProject = null,
                restoredProject = null,
                status = RestoreProjectStatus.STALE_ARCHIVE,
            )
        }

        val insertionIndex = currentArchive.originalProjectIndex.coerceIn(
            minimumValue = 0,
            maximumValue = catalog.projects.size,
        )
        val activeProjects = catalog.projects.toMutableList().apply {
            add(insertionIndex, currentArchive.project)
        }
        val archivedProjects = catalog.archivedProjects.filterIndexed { index, _ ->
            index != archiveIndex
        }
        val updatedCatalog = catalog.copy(
            selectedProjectId = currentArchive.project.id,
            projects = activeProjects,
            archivedProjects = archivedProjects,
        )

        return RestoreProjectResult(
            catalog = updatedCatalog,
            expectedArchivedProject = expectedArchive,
            currentArchivedProject = currentArchive,
            currentActiveProject = null,
            restoredProject = currentArchive.project,
            status = RestoreProjectStatus.RESTORED,
        )
    }
}
