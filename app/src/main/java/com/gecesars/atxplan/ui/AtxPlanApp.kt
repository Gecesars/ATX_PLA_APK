package com.gecesars.atxplan.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.gecesars.atxplan.ui.components.StorageErrorBanner
import com.gecesars.atxplan.ui.navigation.AtxRoute
import com.gecesars.atxplan.ui.navigation.CatalogRoute
import com.gecesars.atxplan.ui.navigation.DashboardRoute
import com.gecesars.atxplan.ui.navigation.MapRoute
import com.gecesars.atxplan.ui.navigation.ProjectRenameRoute
import com.gecesars.atxplan.ui.navigation.ProjectsRoute
import com.gecesars.atxplan.ui.navigation.RfPathEditorRoute
import com.gecesars.atxplan.ui.navigation.StudiesRoute
import com.gecesars.atxplan.ui.navigation.UnsupportedRoute
import com.gecesars.atxplan.ui.navigation.activeRoute
import com.gecesars.atxplan.ui.navigation.rememberAtxNavBackStack
import com.gecesars.atxplan.ui.navigation.replaceTopLevel
import com.gecesars.atxplan.ui.screens.CatalogScreen
import com.gecesars.atxplan.ui.screens.DashboardScreen
import com.gecesars.atxplan.ui.screens.EngineeringMapScreen
import com.gecesars.atxplan.ui.screens.ProjectRenameScreen
import com.gecesars.atxplan.ui.screens.ProjectsScreen
import com.gecesars.atxplan.ui.screens.RfPathEditorScreen
import com.gecesars.atxplan.ui.screens.StudiesScreen
import com.gecesars.atxplan.ui.theme.AtxNavy
import com.gecesars.atxplan.ui.theme.AtxTeal

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
        onSelectProject = viewModel::selectProject,
        onCalculateLink = viewModel::calculateLinkBudget,
        onSaveRfPath = viewModel::addRfPath,
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
    onSelectProject: (String) -> Unit,
    onCalculateLink: (com.gecesars.atxplan.domain.rf.LinkBudgetInput) -> Unit,
    onSaveRfPath: (com.gecesars.atxplan.domain.application.AddRfPathCommand) -> Unit,
    onRetryLoad: () -> Unit,
) {
    val backStack = rememberAtxNavBackStack()
    val currentUiState = rememberUpdatedState(uiState)
    val activeRoute = backStack.activeRoute
    val isNestedEditor = activeRoute is RfPathEditorRoute || activeRoute is ProjectRenameRoute
    var isEditorDirty by rememberSaveable { mutableStateOf(false) }
    var isEditorSavePending by remember { mutableStateOf(false) }
    var pendingEditorNavigation by remember { mutableStateOf<PendingEditorNavigation?>(null) }
    var navigationNotice by remember { mutableStateOf<String?>(null) }
    val activeTopLevelRoute = when (activeRoute) {
        is RfPathEditorRoute,
        is ProjectRenameRoute,
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
                                    )
                                }
                                MapRoute -> NavEntry(route) {
                                    EngineeringMapScreen(project = currentUiState.value.selectedProject)
                                }
                                StudiesRoute -> NavEntry(route) {
                                    StudiesScreen(
                                        project = currentUiState.value.selectedProject,
                                        resultInput = currentUiState.value.linkBudgetInput,
                                        result = currentUiState.value.linkBudgetResult,
                                        calculatorError = currentUiState.value.calculatorError,
                                        isCalculating = currentUiState.value.isCalculating,
                                        onCalculate = onCalculateLink,
                                    )
                                }
                                CatalogRoute -> NavEntry(route) { CatalogScreen() }
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
