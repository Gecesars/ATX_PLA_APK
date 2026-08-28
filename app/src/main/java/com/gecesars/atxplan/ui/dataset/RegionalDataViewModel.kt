package com.gecesars.atxplan.ui.dataset

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.dataset.DefaultRegionalArtifactProcessor
import com.gecesars.atxplan.data.dataset.FileRegionalDatasetRepository
import com.gecesars.atxplan.data.dataset.REGIONAL_DATA_DIRECTORY
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class RegionalDataUiPhase {
    EDITING,
    REVIEW,
    RUNNING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class RegionalCoordinateField {
    WEST,
    SOUTH,
    EAST,
    NORTH,
}

data class RegionalDataUiState(
    val west: String = DEFAULT_WEST,
    val south: String = DEFAULT_SOUTH,
    val east: String = DEFAULT_EAST,
    val north: String = DEFAULT_NORTH,
    val selections: Set<RegionalDatasetSelection> = DEFAULT_SELECTIONS,
    val refreshLiveSnapshot: Boolean = false,
    val phase: RegionalDataUiPhase = RegionalDataUiPhase.EDITING,
    val plan: RegionalDownloadPlan? = null,
    val licensesAccepted: Boolean = false,
    val progress: RegionalDownloadProgress? = null,
    val result: RegionalDownloadResult? = null,
    val inventory: List<RegionalInventoryRecord> = emptyList(),
    val isLoadingInventory: Boolean = true,
    val errorMessage: String? = null,
) {
    val canReview: Boolean
        get() = phase != RegionalDataUiPhase.RUNNING && selections.isNotEmpty()

    val canAcquire: Boolean
        get() = phase == RegionalDataUiPhase.REVIEW && plan != null && licensesAccepted
}

/**
 * Owns one user-triggered, in-app regional acquisition.
 *
 * The repository keeps resumable GET partials, but this ViewModel deliberately does not claim a
 * process-persistent worker. The UI tells users to keep ATX Plan open while a transfer is active.
 */
class RegionalDataViewModel(
    private val repository: RegionalDatasetRepository,
    private val planner: RegionalDatasetPlanner = RegionalDatasetPlanner(),
    initialBounds: RegionalBounds? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        RegionalDataUiState().withBounds(initialBounds),
    )
    val state: StateFlow<RegionalDataUiState> = mutableState.asStateFlow()

    private var acquisitionJob: Job? = null
    @Volatile
    private var cancelRequested = false

    init {
        refreshInventory()
    }

    fun updateCoordinate(field: RegionalCoordinateField, value: String) {
        if (mutableState.value.phase == RegionalDataUiPhase.RUNNING) return
        val bounded = value.take(MAX_COORDINATE_INPUT_LENGTH)
        mutableState.update { current ->
            when (field) {
                RegionalCoordinateField.WEST -> current.copy(west = bounded)
                RegionalCoordinateField.SOUTH -> current.copy(south = bounded)
                RegionalCoordinateField.EAST -> current.copy(east = bounded)
                RegionalCoordinateField.NORTH -> current.copy(north = bounded)
            }.invalidatePlan()
        }
    }

    fun toggleSelection(selection: RegionalDatasetSelection) {
        if (mutableState.value.phase == RegionalDataUiPhase.RUNNING) return
        mutableState.update { current ->
            val changed = current.selections.toMutableSet().apply {
                if (!add(selection)) remove(selection)
            }
            current.copy(
                selections = changed,
                refreshLiveSnapshot = current.refreshLiveSnapshot &&
                    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL in changed,
            ).invalidatePlan()
        }
    }

    fun setLiveSnapshotRefresh(enabled: Boolean) {
        if (mutableState.value.phase == RegionalDataUiPhase.RUNNING) return
        mutableState.update { current ->
            if (RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL !in current.selections) current
            else current.copy(refreshLiveSnapshot = enabled).invalidatePlan()
        }
    }

    fun reviewPlan() {
        if (!mutableState.value.canReview) return
        val current = mutableState.value
        try {
            val bounds = RegionalBounds(
                west = current.west.toCoordinate("West"),
                south = current.south.toCoordinate("South"),
                east = current.east.toCoordinate("East"),
                north = current.north.toCoordinate("North"),
            )
            val plan = planner.plan(
                RegionalDatasetRequest(
                    bounds = bounds,
                    selections = current.selections,
                    reason = "user-selected regional GIS package",
                    liveSnapshotRefresh = current.refreshLiveSnapshot,
                ),
            )
            mutableState.update {
                it.copy(
                    phase = RegionalDataUiPhase.REVIEW,
                    plan = plan,
                    licensesAccepted = false,
                    progress = null,
                    result = null,
                    errorMessage = null,
                )
            }
        } catch (error: IllegalArgumentException) {
            mutableState.update {
                it.copy(
                    phase = RegionalDataUiPhase.FAILED,
                    plan = null,
                    licensesAccepted = false,
                    progress = null,
                    result = null,
                    errorMessage = error.message ?: "The regional request is invalid.",
                )
            }
        }
    }

    fun setLicensesAccepted(accepted: Boolean) {
        if (mutableState.value.phase != RegionalDataUiPhase.REVIEW) return
        mutableState.update { it.copy(licensesAccepted = accepted, errorMessage = null) }
    }

    fun startAcquisition() {
        val plan = mutableState.value.plan ?: return
        if (!mutableState.value.canAcquire || acquisitionJob?.isActive == true) return
        cancelRequested = false
        acquisitionJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    phase = RegionalDataUiPhase.RUNNING,
                    progress = null,
                    result = null,
                    errorMessage = null,
                )
            }
            try {
                val result = repository.acquire(
                    plan = plan,
                    onProgress = { progress ->
                        mutableState.update { current -> current.copy(progress = progress) }
                    },
                    isCancelled = { cancelRequested },
                )
                val cancelled = cancelRequested || result.results.any { resultItem ->
                    resultItem.status == RegionalTransferStatus.CANCELLED
                }
                mutableState.update {
                    it.copy(
                        phase = when {
                            cancelled -> RegionalDataUiPhase.CANCELLED
                            result.isSuccessful -> RegionalDataUiPhase.COMPLETE
                            else -> RegionalDataUiPhase.FAILED
                        },
                        progress = null,
                        result = result,
                        errorMessage = result.results
                            .firstOrNull { item -> item.error != null }
                            ?.error,
                    )
                }
                refreshInventory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        phase = if (cancelRequested) {
                            RegionalDataUiPhase.CANCELLED
                        } else {
                            RegionalDataUiPhase.FAILED
                        },
                        progress = null,
                        errorMessage = error.safeAcquisitionMessage(),
                    )
                }
            }
        }
    }

    fun cancelAcquisition() {
        if (mutableState.value.phase == RegionalDataUiPhase.RUNNING) {
            cancelRequested = true
        }
    }

    fun editRequest() {
        if (mutableState.value.phase == RegionalDataUiPhase.RUNNING) return
        mutableState.update { it.invalidatePlan() }
    }

    fun refreshInventory() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingInventory = true) }
            try {
                val inventory = repository.loadInventory()
                mutableState.update {
                    it.copy(
                        inventory = inventory.artifacts.values
                            .sortedByDescending(RegionalInventoryRecord::checkedAt),
                        isLoadingInventory = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoadingInventory = false) }
            }
        }
    }

    companion object {
        fun factory(
            context: Context,
            initialBounds: RegionalBounds? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RegionalDataViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                val applicationContext = context.applicationContext
                return RegionalDataViewModel(
                    repository = FileRegionalDatasetRepository(
                        rootDirectory = File(applicationContext.noBackupFilesDir, REGIONAL_DATA_DIRECTORY),
                        processor = DefaultRegionalArtifactProcessor(),
                    ),
                    initialBounds = initialBounds,
                ) as T
            }
        }
    }
}

