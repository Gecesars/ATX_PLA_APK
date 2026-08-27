package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog

/** Captures the complete active aggregate reviewed before an archive request. */
data class ArchiveProjectCommand(
    val expectedProject: PlannerProject,
)

enum class ArchiveProjectStatus {
    ARCHIVED,
    STALE_PROJECT,
    ALREADY_ARCHIVED,
    NOT_FOUND,
}

data class ArchiveProjectResult(
    val catalog: ProjectCatalog,
    val expectedProject: PlannerProject,
    val currentProject: PlannerProject?,
    val archivedProject: ArchivedProject?,
    val status: ArchiveProjectStatus,
) {
    init {
        when (status) {
            ArchiveProjectStatus.ARCHIVED -> require(
                currentProject == expectedProject &&
                    archivedProject?.project == expectedProject &&
                    catalog.projects.none { project -> project.id == expectedProject.id } &&
                    catalog.archivedProjects.singleOrNull { archived ->
                        archived.project.id == expectedProject.id
                    } == archivedProject &&
                    if (catalog.projects.isEmpty()) {
                        catalog.selectedProjectId == null
                    } else {
                        catalog.projects.count { project ->
                            project.id == catalog.selectedProjectId
                        } == 1
                    },
            ) {
                "An archived project must move unchanged from the active to the archived catalog."
            }

            ArchiveProjectStatus.STALE_PROJECT -> require(
                currentProject != null &&
                    currentProject.id == expectedProject.id &&
                    currentProject != expectedProject &&
                    archivedProject == null &&
                    catalog.projects.singleOrNull { project ->
                        project.id == expectedProject.id
                    } == currentProject &&
                    catalog.archivedProjects.none { archived ->
                        archived.project.id == expectedProject.id
                    },
            ) {
                "A stale archive request must expose the conflicting active project."
            }

            ArchiveProjectStatus.ALREADY_ARCHIVED -> require(
                currentProject == null &&
                    archivedProject != null &&
                    archivedProject.project.id == expectedProject.id &&
                    catalog.projects.none { project -> project.id == expectedProject.id } &&
                    catalog.archivedProjects.singleOrNull { archived ->
                        archived.project.id == expectedProject.id
                    } == archivedProject,
            ) {
                "An already archived project must remain available in the archive."
            }

            ArchiveProjectStatus.NOT_FOUND -> require(
                currentProject == null &&
                    archivedProject == null &&
                    catalog.projects.none { project -> project.id == expectedProject.id } &&
                    catalog.archivedProjects.none { archived ->
                        archived.project.id == expectedProject.id
                    },
            ) {
                "A missing archive target must be absent from active and archived projects."
            }
        }
    }

    val didArchive: Boolean
        get() = status == ArchiveProjectStatus.ARCHIVED
}

/**
 * Moves one exact active project snapshot into the archive as an immutable catalog transition.
 *
 * The project aggregate is retained unchanged. Stale, repeated, and missing requests are typed
 * no-ops and return the exact supplied catalog instance. Selection follows the same deterministic
 * next/previous policy used by permanent deletion.
 */
class ArchiveProjectUseCase(
    private val clock: EpochMillisClock,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: ArchiveProjectCommand,
    ): ArchiveProjectResult {
        val expectedProject = command.expectedProject
        val projectIndex = catalog.projects.indexOfFirst { project ->
            project.id == expectedProject.id
        }

        if (projectIndex < 0) {
            val existingArchive = catalog.archivedProjects.firstOrNull { archived ->
                archived.project.id == expectedProject.id
            }
            return ArchiveProjectResult(
                catalog = catalog,
                expectedProject = expectedProject,
                currentProject = null,
                archivedProject = existingArchive,
                status = if (existingArchive == null) {
                    ArchiveProjectStatus.NOT_FOUND
                } else {
                    ArchiveProjectStatus.ALREADY_ARCHIVED
                },
            )
        }

        val currentProject = catalog.projects[projectIndex]
        if (currentProject != expectedProject) {
            return ArchiveProjectResult(
                catalog = catalog,
                expectedProject = expectedProject,
                currentProject = currentProject,
                archivedProject = null,
                status = ArchiveProjectStatus.STALE_PROJECT,
            )
        }

        val remainingProjects = catalog.projects.filterIndexed { index, _ ->
            index != projectIndex
        }
        val archivedProject = ArchivedProject(
            project = currentProject,
            archivedAtEpochMillis = clock.nowEpochMillis(),
            originalProjectIndex = projectIndex,
        )
        val updatedCatalog = catalog.copy(
            selectedProjectId = selectedProjectIdAfterRemoval(
                catalog = catalog,
                removedProjectIndex = projectIndex,
                remainingProjects = remainingProjects,
            ),
            projects = remainingProjects,
            archivedProjects = catalog.archivedProjects + archivedProject,
        )

        return ArchiveProjectResult(
            catalog = updatedCatalog,
            expectedProject = expectedProject,
            currentProject = currentProject,
            archivedProject = archivedProject,
            status = ArchiveProjectStatus.ARCHIVED,
        )
    }
}

internal fun selectedProjectIdAfterRemoval(
    catalog: ProjectCatalog,
    removedProjectIndex: Int,
    remainingProjects: List<PlannerProject>,
): String? = when {
    remainingProjects.isEmpty() -> null
    catalog.selectedProjectId == catalog.projects[removedProjectIndex].id ->
        catalog.projects.getOrNull(removedProjectIndex + 1)?.id
            ?: catalog.projects[removedProjectIndex - 1].id

    remainingProjects.any { project -> project.id == catalog.selectedProjectId } ->
        catalog.selectedProjectId

    else -> remainingProjects.first().id
}
