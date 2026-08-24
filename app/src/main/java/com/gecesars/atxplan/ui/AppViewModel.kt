package com.gecesars.atxplan.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.project.FileProjectRepository
import com.gecesars.atxplan.data.project.ProjectRepository
import com.gecesars.atxplan.data.project.ProjectStorageException
import com.gecesars.atxplan.domain.application.AppUseCases
import com.gecesars.atxplan.domain.application.AddRfPathCommand
import com.gecesars.atxplan.domain.application.DuplicateProjectCommand
import com.gecesars.atxplan.domain.application.RenameProjectCommand
import com.gecesars.atxplan.domain.application.RenameProjectStatus
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AppUiAction {
    data class CreateProject(val name: String, val customer: String) : AppUiAction

    data class RenameProject(val command: RenameProjectCommand) : AppUiAction

    data class DuplicateProject(val command: DuplicateProjectCommand) : AppUiAction

    data class SelectProject(val projectId: String) : AppUiAction

    data class CalculateLinkBudget(val input: LinkBudgetInput) : AppUiAction

    data class AddRfPath(val command: AddRfPathCommand) : AppUiAction

    data object RetryLoad : AppUiAction

    data object DismissNotice : AppUiAction
}

sealed interface AppUiEffect {
    data class ShowNotice(val message: String) : AppUiEffect
}

enum class AppProblemCode {
    CATALOG_LOAD_FAILED,
    CATALOG_SAVE_FAILED,
    LINK_BUDGET_FAILED,
}

enum class AppRecoveryAction {
    RETRY_CATALOG_LOAD,
    EDIT_LINK_PARAMETERS,
}

data class AppProblem(
    val code: AppProblemCode,
    val userMessage: String,
    val recoveryAction: AppRecoveryAction,
)

data class AppUiState(
    val isLoading: Boolean = true,
    val isCatalogWritable: Boolean = false,
    val isSavingCatalog: Boolean = false,
    val catalogMutationCompletionCount: Long = 0L,
    val isCalculating: Boolean = false,
    val catalog: ProjectCatalog = ProjectCatalog(),
    val pendingEffect: AppUiEffect? = null,
    val storageProblem: AppProblem? = null,
    val linkBudgetInput: LinkBudgetInput? = null,
    val linkBudgetResult: LinkBudgetResult? = null,
    val calculatorProblem: AppProblem? = null,
) {
    val selectedProject: PlannerProject?
        get() = catalog.selectedProject

    // Compatibility bridge for the current snackbar host. New UI code should consume pendingEffect.
    val notice: String?
        get() = (pendingEffect as? AppUiEffect.ShowNotice)?.message

    val storageError: String?
        get() = storageProblem?.userMessage

    val calculatorError: String?
        get() = calculatorProblem?.userMessage
}

