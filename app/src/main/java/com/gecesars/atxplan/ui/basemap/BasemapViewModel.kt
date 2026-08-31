package com.gecesars.atxplan.ui.basemap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.basemap.BasemapProviderCatalog
import com.gecesars.atxplan.data.basemap.BasemapTileRepository
import com.gecesars.atxplan.data.basemap.CachedBasemapTile
import com.gecesars.atxplan.data.basemap.FileBasemapTileRepository
import com.gecesars.atxplan.domain.basemap.RasterBasemapProvider
import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BasemapUiState(
    val providers: List<RasterBasemapProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val tiles: List<CachedBasemapTile> = emptyList(),
    val tileZoom: Int? = null,
    val requestedTileCount: Int = 0,
    val downloadedTileCount: Int = 0,
    val reusedTileCount: Int = 0,
    val failureCount: Int = 0,
    val cacheByteCount: Long = 0L,
    val isLoading: Boolean = false,
    val message: String? = null,
) {
    val selectedProvider: RasterBasemapProvider?
        get() = providers.firstOrNull { provider -> provider.id == selectedProviderId }

    companion object {
        val gridOnly = BasemapUiState()
    }
}

class BasemapViewModel(
    private val repository: BasemapTileRepository,
    providers: List<RasterBasemapProvider> = BasemapProviderCatalog.providers,
    defaultProviderId: String = BasemapProviderCatalog.defaultProviderId,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        BasemapUiState(
            providers = providers,
            selectedProviderId = defaultProviderId.takeIf { id ->
                providers.any { provider -> provider.id == id }
            },
        ),
    )
    val state: StateFlow<BasemapUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var lastRequest: VisibleBasemapRequest? = null
    private var activeRequest: VisibleBasemapRequest? = null
    private var completedRequest: VisibleBasemapRequest? = null

    fun selectProvider(providerId: String?) {
        require(providerId == null || mutableState.value.providers.any { it.id == providerId }) {
            "The selected basemap provider is not approved."
        }
        if (providerId == mutableState.value.selectedProviderId) return
        loadJob?.cancel()
        completedRequest = null
        mutableState.update {
            it.copy(
                selectedProviderId = providerId,
                tiles = emptyList(),
                tileZoom = null,
                requestedTileCount = 0,
                downloadedTileCount = 0,
                reusedTileCount = 0,
                failureCount = 0,
                isLoading = false,
                message = null,
            )
        }
        lastRequest?.let { request ->
            if (providerId != null) load(request.copy(providerId = providerId), force = true)
        }
    }

    fun requestVisibleTiles(
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        displayTileSizePx: Double,
    ) {
        val providerId = mutableState.value.selectedProviderId ?: return
        val request = VisibleBasemapRequest(providerId, camera, viewport, displayTileSizePx)
        lastRequest = request
        load(request, force = false)
    }

    fun refreshVisibleTiles() {
        val request = lastRequest ?: return
        val providerId = mutableState.value.selectedProviderId ?: return
        load(request.copy(providerId = providerId), force = true)
    }

    private fun load(request: VisibleBasemapRequest, force: Boolean) {
        if (!force && (request == completedRequest || (loadJob?.isActive == true && request == activeRequest))) {
            return
        }
        val provider = mutableState.value.providers.firstOrNull { it.id == request.providerId }
            ?: return
        loadJob?.cancel()
        lastRequest = request
        activeRequest = request
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isLoading = true,
                    downloadedTileCount = 0,
                    reusedTileCount = 0,
                    failureCount = 0,
                    message = null,
                )
            }
            try {
                val result = repository.loadVisibleTiles(
                    provider = provider,
                    camera = request.camera,
                    viewport = request.viewport,
                    displayTileSizePx = request.displayTileSizePx,
                )
                if (mutableState.value.selectedProviderId != request.providerId) return@launch
                completedRequest = request
                mutableState.update {
                    it.copy(
                        tiles = result.tiles,
                        tileZoom = result.plan.tileZoom,
                        requestedTileCount = result.plan.coordinates.size,
                        downloadedTileCount = result.downloadedCount,
                        reusedTileCount = result.reusedCount,
                        failureCount = result.failureCount,
                        cacheByteCount = result.cacheByteCount,
                        isLoading = false,
                        message = when {
                            result.tiles.isEmpty() -> result.firstFailure
                                ?: "No visible basemap tiles could be loaded."
                            result.failureCount > 0 ->
                                "${result.failureCount} visible tiles are unavailable. Cached or loaded tiles remain visible."
                            else -> null
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (mutableState.value.selectedProviderId == request.providerId) {
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            message = "The visible basemap could not be loaded. Check the connection and provider terms, then retry.",
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(BasemapViewModel::class.java)) {
                        "Unsupported ViewModel class: ${modelClass.name}"
                    }
                    return BasemapViewModel(
                        repository = FileBasemapTileRepository.create(context.applicationContext),
                    ) as T
                }
            }
    }
}

private data class VisibleBasemapRequest(
    val providerId: String,
    val camera: GeographicCamera,
    val viewport: ViewportSizePx,
    val displayTileSizePx: Double,
)
