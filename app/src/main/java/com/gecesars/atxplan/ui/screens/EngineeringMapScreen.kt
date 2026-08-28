package com.gecesars.atxplan.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gecesars.atxplan.domain.application.RfAssetKind
import com.gecesars.atxplan.domain.application.RfAssetMutationCommand
import com.gecesars.atxplan.domain.application.RfAssetMutationReceipt
import com.gecesars.atxplan.domain.application.RfAssetMutationStatus
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.ContourPurpose
import com.gecesars.atxplan.domain.contour.ContourStatus
import com.gecesars.atxplan.domain.contour.ServiceContourOverlay
import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.GeographicViewport
import com.gecesars.atxplan.domain.geo.ScreenPointPx
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxDarkBackground
import com.gecesars.atxplan.ui.theme.AtxSignal
import com.gecesars.atxplan.ui.theme.AtxTealLight
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Offline geographic overview for durable project sites.
 *
 * The caller owns persistence. A location editor emits one stale-safe [RfAssetMutationCommand.MoveSite]
 * and waits for the correlated durable receipt before dismissing its reviewed draft.
 */
@Composable
fun EngineeringMapScreen(
    project: PlannerProject?,
    serviceContours: List<ServiceContourOverlay> = emptyList(),
    isCatalogWritable: Boolean = false,
    isSaving: Boolean = false,
    catalogMutationCompletionCount: Long = 0L,
    mutationSessionId: String = "",
    activeMutationRequestId: String? = null,
    lastMutationReceipt: RfAssetMutationReceipt? = null,
    onMoveSite: (RfAssetMutationCommand.MoveSite) -> Unit = {},
    onExportServiceContours: (Uri) -> Unit = {},
) {
    val sites = project?.sites.orEmpty()
    val density = LocalDensity.current
    val largeText = density.fontScale >= 1.3f
    val fitPaddingPx = with(density) { 36.dp.toPx().toDouble() }
    val mapTileSizePx = with(density) { 256.dp.toPx().toDouble() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var retainedProjectId by rememberSaveable(
        saver = boundedNullableIdStateSaver,
    ) { mutableStateOf(project?.id) }
    var cameraLatitude by rememberSaveable { mutableStateOf(0.0) }
    var cameraLongitude by rememberSaveable { mutableStateOf(0.0) }
    var cameraZoom by rememberSaveable { mutableStateOf(DEFAULT_CAMERA_ZOOM) }
    var hasFittedProject by rememberSaveable { mutableStateOf(false) }
    var selectedSiteId by rememberSaveable(
        saver = boundedNullableIdStateSaver,
    ) { mutableStateOf<String?>(null) }
    var movingSiteId by rememberSaveable(
        saver = boundedNullableIdStateSaver,
    ) { mutableStateOf<String?>(null) }
    var moveLatitudeDraft by rememberSaveable { mutableStateOf("") }
    var moveLongitudeDraft by rememberSaveable { mutableStateOf("") }
    var moveSiteSnapshotToken by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRequestId by rememberSaveable(
        saver = boundedNullableIdStateSaver,
    ) { mutableStateOf<String?>(null) }
    var pendingCompletionCount by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingMutationSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveDialogMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val kmzExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kmz"),
    ) { uri -> uri?.let(onExportServiceContours) }

    fun currentCamera(): GeographicCamera = GeographicCamera(
        center = GeographicViewport.canonicalPoint(
            GeoPoint(cameraLatitude, cameraLongitude),
        ),
        zoom = cameraZoom,
    )

    fun updateCamera(camera: GeographicCamera) {
        cameraLatitude = camera.center.latitude
        cameraLongitude = camera.center.longitude
        cameraZoom = camera.zoom
    }

    fun loadMoveDraft(site: RadioSite) {
        moveLatitudeDraft = formatCoordinate(site.location.latitude)
        moveLongitudeDraft = formatCoordinate(site.location.longitude)
        moveSiteSnapshotToken = siteSnapshotToken(site)
    }

    fun clearPendingMove() {
        pendingRequestId = null
        pendingCompletionCount = null
        pendingMutationSessionId = null
    }

    fun currentViewport(): ViewportSizePx? = viewportSize
        .takeIf { it.width > 0 && it.height > 0 }
        ?.let { ViewportSizePx(it.width.toDouble(), it.height.toDouble()) }

    fun fitSites() {
        val viewport = currentViewport() ?: return
        val contourPoints = serviceContours
            .filter { contour -> contour.status != ContourStatus.NO_DATA }
            .flatMap(ServiceContourOverlay::points)
        val visibleGeometry = sites.map(RadioSite::location) + contourPoints
        if (visibleGeometry.isEmpty()) {
            updateCamera(DEFAULT_GEOGRAPHIC_CAMERA)
            return
        }
        val padding = fitPaddingPx.coerceAtMost(min(viewport.width, viewport.height) * 0.28)
        updateCamera(
            GeographicViewport.fitCamera(
                points = visibleGeometry,
                viewport = viewport,
                paddingPx = padding,
                minZoom = MIN_MAP_UI_ZOOM,
                maxZoom = MAX_MAP_UI_ZOOM,
                tileSizePx = mapTileSizePx,
            ),
        )
    }

    fun centerOnSite(site: RadioSite) {
        updateCamera(
            GeographicCamera(
                center = GeographicViewport.canonicalPoint(site.location),
                zoom = max(cameraZoom, SITE_FOCUS_ZOOM).coerceAtMost(MAX_MAP_UI_ZOOM),
            ),
        )
    }

    val siteGeometryKey = remember(sites) {
        sites.map { site -> site.id to site.location }
    }
    val contourGeometryKey = remember(serviceContours) {
        serviceContours
            .filter { contour -> contour.status != ContourStatus.NO_DATA }
            .map { contour ->
                ContourGeometryKey(
                    id = contour.id,
                    status = contour.status,
                    points = contour.points,
                )
            }
    }
    val selectedSite = sites.firstOrNull { it.id == selectedSiteId }
    val movingSite = sites.firstOrNull { it.id == movingSiteId }
    val currentMovingSiteToken = remember(movingSite) {
        movingSite?.let(::siteSnapshotToken)
    }
    val mutationPending = pendingRequestId != null || activeMutationRequestId != null

    LaunchedEffect(project?.id) {
        val loadedProjectId = project?.id ?: return@LaunchedEffect
        if (retainedProjectId != loadedProjectId) {
            retainedProjectId = loadedProjectId
            cameraLatitude = DEFAULT_GEOGRAPHIC_CAMERA.center.latitude
            cameraLongitude = DEFAULT_GEOGRAPHIC_CAMERA.center.longitude
            cameraZoom = DEFAULT_GEOGRAPHIC_CAMERA.zoom
            hasFittedProject = false
            selectedSiteId = null
            movingSiteId = null
            moveLatitudeDraft = ""
            moveLongitudeDraft = ""
            moveSiteSnapshotToken = null
            clearPendingMove()
            moveDialogMessage = null
        }
    }

    LaunchedEffect(
        project?.id,
        viewportSize,
        siteGeometryKey,
        contourGeometryKey,
        hasFittedProject,
    ) {
        if (project == null) return@LaunchedEffect
        if (!hasFittedProject && viewportSize.width > 0 && viewportSize.height > 0) {
            fitSites()
            hasFittedProject = true
        }
    }

    LaunchedEffect(project?.id, siteGeometryKey, selectedSiteId, movingSiteId) {
        if (project == null) return@LaunchedEffect
        if (selectedSiteId != null && sites.none { it.id == selectedSiteId }) {
            selectedSiteId = null
        }
        if (movingSiteId != null && sites.none { it.id == movingSiteId }) {
            movingSiteId = null
            moveLatitudeDraft = ""
            moveLongitudeDraft = ""
            moveSiteSnapshotToken = null
            clearPendingMove()
        }
    }

    LaunchedEffect(mutationSessionId, pendingRequestId, pendingMutationSessionId) {
        if (pendingRequestId != null && pendingMutationSessionId != mutationSessionId) {
            clearPendingMove()
            moveDialogMessage =
                "The previous save session ended before its result could be confirmed. Your coordinate draft is preserved; review it before retrying."
        }
    }

    LaunchedEffect(
        movingSiteId,
        currentMovingSiteToken,
        moveSiteSnapshotToken,
        mutationPending,
    ) {
        val currentSite = movingSite ?: return@LaunchedEffect
        if (!mutationPending && currentMovingSiteToken != moveSiteSnapshotToken) {
            loadMoveDraft(currentSite)
            moveDialogMessage =
                "This site changed while its location was being edited. The latest coordinates are shown; review and retry."
        }
    }

    LaunchedEffect(project?.id, selectedSiteId) {
        if (project != null && selectedSiteId != null) {
            listState.animateScrollToItem(SELECTED_SITE_PANEL_INDEX)
        }
    }

    LaunchedEffect(
        movingSiteId,
        pendingRequestId,
        activeMutationRequestId,
        lastMutationReceipt?.requestId,
        catalogMutationCompletionCount,
        isSaving,
    ) {
        if (movingSiteId == null) return@LaunchedEffect
        val requestId = pendingRequestId ?: return@LaunchedEffect
        val receipt = lastMutationReceipt?.takeIf { it.requestId == requestId }
        if (receipt == null) {
            val baseline = pendingCompletionCount ?: return@LaunchedEffect
            if (
                activeMutationRequestId != requestId &&
                !isSaving &&
                catalogMutationCompletionCount != baseline
            ) {
                clearPendingMove()
                moveDialogMessage =
                    "The location was not saved. Review the coordinates and retry."
            }
            return@LaunchedEffect
        }

        clearPendingMove()
        if (receipt.kind != RfAssetKind.SITE) {
            moveDialogMessage = "The location result could not be correlated. Retry the move."
            return@LaunchedEffect
        }
        when (receipt.status) {
            RfAssetMutationStatus.UPDATED,
            RfAssetMutationStatus.UNCHANGED,
            -> {
                movingSiteId = null
                moveLatitudeDraft = ""
                moveLongitudeDraft = ""
                moveSiteSnapshotToken = null
                moveDialogMessage = null
            }

            RfAssetMutationStatus.STALE -> {
                movingSite?.let(::loadMoveDraft)
                moveDialogMessage =
                    "This site changed before the move was saved. The latest coordinates are shown; review and retry."
            }

            RfAssetMutationStatus.NOT_FOUND -> {
                movingSiteId = null
                selectedSiteId = null
                moveLatitudeDraft = ""
                moveLongitudeDraft = ""
                moveSiteSnapshotToken = null
                moveDialogMessage = null
            }

            RfAssetMutationStatus.BLOCKED_REFERENCES -> {
                moveDialogMessage = "The location change was blocked. Review the project and retry."
            }

            RfAssetMutationStatus.CREATED,
            RfAssetMutationStatus.DELETED,
            -> {
                moveDialogMessage = "The location result was unexpected. Review the project before retrying."
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useTwoColumnSiteRows = !largeText && maxWidth >= 600.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .testTag("engineering_map_screen"),
            contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = project?.name ?: "No Project Selected",
                    subtitle = if (serviceContours.isEmpty()) {
                        "Offline WGS 84 site geometry with a coordinate-grid overlay."
                    } else {
                        "Offline WGS 84 site and service-contour geometry on a coordinate grid."
                    },
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(
                        "${sites.size} ${if (sites.size == 1) "Site" else "Sites"}",
                        if (sites.isEmpty()) StatusTone.INFO else StatusTone.POSITIVE,
                    )
                    StatusPill("Pan + Pinch", StatusTone.INFO)
                    if (serviceContours.isNotEmpty()) {
                        StatusPill(
                            "${serviceContours.size} ${if (serviceContours.size == 1) "Contour" else "Contours"}",
                            if (serviceContours.any { it.status != ContourStatus.COMPLETE }) {
                                StatusTone.WARNING
                            } else {
                                StatusTone.POSITIVE
                            },
                        )
                        OutlinedButton(
                            onClick = { kmzExportLauncher.launch("atx-service-contours.kmz") },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("export_service_contours_kmz"),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Text("Export KMZ")
                        }
                    }
                    StatusPill("No Basemap Installed", StatusTone.WARNING)
                }
            }
            item {
                GeographicMapCard(
                    sites = sites,
                    siteGeometryKey = siteGeometryKey,
                    serviceContours = serviceContours,
                    contourGeometryKey = contourGeometryKey,
                    selectedSiteId = selectedSiteId,
                    camera = currentCamera(),
                    tileSizePx = mapTileSizePx,
                    viewportSize = viewportSize,
                    onViewportSizeChanged = { viewportSize = it },
                    onCameraChanged = ::updateCamera,
                    onSelectSite = { selectedSiteId = it },
                    onFit = {
                        fitSites()
                        hasFittedProject = true
                    },
                    onReset = {
                        updateCamera(DEFAULT_GEOGRAPHIC_CAMERA)
                        hasFittedProject = true
                    },
                )
            }
            selectedSite?.let { site ->
                item(key = "selected-site:${site.id}") {
                    SelectedSitePanel(
                        site = site,
                        isCatalogWritable = isCatalogWritable,
                        isBusy = isSaving || mutationPending,
                        onCenter = { centerOnSite(site) },
                        onEditLocation = {
                            moveDialogMessage = null
                            loadMoveDraft(site)
                            movingSiteId = site.id
                        },
                    )
                }
            }
            if (serviceContours.isNotEmpty()) {
                item { ServiceContourLegendCard(serviceContours) }
            }
            item { BasemapDisclosureCard(hasServiceContours = serviceContours.isNotEmpty()) }
            item { Text("Project Sites", style = MaterialTheme.typography.titleLarge) }
            if (sites.isEmpty()) {
                item { EmptySitesCard(projectSelected = project != null) }
            } else if (useTwoColumnSiteRows) {
                items(sites.chunked(2), key = { row -> "site-row:${row.first().id}" }) { rowSites ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        rowSites.forEach { site ->
                            SiteMapRow(
                                site = site,
                                selected = site.id == selectedSiteId,
                                onSelect = {
                                    selectedSiteId = site.id
                                    centerOnSite(site)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowSites.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(sites, key = { site -> "site-row:${site.id}" }) { site ->
                    SiteMapRow(
                        site = site,
                        selected = site.id == selectedSiteId,
                        onSelect = {
                            selectedSiteId = site.id
                            centerOnSite(site)
                        },
                    )
                }
            }
        }
    }

    if (project != null && movingSite != null) {
        MoveSiteLocationDialog(
            projectId = project.id,
            site = movingSite,
            latitude = moveLatitudeDraft,
            longitude = moveLongitudeDraft,
            onLatitudeChange = { moveLatitudeDraft = it },
            onLongitudeChange = { moveLongitudeDraft = it },
            isCatalogWritable = isCatalogWritable,
            isSaving = isSaving || mutationPending,
            statusMessage = moveDialogMessage,
            onDismiss = {
                if (!isSaving && !mutationPending) {
                    movingSiteId = null
                    moveLatitudeDraft = ""
                    moveLongitudeDraft = ""
                    moveSiteSnapshotToken = null
                    moveDialogMessage = null
                }
            },
            onSubmit = submit@ { command ->
                val latestSite = project.sites.firstOrNull { it.id == movingSiteId }
                val latestToken = latestSite?.let(::siteSnapshotToken)
                if (latestSite == null) {
                    movingSiteId = null
                    selectedSiteId = null
                    moveLatitudeDraft = ""
                    moveLongitudeDraft = ""
                    moveSiteSnapshotToken = null
                    return@submit
                }
                if (latestToken != moveSiteSnapshotToken) {
                    loadMoveDraft(latestSite)
                    moveDialogMessage =
                        "This site changed while its location was being edited. The latest coordinates are shown; review and retry."
                    return@submit
                }
                val safeCommand = command.copy(expected = latestSite)
                pendingRequestId = safeCommand.requestId
                pendingCompletionCount = catalogMutationCompletionCount
                pendingMutationSessionId = mutationSessionId
                moveDialogMessage = null
                runCatching { onMoveSite(safeCommand) }.onFailure {
                    clearPendingMove()
                    moveDialogMessage =
                        "The location request could not be started. Review the project and retry."
                }
            },
        )
    }
}

@Composable
private fun GeographicMapCard(
    sites: List<RadioSite>,
    siteGeometryKey: List<Pair<String, GeoPoint>>,
    serviceContours: List<ServiceContourOverlay>,
    contourGeometryKey: List<ContourGeometryKey>,
    selectedSiteId: String?,
    camera: GeographicCamera,
    tileSizePx: Double,
    viewportSize: IntSize,
    onViewportSizeChanged: (IntSize) -> Unit,
    onCameraChanged: (GeographicCamera) -> Unit,
    onSelectSite: (String?) -> Unit,
    onFit: () -> Unit,
    onReset: () -> Unit,
) {
    val density = LocalDensity.current
    val latestCamera by rememberUpdatedState(camera)
    val latestCameraChanged by rememberUpdatedState(onCameraChanged)
    val renderableContourCount = serviceContours.count { contour ->
        contour.status != ContourStatus.NO_DATA && contour.points.size >= 2
    }
    val fitEnabled = sites.isNotEmpty() || renderableContourCount > 0
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val mapHeight = (maxWidth * 0.90f).coerceIn(340.dp, 460.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(AtxDarkBackground),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.36f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onFit,
                    enabled = fitEnabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("fit_map_sites"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.38f),
                    ),
                ) {
                    Text("Fit")
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reset_map_camera"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Reset")
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (renderableContourCount == 0) "GRID ONLY" else "GRID + CONTOURS",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged(onViewportSizeChanged)
                    .pointerInput(viewportSize, siteGeometryKey, contourGeometryKey) {
                        detectTapGestures { tap ->
                            if (viewportSize.width <= 0 || viewportSize.height <= 0) {
                                return@detectTapGestures
                            }
                            val viewport = viewportSize.toGeographicViewport()
                            val hitRadiusPx = 28.dp.toPx().toDouble()
                            val nearest = sites
                                .map { site ->
                                    val point = GeographicViewport.toScreen(
                                        site.location,
                                        latestCamera,
                                        viewport,
                                        tileSizePx,
                                    )
                                    site to hypot(point.x - tap.x, point.y - tap.y)
                                }
                                .minByOrNull { (_, distance) -> distance }
                            onSelectSite(nearest?.takeIf { it.second <= hitRadiusPx }?.first?.id)
                        }
                    }
                    .pointerInput(viewportSize) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            if (viewportSize.width <= 0 || viewportSize.height <= 0) {
                                return@detectTransformGestures
                            }
                            val viewport = viewportSize.toGeographicViewport()
                            val panned = GeographicViewport.panBy(
                                camera = latestCamera,
                                deltaXpx = pan.x.toDouble(),
                                deltaYpx = pan.y.toDouble(),
                                tileSizePx = tileSizePx,
                            )
                            val transformed = GeographicViewport.zoomBy(
                                camera = panned,
                                zoomFactor = zoomChange.toDouble(),
                                anchor = ScreenPointPx(
                                    centroid.x.toDouble(),
                                    centroid.y.toDouble(),
                                ),
                                viewport = viewport,
                                tileSizePx = tileSizePx,
                                minZoom = MIN_MAP_UI_ZOOM,
                                maxZoom = MAX_MAP_UI_ZOOM,
                            )
                            latestCameraChanged(transformed)
                        }
                    },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = mapCanvasDescription(
                                siteCount = sites.size,
                                serviceContours = serviceContours,
                            )
                        }
                        .testTag("engineering_map_canvas"),
                ) {
                    drawRect(AtxDarkBackground)
                    val viewport = ViewportSizePx(size.width.toDouble(), size.height.toDouble())
                    drawCoordinateGrid(camera, viewport, tileSizePx)
                    drawServiceContours(serviceContours, camera, viewport, tileSizePx)
                    drawSiteGeometry(sites, selectedSiteId, camera, viewport, tileSizePx)
                    drawCenterCrosshair()
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 84.dp, max = 124.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    MapScaleBar(
                        camera = camera,
                        viewportSize = viewportSize,
                        density = density,
                        tileSizePx = tileSizePx,
                    )
                    Text(
                        "ATX grid | WGS 84",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Center ${formatLatitude(camera.center.latitude)}  " +
                        "${formatLongitude(camera.center.longitude)}  |  " +
                        "z${String.format(Locale.US, "%.1f", camera.zoom)}",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("map_center_coordinates"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ServiceContourLegendCard(serviceContours: List<ServiceContourOverlay>) {
    val completeCount = serviceContours.count { it.status == ContourStatus.COMPLETE }
    val incompleteCount = serviceContours.count { it.status == ContourStatus.INCOMPLETE }
    val noDataCount = serviceContours.count { it.status == ContourStatus.NO_DATA }
    var showDetails by remember(serviceContours) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("service_contour_legend"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Layers, contentDescription = null)
                Text(
                    "Service Contours",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${serviceContours.size} local ${if (serviceContours.size == 1) "result" else "results"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ContourLegendItem(
                    color = AtxTealLight,
                    dashed = false,
                    label = "Protected — solid; complete geometry filled",
                )
                ContourLegendItem(
                    color = AtxAmber,
                    dashed = true,
                    label = "Statistical screening — dashed",
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (completeCount > 0) {
                    StatusPill("Complete geometry: $completeCount", StatusTone.POSITIVE)
                }
                if (incompleteCount > 0) {
                    StatusPill("Incomplete geometry: $incompleteCount", StatusTone.WARNING)
                }
                if (noDataCount > 0) StatusPill("NoData: $noDataCount", StatusTone.NEGATIVE)
            }
            Text(
                "Geometry state is not regulatory approval. Details expose each result's " +
                    "statistical basis, threshold, model, ruleset, warnings, and NoData.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("service_contour_details_toggle"),
            ) {
                Text(if (showDetails) "Hide details" else "Show details (${serviceContours.size})")
            }
            if (showDetails) {
                serviceContours.forEachIndexed { index, contour ->
                    if (index > 0) HorizontalDivider()
                    ServiceContourSummary(contour)
                }
            }
        }
    }
}

@Composable
private fun ContourLegendItem(
    color: Color,
    dashed: Boolean,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(24.dp).height(8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) {
                    PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                } else {
                    null
                },
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceContourSummary(contour: ServiceContourOverlay) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("service_contour_${contour.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${contourServiceLabel(contour.service)} ${contourPurposeLabel(contour.purpose)}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${contour.statisticalBasis} · ${contourThresholdLabel(contour.thresholdDbuvPerM)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${contourStatusLabel(contour.status)} · Model ${contour.model} · Ruleset ${contour.rulesetId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (contour.status) {
            ContourStatus.COMPLETE -> Unit
            ContourStatus.INCOMPLETE -> Text(
                "Incomplete geometry is shown without fill and must not be read as a closed service area.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            ContourStatus.NO_DATA -> Text(
                "NoData: no contour geometry is rendered for this result.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        contour.warnings.forEach { warning ->
            Text(
                "Warning: $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MapScaleBar(
    camera: GeographicCamera,
    viewportSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    tileSizePx: Double,
) {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return
    val maxScaleWidthPx = min(
        viewportSize.width * 0.25,
        with(density) { 120.dp.toPx().toDouble() },
    )
    val scale = remember(camera, viewportSize, tileSizePx, maxScaleWidthPx) {
        GeographicViewport.scaleBar(
            camera = camera,
            maxWidthPx = maxScaleWidthPx,
            tileSizePx = tileSizePx,
        )
    }
    val widthDp = with(density) { scale.widthPx.toFloat().toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            formatScaleDistance(scale.distanceMeters),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier
                .width(widthDp)
                .height(3.dp)
                .background(Color.White),
        )
    }
}

private fun DrawScope.drawCoordinateGrid(
    camera: GeographicCamera,
    viewport: ViewportSizePx,
    tileSizePx: Double,
) {
    val worldSizePx = GeographicViewport.worldSizePx(camera.zoom, tileSizePx)
    val visibleLongitudeSpan = (viewport.width / worldSizePx * 360.0).coerceAtMost(360.0)
    val longitudeStep = coordinateGridStep(visibleLongitudeSpan)
    val longitudeStart = floor(
        (camera.center.longitude - visibleLongitudeSpan / 2.0) / longitudeStep,
    ) * longitudeStep
    val gridColor = Color.White.copy(alpha = 0.11f)
    val majorGridColor = Color.White.copy(alpha = 0.18f)
    val strokeWidth = 1.dp.toPx()

    var longitude = longitudeStart
    repeat(MAX_GRID_LINES) {
        if (longitude > camera.center.longitude + visibleLongitudeSpan / 2.0 + longitudeStep) {
            return@repeat
        }
        val canonicalLongitude = normalizeLongitude(longitude)
        val point = GeographicViewport.toScreen(
            GeoPoint(camera.center.latitude, canonicalLongitude),
            camera,
            viewport,
            tileSizePx,
        )
        if (point.x in -1.0..(viewport.width + 1.0)) {
            val major = abs(longitude / longitudeStep % 5.0) < 0.0001
            drawLine(
                color = if (major) majorGridColor else gridColor,
                start = Offset(point.x.toFloat(), 0f),
                end = Offset(point.x.toFloat(), size.height),
                strokeWidth = strokeWidth,
            )
        }
        longitude += longitudeStep
    }

    val topLatitude = GeographicViewport.fromScreen(
        ScreenPointPx(viewport.width / 2.0, 0.0),
        camera,
        viewport,
        tileSizePx,
    ).latitude
    val bottomLatitude = GeographicViewport.fromScreen(
        ScreenPointPx(viewport.width / 2.0, viewport.height),
        camera,
        viewport,
        tileSizePx,
    ).latitude
    val visibleLatitudeSpan = abs(topLatitude - bottomLatitude).coerceAtLeast(0.000001)
    val latitudeStep = coordinateGridStep(visibleLatitudeSpan)
    var latitude = floor(bottomLatitude / latitudeStep) * latitudeStep
    repeat(MAX_GRID_LINES) {
        if (latitude > topLatitude + latitudeStep) return@repeat
        if (latitude in -85.0..85.0) {
            val point = GeographicViewport.toScreen(
                GeoPoint(latitude, camera.center.longitude),
                camera,
                viewport,
                tileSizePx,
            )
            if (point.y in -1.0..(viewport.height + 1.0)) {
                val major = abs(latitude / latitudeStep % 5.0) < 0.0001
                drawLine(
                    color = if (major) majorGridColor else gridColor,
                    start = Offset(0f, point.y.toFloat()),
                    end = Offset(size.width, point.y.toFloat()),
                    strokeWidth = strokeWidth,
                )
            }
        }
        latitude += latitudeStep
    }
}

private fun DrawScope.drawServiceContours(
    serviceContours: List<ServiceContourOverlay>,
    camera: GeographicCamera,
    viewport: ViewportSizePx,
    tileSizePx: Double,
) {
    val protectedStrokeWidth = 2.25.dp.toPx()
    val screeningStrokeWidth = 2.dp.toPx()
    val screeningDash = PathEffect.dashPathEffect(
        floatArrayOf(8.dp.toPx(), 5.dp.toPx()),
    )
    serviceContours.forEach { contour ->
        if (contour.status == ContourStatus.NO_DATA || contour.points.size < 2) {
            return@forEach
        }
        val drawablePoints = if (
            contour.status == ContourStatus.INCOMPLETE &&
            contour.points.size > 2 &&
            contour.points.first() == contour.points.last()
        ) {
            contour.points.dropLast(1)
        } else {
            contour.points
        }
        val path = Path()
        drawablePoints.forEachIndexed { index, point ->
            val projected = GeographicViewport.toScreen(point, camera, viewport, tileSizePx)
            if (index == 0) {
                path.moveTo(projected.x.toFloat(), projected.y.toFloat())
            } else {
                path.lineTo(projected.x.toFloat(), projected.y.toFloat())
            }
        }
        val complete = contour.status == ContourStatus.COMPLETE
        if (complete) path.close()
        val color = when (contour.purpose) {
            ContourPurpose.PROTECTED -> AtxTealLight
            ContourPurpose.SCREENING -> AtxAmber
        }
        if (
            contour.purpose == ContourPurpose.PROTECTED &&
            complete &&
            contour.points.size >= 3
        ) {
            drawPath(path = path, color = color.copy(alpha = 0.10f))
        }
        drawPath(
            path = path,
            color = color.copy(alpha = if (complete) 0.96f else 0.82f),
            style = Stroke(
                width = if (contour.purpose == ContourPurpose.PROTECTED) {
                    protectedStrokeWidth
                } else {
                    screeningStrokeWidth
                },
                cap = StrokeCap.Round,
                pathEffect = if (contour.purpose == ContourPurpose.SCREENING) {
                    screeningDash
                } else {
                    null
                },
            ),
        )
    }
}

private fun DrawScope.drawSiteGeometry(
    sites: List<RadioSite>,
    selectedSiteId: String?,
    camera: GeographicCamera,
    viewport: ViewportSizePx,
    tileSizePx: Double,
) {
    val haloRadius = 22.dp.toPx()
    val ringRadius = 13.dp.toPx()
    val selectedRadius = 18.dp.toPx()
    val vectorLength = 30.dp.toPx()
    val vectorStrokeWidth = 2.dp.toPx()
    sites.forEachIndexed { index, site ->
        val projected = GeographicViewport.toScreen(site.location, camera, viewport, tileSizePx)
        val point = Offset(projected.x.toFloat(), projected.y.toFloat())
        if (
            point.x !in -haloRadius..(size.width + haloRadius) ||
            point.y !in -haloRadius..(size.height + haloRadius)
        ) {
            return@forEachIndexed
        }
        val siteColor = when (index % 3) {
            0 -> AtxTealLight
            1 -> AtxAmber
            else -> AtxSignal
        }
        if (site.id == selectedSiteId) {
            drawCircle(
                Color.White.copy(alpha = 0.92f),
                radius = selectedRadius,
                center = point,
                style = Stroke(2.dp.toPx()),
            )
        }
        drawCircle(siteColor.copy(alpha = 0.10f), radius = haloRadius, center = point)
        drawCircle(
            siteColor.copy(alpha = 0.45f),
            radius = ringRadius,
            center = point,
            style = Stroke(1.dp.toPx()),
        )
        site.sectors.filter { it.active }.forEach { sector ->
            val angle = Math.toRadians(sector.azimuthDegrees)
            val end = Offset(
                x = point.x + sin(angle).toFloat() * vectorLength,
                y = point.y - cos(angle).toFloat() * vectorLength,
            )
            drawLine(
                color = siteColor,
                start = point,
                end = end,
                strokeWidth = vectorStrokeWidth,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(Color.White, radius = 4.dp.toPx(), center = point)
        drawCircle(siteColor, radius = 2.5.dp.toPx(), center = point)
    }
}

private fun DrawScope.drawCenterCrosshair() {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = 5.dp.toPx()
    val length = 10.dp.toPx()
    val color = Color.White.copy(alpha = 0.62f)
    drawCircle(color, radius = radius, center = center, style = Stroke(1.dp.toPx()))
    drawLine(color, center.copy(x = center.x - length), center.copy(x = center.x + length))
    drawLine(color, center.copy(y = center.y - length), center.copy(y = center.y + length))
}

@Composable
private fun SelectedSitePanel(
    site: RadioSite,
    isCatalogWritable: Boolean,
    isBusy: Boolean,
    onCenter: () -> Unit,
    onEditLocation: () -> Unit,
) {
    val activeSectors = site.sectors.count { it.active }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("map_selected_site_panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null)
                Text(
                    site.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$activeSectors/${site.sectors.size} active",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "${formatLatitude(site.location.latitude)}  ${formatLongitude(site.location.longitude)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                siteElevationLabel(site),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Moving coordinates does not resample or alter the stored project elevation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onCenter,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Center on Site")
                }
                Button(
                    onClick = onEditLocation,
                    enabled = isCatalogWritable && !isBusy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit_map_site_location"),
                ) {
                    Icon(Icons.Outlined.EditLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit Location")
                }
            }
            if (!isCatalogWritable) {
                Text(
                    "Location editing is unavailable while the project catalog is read-only.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BasemapDisclosureCard(hasServiceContours: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "No basemap installed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "The canvas is an offline WGS 84 longitude/latitude coordinate overlay using " +
                        "Web Mercator display geometry. Attribution: ATX Plan coordinate grid and " +
                        "local project data. No third-party map tiles are rendered.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (hasServiceContours) {
                        "Service-contour geometry is rendered only from supplied local results; this " +
                            "screen does not recalculate it. The model, ruleset, statistical basis, " +
                            "threshold, warnings, and NoData state remain visible above. Terrain, " +
                            "clutter, other GIS features, and raster coverage are not rendered."
                    } else {
                        "Terrain, clutter, GIS features, and coverage results are not rendered in this view."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptySitesCard(projectSelected: Boolean) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null)
            Text(
                if (projectSelected) {
                    "The selected project does not have any sites yet."
                } else {
                    "Select a project to inspect its sites."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SiteMapRow(
    site: RadioSite,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSectors = site.sectors.count { it.active }
    Card(
        onClick = onSelect,
        modifier = modifier
            .heightIn(min = 72.dp)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected site" else "Not selected"
            }
            .testTag("map_site_${site.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        site.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$activeSectors/${site.sectors.size} active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "${formatLatitude(site.location.latitude)}  ${formatLongitude(site.location.longitude)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    siteElevationLabel(site),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MoveSiteLocationDialog(
    projectId: String,
    site: RadioSite,
    latitude: String,
    longitude: String,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    isCatalogWritable: Boolean,
    isSaving: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (RfAssetMutationCommand.MoveSite) -> Unit,
) {
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val formMessage = validationMessage
        ?: statusMessage
        ?: if (!isCatalogWritable) {
            "This project is read-only. Location changes cannot be saved."
        } else {
            null
        }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val heightFraction = when {
                maxHeight < 600.dp -> 0.90f
                largeText -> 0.74f
                else -> 0.58f
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(heightFraction)
                    .widthIn(max = 620.dp)
                    .testTag("map_site_location_editor"),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Move ${site.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Edit WGS 84 coordinates only. All other imported site fields are preserved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SignedCoordinateField(
                        value = latitude,
                        onValueChange = {
                            if (it.length <= MAX_COORDINATE_DRAFT_LENGTH) {
                                onLatitudeChange(it)
                                validationMessage = null
                            } else {
                                validationMessage =
                                    "Latitude is limited to $MAX_COORDINATE_DRAFT_LENGTH characters."
                            }
                        },
                        label = "Latitude (degrees)",
                        fieldTestTag = "map_site_latitude_field",
                        signDescription = "Change latitude sign",
                        enabled = !isSaving,
                    )
                    SignedCoordinateField(
                        value = longitude,
                        onValueChange = {
                            if (it.length <= MAX_COORDINATE_DRAFT_LENGTH) {
                                onLongitudeChange(it)
                                validationMessage = null
                            } else {
                                validationMessage =
                                    "Longitude is limited to $MAX_COORDINATE_DRAFT_LENGTH characters."
                            }
                        },
                        label = "Longitude (degrees)",
                        fieldTestTag = "map_site_longitude_field",
                        signDescription = "Change longitude sign",
                        enabled = !isSaving,
                    )
                    Text(
                        siteElevationLabel(site),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "This move does not sample a DEM or recalculate the stored project elevation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                formMessage?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        MapFormMessage(message, isError = true)
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val parsedLatitude = parseCoordinateDraft(latitude)
                            val parsedLongitude = parseCoordinateDraft(longitude)
                            when {
                                parsedLatitude == null || !parsedLatitude.isFinite() -> {
                                    validationMessage = "Latitude requires a finite number."
                                }

                                parsedLatitude !in -90.0..90.0 -> {
                                    validationMessage = "Latitude must be between -90 and 90 degrees."
                                }

                                parsedLongitude == null || !parsedLongitude.isFinite() -> {
                                    validationMessage = "Longitude requires a finite number."
                                }

                                parsedLongitude !in -180.0..180.0 -> {
                                    validationMessage = "Longitude must be between -180 and 180 degrees."
                                }

                                else -> {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    onSubmit(
                                        RfAssetMutationCommand.MoveSite(
                                            projectId = projectId,
                                            expected = site,
                                            location = GeoPoint(parsedLatitude, parsedLongitude),
                                            requestId = "map-move-${UUID.randomUUID()}",
                                        ),
                                    )
                                }
                            }
                        },
                        enabled = isCatalogWritable && !isSaving,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("save_map_site_location"),
                    ) {
                        Text(if (isSaving) "Saving..." else "Save Location")
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SignedCoordinateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldTestTag: String,
    signDescription: String,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
            ),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(fieldTestTag),
        )
        OutlinedButton(
            onClick = { onValueChange(toggleCoordinateSign(value)) },
            enabled = enabled,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .width(52.dp)
                .heightIn(min = 56.dp)
                .semantics { contentDescription = signDescription },
        ) {
            Text("\u00B1", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MapFormMessage(message: String, isError: Boolean) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("map_location_message"),
    ) {
        Text(
            message,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

private fun IntSize.toGeographicViewport(): ViewportSizePx =
    ViewportSizePx(width.toDouble(), height.toDouble())

private fun mapCanvasDescription(
    siteCount: Int,
    serviceContours: List<ServiceContourOverlay>,
): String {
    val siteLabel = "$siteCount project ${if (siteCount == 1) "site" else "sites"}"
    if (serviceContours.isEmpty()) {
        return "Offline geographic coordinate grid with $siteLabel. " +
            "No basemap is installed. Pan with one finger and pinch to zoom."
    }
    val protectedCount = serviceContours.count { it.purpose == ContourPurpose.PROTECTED }
    val screeningCount = serviceContours.count { it.purpose == ContourPurpose.SCREENING }
    val completeCount = serviceContours.count { it.status == ContourStatus.COMPLETE }
    val incompleteCount = serviceContours.count { it.status == ContourStatus.INCOMPLETE }
    val noDataCount = serviceContours.count { it.status == ContourStatus.NO_DATA }
    return "Offline geographic coordinate grid with $siteLabel and " +
        "${serviceContours.size} service contour ${if (serviceContours.size == 1) "record" else "records"}: " +
        "$protectedCount protected, $screeningCount statistical screening; " +
        "$completeCount complete geometry, $incompleteCount incomplete geometry, $noDataCount NoData. " +
        "No basemap is installed. Pan with one finger and pinch to zoom."
}

private fun contourServiceLabel(service: BroadcastService): String = when (service) {
    BroadcastService.FM -> "FM"
    BroadcastService.DIGITAL_TV -> "Digital TV"
}

private fun contourPurposeLabel(purpose: ContourPurpose): String = when (purpose) {
    ContourPurpose.PROTECTED -> "Protected"
    ContourPurpose.SCREENING -> "Statistical Screening"
}

private fun contourStatusLabel(status: ContourStatus): String = when (status) {
    ContourStatus.COMPLETE -> "Complete geometry"
    ContourStatus.INCOMPLETE -> "Incomplete geometry"
    ContourStatus.NO_DATA -> "NoData"
}

private fun contourThresholdLabel(thresholdDbuvPerM: Double?): String = thresholdDbuvPerM?.let { threshold ->
    String.format(Locale.US, "%.1f dB\u00B5V/m", threshold)
} ?: "Threshold NoData"

private fun coordinateGridStep(visibleSpanDegrees: Double): Double {
    val desiredStep = (visibleSpanDegrees / 6.0).coerceAtLeast(0.000001)
    val magnitude = 10.0.pow(floor(log10(desiredStep)))
    val normalized = desiredStep / magnitude
    val multiplier = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return multiplier * magnitude
}

private fun normalizeLongitude(longitude: Double): Double {
    val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (wrapped == -180.0 && longitude > 0.0) 180.0 else wrapped
}

private fun formatLatitude(value: Double): String =
    String.format(Locale.US, "%.5f\u00B0 %s", abs(value), if (value < 0.0) "S" else "N")

private fun formatLongitude(value: Double): String =
    String.format(Locale.US, "%.5f\u00B0 %s", abs(value), if (value < 0.0) "W" else "E")

private fun formatCoordinate(value: Double): String {
    if (value == 0.0 && value.toRawBits() == (-0.0).toRawBits()) return "-0"
    val plainText = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    return plainText.takeIf { it.length <= MAX_COORDINATE_DRAFT_LENGTH } ?: value.toString()
}

private fun parseCoordinateDraft(value: String): Double? {
    val trimmed = value.trim()
    val commaCount = trimmed.count { it == ',' }
    if (commaCount > 1 || (commaCount == 1 && '.' in trimmed)) return null
    return trimmed.replace(',', '.').toDoubleOrNull()
}

private fun toggleCoordinateSign(value: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("-") -> trimmed.removePrefix("-")
        trimmed.startsWith("+") -> "-${trimmed.removePrefix("+")}"
        trimmed.isEmpty() -> "-"
        else -> "-$trimmed"
    }
}

private fun formatScaleDistance(distanceMeters: Double): String = if (distanceMeters >= 1_000.0) {
    val kilometers = distanceMeters / 1_000.0
    String.format(Locale.US, if (kilometers >= 10.0) "%.0f km" else "%.1f km", kilometers)
} else {
    String.format(Locale.US, "%.0f m", distanceMeters)
}

private fun siteElevationLabel(site: RadioSite): String = site.groundElevationM?.let { elevation ->
    "Elevation: Project value | ${formatCompactNumber(elevation)} m (stored, not DEM-derived)"
} ?: "Elevation: NoData | no stored project elevation"

private fun formatCompactNumber(value: Double): String = String.format(Locale.US, "%.2f", value)
    .trimEnd('0')
    .trimEnd('.')

private val siteSnapshotJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun siteSnapshotToken(site: RadioSite): String = MessageDigest.getInstance("SHA-256")
    .digest(
        siteSnapshotJson
            .encodeToString(RadioSite.serializer(), site)
            .toByteArray(Charsets.UTF_8),
    )
    .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

private val DEFAULT_GEOGRAPHIC_CAMERA = GeographicCamera(
    center = GeoPoint(latitude = 0.0, longitude = 0.0),
    zoom = DEFAULT_CAMERA_ZOOM,
)
private val boundedNullableIdStateSaver = Saver<MutableState<String?>, String>(
    save = { state ->
        state.value?.takeIf { value -> value.length <= MAX_SAVEABLE_UI_ID_LENGTH }
    },
    restore = { value -> mutableStateOf(value) },
)
private data class ContourGeometryKey(
    val id: String,
    val status: ContourStatus,
    val points: List<GeoPoint>,
)

private const val DEFAULT_CAMERA_ZOOM = 1.0
private const val SITE_FOCUS_ZOOM = 12.0
private const val MIN_MAP_UI_ZOOM = 0.0
private const val MAX_MAP_UI_ZOOM = 20.0
private const val MAX_GRID_LINES = 48
private const val SELECTED_SITE_PANEL_INDEX = 3
private const val MAX_SAVEABLE_UI_ID_LENGTH = 256
private const val MAX_COORDINATE_DRAFT_LENGTH = 64
