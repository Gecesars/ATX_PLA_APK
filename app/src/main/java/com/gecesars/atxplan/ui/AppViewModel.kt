package com.gecesars.atxplan.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.project.FileProjectRepository
import com.gecesars.atxplan.data.project.ProjectRepository
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.domain.rf.RfCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val isLoading: Boolean = true,
    val catalog: ProjectCatalog = ProjectCatalog(),
    val notice: String? = null,
    val storageError: String? = null,
    val linkBudgetResult: LinkBudgetResult? = null,
    val calculatorError: String? = null,
) {
    val selectedProject: PlannerProject?
        get() = catalog.selectedProject
}

class AppViewModel(
    private val repository: ProjectRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        loadCatalog()
    }

    fun createProject(name: String, customer: String) {
        val project = runCatching { ProjectFactory.create(name, customer) }
            .getOrElse { error ->
                mutableState.update { it.copy(notice = error.message ?: "Invalid project data.") }
                return
            }
        updateCatalog(successMessage = "Project “${project.name}” was created in local storage.") { current ->
            current.copy(
                selectedProjectId = project.id,
                projects = current.projects + project,
            )
        }
    }

    fun selectProject(projectId: String) {
        if (mutableState.value.catalog.projects.none { it.id == projectId }) return
        updateCatalog(successMessage = null) { it.copy(selectedProjectId = projectId) }
    }

    fun calculateLinkBudget(input: LinkBudgetInput) {
        runCatching { RfCalculator.linkBudget(input) }
            .onSuccess { result ->
                mutableState.update {
                    it.copy(linkBudgetResult = result, calculatorError = null)
                }
            }
            .onFailure { error ->
                mutableState.update {
                    it.copy(
                        linkBudgetResult = null,
                        calculatorError = error.message ?: "The link budget could not be calculated.",
                    )
                }
            }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, storageError = null) }
            runCatching { repository.loadCatalog() }
                .onSuccess { catalog ->
                    mutableState.update {
                        it.copy(isLoading = false, catalog = catalog, storageError = null)
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            catalog = ProjectCatalog(),
                            storageError = error.message ?: "The local catalog could not be opened.",
                        )
                    }
                }
        }
    }

    private fun updateCatalog(
        successMessage: String?,
        transform: (ProjectCatalog) -> ProjectCatalog,
    ) {
        val previous = mutableState.value.catalog
        val updated = transform(previous)
        mutableState.update { it.copy(catalog = updated, storageError = null) }
        viewModelScope.launch {
            runCatching { repository.saveCatalog(updated) }
                .onSuccess {
                    if (successMessage != null) {
                        mutableState.update { state -> state.copy(notice = successMessage) }
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            catalog = previous,
                            storageError = error.message ?: "The local catalog could not be saved.",
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    return AppViewModel(FileProjectRepository(context.applicationContext)) as T
                }
            }
    }
}
