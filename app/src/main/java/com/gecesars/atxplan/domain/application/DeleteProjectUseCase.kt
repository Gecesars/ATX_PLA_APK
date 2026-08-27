package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog

/**
 * Captures the complete project aggregate that the caller reviewed before requesting deletion.
 *
 * The snapshot is compared structurally with the project in the latest durable catalog. This
 * prevents an older screen from deleting a peer's rename, RF edit, study update, or any other
 * aggregate change that happened after the confirmation content was presented.
 */
data class DeleteProjectCommand(
    val expectedProject: PlannerProject,
)

enum class DeleteProjectStatus {
    DELETED,
    STALE_PROJECT,
    NOT_FOUND,
}

data class DeleteProjectResult(
    val catalog: ProjectCatalog,
    val expectedProject: PlannerProject,
    val currentProject: PlannerProject?,
    val status: DeleteProjectStatus,
) {
    init {
        when (status) {
            DeleteProjectStatus.DELETED -> {
                require(
                    currentProject == expectedProject &&
                        catalog.projects.none { project -> project.id == expectedProject.id },
                ) {
                    "A deleted project must match the expected snapshot and be absent from the result."
                }
                require(
                    if (catalog.projects.isEmpty()) {
                        catalog.selectedProjectId == null
                    } else {
                        catalog.projects.count { project ->
                            project.id == catalog.selectedProjectId
                        } == 1
                    },
                ) {
                    "A completed deletion must have a valid selection, or no selection when empty."
                }
            }

            DeleteProjectStatus.STALE_PROJECT -> require(
                currentProject != null &&
                    currentProject.id == expectedProject.id &&
                    currentProject != expectedProject &&
                    catalog.projects.singleOrNull { project ->
                        project.id == expectedProject.id
                    } == currentProject,
            ) {
                "A stale project must expose the conflicting aggregate from the result catalog."
            }

            DeleteProjectStatus.NOT_FOUND -> require(
                currentProject == null &&
                    catalog.projects.none { project -> project.id == expectedProject.id },
            ) {
                "A missing project must remain absent from the result catalog."
            }
        }
    }

    val didDelete: Boolean
        get() = status == DeleteProjectStatus.DELETED
}

/**
 * Deletes one exact project snapshot from the latest catalog supplied by the repository
 * transaction.
 *
 * Peer removal and peer edits are represented by [DeleteProjectStatus] instead of exceptions.
 * Stale and missing outcomes return the supplied catalog instance unchanged, avoiding an
 * unrelated repair write during a rejected deletion.
 * Unaffected aggregate instances and their original ordering are preserved. Selection stays on a
 * valid unaffected project when possible. Deleting the selected project chooses the next project
 * in the original order, or the previous project when the deleted item was last.
 */
class DeleteProjectUseCase {
    operator fun invoke(
        catalog: ProjectCatalog,
        command: DeleteProjectCommand,
    ): DeleteProjectResult {
        val expectedProject = command.expectedProject
        val projectIndex = catalog.projects.indexOfFirst { project ->
            project.id == expectedProject.id
        }

        if (projectIndex < 0) {
            return DeleteProjectResult(
                catalog = catalog,
                expectedProject = expectedProject,
                currentProject = null,
                status = DeleteProjectStatus.NOT_FOUND,
            )
        }

        val currentProject = catalog.projects[projectIndex]
        if (currentProject != expectedProject) {
            return DeleteProjectResult(
                catalog = catalog,
                expectedProject = expectedProject,
                currentProject = currentProject,
                status = DeleteProjectStatus.STALE_PROJECT,
            )
        }

        val remainingProjects = catalog.projects.filterIndexed { index, _ ->
            index != projectIndex
        }
        val selectedProjectId = when {
            remainingProjects.isEmpty() -> null
            catalog.selectedProjectId == expectedProject.id ->
                catalog.projects.getOrNull(projectIndex + 1)?.id
                    ?: catalog.projects[projectIndex - 1].id

            remainingProjects.any { project -> project.id == catalog.selectedProjectId } ->
                catalog.selectedProjectId

            else -> remainingProjects.first().id
        }
        val updatedCatalog = catalog.copy(
            selectedProjectId = selectedProjectId,
            projects = remainingProjects,
        )

        return DeleteProjectResult(
            catalog = updatedCatalog,
            expectedProject = expectedProject,
            currentProject = currentProject,
            status = DeleteProjectStatus.DELETED,
        )
    }
}
