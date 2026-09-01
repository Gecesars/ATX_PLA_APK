package com.gecesars.atxplan.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gecesars.atxplan.ui.components.StorageErrorBanner
import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyPdfExporter
import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyReportExporter
import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyXlsxExporter
import com.gecesars.atxplan.data.export.ServiceContourKmzExporter
import com.gecesars.atxplan.data.regulatory.AndroidBrazilDigitalTvStudyRunner
import com.gecesars.atxplan.data.regulatory.RegulatoryArtifactProgress
import com.gecesars.atxplan.ui.dataset.DataCatalogViewModel
import com.gecesars.atxplan.ui.dataset.IbgeCatalogStatus
import com.gecesars.atxplan.ui.dataset.RegionalDataViewModel
import com.gecesars.atxplan.domain.contour.BrazilBroadcastContourPlanner
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.ContourPurpose
import com.gecesars.atxplan.domain.contour.ServiceContourOverlay
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.ui.navigation.AtxRoute
import com.gecesars.atxplan.ui.navigation.AntennaPatternsRoute
import com.gecesars.atxplan.ui.navigation.CatalogRoute
import com.gecesars.atxplan.ui.navigation.DashboardRoute
import com.gecesars.atxplan.ui.navigation.MapRoute
import com.gecesars.atxplan.ui.navigation.ProjectRenameRoute
import com.gecesars.atxplan.ui.navigation.ProjectsRoute
import com.gecesars.atxplan.ui.navigation.RfPathEditorRoute
import com.gecesars.atxplan.ui.navigation.RfAssetsRoute
import com.gecesars.atxplan.ui.navigation.StudiesRoute
import com.gecesars.atxplan.ui.navigation.UnsupportedRoute
import com.gecesars.atxplan.ui.navigation.activeRoute
import com.gecesars.atxplan.ui.navigation.rememberAtxNavBackStack
import com.gecesars.atxplan.ui.navigation.replaceTopLevel
import com.gecesars.atxplan.ui.screens.CatalogScreen
import com.gecesars.atxplan.ui.screens.AntennaPatternLabScreen
import com.gecesars.atxplan.ui.screens.DashboardScreen
import com.gecesars.atxplan.ui.screens.EngineeringMapScreen
import com.gecesars.atxplan.ui.screens.ProjectRenameScreen
import com.gecesars.atxplan.ui.screens.ProjectsScreen
import com.gecesars.atxplan.ui.screens.RfPathEditorScreen
import com.gecesars.atxplan.ui.screens.RfAssetsScreen
import com.gecesars.atxplan.ui.screens.StudiesScreen
import com.gecesars.atxplan.ui.theme.AtxNavy
import com.gecesars.atxplan.ui.theme.AtxTeal
import com.gecesars.atxplan.ui.antenna.AntennaPatternLabViewModel
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanViewModel
import com.gecesars.atxplan.ui.basemap.BasemapViewModel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TopLevelDestination(
    val route: AtxRoute,
    val label: String,
    val compactLabel: String = label,
    val icon: ImageVector,
)

private sealed interface PendingEditorNavigation {
    data object Back : PendingEditorNavigation

    data class TopLevel(val route: AtxRoute) : PendingEditorNavigation
}

private val destinations = listOf(
    TopLevelDestination(DashboardRoute, "Overview", "Home", Icons.Outlined.Dashboard),
    TopLevelDestination(ProjectsRoute, "Projects", icon = Icons.Outlined.FolderOpen),
    TopLevelDestination(MapRoute, "Engineering Map", "Map", Icons.Outlined.Map),
    TopLevelDestination(StudiesRoute, "Studies", icon = Icons.Outlined.Calculate),
    TopLevelDestination(CatalogRoute, "Data", "Data", Icons.Outlined.Storage),
)

