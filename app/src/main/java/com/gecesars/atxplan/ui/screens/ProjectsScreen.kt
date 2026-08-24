package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.ui.AppUiState
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsScreen(
    uiState: AppUiState,
    onCreateProject: (String, String) -> Unit,
    onSelectProject: (String) -> Unit,
    onAddRfPath: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var projectCountBeforeCreate by rememberSaveable { mutableStateOf<Int?>(null) }
    var observedCreateSave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.isSavingCatalog, uiState.catalog.projects.size) {
        val previousCount = projectCountBeforeCreate ?: return@LaunchedEffect
        if (uiState.isSavingCatalog) {
            observedCreateSave = true
        } else if (observedCreateSave) {
            if (uiState.catalog.projects.size > previousCount) showCreateDialog = false
            projectCountBeforeCreate = null
            observedCreateSave = false
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Local Projects",
                    subtitle = "Every change is saved on your device first, with no connection required.",
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        projectCountLabel(uiState.catalog.projects.size, "workspace", "workspaces"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    StatusPill(
                        label = if (uiState.isSavingCatalog) {
                            "Saving Locally"
                        } else {
                            "Schema ${uiState.catalog.schemaVersion}"
                        },
                        tone = StatusTone.INFO,
                        modifier = if (uiState.isSavingCatalog) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (uiState.isLoading) {
                item { LoadingProjectsCard() }
            } else {
                items(uiState.catalog.projects, key = PlannerProject::id) { project ->
                    ProjectCard(
                        project = project,
                        selected = uiState.selectedProject?.id == project.id,
                        enabled = uiState.isCatalogWritable && !uiState.isSavingCatalog,
                        onClick = { onSelectProject(project.id) },
                    )
                }
            }
            if (!uiState.isLoading && uiState.catalog.projects.isEmpty() && uiState.isCatalogWritable) {
                item { EmptyProjectsCard() }
            }
            uiState.selectedProject?.let { selected ->
                item {
                    SelectedProjectDetails(
                        project = selected,
                        canEdit = uiState.isCatalogWritable && !uiState.isSavingCatalog,
                        onAddRfPath = { onAddRfPath(selected.id) },
                    )
                }
            }
        }
        if (uiState.isCatalogWritable && !uiState.isSavingCatalog) {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .testTag("new_project_button"),
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New Project") },
            )
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            isSubmitting = projectCountBeforeCreate != null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, customer ->
                projectCountBeforeCreate = uiState.catalog.projects.size
                onCreateProject(name, customer)
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: PlannerProject,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.customer.ifBlank { "No customer specified" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Selected project",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    projectCountLabel(project.networks.size, "network", "networks"),
                    StatusTone.NEUTRAL,
                )
                StatusPill(
                    projectCountLabel(project.sites.size, "site", "sites"),
                    StatusTone.NEUTRAL,
                )
                if (project.isDemonstration) {
                    StatusPill("Demo", StatusTone.WARNING)
                }
            }
            Text(
                "Updated ${formatDate(project.updatedAtEpochMillis)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingProjectsCard() {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text("Loading local projects")
        }
    }
}

@Composable
private fun SelectedProjectDetails(
    project: PlannerProject,
    canEdit: Boolean,
    onAddRfPath: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("Selected Project", style = MaterialTheme.typography.titleLarge)
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                project.notes.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Networks", style = MaterialTheme.typography.titleMedium)
                if (project.networks.isEmpty()) {
                    Text(
                        "No networks have been added in this stage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    project.networks.forEach { network ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(network.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    radioSystemLabel(network.system),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("${network.downlinkFrequencyMHz} MHz")
                        }
                    }
                }
                Text("RF Assets", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${projectCountLabel(project.sites.size, "transmitter site", "transmitter sites")} and " +
                        "${projectCountLabel(project.receivers.size, "receiver", "receivers")} are linked " +
                        "to this project.",
                    modifier = Modifier.testTag("rf_asset_summary"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onAddRfPath,
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth().testTag("add_rf_path_button"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Add RF Path")
                }
                Text("Studies", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${projectCountLabel(project.studies.size, "study", "studies")} in the catalog; " +
                        "large results will become immutable, hash-addressed artifacts in future stages.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyProjectsCard() {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text("Your Catalog Is Empty", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create a project to get started. No data will be sent to the cloud.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreateProjectDialog(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var customer by rememberSaveable { mutableStateOf("") }
    val valid = name.trim().length in 2..80 && customer.trim().length <= 80
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Science, contentDescription = null) },
        title = { Text("New Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The workspace will be created in the app's private storage.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("project_name_field"),
                )
                OutlinedTextField(
                    value = customer,
                    onValueChange = { customer = it.take(80) },
                    label = { Text("Customer (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, customer) },
                enabled = valid && !isSubmitting,
                modifier = Modifier.testTag("create_project_confirm"),
            ) {
                Text(if (isSubmitting) "Saving..." else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMillis))

private fun radioSystemLabel(system: RadioSystem): String = when (system) {
    RadioSystem.GENERIC -> "Generic System"
    RadioSystem.FM_BROADCAST -> "FM Broadcast"
    RadioSystem.TV_BROADCAST -> "TV Broadcast"
    RadioSystem.LTE -> "LTE"
    RadioSystem.NR_5G -> "5G NR"
    RadioSystem.LAND_MOBILE -> "Land Mobile Radio"
    RadioSystem.FWA -> "Fixed Wireless Access"
    RadioSystem.AIR_TO_GROUND -> "Air-to-Ground"
}

private fun projectCountLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"
