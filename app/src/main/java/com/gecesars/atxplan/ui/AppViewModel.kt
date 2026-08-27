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
import com.gecesars.atxplan.domain.application.ArchiveProjectCommand
import com.gecesars.atxplan.domain.application.ArchiveProjectStatus
import com.gecesars.atxplan.domain.application.DeleteProjectCommand
import com.gecesars.atxplan.domain.application.DeleteProjectStatus
import com.gecesars.atxplan.domain.application.DuplicateProjectCommand
import com.gecesars.atxplan.domain.application.RenameProjectCommand
import com.gecesars.atxplan.domain.application.RenameProjectStatus
import com.gecesars.atxplan.domain.application.RestoreProjectCommand
import com.gecesars.atxplan.domain.application.RestoreProjectStatus
import com.gecesars.atxplan.domain.application.RfAssetKind
import com.gecesars.atxplan.domain.application.RfAssetMutationCommand
import com.gecesars.atxplan.domain.application.RfAssetMutationReceipt
import com.gecesars.atxplan.domain.application.RfAssetMutationStatus
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
import java.util.UUID

sealed interface AppUiAction {
    data class CreateProject(val name: String, val customer: String) : AppUiAction

    data class RenameProject(val command: RenameProjectCommand) : AppUiAction

    data class DuplicateProject(val command: DuplicateProjectCommand) : AppUiAction

    data class ArchiveProject(val command: ArchiveProjectCommand) : AppUiAction

    data class RestoreProject(val command: RestoreProjectCommand) : AppUiAction

    data class DeleteProject(val command: DeleteProjectCommand) : AppUiAction

    data class SelectProject(val projectId: String) : AppUiAction

    data class CalculateLinkBudget(val input: LinkBudgetInput) : AppUiAction

    data class AddRfPath(val command: AddRfPathCommand) : AppUiAction

    data class MutateRfAsset(val command: RfAssetMutationCommand) : AppUiAction

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
    val lastRfMutationReceipt: RfAssetMutationReceipt? = null,
    val activeRfMutationRequestId: String? = null,
    val rfMutationSessionId: String = "",
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

    private val mutableState = MutableStateFlow(
        AppUiState(rfMutationSessionId = UUID.randomUUID().toString()),
    )
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
            is AppUiAction.ArchiveProject -> handleArchiveProject(action.command)
            is AppUiAction.RestoreProject -> handleRestoreProject(action.command)
            is AppUiAction.DeleteProject -> handleDeleteProject(action.command)
            is AppUiAction.SelectProject -> handleSelectProject(action.projectId)
            is AppUiAction.CalculateLinkBudget -> handleCalculateLinkBudget(action.input)
            is AppUiAction.AddRfPath -> handleAddRfPath(action.command)
            is AppUiAction.MutateRfAsset -> handleRfAssetMutation(action.command)
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

    fun archiveProject(command: ArchiveProjectCommand) {
        onAction(AppUiAction.ArchiveProject(command))
    }

    fun restoreProject(command: RestoreProjectCommand) {
        onAction(AppUiAction.RestoreProject(command))
    }