@Composable
fun AtxPlanApp() {
    val context = LocalContext.current
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(context))
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    AtxPlanShell(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onCreateProject = viewModel::createProject,
        onRenameProject = viewModel::renameProject,
        onDuplicateProject = viewModel::duplicateProject,
        onArchiveProject = viewModel::archiveProject,
        onRestoreProject = viewModel::restoreProject,
        onDeleteProject = viewModel::deleteProject,
        onSelectProject = viewModel::selectProject,
        onCalculateLink = viewModel::calculateLinkBudget,
        onRunProjectLinkStudy = viewModel::runProjectLinkStudy,
        onSaveRfPath = viewModel::addRfPath,
        onMutateRfAsset = viewModel::mutateRfAsset,
        onRetryLoad = viewModel::retryLoad,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtxPlanShell(
    uiState: AppUiState,
    snackbarHostState: SnackbarHostState,
    onCreateProject: (String, String) -> Unit,
    onRenameProject: (com.gecesars.atxplan.domain.application.RenameProjectCommand) -> Unit,
    onDuplicateProject: (com.gecesars.atxplan.domain.application.DuplicateProjectCommand) -> Unit,
    onArchiveProject: (com.gecesars.atxplan.domain.application.ArchiveProjectCommand) -> Unit,
    onRestoreProject: (com.gecesars.atxplan.domain.application.RestoreProjectCommand) -> Unit,
    onDeleteProject: (com.gecesars.atxplan.domain.application.DeleteProjectCommand) -> Unit,
    onSelectProject: (String) -> Unit,
    onCalculateLink: (com.gecesars.atxplan.domain.rf.LinkBudgetInput) -> Unit,
    onRunProjectLinkStudy: (com.gecesars.atxplan.domain.application.RunProjectLinkStudyCommand) -> Unit,
    onSaveRfPath: (com.gecesars.atxplan.domain.application.AddRfPathCommand) -> Unit,
    onMutateRfAsset: (com.gecesars.atxplan.domain.application.RfAssetMutationCommand) -> Unit,
    onRetryLoad: () -> Unit,
) {
    val backStack = rememberAtxNavBackStack()
    val currentUiState = rememberUpdatedState(uiState)
    val activeRoute = backStack.activeRoute
    val isNestedEditor = activeRoute is RfPathEditorRoute ||
        activeRoute is ProjectRenameRoute ||
        activeRoute is RfAssetsRoute ||
        activeRoute is AntennaPatternsRoute
    var isEditorDirty by rememberSaveable { mutableStateOf(false) }
    var isEditorSavePending by remember { mutableStateOf(false) }
    var pendingEditorNavigation by remember { mutableStateOf<PendingEditorNavigation?>(null) }
    var navigationNotice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val regulatoryIbgeViewModel: DataCatalogViewModel = viewModel(
        key = "regulatory-ibge-catalog",
        factory = DataCatalogViewModel.factory(context),
    )
    val regulatoryIbgeState by regulatoryIbgeViewModel.state.collectAsStateWithLifecycle()
    val regulatoryStudyRunner = remember(context.applicationContext) {
        AndroidBrazilDigitalTvStudyRunner(context.applicationContext)
    }
    var regulatoryStudy by remember { mutableStateOf<BrazilDigitalTvRegulatoryStudyResult?>(null) }
    var regulatoryStudyError by remember { mutableStateOf<String?>(null) }
    var regulatoryStudyProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var regulatoryStudyPreparation by remember { mutableStateOf<RegulatoryArtifactProgress?>(null) }
    var isRunningRegulatoryStudy by remember { mutableStateOf(false) }
    var regulatoryStudyJob by remember { mutableStateOf<Job?>(null) }
    val activeTopLevelRoute = when (activeRoute) {
        is RfPathEditorRoute,
        is ProjectRenameRoute,
        is RfAssetsRoute,
        is AntennaPatternsRoute,
        -> ProjectsRoute
        else -> activeRoute
    }
    val navigateImmediately: (AtxRoute) -> Unit = backStack::replaceTopLevel
    val navigateBackImmediately: () -> Unit = {
        if (backStack.size > 1) backStack.removeLastOrNull() else backStack.replaceTopLevel(ProjectsRoute)
    }
    val canLeaveEditor = !isNestedEditor ||
        (!isEditorSavePending && !uiState.isSavingCatalog)
    val performNavigation: (PendingEditorNavigation) -> Unit = { request ->
        when (request) {
            PendingEditorNavigation.Back -> navigateBackImmediately()
            is PendingEditorNavigation.TopLevel -> navigateImmediately(request.route)
        }
    }
    val requestNavigation: (PendingEditorNavigation) -> Unit = { request ->
        when {
            isNestedEditor && !canLeaveEditor -> Unit
            isNestedEditor && isEditorDirty -> {
                pendingEditorNavigation = request
            }
            else -> performNavigation(request)
        }
    }
    val navigate: (AtxRoute) -> Unit = { route ->
        requestNavigation(PendingEditorNavigation.TopLevel(route))
    }
    val navigateBack: () -> Unit = {
        requestNavigation(PendingEditorNavigation.Back)
    }

    LaunchedEffect(activeRoute) {
        if (!isNestedEditor) {
            isEditorDirty = false
            isEditorSavePending = false
            pendingEditorNavigation = null
        }
    }

    LaunchedEffect(uiState.selectedProject) {
        regulatoryStudyJob?.cancel()
        regulatoryStudyJob = null
        regulatoryStudy = null
        regulatoryStudyError = null
        regulatoryStudyProgress = null
        isRunningRegulatoryStudy = false
    }

    LaunchedEffect(navigationNotice) {
        navigationNotice?.let { message ->
            snackbarHostState.showSnackbar(message)
            navigationNotice = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 720.dp
        val compactRail = maxHeight < 520.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                AtxNavigationRail(
                    activeRoute = activeTopLevelRoute,
                    onNavigate = navigate,
                    enabled = canLeaveEditor,
                    compact = compactRail,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    CenterAlignedTopAppBar(
                        navigationIcon = {
                            if (isNestedEditor) {
                                IconButton(
                                    onClick = navigateBack,
                                    enabled = canLeaveEditor,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Back to Projects",
                                    )
                                }
                            }
                        },
                        title = {
                            Text(
                                text = when (activeRoute) {
                                    is RfPathEditorRoute -> "Add RF Path"
                                    is ProjectRenameRoute -> "Rename Project"
                                    is RfAssetsRoute -> "RF Assets"
                                    is AntennaPatternsRoute -> "Antenna Pattern Lab"
                                    else -> destinations.firstOrNull { it.route == activeRoute }
                                        ?.label
                                        ?: destinations.first().label
                                },
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                },
                bottomBar = {
                    if (!expanded) {
                        AtxBottomNavigation(
                            activeRoute = activeTopLevelRoute,
                            onNavigate = navigate,
                            enabled = canLeaveEditor,
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    uiState.storageError?.let {
                        StorageErrorBanner(message = it, onRetry = onRetryLoad)
                    }
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            if (backStack.size > 1) navigateBack()
                        },
                        entryProvider = { route ->
                            when (route) {
                                DashboardRoute -> NavEntry(route) {
                                    DashboardScreen(
                                        uiState = currentUiState.value,
                                        onOpenProjects = { navigate(ProjectsRoute) },
                                        onOpenMap = { navigate(MapRoute) },
                                        onOpenStudies = { navigate(StudiesRoute) },
                                    )
                                }
                                ProjectsRoute -> NavEntry(route) {
                                    ProjectsScreen(
                                        uiState = currentUiState.value,
                                        onCreateProject = onCreateProject,
                                        onSelectProject = onSelectProject,
                                        onDuplicateProject = onDuplicateProject,
                                        onArchiveProject = onArchiveProject,
                                        onRestoreProject = onRestoreProject,
                                        onDeleteProject = onDeleteProject,
                                        onRenameProject = { projectId ->
                                            val route = AtxRoute.projectRename(projectId)
                                            if (
                                                route is ProjectRenameRoute &&
                                                backStack.lastOrNull() != route
                                            ) {
                                                backStack.add(route)
                                            } else if (route !is ProjectRenameRoute) {
                                                navigationNotice = "The project name editor cannot open this " +
                                                    "project because its stored ID is not navigation-safe. " +
                                                    "The project data was not changed."
                                            }
                                        },
                                        onAddRfPath = { projectId ->
                                            val route = AtxRoute.rfPathEditor(projectId)
                                            if (route is RfPathEditorRoute && backStack.lastOrNull() != route) {
                                                backStack.add(route)
                                            } else if (route !is RfPathEditorRoute) {
                                                navigationNotice = "The RF Path Editor cannot open this project " +
                                                    "because its stored ID is not navigation-safe. The project " +
                                                    "data was not changed."
                                            }
                                        },
                                        onManageRfAssets = { projectId ->
                                            val route = AtxRoute.rfAssets(projectId)
                                            if (route is RfAssetsRoute && backStack.lastOrNull() != route) {
                                                backStack.add(route)
                                            } else if (route !is RfAssetsRoute) {
                                                navigationNotice = "The RF asset manager cannot open this project " +
                                                    "because its stored ID is not navigation-safe. The project " +
                                                    "data was not changed."
                                            }
                                        },
                                    )
                                }
                                MapRoute -> NavEntry(route) {
                                    val basemapViewModel: BasemapViewModel = viewModel(
                                        factory = BasemapViewModel.factory(context),
                                    )
                                    val basemapState by basemapViewModel.state
                                        .collectAsStateWithLifecycle()
                                    val state = currentUiState.value
                                    val serviceContours = remember(state.selectedProject, regulatoryStudy) {
                                        val planned = BrazilBroadcastContourPlanner
                                            .plan(state.selectedProject)
                                            .overlays
                                        val calculated = regulatoryStudy?.takeIf { study ->
                                            study.projectId == state.selectedProject?.id
                                        }
                                        if (calculated == null) {
                                            planned
                                        } else {
                                            planned.filterNot { overlay ->
                                                overlay.siteId == calculated.siteId &&
                                                    overlay.sectorId == calculated.sectorId &&
                                                    overlay.service == calculated.service &&
                                                    overlay.purpose == ContourPurpose.PROTECTED
                                            } + calculated.contour +
                                                calculated.referenceContours.map { it.contour }
                                        }
                                    }
                                    EngineeringMapScreen(
                                        project = state.selectedProject,
                                        serviceContours = serviceContours,
                                        coverageSurface = regulatoryStudy?.takeIf { study ->
                                            study.projectId == state.selectedProject?.id
                                        }?.coverageSurface,
                                        duAssessments = regulatoryStudy?.takeIf { study ->
                                            study.projectId == state.selectedProject?.id
                                        }?.duAssessments.orEmpty(),
                                        censusGeometry = regulatoryStudy?.takeIf { study ->
                                            study.projectId == state.selectedProject?.id
                                        }?.censusGeometry,
                                        basemapState = basemapState,
                                        isCatalogWritable = state.isCatalogWritable,
                                        isSaving = state.isSavingCatalog,
                                        catalogMutationCompletionCount =
                                            state.catalogMutationCompletionCount,
                                        mutationSessionId = state.rfMutationSessionId,
                                        activeMutationRequestId = state.activeRfMutationRequestId,
                                        lastMutationReceipt = state.lastRfMutationReceipt,
                                        onMoveSite = onMutateRfAsset,
                                        onSelectBasemapProvider = basemapViewModel::selectProvider,
                                        onRequestVisibleBasemap = basemapViewModel::requestVisibleTiles,
                                        onRefreshVisibleBasemap = basemapViewModel::refreshVisibleTiles,
                                        onExportServiceContours = { destination ->
                                            val contourSnapshot = serviceContours
                                            exportScope.launch {
                                                navigationNotice = exportServiceContoursKmz(
                                                    context = context,
                                                    overlays = contourSnapshot,
                                                    destination = destination,
                                                )
                                            }
                                        },
                                    )
                                }
                                StudiesRoute -> NavEntry(route) {
                                    StudiesScreen(
                                        project = currentUiState.value.selectedProject,
                                        resultInput = currentUiState.value.linkBudgetInput,
                                        result = currentUiState.value.linkBudgetResult,
                                        calculatorError = currentUiState.value.calculatorError,
                                        isCalculating = currentUiState.value.isCalculating,
                                        isRunningProjectLinkStudy =
                                            currentUiState.value.isRunningProjectLinkStudy,
                                        canSaveProjectStudy = currentUiState.value.isCatalogWritable &&
                                            !currentUiState.value.isSavingCatalog,
                                        onCalculate = onCalculateLink,
                                        onRunProjectLinkStudy = onRunProjectLinkStudy,
                                        brazilDigitalTvStudy = regulatoryStudy?.takeIf { study ->
                                            study.projectId == currentUiState.value.selectedProject?.id
                                        },
                                        brazilDigitalTvStudyError = regulatoryStudyError,
                                        isRunningBrazilDigitalTvStudy = isRunningRegulatoryStudy,
                                        brazilDigitalTvStudyProgress = regulatoryStudyProgress,
                                        brazilDigitalTvStudyPreparation = regulatoryStudyPreparation,
                                        municipalityQuery = regulatoryIbgeState.municipalityQuery,
                                        municipalityResults = regulatoryIbgeState.municipalityResults,
                                        selectedMunicipality = regulatoryIbgeState.selectedMunicipality,
                                        isMunicipalityCatalogReady =
                                            regulatoryIbgeState.ibgeStatus == IbgeCatalogStatus.READY,
                                        isSearchingMunicipalities =
                                            regulatoryIbgeState.isSearchingMunicipalities,
                                        municipalityError = regulatoryIbgeState.datasetErrorMessage
                                            ?: regulatoryIbgeState.searchErrorMessage,
                                        onMunicipalityQueryChange =
                                            regulatoryIbgeViewModel::updateMunicipalityQuery,
                                        onMunicipalitySelected = regulatoryIbgeViewModel::selectMunicipality,
                                        onRunBrazilDigitalTvStudy = { radiusKm, municipality ->
                                            val projectSnapshot = currentUiState.value.selectedProject
                                            if (projectSnapshot == null) {
                                                regulatoryStudyError =
                                                    "Create or select an independent project before running this study."
                                            } else {
                                                regulatoryStudyJob?.cancel()
                                                regulatoryStudy = null
                                                regulatoryStudyError = null
                                                regulatoryStudyProgress = 0 to 1
                                                regulatoryStudyPreparation = null
                                                isRunningRegulatoryStudy = true
                                                regulatoryStudyJob = exportScope.launch {
                                                    try {
                                                        val calculated = regulatoryStudyRunner.run(
                                                            project = projectSnapshot,
                                                            radiusKm = radiusKm,
                                                            municipality = municipality,
                                                            onPreparation = { preparation ->
                                                                regulatoryStudyPreparation = preparation
                                                            },
                                                            onProgress = { completed, total ->
                                                                regulatoryStudyPreparation = null
                                                                regulatoryStudyProgress = completed to total
                                                            },
                                                        )
                                                        if (
                                                            currentUiState.value.selectedProject?.id ==
                                                            projectSnapshot.id
                                                        ) {
                                                            regulatoryStudy = calculated
                                                        }
                                                    } catch (error: CancellationException) {
                                                        throw error
                                                    } catch (error: Exception) {
                                                        regulatoryStudyError = regulatoryStudyErrorMessage(error)
                                                    } finally {
                                                        isRunningRegulatoryStudy = false
                                                        regulatoryStudyProgress = null
                                                        regulatoryStudyPreparation = null
                                                        regulatoryStudyJob = null
                                                    }
                                                }
                                            }
                                        },
                                        onExportBrazilDigitalTvReport = { destination ->
                                            val resultSnapshot = regulatoryStudy
                                            if (resultSnapshot == null) {
                                                regulatoryStudyError = "Run the regulatory study before exporting its report."
                                            } else {
                                                exportScope.launch {
                                                    navigationNotice = exportBrazilDigitalTvReport(
                                                        context = context,
                                                        result = resultSnapshot,
                                                        destination = destination,
                                                    )
                                                }
                                            }
                                        },
                                        onExportBrazilDigitalTvPdf = { destination ->
                                            val resultSnapshot = regulatoryStudy
                                            if (resultSnapshot == null) {
                                                regulatoryStudyError = "Run the regulatory study before exporting its PDF."
                                            } else {
                                                exportScope.launch {
                                                    navigationNotice = exportBrazilDigitalTvPdf(
                                                        context = context,
                                                        result = resultSnapshot,
                                                        destination = destination,
                                                    )
                                                }
                                            }
                                        },
                                        onExportBrazilDigitalTvXlsx = { destination ->
                                            val resultSnapshot = regulatoryStudy
                                            if (resultSnapshot == null) {
                                                regulatoryStudyError = "Run the regulatory study before exporting its workbook."
                                            } else {
                                                exportScope.launch {
                                                    navigationNotice = exportBrazilDigitalTvXlsx(
                                                        context = context,
                                                        result = resultSnapshot,
                                                        destination = destination,
                                                    )
                                                }
                                            }
                                        },
                                        onExportBrazilDigitalTvKmz = { destination ->
                                            val resultSnapshot = regulatoryStudy
                                            if (resultSnapshot == null) {
                                                regulatoryStudyError = "Run the regulatory study before exporting its KMZ."
                                            } else {
                                                exportScope.launch {
                                                    navigationNotice = exportServiceContoursKmz(
                                                        context = context,
                                                        overlays = listOf(resultSnapshot.contour) +
                                                            resultSnapshot.referenceContours.map { it.contour },
                                                        destination = destination,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                                CatalogRoute -> NavEntry(route) {
                                    DataCatalogRouteContent(
                                        project = currentUiState.value.selectedProject,
                                    )
                                }
                                is RfPathEditorRoute -> NavEntry(route) {
                                    val state = currentUiState.value
                                    RfPathEditorScreen(
                                        project = state.catalog.projects
                                            .firstOrNull { project -> project.id == route.projectId },
                                        isLoadingCatalog = state.isLoading,
                                        isCatalogWritable = state.isCatalogWritable,
                                        isSaving = state.isSavingCatalog,
                                        catalogMutationCompletionCount =
                                            state.catalogMutationCompletionCount,
                                        onSave = onSaveRfPath,
                                        onDirtyStateChange = { isEditorDirty = it },
                                        onSavePendingChange = { isEditorSavePending = it },
                                        onSaveSucceeded = {
                                            isEditorDirty = false
                                            isEditorSavePending = false
                                            pendingEditorNavigation = null
                                            navigateBackImmediately()
                                        },
                                        onBack = navigateBack,
                                    )
                                }
                                is ProjectRenameRoute -> NavEntry(route) {
                                    val state = currentUiState.value
                                    ProjectRenameScreen(
                                        project = state.catalog.projects
                                            .firstOrNull { project -> project.id == route.projectId },
                                        isLoadingCatalog = state.isLoading,
                                        isCatalogWritable = state.isCatalogWritable,
                                        isSaving = state.isSavingCatalog,
                                        catalogMutationCompletionCount =
                                            state.catalogMutationCompletionCount,
                                        onSave = onRenameProject,
                                        onDirtyStateChange = { isEditorDirty = it },
                                        onSavePendingChange = { isEditorSavePending = it },
                                        onSaveSucceeded = {
                                            isEditorDirty = false
                                            isEditorSavePending = false
                                            pendingEditorNavigation = null
                                            navigateBackImmediately()
                                        },
                                        onBack = navigateBack,
                                    )
                                }
                                is RfAssetsRoute -> NavEntry(route) {
                                    val state = currentUiState.value
                                    RfAssetsScreen(
                                        project = state.catalog.projects
                                            .firstOrNull { project -> project.id == route.projectId },
                                        isLoadingCatalog = state.isLoading,
                                        isCatalogWritable = state.isCatalogWritable,
                                        isSaving = state.isSavingCatalog,
                                        catalogMutationCompletionCount =
                                            state.catalogMutationCompletionCount,
                                        activeMutationRequestId = state.activeRfMutationRequestId,
                                        lastMutationReceipt = state.lastRfMutationReceipt,
                                        onMutate = onMutateRfAsset,
                                        onOpenAntennaPatterns = {
                                            val patternRoute = AtxRoute.antennaPatterns(route.projectId)
                                            if (
                                                patternRoute is AntennaPatternsRoute &&
                                                backStack.lastOrNull() != patternRoute
                                            ) {
                                                backStack.add(patternRoute)
                                            }
                                        },
                                        onBack = navigateBack,
                                    )
                                }
                                is AntennaPatternsRoute -> NavEntry(route) {
                                    val state = currentUiState.value
                                    AntennaPatternRouteContent(
                                        projectId = route.projectId,
                                        project = state.catalog.projects
                                            .firstOrNull { project -> project.id == route.projectId },
                                        isCatalogWritable = state.isCatalogWritable,
                                        onCatalogChanged = onRetryLoad,
                                        onBack = navigateBack,
                                    )
                                }
                                is UnsupportedRoute -> NavEntry(route) {
                                    DashboardScreen(
                                        uiState = currentUiState.value,
                                        onOpenProjects = { navigate(ProjectsRoute) },
                                        onOpenMap = { navigate(MapRoute) },
                                        onOpenStudies = { navigate(StudiesRoute) },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pendingEditorNavigation?.let { request ->
        val isProjectRename = activeRoute is ProjectRenameRoute
        AlertDialog(
            onDismissRequest = { pendingEditorNavigation = null },
            title = {
                Text(
                    if (isProjectRename) {
                        "Discard project name changes?"
                    } else {
                        "Discard unsaved RF path?"
                    },
                )
            },
            text = {
                Text(
                    if (isProjectRename) {
                        "The new project name has not been saved. Discard it and leave this screen?"
                    } else {
                        "Your changes have not been saved. Discard the draft and leave the editor?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingEditorNavigation = null
                        isEditorDirty = false
                        performNavigation(request)
                    },
                ) {
                    Text(if (isProjectRename) "Discard Changes" else "Discard Draft")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEditorNavigation = null }) {
                    Text("Keep Editing")
                }
            },
        )
    }
}

@Composable
private fun AntennaPatternRouteContent(
    projectId: String,
    project: PlannerProject?,
    isCatalogWritable: Boolean,
    onCatalogChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AntennaPatternLabViewModel = viewModel(
        key = "antenna-patterns:$projectId",
        factory = AntennaPatternLabViewModel.factory(context, projectId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.catalogMutationCount) {
        if (state.catalogMutationCount > 0L) onCatalogChanged()
    }
    AntennaPatternLabScreen(
        project = project,
        state = state,
        isCatalogWritable = isCatalogWritable,
        onImportUri = viewModel::inspectImport,
        onImportPairUris = viewModel::inspectImportPair,
        onConfirmImport = viewModel::confirmImport,
        onDismissImport = viewModel::dismissImport,
        onResolvePrnConvention = viewModel::resolvePrnConventionChoice,
        onDismissPrnConvention = viewModel::dismissPrnConventionChoice,
        onSynthesize = viewModel::synthesize,
        onPrepareExport = viewModel::prepareExport,
        onExportUri = viewModel::export,
        onDismissExport = viewModel::dismissExport,
        onAssignTransmitPattern = viewModel::assignTransmitPattern,
        onDeletePattern = viewModel::delete,
        onDismissMessage = viewModel::dismissMessage,
        onBack = onBack,
    )
}

@Composable
private fun DataCatalogRouteContent(project: PlannerProject?) {
    val context = LocalContext.current
    val viewModel: DataCatalogViewModel = viewModel(
        factory = DataCatalogViewModel.factory(context),
    )
    val regionalViewModel: RegionalDataViewModel = viewModel(
        factory = RegionalDataViewModel.factory(
            context = context,
            initialBounds = project.suggestedRegionalBounds(),
        ),
    )
    val anatelViewModel: AnatelBasicPlanViewModel = viewModel(
        factory = AnatelBasicPlanViewModel.factory(context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val regionalState by regionalViewModel.state.collectAsStateWithLifecycle()
    val anatelState by anatelViewModel.state.collectAsStateWithLifecycle()
    CatalogScreen(
        state = state,
        regionalState = regionalState,
        anatelState = anatelState,
        onMunicipalityQueryChange = viewModel::updateMunicipalityQuery,
        onMunicipalitySelected = viewModel::selectMunicipality,
        onRetryDataset = viewModel::retryDataset,
        onRegionalCoordinateChange = regionalViewModel::updateCoordinate,
        onRegionalSelectionToggle = regionalViewModel::toggleSelection,
        onRegionalLiveSnapshotRefreshChange = regionalViewModel::setLiveSnapshotRefresh,
        onReviewRegionalPlan = regionalViewModel::reviewPlan,
        onRegionalLicensesAccepted = regionalViewModel::setLicensesAccepted,
        onStartRegionalAcquisition = regionalViewModel::startAcquisition,
        onCancelRegionalAcquisition = regionalViewModel::cancelAcquisition,
        onEditRegionalRequest = regionalViewModel::editRequest,
        onAnatelLicenseReviewAcknowledged =
            anatelViewModel::setLicenseReviewAcknowledged,
        onRefreshAnatelCatalog = anatelViewModel::refresh,
        onAnatelServiceSelected = anatelViewModel::setService,
        onAnatelQueryTextChange = anatelViewModel::setQueryText,
        onAnatelStateCodeChange = anatelViewModel::setStateCode,
        onAnatelChannelChange = anatelViewModel::setChannelText,
        onSearchAnatelCatalog = anatelViewModel::search,
        onLoadPreviousAnatelRecords = anatelViewModel::loadPrevious,
        onLoadMoreAnatelRecords = anatelViewModel::loadMore,
        onDismissAnatelMessage = anatelViewModel::dismissMessage,
    )
}

private fun PlannerProject?.suggestedRegionalBounds(): RegionalBounds? {
    val locations = this?.sites?.map { site -> site.location }.orEmpty()
    if (locations.isEmpty()) return null
    val west = locations.minOf { point -> point.longitude }
    val east = locations.maxOf { point -> point.longitude }
    val south = locations.minOf { point -> point.latitude }
    val north = locations.maxOf { point -> point.latitude }
    val longitudePadding = maxOf(0.01, (east - west) * 0.08)
    val latitudePadding = maxOf(0.01, (north - south) * 0.08)
    val candidate = runCatching {
        RegionalBounds(
            west = (west - longitudePadding).coerceAtLeast(-180.0),
            south = (south - latitudePadding).coerceAtLeast(-90.0),
            east = (east + longitudePadding).coerceAtMost(180.0),
            north = (north + latitudePadding).coerceAtMost(90.0),
        )
    }.getOrNull()
    return candidate?.takeIf { bounds ->
        bounds.widthDegrees <= 1.0 && bounds.heightDegrees <= 1.0
    }
}

private fun regulatoryStudyErrorMessage(error: Exception): String = when (error) {
    is IOException,
    is IllegalArgumentException,
    is IllegalStateException,
    is SecurityException,
    -> error.message?.take(500) ?: "The regulatory study failed validation."

    else -> "The regulatory study could not be completed."
}

private suspend fun exportBrazilDigitalTvReport(
    context: Context,
    result: BrazilDigitalTvRegulatoryStudyResult,
    destination: Uri,
): String = try {
    val report = withContext(Dispatchers.Default) {
        BrazilDigitalTvStudyReportExporter.export(result)
    }
    val expectedSha256 = MessageDigest.getInstance("SHA-256")
        .digest(report)
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            output.write(report)
            output.flush()
        } ?: throw IOException("The selected report destination could not be opened.")
        val verified = context.contentResolver.openInputStream(destination)?.use { input ->
            input.readBounded(BrazilDigitalTvStudyReportExporter.MAX_OUTPUT_BYTES)
        } ?: throw IOException("The exported report could not be reopened for verification.")
        if (!verified.contentEquals(report)) {
            throw IOException("The exported report failed read-back verification.")
        }
        val verifiedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(verified)
            .joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        if (verifiedSha256 != expectedSha256) {
            throw IOException("The exported report failed SHA-256 verification.")
        }
    }
    "HTML report verified · ${report.size} bytes · SHA-256 ${expectedSha256.take(12)}…"
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    when (error) {
        is IOException,
        is IllegalArgumentException,
        is SecurityException,
        -> error.message?.take(500) ?: "The report export failed validation."

        else -> "The report export could not be completed."
    }
}

private suspend fun exportBrazilDigitalTvPdf(
    context: Context,
    result: BrazilDigitalTvRegulatoryStudyResult,
    destination: Uri,
): String = exportVerifiedRegulatoryDocument(
    context = context,
    destination = destination,
    maximumBytes = BrazilDigitalTvStudyPdfExporter.MAX_OUTPUT_BYTES,
    formatLabel = "PDF",
    build = { BrazilDigitalTvStudyPdfExporter.export(result) },
)

private suspend fun exportBrazilDigitalTvXlsx(
    context: Context,
    result: BrazilDigitalTvRegulatoryStudyResult,
    destination: Uri,
): String = exportVerifiedRegulatoryDocument(
    context = context,
    destination = destination,
    maximumBytes = BrazilDigitalTvStudyXlsxExporter.MAX_OUTPUT_BYTES,
    formatLabel = "XLSX",
    build = { BrazilDigitalTvStudyXlsxExporter.export(result) },
)

private suspend fun exportVerifiedRegulatoryDocument(
    context: Context,
    destination: Uri,
    maximumBytes: Int,
    formatLabel: String,
    build: () -> ByteArray,
): String = try {
    val document = withContext(Dispatchers.Default) { build() }
    val expectedSha256 = MessageDigest.getInstance("SHA-256")
        .digest(document)
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            output.write(document)
            output.flush()
        } ?: throw IOException("The selected $formatLabel destination could not be opened.")
        val verified = context.contentResolver.openInputStream(destination)?.use { input ->
            input.readBounded(maximumBytes)
        } ?: throw IOException("The exported $formatLabel could not be reopened for verification.")
        if (!verified.contentEquals(document)) {
            throw IOException("The exported $formatLabel failed read-back verification.")
        }
        val verifiedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(verified)
            .joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        if (verifiedSha256 != expectedSha256) {
            throw IOException("The exported $formatLabel failed SHA-256 verification.")
        }
    }
    "$formatLabel verified · ${document.size} bytes · SHA-256 ${expectedSha256.take(12)}…"
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    when (error) {
        is IOException,
        is IllegalArgumentException,
        is SecurityException,
        -> error.message?.take(500) ?: "The $formatLabel export failed validation."

        else -> "The $formatLabel export could not be completed."
    }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    require(maximumBytes > 0) { "The verification byte limit must be positive." }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maximumBytes) {
            throw IOException("The exported artifact exceeds its verification limit.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private suspend fun exportServiceContoursKmz(
    context: Context,
    overlays: List<ServiceContourOverlay>,
    destination: Uri,
): String = try {
    require(overlays.isNotEmpty()) { "There are no service-contour records to export." }
    val archive = withContext(Dispatchers.Default) {
        val output = ByteArrayOutputStream()
        val summary = ServiceContourKmzExporter().write(overlays, output)
        KmzArchive(output.toByteArray(), summary.sha256, summary.includedOverlayCount, summary.omittedNoDataOverlayCount)
    }
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            output.write(archive.bytes)
            output.flush()
        } ?: throw IOException("The selected KMZ destination could not be opened.")
        val verified = context.contentResolver.openInputStream(destination)?.use { input ->
            input.readBoundedKmz()
        } ?: throw IOException("The exported KMZ could not be reopened for verification.")
        if (!verified.contentEquals(archive.bytes)) {
            throw IOException("The exported KMZ failed read-back verification.")
        }
        val verifiedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(verified)
            .joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        if (verifiedSha256 != archive.sha256) {
            throw IOException("The exported KMZ hash does not match its generated manifest evidence.")
        }
    }
    "KMZ export verified · ${archive.drawableCount} drawable contour(s) · " +
        "${archive.noDataCount} NoData record(s) · SHA-256 ${archive.sha256.take(12)}…"
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    when (error) {
        is IOException,
        is IllegalArgumentException,
        is SecurityException,
        -> error.message?.take(500) ?: "The KMZ export failed validation."

        else -> "The KMZ export could not be completed."
    }
}

private fun InputStream.readBoundedKmz(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > ServiceContourKmzExporter.MAX_OUTPUT_BYTES) {
            throw IOException("The exported KMZ exceeds the 16 MiB verification limit.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private data class KmzArchive(
    val bytes: ByteArray,
    val sha256: String,
    val drawableCount: Int,
    val noDataCount: Int,
)

@Composable
private fun AtxNavigationRail(
    activeRoute: AtxRoute,
    onNavigate: (AtxRoute) -> Unit,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        header = if (compact) {
            null
        } else {
            {
                Box(
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ATX",
                        color = AtxTeal,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = activeRoute == destination.route,
                enabled = enabled,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = if (compact) null else {
                    { Text(destination.compactLabel) }
                },
            )
        }
    }
}

@Composable
private fun AtxBottomNavigation(
    activeRoute: AtxRoute,
    onNavigate: (AtxRoute) -> Unit,
    enabled: Boolean,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = activeRoute == destination.route,
                enabled = enabled,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.compactLabel) },
            )
        }
    }
}
