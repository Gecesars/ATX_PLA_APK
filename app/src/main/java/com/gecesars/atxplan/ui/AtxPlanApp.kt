package com.gecesars.atxplan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.gecesars.atxplan.ui.components.StorageErrorBanner
import com.gecesars.atxplan.ui.screens.CatalogScreen
import com.gecesars.atxplan.ui.screens.DashboardScreen
import com.gecesars.atxplan.ui.screens.EngineeringMapScreen
import com.gecesars.atxplan.ui.screens.ProjectsScreen
import com.gecesars.atxplan.ui.screens.StudiesScreen
import com.gecesars.atxplan.ui.theme.AtxNavy
import com.gecesars.atxplan.ui.theme.AtxTeal

private data object DashboardRoute
private data object ProjectsRoute
private data object MapRoute
private data object StudiesRoute
private data object CatalogRoute

private data class TopLevelDestination(
    val route: Any,
    val label: String,
    val compactLabel: String = label,
    val icon: ImageVector,
)

private val destinations = listOf(
    TopLevelDestination(DashboardRoute, "Overview", "Home", Icons.Outlined.Dashboard),
    TopLevelDestination(ProjectsRoute, "Projects", icon = Icons.Outlined.FolderOpen),
    TopLevelDestination(MapRoute, "Engineering Map", "Map", Icons.Outlined.Map),
    TopLevelDestination(StudiesRoute, "Studies", icon = Icons.Outlined.Calculate),
    TopLevelDestination(CatalogRoute, "Data & Capabilities", "Data", Icons.Outlined.Storage),
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
        onSelectProject = viewModel::selectProject,
        onCalculateLink = viewModel::calculateLinkBudget,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtxPlanShell(
    uiState: AppUiState,
    snackbarHostState: SnackbarHostState,
    onCreateProject: (String, String) -> Unit,
    onSelectProject: (String) -> Unit,
    onCalculateLink: (com.gecesars.atxplan.domain.rf.LinkBudgetInput) -> Unit,
) {
    val backStack = remember { mutableStateListOf<Any>(DashboardRoute) }
    val currentUiState = rememberUpdatedState(uiState)
    val activeRoute = backStack.lastOrNull() ?: DashboardRoute
    val navigate: (Any) -> Unit = { route ->
        if (activeRoute != route) {
            backStack.clear()
            backStack.add(route)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 900.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                AtxNavigationRail(
                    activeRoute = activeRoute,
                    onNavigate = navigate,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = destinations.first { it.route == activeRoute }.label,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                uiState.selectedProject?.let { project ->
                                    Text(
                                        text = project.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!expanded) {
                        AtxBottomNavigation(activeRoute = activeRoute, onNavigate = navigate)
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    uiState.storageError?.let { StorageErrorBanner(it) }
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
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
                                    )
                                }
                                MapRoute -> NavEntry(route) {
                                    EngineeringMapScreen(project = currentUiState.value.selectedProject)
                                }
                                StudiesRoute -> NavEntry(route) {
                                    StudiesScreen(
                                        project = currentUiState.value.selectedProject,
                                        result = currentUiState.value.linkBudgetResult,
                                        calculatorError = currentUiState.value.calculatorError,
                                        onCalculate = onCalculateLink,
                                    )
                                }
                                CatalogRoute -> NavEntry(route) { CatalogScreen() }
                                else -> NavEntry(Unit) { Text("Unknown destination") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AtxNavigationRail(
    activeRoute: Any,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        header = {
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
        },
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = activeRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.compactLabel) },
            )
        }
    }
}

@Composable
private fun AtxBottomNavigation(activeRoute: Any, onNavigate: (Any) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = activeRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.compactLabel) },
            )
        }
    }
}
