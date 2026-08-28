package com.gecesars.atxplan.ui.anatel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.anatel.AndroidAnatelBasicPlanCatalog
import com.gecesars.atxplan.data.anatel.AnatelBasicPlanCatalogException
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalog
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogAvailability
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class AnatelBasicPlanUiPhase {
    CHECKING,
    NOT_ACQUIRED,
    READY,
    REFRESHING,
    FAILED,
}

data class AnatelBasicPlanUiState(
    val phase: AnatelBasicPlanUiPhase = AnatelBasicPlanUiPhase.CHECKING,
    val snapshot: AnatelBasicPlanCatalogSnapshot? = null,
    val noDataReason: AnatelBasicPlanNoDataReason? = null,
    val service: AnatelBroadcastService = AnatelBroadcastService.FM,
    val queryText: String = "",
    val stateCode: String = "",
    val channelText: String = "",
    val records: List<AnatelBasicPlanRecord> = emptyList(),
    val resultOffset: Int = 0,
    val isSearching: Boolean = false,
    val hasMore: Boolean = false,
    val filtersDirty: Boolean = false,
    val licenseReviewAcknowledged: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
)

class AnatelBasicPlanViewModel(
    private val catalog: AnatelBasicPlanCatalog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AnatelBasicPlanUiState())
    val state: StateFlow<AnatelBasicPlanUiState> = mutableState.asStateFlow()

    private var operationJob: Job? = null
    private var queryRevision: Long = 0L

    init {
        inspectLocalCatalog()
    }

    fun setLicenseReviewAcknowledged(acknowledged: Boolean) {
        if (mutableState.value.phase == AnatelBasicPlanUiPhase.REFRESHING) return
        mutableState.update {
            it.copy(licenseReviewAcknowledged = acknowledged, errorMessage = null)
        }
    }

    fun setService(service: AnatelBroadcastService) {
        if (service == AnatelBroadcastService.UNKNOWN || mutableState.value.service == service) return
        updateFilters { state -> state.copy(service = service) }
    }

    fun setQueryText(value: String) {
        val bounded = value.take(MAX_QUERY_CHARS)
        if (mutableState.value.queryText == bounded) return
        updateFilters { state -> state.copy(queryText = bounded) }
    }

    fun setStateCode(value: String) {
        val normalized = value.filter(Char::isLetter).take(2).uppercase(Locale.ROOT)
        if (mutableState.value.stateCode == normalized) return
        updateFilters { state -> state.copy(stateCode = normalized) }
    }

    fun setChannelText(value: String) {
        val normalized = value.filter(Char::isDigit).take(3)
        if (mutableState.value.channelText == normalized) return
        updateFilters { state -> state.copy(channelText = normalized) }
    }

    fun refresh() {
        val reviewed = mutableState.value.licenseReviewAcknowledged
        if (!reviewed) {
            mutableState.update {
                it.copy(
                    errorMessage = "Review and acknowledge the official source and attribution before downloading.",
                    notice = null,
                )
            }
            return
        }
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    phase = AnatelBasicPlanUiPhase.REFRESHING,
                    isSearching = false,
                    errorMessage = null,
                    notice = null,
                )
            }
            try {
                val result = withContext(ioDispatcher) { catalog.refresh() }
                mutableState.update {
                    it.copy(
                        phase = AnatelBasicPlanUiPhase.READY,
                        snapshot = result.snapshot,
                        noDataReason = null,
                        notice = if (result.reusedIndex) {
                            "The integrity-checked Basic Plan snapshot was already indexed locally."
                        } else {
                            "The Basic Plan snapshot was downloaded, integrity-checked, and indexed."
                        },
                    )
                }
                val revision = ++queryRevision
                runQuery(offset = 0, revision = revision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val preserved = withContext(ioDispatcher) { runCatching(catalog::status).getOrNull() }
                mutableState.update {
                    it.copy(
                        phase = if (
                            preserved?.availability == AnatelBasicPlanCatalogAvailability.READY
                        ) {
                            AnatelBasicPlanUiPhase.READY
                        } else {
                            AnatelBasicPlanUiPhase.FAILED
                        },
                        snapshot = preserved?.snapshot,
                        noDataReason = preserved?.noDataReason,
                        errorMessage = error.safeCatalogMessage(),
                    )
                }
            }
        }
    }

    fun search() {
        if (mutableState.value.phase != AnatelBasicPlanUiPhase.READY) return
        startQuery(offset = 0)
    }

    fun loadPrevious() {
        val current = mutableState.value
        if (
            current.phase != AnatelBasicPlanUiPhase.READY ||
            current.isSearching ||
            current.filtersDirty ||
            current.resultOffset <= 0
        ) {
            return
        }
        startQuery(offset = (current.resultOffset - RESULT_PAGE_SIZE).coerceAtLeast(0))
    }

    fun loadMore() {
        val current = mutableState.value
        if (
            current.phase != AnatelBasicPlanUiPhase.READY ||
            current.isSearching ||
            current.filtersDirty ||
            !current.hasMore
        ) {
            return
        }
        startQuery(offset = current.resultOffset + current.records.size)
    }

    private fun startQuery(offset: Int) {
        operationJob?.cancel()
        val revision = ++queryRevision
        operationJob = viewModelScope.launch {
            runQuery(offset = offset, revision = revision)
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(errorMessage = null, notice = null) }
    }

    private fun inspectLocalCatalog() {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            mutableState.update { it.copy(phase = AnatelBasicPlanUiPhase.CHECKING) }
            try {
                val status = withContext(ioDispatcher) { catalog.status() }
                if (status.availability == AnatelBasicPlanCatalogAvailability.READY) {
                    mutableState.update {
                        it.copy(
                            phase = AnatelBasicPlanUiPhase.READY,
                            snapshot = status.snapshot,
                            noDataReason = null,
                            errorMessage = null,
                        )
                    }
                    val revision = ++queryRevision
                    runQuery(offset = 0, revision = revision)
                } else {
                    mutableState.update {
                        it.copy(
                            phase = AnatelBasicPlanUiPhase.NOT_ACQUIRED,
                            snapshot = null,
                            noDataReason = status.noDataReason,
                            records = emptyList(),
                            resultOffset = 0,
                            hasMore = false,
                            filtersDirty = false,
                            errorMessage = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        phase = AnatelBasicPlanUiPhase.FAILED,
                        errorMessage = error.safeCatalogMessage(),
                    )
                }
            }
        }
    }

    private suspend fun runQuery(
        offset: Int,
        revision: Long,
    ) {
        if (revision != queryRevision) return
        val input = mutableState.value
        val query = try {
            input.toQuery(offset)
        } catch (error: IllegalArgumentException) {
            mutableState.update { current ->
                if (revision != queryRevision) current else current.copy(
                    isSearching = false,
                    errorMessage = error.message ?: "The catalog filters are invalid.",
                )
            }
            return
        }
        mutableState.update { current ->
            if (revision != queryRevision) current else current.copy(isSearching = true, errorMessage = null)
        }
        try {
            val page = withContext(ioDispatcher) { catalog.query(query) }
            if (revision != queryRevision) return
            if (page.status.availability != AnatelBasicPlanCatalogAvailability.READY) {
                mutableState.update {
                    it.copy(
                        phase = AnatelBasicPlanUiPhase.NOT_ACQUIRED,
                        snapshot = null,
                        noDataReason = page.status.noDataReason,
                        records = emptyList(),
                        resultOffset = 0,
                        isSearching = false,
                        hasMore = false,
                        filtersDirty = false,
                    )
                }
                return
            }
            mutableState.update { current ->
                if (revision != queryRevision) current else current.copy(
                    phase = AnatelBasicPlanUiPhase.READY,
                    snapshot = page.status.snapshot,
                    noDataReason = null,
                    records = page.records,
                    resultOffset = query.offset,
                    isSearching = false,
                    hasMore = page.hasMore,
                    filtersDirty = false,
                    errorMessage = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { current ->
                if (revision != queryRevision) current else current.copy(
                    isSearching = false,
                    errorMessage = error.safeCatalogMessage(),
                )
            }
        }
    }

    private fun updateFilters(transform: (AnatelBasicPlanUiState) -> AnatelBasicPlanUiState) {
        val current = mutableState.value
        if (current.phase == AnatelBasicPlanUiPhase.REFRESHING) return
        queryRevision += 1L
        if (current.isSearching) operationJob?.cancel()
        mutableState.update { state ->
            transform(state).copy(
                records = emptyList(),
                resultOffset = 0,
                isSearching = false,
                hasMore = false,
                filtersDirty = true,
                errorMessage = null,
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AnatelBasicPlanViewModel::class.java)) {
                        "Unsupported ViewModel class: ${modelClass.name}"
                    }
                    return AnatelBasicPlanViewModel(
                        AndroidAnatelBasicPlanCatalog(context.applicationContext),
                    ) as T
                }
            }
    }
}

private fun AnatelBasicPlanUiState.toQuery(offset: Int): AnatelBasicPlanQuery {
    val channel = channelText.takeIf(String::isNotBlank)?.toIntOrNull()
    if (channelText.isNotBlank() && channel == null) {
        throw IllegalArgumentException("The channel filter must be an integer from 1 to 999.")
    }
    if (queryText.trim().length == 1) {
        throw IllegalArgumentException("The text filter must contain at least two characters.")
    }
    return AnatelBasicPlanQuery(
        service = service,
        stateCode = stateCode.takeIf(String::isNotBlank),
        channel = channel,
        text = queryText.trim().takeIf(String::isNotBlank),
        pageSize = RESULT_PAGE_SIZE,
        offset = offset,
    )
}

private fun Exception.safeCatalogMessage(): String = when (this) {
    is AnatelBasicPlanCatalogException,
    is IllegalArgumentException,
    -> message?.take(500) ?: "The Basic Plan catalog operation failed."

    else -> "The Basic Plan catalog operation failed without changing the current snapshot."
}

private const val MAX_QUERY_CHARS = 256
private const val RESULT_PAGE_SIZE = 25