private fun RegionalDataUiState.invalidatePlan(): RegionalDataUiState = copy(
    phase = RegionalDataUiPhase.EDITING,
    plan = null,
    licensesAccepted = false,
    progress = null,
    result = null,
    errorMessage = null,
)

private fun RegionalDataUiState.withBounds(bounds: RegionalBounds?): RegionalDataUiState {
    if (bounds == null) return this
    return copy(
        west = bounds.west.coordinateText(),
        south = bounds.south.coordinateText(),
        east = bounds.east.coordinateText(),
        north = bounds.north.coordinateText(),
    )
}

private fun String.toCoordinate(label: String): Double = trim().toDoubleOrNull()
    ?: throw IllegalArgumentException("$label must be a decimal WGS84 coordinate.")

private fun Double.coordinateText(): String = String.format(Locale.US, "%.6f", this)

private fun Exception.safeAcquisitionMessage(): String = message
    ?.takeIf { it.isNotBlank() }
    ?.take(500)
    ?: "Regional data acquisition failed. Review the request and try again."

private val DEFAULT_SELECTIONS = setOf(
    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
    RegionalDatasetSelection.ESA_WORLDCOVER_2021,
)
private const val DEFAULT_WEST = "-46.670000"
private const val DEFAULT_SOUTH = "-23.570000"
private const val DEFAULT_EAST = "-46.640000"
private const val DEFAULT_NORTH = "-23.540000"
private const val MAX_COORDINATE_INPUT_LENGTH = 18
