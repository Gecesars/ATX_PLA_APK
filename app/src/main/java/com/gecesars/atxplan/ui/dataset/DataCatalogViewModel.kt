package com.gecesars.atxplan.ui.dataset

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.dataset.BundledIbgeDatasetRepository
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeDatasetException
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationProgress
import com.gecesars.atxplan.domain.dataset.IbgeDatasetRepository
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.domain.dataset.MAX_MUNICIPALITY_QUERY_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class IbgeCatalogStatus {
    CHECKING,
    INSTALLING,
    VALIDATING,
    READY,
    FAILED,
}

data class DataCatalogUiState(
    val ibgeStatus: IbgeCatalogStatus = IbgeCatalogStatus.CHECKING,
    val ibgeProgress: IbgeDatasetPreparationProgress? = null,
    val ibgeDescriptor: IbgeDatasetDescriptor? = null,
    val municipalityQuery: String = "",
    val municipalityResults: List<IbgeMunicipalitySummary> = emptyList(),
    val selectedMunicipality: IbgeMunicipalitySummary? = null,
    val isSearchingMunicipalities: Boolean = false,
    val datasetErrorMessage: String? = null,
    val searchErrorMessage: String? = null,
)

class DataCatalogViewModel(
    private val repository: IbgeDatasetRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DataCatalogUiState())
    val state: StateFlow<DataCatalogUiState> = mutableState.asStateFlow()

    private var preparationJob: Job? = null
    private var searchJob: Job? = null

    init {
        prepareDataset()
    }

    fun retryDataset() {
        prepareDataset()
    }

    fun updateMunicipalityQuery(value: String) {
        val bounded = value.take(MAX_MUNICIPALITY_QUERY_LENGTH)
        mutableState.update {
            it.copy(
                municipalityQuery = bounded,
                searchErrorMessage = null,
            )
        }
        if (mutableState.value.ibgeStatus != IbgeCatalogStatus.READY) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            searchMunicipalities(bounded)
        }
    }

    fun selectMunicipality(code: String) {
        val selection = mutableState.value.municipalityResults.firstOrNull { it.code == code }
            ?: return
        mutableState.update { it.copy(selectedMunicipality = selection) }
    }

    private fun prepareDataset() {
        preparationJob?.cancel()
        searchJob?.cancel()
        preparationJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    ibgeStatus = IbgeCatalogStatus.CHECKING,
                    ibgeProgress = null,
                    ibgeDescriptor = null,
                    municipalityResults = emptyList(),
                    selectedMunicipality = null,
                    isSearchingMunicipalities = false,
                    datasetErrorMessage = null,
                    searchErrorMessage = null,
                )
            }
            try {
                val descriptor = repository.prepare { progress ->
                    mutableState.update { current ->
                        current.copy(
                            ibgeStatus = progress.phase.toCatalogStatus(),
                            ibgeProgress = progress,
                        )
                    }
                }
                mutableState.update {
                    it.copy(
                        ibgeStatus = IbgeCatalogStatus.READY,
                        ibgeProgress = null,
                        ibgeDescriptor = descriptor,
                        datasetErrorMessage = null,
                    )
                }
                searchMunicipalities(mutableState.value.municipalityQuery)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        ibgeStatus = IbgeCatalogStatus.FAILED,
                        ibgeProgress = null,
                        ibgeDescriptor = null,
                        municipalityResults = emptyList(),
                        selectedMunicipality = null,
                        isSearchingMunicipalities = false,
                        datasetErrorMessage = error.datasetMessage(),
                    )
                }
            }
        }
    }

    private suspend fun searchMunicipalities(query: String) {
        mutableState.update {
            it.copy(
                isSearchingMunicipalities = true,
                searchErrorMessage = null,
            )
        }
        try {
            val results = repository.searchMunicipalities(
                query = query,
                limit = if (query.isBlank()) INITIAL_MUNICIPALITY_RESULT_LIMIT else SEARCH_RESULT_LIMIT,
            )
            if (query != mutableState.value.municipalityQuery) return
            mutableState.update { current ->
                current.copy(
                    municipalityResults = results,
                    selectedMunicipality = current.selectedMunicipality
                        ?.takeIf { selected -> results.any { result -> result.code == selected.code } },
                    isSearchingMunicipalities = false,
                    searchErrorMessage = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (query != mutableState.value.municipalityQuery) return
            mutableState.update {
                it.copy(
                    municipalityResults = emptyList(),
                    selectedMunicipality = null,
                    isSearchingMunicipalities = false,
                    searchErrorMessage = error.datasetMessage(),
                )
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(DataCatalogViewModel::class.java)) {
                        "Unsupported ViewModel class: ${modelClass.name}"
                    }
                    return DataCatalogViewModel(
                        BundledIbgeDatasetRepository(context.applicationContext),
                    ) as T
                }
            }
    }
}

private fun IbgeDatasetPreparationPhase.toCatalogStatus(): IbgeCatalogStatus = when (this) {
    IbgeDatasetPreparationPhase.CHECKING -> IbgeCatalogStatus.CHECKING
    IbgeDatasetPreparationPhase.INSTALLING -> IbgeCatalogStatus.INSTALLING
    IbgeDatasetPreparationPhase.VALIDATING -> IbgeCatalogStatus.VALIDATING
}

private fun Exception.datasetMessage(): String = when (this) {
    is IbgeDatasetException -> message ?: "The offline IBGE dataset is unavailable."
    else -> "The offline IBGE dataset is unavailable. Review local storage and retry."
}

private const val SEARCH_DEBOUNCE_MILLIS = 220L
private const val INITIAL_MUNICIPALITY_RESULT_LIMIT = 6
private const val SEARCH_RESULT_LIMIT = 12