    fun deleteProject(command: DeleteProjectCommand) {
        onAction(AppUiAction.DeleteProject(command))
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

    fun mutateRfAsset(command: RfAssetMutationCommand) {
        onAction(AppUiAction.MutateRfAsset(command))
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

    private fun handleArchiveProject(command: ArchiveProjectCommand) {
        persistCatalogMutation { current ->
            val result = useCases.archiveProject(current, command)
            when (result.status) {
                ArchiveProjectStatus.ARCHIVED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice(
                        "Project moved to the local archive.",
                    ),
                )
                ArchiveProjectStatus.STALE_PROJECT -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project changed in local storage. Review its latest details and " +
                            "archive it again.",
                    ),
                )
                ArchiveProjectStatus.ALREADY_ARCHIVED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project is already in the local archive.",
                    ),
                )
                ArchiveProjectStatus.NOT_FOUND -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project no longer exists in local storage.",
                    ),
                )
            }
        }
    }

    private fun handleRestoreProject(command: RestoreProjectCommand) {
        persistCatalogMutation { current ->
            val result = useCases.restoreProject(current, command)
            when (result.status) {
                RestoreProjectStatus.RESTORED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice(
                        "Project restored from the local archive.",
                    ),
                )
                RestoreProjectStatus.STALE_ARCHIVE -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The archived project changed in local storage. Review its latest " +
                            "details and restore it again.",
                    ),
                )
                RestoreProjectStatus.ALREADY_ACTIVE -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project is already active in the local catalog.",
                    ),
                )
                RestoreProjectStatus.NOT_FOUND -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The archived project no longer exists in local storage.",
                    ),
                )
            }
        }
    }

    private fun handleDeleteProject(command: DeleteProjectCommand) {
        persistCatalogMutation { current ->
            val result = useCases.deleteProject(current, command)
            when (result.status) {
                DeleteProjectStatus.DELETED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice(
                        "Project and its project-scoped data were removed from the local catalog.",
                    ),
                )
                DeleteProjectStatus.STALE_PROJECT -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project changed in local storage. Review its latest details and " +
                            "confirm deletion again.",
                    ),
                )
                DeleteProjectStatus.NOT_FOUND -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project no longer exists in local storage.",
                    ),
                )
                DeleteProjectStatus.ARCHIVED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The project is archived in local storage. Restore it before permanent " +
                            "deletion.",
                    ),
                )
            }
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

    private fun handleRfAssetMutation(command: RfAssetMutationCommand) {
        if (!reserveRfMutation(command.requestId)) return
        persistCatalogMutation(rfRequestId = command.requestId) { current ->
            val result = useCases.mutateRfAsset(current, command)
            val receipt = result.receipt
            val entityLabel = receipt.kind.name.lowercase().replaceFirstChar(Char::uppercase)
            when (receipt.status) {
                RfAssetMutationStatus.CREATED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice("$entityLabel created in local storage."),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.UPDATED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice("$entityLabel updated in local storage."),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.DELETED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    successEffect = AppUiEffect.ShowNotice("$entityLabel removed from the local project."),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.UNCHANGED -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice("No $entityLabel changes were needed."),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.STALE -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The $entityLabel changed in local storage. Review it and try again.",
                    ),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.NOT_FOUND -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        "The $entityLabel no longer exists in the active project.",
                    ),
                    rfMutationReceipt = receipt,
                )
                RfAssetMutationStatus.BLOCKED_REFERENCES -> CatalogMutation(
                    updatedCatalog = result.catalog,
                    noChangeEffect = AppUiEffect.ShowNotice(
                        rfBlockedReferenceMessage(entityLabel, receipt),
                    ),
                    rfMutationReceipt = receipt,
                )
            }
        }
    }

    private fun reserveRfMutation(requestId: String): Boolean {
        while (true) {
            val current = mutableState.value
            val activeRequestId = current.activeRfMutationRequestId
            if (activeRequestId != null) {
                val rejected = current.copy(
                    pendingEffect = AppUiEffect.ShowNotice(
                        "Another RF asset change is still being saved. Wait for it to finish, then retry.",
                    ),
                )
                if (mutableState.compareAndSet(current, rejected)) return false
            } else {
                val reserved = current.copy(
                    activeRfMutationRequestId = requestId,
                    lastRfMutationReceipt = null,
                )
                if (mutableState.compareAndSet(current, reserved)) return true
            }
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
        rfRequestId: String? = null,
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
                            activeRfMutationRequestId = it.activeRfMutationRequestId
                                .clearedIfCompleted(rfRequestId),
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
                            throw CatalogMutationRejected(
                                latestCatalog = latestCatalog,
                                cause = error,
                            )
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
                        rfMutationReceipt = requestedMutation?.rfMutationReceipt,
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
                                lastRfMutationReceipt =
                                    committed.rfMutationReceipt ?: it.lastRfMutationReceipt,
                                activeRfMutationRequestId = it.activeRfMutationRequestId
                                    .clearedIfCompleted(rfRequestId),
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
                                    catalog = rejected.latestCatalog,
                                    pendingEffect = AppUiEffect.ShowNotice(
                                        rejected.cause?.message ?: "Invalid project data.",
                                    ),
                                    activeRfMutationRequestId = it.activeRfMutationRequestId
                                        .clearedIfCompleted(rfRequestId),
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
                                    activeRfMutationRequestId = it.activeRfMutationRequestId
                                        .clearedIfCompleted(rfRequestId),
                                )
                            }
                        }
                    }
            }
        }
    }

    private fun String?.clearedIfCompleted(requestId: String?): String? =
        if (requestId != null && this == requestId) null else this

    private data class CatalogMutation(
        val updatedCatalog: ProjectCatalog,
        val successEffect: AppUiEffect? = null,
        val noChangeEffect: AppUiEffect? = null,
        val rfMutationReceipt: RfAssetMutationReceipt? = null,
    )

    private data class PersistedCatalogMutation(
        val catalog: ProjectCatalog,
        val completionEffect: AppUiEffect?,
        val didCommitChange: Boolean,
        val rfMutationReceipt: RfAssetMutationReceipt?,
    )

    private class CatalogMutationRejected(
        val latestCatalog: ProjectCatalog,
        cause: Throwable,
    ) : RuntimeException(cause)

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

private fun rfBlockedReferenceMessage(
    entityLabel: String,
    receipt: RfAssetMutationReceipt,
): String {
    val impact = receipt.impact
    return when {
        receipt.kind == RfAssetKind.NETWORK &&
            impact.hasReferences -> {
            val references = buildList {
                if (impact.sectorCount > 0) {
                    add(rfReferenceCount(impact.sectorCount, "sector", "sectors"))
                }
                if (impact.receiverCount > 0) {
                    add(rfReferenceCount(impact.receiverCount, "receiver", "receivers"))
                }
            }
            val verb = if (impact.sectorCount + impact.receiverCount == 1) "references" else "reference"
            "$entityLabel cannot be deleted while ${references.joinToString(" and ")} $verb it. " +
                "Reassign or remove the linked records first."
        }
        receipt.kind == RfAssetKind.SITE &&
            impact.sectorCount > 0 ->
            "$entityLabel contains ${rfReferenceCount(impact.sectorCount, "sector", "sectors")}. " +
                "Review and explicitly confirm removal of the contained " +
                if (impact.sectorCount == 1) "sector." else "sectors."
        else -> "$entityLabel cannot be changed because linked RF records still reference it."
    }
}

private fun rfReferenceCount(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"

private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
