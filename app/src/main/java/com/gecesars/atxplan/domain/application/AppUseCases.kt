package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.data.project.ProjectRepository
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.domain.rf.RfCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppCoroutineDispatchers(
    val storage: CoroutineDispatcher = Dispatchers.IO,
    val computation: CoroutineDispatcher = Dispatchers.Default,
)

fun interface ProjectCreator {
    fun create(name: String, customer: String): PlannerProject
}

fun interface LinkBudgetCalculator {
    suspend fun calculate(input: LinkBudgetInput): LinkBudgetResult
}

data class ProjectCreationResult(
    val catalog: ProjectCatalog,
    val project: PlannerProject,
)

class LoadProjectCatalogUseCase(
    private val repository: ProjectRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ProjectCatalog = withContext(dispatcher) {
        repository.loadCatalog()
    }
}

class UpdateProjectCatalogUseCase(
    private val repository: ProjectRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(
        transform: (ProjectCatalog) -> ProjectCatalog,
    ): ProjectCatalog = withContext(dispatcher) {
        repository.updateCatalog(transform)
    }
}

class CreateProjectUseCase(
    private val projectCreator: ProjectCreator,
) {
    operator fun invoke(
        catalog: ProjectCatalog,
        name: String,
        customer: String,
    ): ProjectCreationResult {
        val project = projectCreator.create(name, customer)
        return ProjectCreationResult(
            catalog = catalog.copy(
                selectedProjectId = project.id,
                projects = catalog.projects + project,
            ),
            project = project,
        )
    }
}

class SelectProjectUseCase {
    operator fun invoke(catalog: ProjectCatalog, projectId: String): ProjectCatalog? {
        if (catalog.projects.none { it.id == projectId }) return null
        if (catalog.selectedProjectId == projectId) return null
        return catalog.copy(selectedProjectId = projectId)
    }
}

class CalculateLinkBudgetUseCase(
    private val calculator: LinkBudgetCalculator,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(input: LinkBudgetInput): LinkBudgetResult = withContext(dispatcher) {
        calculator.calculate(input)
    }
}

data class AppUseCases(
    val loadProjectCatalog: LoadProjectCatalogUseCase,
    val updateProjectCatalog: UpdateProjectCatalogUseCase,
    val createProject: CreateProjectUseCase,
    val renameProject: RenameProjectUseCase,
    val duplicateProject: DuplicateProjectUseCase,
    val selectProject: SelectProjectUseCase,
    val addRfPath: AddRfPathUseCase,
    val calculateLinkBudget: CalculateLinkBudgetUseCase,
) {
    companion object {
        fun create(
            repository: ProjectRepository,
            dispatchers: AppCoroutineDispatchers = AppCoroutineDispatchers(),
            projectCreator: ProjectCreator = ProjectCreator { name, customer ->
                ProjectFactory.create(name, customer)
            },
            linkBudgetCalculator: LinkBudgetCalculator = LinkBudgetCalculator { input ->
                RfCalculator.linkBudget(input)
            },
            rfEntityIdGenerator: RfEntityIdGenerator = RfEntityIdGenerator { kind ->
                "${kind.idPrefix}-${UUID.randomUUID()}"
            },
            projectDuplicationIdGenerator: ProjectDuplicationIdGenerator =
                ProjectDuplicationIdGenerator { "project-${UUID.randomUUID()}" },
            clock: EpochMillisClock = EpochMillisClock { System.currentTimeMillis() },
        ): AppUseCases = AppUseCases(
            loadProjectCatalog = LoadProjectCatalogUseCase(repository, dispatchers.storage),
            updateProjectCatalog = UpdateProjectCatalogUseCase(repository, dispatchers.storage),
            createProject = CreateProjectUseCase(projectCreator),
            renameProject = RenameProjectUseCase(clock),
            duplicateProject = DuplicateProjectUseCase(projectDuplicationIdGenerator, clock),
            selectProject = SelectProjectUseCase(),
            addRfPath = AddRfPathUseCase(rfEntityIdGenerator, clock),
            calculateLinkBudget = CalculateLinkBudgetUseCase(
                calculator = linkBudgetCalculator,
                dispatcher = dispatchers.computation,
            ),
        )
    }
}