class AppViewModel(
    private val useCases: AppUseCases,
) : ViewModel() {
    constructor(repository: ProjectRepository) : this(AppUseCases.create(repository))

    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private val catalogMutex = Mutex()
    private val catalogReady = CompletableDeferred<Unit>()
    private var calculationJob: Job? = null

    init {
        loadCatalog()
    }

    fun onAction(action: AppUiAction) {
        when (action) {
            is AppUiAction.CreateProject -> handleCreateProject(action.name, action.customer)
            is AppUiAction.RenameProject -> handleRenameProject(action.command)
            is AppUiAction.DuplicateProject -> handleDuplicateProject(action.command)
            is AppUiAction.SelectProject -> handleSelectProject(action.projectId)
            is AppUiAction.CalculateLinkBudget -> handleCalculateLinkBudget(action.input)
            is AppUiAction.AddRfPath -> handleAddRfPath(action.command)
            AppUiAction.RetryLoad -> loadCatalog()
            AppUiAction.DismissNotice -> mutableState.update { it.copy(pendingEffect = null) }
        }
    }

    fun createProject(name: String, customer: String) {
        onAction(AppUiAction.CreateProject(name, customer))
    }

    fun renameProject(command: RenameProjectCommand) {
        onAction(AppUiAction.RenameProject(command))
    }

    fun duplicateProject(command: DuplicateProjectCommand) {
        onAction(AppUiAction.DuplicateProject(command))
    }

    fun selectProject(projectId: String) {
        onAction(AppUiAction.SelectProject(projectId))
    }

    fun calculateLinkBudget(input: LinkBudgetInput) {
        onAction(AppUiAction.CalculateLinkBudget(input))
    }

    fun addRfPath(command: AddRfPathCommand) {
        onAction(AppUiAction.AddRfPath(command))
    }

    fun dismissNotice() {
        onAction(AppUiAction.DismissNotice)
    }

    fun retryLoad() {
        onAction(AppUiAction.RetryLoad)
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            catalogMutex.withLock {
                mutableState.update {
                    it.copy(
                        isLoading = true,
                        isCatalogWritable = false,
                        storageProblem = null,
                    )
                }
                try {
                    runSuspendCatching { useCases.loadProjectCatalog() }
                        .onSuccess { catalog ->
                            mutableState.update {
                                it.copy(
                                    isLoading = false,
                                    isCatalogWritable = true,
                                    catalog = catalog,
                                    pendingEffect = null,
                                    storageProblem = null,
                                )
                            }
                        }
                        .onFailure { error ->
                            mutableState.update {
                                it.copy(
                                    isLoading = false,
                                    isCatalogWritable = false,
                                    catalog = ProjectCatalog(),
                                    storageProblem = storageProblem(
                                        code = AppProblemCode.CATALOG_LOAD_FAILED,
                                        error = error,
                                        fallbackMessage = "The local catalog could not be opened.",
                                    ),
                                )
                            }
                        }
                } finally {
                    catalogReady.complete(Unit)
                }
            }
        }
    }

    private fun handleCreateProject(name: String, customer: String) {
        persistCatalogMutation { current ->
            val result = useCases.createProject(current, name, customer)
            CatalogMutation(
                updatedCatalog = result.catalog,
                successEffect = AppUiEffect.ShowNotice(
                    "Project \"${result.project.name}\" was created in local storage.",
                ),
            )
        }
    }

    private fun handleRenameProject(command: RenameProjectCommand) {
        persistCatalogMutation { current ->
            val result = useCases.renameProject(current, command)
            when (result.status) {
                RenameProjectStatus.UNCHANGED -> null
                RenameProjectStatus.STALE_NAME -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project name changed in local storage. " +
                            "Review the latest name and try again.",
                    ),
                )
                RenameProjectStatus.CHANGED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice(
                        "Project renamed from \"${command.expectedName}\" " +
                            "to \"${result.project.name}\" " +
                            "in local storage.",
                    ),
                )
            }
        }
    }

    private fun handleDuplicateProject(command: DuplicateProjectCommand) {
        persistCatalogMutation { current ->
            val result = useCases.duplicateProject(current, command)
            CatalogMutation(
                updatedCatalog = result.catalog,
                successEffect = AppUiEffect.ShowNotice(
                    "Project \"${result.sourceProject.name}\" was duplicated as " +
                        "\"${result.duplicatedProject.name}\" in local storage.",
                ),
            )
        }
    }

    private fun handleSelectProject(projectId: String) {
        persistCatalogMutation { current ->
            useCases.selectProject(current, projectId)?.let { updated ->
                CatalogMutation(updatedCatalog = updated)
            }
        }
    }

    private fun handleAddRfPath(command: AddRfPathCommand) {
        persistCatalogMutation { current ->
            val result = useCases.addRfPath(current, command)
            CatalogMutation(
                updatedCatalog = result.catalog,
                successEffect = AppUiEffect.ShowNotice(
                    "RF path \"${result.network.name}\" was saved with its transmitter and receiver.",
                ),
            )
        }
    }

    private fun handleCalculateLinkBudget(input: LinkBudgetInput) {
        calculationJob?.cancel()
        mutableState.update {
            it.copy(
                isCalculating = true,
                linkBudgetInput = null,
                linkBudgetResult = null,
                calculatorProblem = null,
            )
        }
        calculationJob = viewModelScope.launch {
            runSuspendCatching { useCases.calculateLinkBudget(input) }
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(
                            isCalculating = false,
                            linkBudgetInput = input,
                            linkBudgetResult = result,
                            calculatorProblem = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isCalculating = false,
                            linkBudgetInput = null,
                            linkBudgetResult = null,
                            calculatorProblem = AppProblem(
                                code = AppProblemCode.LINK_BUDGET_FAILED,
                                userMessage = when (error) {
                                    is IllegalArgumentException -> error.message
                                        ?: "Check the link parameters and try again."
                                    else -> "The link budget could not be calculated."
                                },
                                recoveryAction = AppRecoveryAction.EDIT_LINK_PARAMETERS,
                            ),
                        )
                    }
                }
        }
    }

    private fun persistCatalogMutation(
        mutation: (ProjectCatalog) -> CatalogMutation?,
    ) {
        viewModelScope.launch {
            catalogReady.await()
            catalogMutex.withLock {
                if (!mutableState.value.isCatalogWritable) {
                    mutableState.update {
                        it.copy(
                            pendingEffect = AppUiEffect.ShowNotice(
                                "The local catalog must load successfully before it can be changed.",
                            ),
                            catalogMutationCompletionCount =
                                it.catalogMutationCompletionCount + 1L,
                        )
                    }
                    return@withLock
                }
                mutableState.update { it.copy(isSavingCatalog = true) }
                runSuspendCatching {
                    var requestedMutation: CatalogMutation? = null
                    var didCommitChange = false
                    val committedCatalog = useCases.updateProjectCatalog { latestCatalog ->
                        val requested = try {
                            mutation(latestCatalog)
                        } catch (error: Exception) {
                            throw CatalogMutationRejected(error)
                        }
                        requestedMutation = requested
                        didCommitChange = requested != null &&
                            requested.updatedCatalog != latestCatalog
                        if (didCommitChange) {
                            checkNotNull(requested).updatedCatalog
                        } else {
                            latestCatalog
                        }
                    }
                    PersistedCatalogMutation(
                        catalog = committedCatalog,
                        completionEffect = if (didCommitChange) {
                            requestedMutation?.successEffect
                        } else {
                            requestedMutation?.noChangeEffect
                        },
                        didCommitChange = didCommitChange,
                    )
                }
                    .onSuccess { committed ->
                        mutableState.update {
                            it.copy(
                                isSavingCatalog = false,
                                catalogMutationCompletionCount =
                                    it.catalogMutationCompletionCount + 1L,
                                catalog = committed.catalog,
                                pendingEffect = committed.completionEffect ?: it.pendingEffect,
                                storageProblem = if (committed.didCommitChange) {
                                    null
                                } else {
                                    it.storageProblem
                                },
                            )
                        }
                    }
                    .onFailure { error ->
                        val rejected = error as? CatalogMutationRejected
                        if (rejected != null) {
                            mutableState.update {
                                it.copy(
                                    isSavingCatalog = false,
                                    catalogMutationCompletionCount =
                                        it.catalogMutationCompletionCount + 1L,
                                    pendingEffect = AppUiEffect.ShowNotice(
                                        rejected.cause?.message ?: "Invalid project data.",
                                    ),
                                )
                            }
                        } else {
                            mutableState.update {
                                it.copy(
                                    isSavingCatalog = false,
                                    catalogMutationCompletionCount =
                                        it.catalogMutationCompletionCount + 1L,
                                    storageProblem = storageProblem(
                                        code = AppProblemCode.CATALOG_SAVE_FAILED,
                                        error = error,
                                        fallbackMessage = "The local catalog could not be saved.",
                                    ),
                                )
                            }
                        }
                    }
            }
        }
    }

    private data class CatalogMutation(
        val updatedCatalog: ProjectCatalog,
        val successEffect: AppUiEffect? = null,
        val noChangeEffect: AppUiEffect? = null,
    )

    private data class PersistedCatalogMutation(
        val catalog: ProjectCatalog,
        val completionEffect: AppUiEffect?,
        val didCommitChange: Boolean,
    )

    private class CatalogMutationRejected(cause: Throwable) : RuntimeException(cause)

    private fun storageProblem(
        code: AppProblemCode,
        error: Throwable,
        fallbackMessage: String,
    ) = AppProblem(
        code = code,
        userMessage = if (error is ProjectStorageException) {
            error.message ?: fallbackMessage
        } else {
            fallbackMessage
        },
        recoveryAction = AppRecoveryAction.RETRY_CATALOG_LOAD,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    val repository = FileProjectRepository(context.applicationContext)
                    return AppViewModel(AppUseCases.create(repository)) as T
                }
            }
    }
}

private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
