package com.gecesars.atxplan.ui.screens

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.gecesars.atxplan.domain.application.DeleteProjectCommand
import com.gecesars.atxplan.domain.application.DuplicateProjectCommand
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.ui.AppUiState
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PROJECT_NAME_LIMIT = 80
private const val PROJECT_NAME_DISPLAY_LIMIT = 160
private const val DELETE_CONFIRMATION_KEYWORD = "DELETE"
private val deleteFingerprintJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Composable
fun ProjectsScreen(
    uiState: AppUiState,
    onCreateProject: (String, String) -> Unit,
    onSelectProject: (String) -> Unit,
    onAddRfPath: (String) -> Unit,
    onRenameProject: (String) -> Unit,
    onDuplicateProject: (DuplicateProjectCommand) -> Unit,
    onDeleteProject: (DeleteProjectCommand) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var projectCountBeforeCreate by rememberSaveable { mutableStateOf<Int?>(null) }
    var observedCreateSave by rememberSaveable { mutableStateOf(false) }
    var duplicateSourceProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateDraftName by rememberSaveable { mutableStateOf("") }
    var duplicateBaselineProjectIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    var duplicateBaselineCompletionCount by rememberSaveable { mutableLongStateOf(0L) }
    var pendingDuplicateCompletionCount by remember { mutableStateOf<Long?>(null) }
    var deleteSourceProjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteReviewedProjectFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConfirmationDraft by rememberSaveable { mutableStateOf("") }
    var deleteExpectedProject by remember { mutableStateOf<PlannerProject?>(null) }
    var deleteReviewResetCount by remember { mutableLongStateOf(0L) }
    var pendingDeleteCompletionCount by remember { mutableStateOf<Long?>(null) }

    fun dismissDuplicateDialog() {
        duplicateSourceProjectId = null
        duplicateDraftName = ""
        duplicateBaselineProjectIds = emptyList()
        duplicateBaselineCompletionCount = 0L
        pendingDuplicateCompletionCount = null
    }

    fun refreshDeleteSnapshot(
        project: PlannerProject,
        clearConfirmation: Boolean = false,
        reviewedFingerprint: String? = null,
    ) {
        if (clearConfirmation) {
            deleteConfirmationDraft = ""
            deleteReviewResetCount += 1L
        }
        deleteExpectedProject = project
        deleteReviewedProjectFingerprint =
            reviewedFingerprint ?: projectSavedStateFingerprint(project)
    }

    fun dismissDeleteDialog() {
        deleteSourceProjectKey = null
        deleteReviewedProjectFingerprint = null
        deleteConfirmationDraft = ""
        deleteExpectedProject = null
        deleteReviewResetCount = 0L
        pendingDeleteCompletionCount = null
    }

    val duplicateSourceProject = duplicateSourceProjectId?.let { sourceId ->
        uiState.catalog.projects.firstOrNull { project -> project.id == sourceId }
    }
    val normalizedDuplicateName = duplicateDraftName.trim()
    val selectedNewDuplicate = uiState.catalog.selectedProjectId?.let { selectedId ->
        uiState.catalog.projects.firstOrNull { project ->
            project.id == selectedId &&
                project.id !in duplicateBaselineProjectIds &&
                project.name == normalizedDuplicateName
        }
    }
    val durableDuplicateIsObservable = duplicateSourceProjectId != null &&
        duplicateSourceProjectId in duplicateBaselineProjectIds &&
        uiState.catalogMutationCompletionCount != duplicateBaselineCompletionCount &&
        selectedNewDuplicate != null
    val currentDeleteProject = deleteSourceProjectKey?.let { sourceKey ->
        deleteExpectedProject?.let { expectedProject ->
            uiState.catalog.projects.firstOrNull { project ->
                project.id == expectedProject.id
            }
        } ?: uiState.catalog.projects.firstOrNull { project ->
            projectSavedStateKey(project.id) == sourceKey
        }
    }
    val durableDeleteIsObservable = deleteSourceProjectKey != null &&
        uiState.isCatalogWritable &&
        !uiState.isLoading &&
        currentDeleteProject == null

    LaunchedEffect(
        durableDuplicateIsObservable,
        uiState.catalogMutationCompletionCount,
        pendingDuplicateCompletionCount,
    ) {
        if (durableDuplicateIsObservable) {
            dismissDuplicateDialog()
        } else {
            val pendingCompletionCount = pendingDuplicateCompletionCount
            if (
                pendingCompletionCount != null &&
                uiState.catalogMutationCompletionCount != pendingCompletionCount
            ) {
                pendingDuplicateCompletionCount = null
            }
        }
    }
    LaunchedEffect(duplicateSourceProjectId, duplicateSourceProject, uiState.isLoading) {
        if (
            duplicateSourceProjectId != null &&
            duplicateSourceProject == null &&
            !uiState.isLoading
        ) {
            dismissDuplicateDialog()
        }
    }
    LaunchedEffect(durableDeleteIsObservable) {
        if (durableDeleteIsObservable) dismissDeleteDialog()
    }
    LaunchedEffect(
        deleteSourceProjectKey,
        currentDeleteProject,
        uiState.isSavingCatalog,
        pendingDeleteCompletionCount,
    ) {
        val currentProject = currentDeleteProject ?: return@LaunchedEffect
        if (
            deleteSourceProjectKey == null ||
            uiState.isSavingCatalog ||
            pendingDeleteCompletionCount != null
        ) {
            return@LaunchedEffect
        }
        when {
            deleteExpectedProject == null -> {
                val currentFingerprint = projectSavedStateFingerprint(currentProject)
                refreshDeleteSnapshot(
                    project = currentProject,
                    clearConfirmation = deleteReviewedProjectFingerprint != currentFingerprint,
                    reviewedFingerprint = currentFingerprint,
                )
            }

            deleteExpectedProject != currentProject -> refreshDeleteSnapshot(
                project = currentProject,
                clearConfirmation = true,
            )
        }
    }
    LaunchedEffect(
        uiState.catalogMutationCompletionCount,
        pendingDeleteCompletionCount,
        currentDeleteProject,
    ) {
        val pendingCompletionCount = pendingDeleteCompletionCount
        if (
            pendingCompletionCount != null &&
            uiState.catalogMutationCompletionCount != pendingCompletionCount
        ) {
            pendingDeleteCompletionCount = null
            currentDeleteProject?.let { currentProject ->
                refreshDeleteSnapshot(
                    project = currentProject,
                    clearConfirmation = deleteExpectedProject != currentProject,
                )
            }
        }
    }

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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("projects_list")
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
            item {
                ScreenHeader(
                    title = "Local Projects",
                    subtitle = "Every change is saved on your device first, with no connection required.",
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
            if (uiState.isCatalogWritable && !uiState.isSavingCatalog) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("new_project_button"),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text("New Project")
                        }
                    }
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
                        onRenameProject = { onRenameProject(selected.id) },
                        onDuplicateProject = {
                            duplicateSourceProjectId = selected.id
                            duplicateDraftName = suggestedDuplicateProjectName(
                                sourceName = selected.name,
                                existingNames = uiState.catalog.projects.map(PlannerProject::name),
                            )
                            duplicateBaselineProjectIds =
                                uiState.catalog.projects.map(PlannerProject::id)
                            duplicateBaselineCompletionCount =
                                uiState.catalogMutationCompletionCount
                            pendingDuplicateCompletionCount = null
                        },
                        onDeleteProject = {
                            deleteSourceProjectKey = projectSavedStateKey(selected.id)
                            deleteConfirmationDraft = ""
                            pendingDeleteCompletionCount = null
                            refreshDeleteSnapshot(selected)
                        },
                    )
                }
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

    duplicateSourceProject?.let { sourceProject ->
        DuplicateProjectDialog(
            sourceProject = sourceProject,
            draftName = duplicateDraftName,
            isCatalogWritable = uiState.isCatalogWritable,
            isSubmitting = pendingDuplicateCompletionCount != null || uiState.isSavingCatalog,
            onNameChange = { duplicateDraftName = it.take(PROJECT_NAME_LIMIT) },
            onDismiss = ::dismissDuplicateDialog,
            onConfirm = {
                if (
                    pendingDuplicateCompletionCount == null &&
                    !uiState.isSavingCatalog &&
                    uiState.isCatalogWritable &&
                    normalizedDuplicateName.length in 2..PROJECT_NAME_LIMIT
                ) {
                    pendingDuplicateCompletionCount = uiState.catalogMutationCompletionCount
                    onDuplicateProject(
                        DuplicateProjectCommand(
                            sourceProjectId = sourceProject.id,
                            newName = normalizedDuplicateName,
                        ),
                    )
                }
            },
        )
    }

    if (deleteSourceProjectKey != null) {
        DeleteProjectDialog(
            sourceProject = deleteExpectedProject ?: currentDeleteProject,
            confirmationDraft = deleteConfirmationDraft,
            reviewResetCount = deleteReviewResetCount,
            sourceProjectExists = currentDeleteProject != null,
            sourceProjectIsAvailable = currentDeleteProject != null &&
                deleteExpectedProject != null,
            isCatalogLoading = uiState.isLoading,
            isCatalogWritable = uiState.isCatalogWritable,
            isSubmitting = pendingDeleteCompletionCount != null || uiState.isSavingCatalog,
            onConfirmationChange = {
                deleteConfirmationDraft = it.take(DELETE_CONFIRMATION_KEYWORD.length)
            },
            onDismiss = ::dismissDeleteDialog,
            onConfirm = {
                val expectedProject = deleteExpectedProject
                if (
                    expectedProject != null &&
                    projectSavedStateKey(expectedProject.id) == deleteSourceProjectKey &&
                    pendingDeleteCompletionCount == null &&
                    !uiState.isSavingCatalog &&
                    uiState.isCatalogWritable &&
                    projectDeleteConfirmationMatches(deleteConfirmationDraft)
                ) {
                    pendingDeleteCompletionCount = uiState.catalogMutationCompletionCount
                    onDeleteProject(DeleteProjectCommand(expectedProject = expectedProject))
                }
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
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.testTag("selected_project_card") else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.customer.ifBlank { "No customer specified" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingProjectsCard() {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Text("Loading local projects")
        }
    }
}

@Composable
private fun SelectedProjectDetails(
    project: PlannerProject,
    canEdit: Boolean,
    onAddRfPath: () -> Unit,
    onRenameProject: () -> Unit,
    onDuplicateProject: () -> Unit,
    onDeleteProject: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Selected Project",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                project.notes.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            Text(
                                "${network.downlinkFrequencyMHz} MHz",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text("RF Assets", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${projectCountLabel(project.sites.size, "transmitter site", "transmitter sites")} and " +
                        "${projectCountLabel(project.receivers.size, "receiver", "receivers")} are linked " +
                        "to this project.",
                    modifier = Modifier.testTag("rf_asset_summary"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val fontScale = LocalDensity.current.fontScale
                    val useSingleActionRow = maxWidth >= if (fontScale <= 1.2f) {
                        500.dp
                    } else {
                        650.dp
                    }
                    val useTwoActionRows = maxWidth >= 330.dp && fontScale <= 1.2f
                    if (useSingleActionRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RenameProjectButton(
                                enabled = canEdit,
                                onClick = onRenameProject,
                                modifier = Modifier.weight(1f),
                            )
                            DuplicateProjectButton(
                                enabled = canEdit,
                                onClick = onDuplicateProject,
                                modifier = Modifier.weight(1f),
                            )
                            DeleteProjectButton(
                                enabled = canEdit,
                                onClick = onDeleteProject,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (useTwoActionRows) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RenameProjectButton(
                                    enabled = canEdit,
                                    onClick = onRenameProject,
                                    modifier = Modifier.weight(1f),
                                )
                                DuplicateProjectButton(
                                    enabled = canEdit,
                                    onClick = onDuplicateProject,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            DeleteProjectButton(
                                enabled = canEdit,
                                onClick = onDeleteProject,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RenameProjectButton(
                                enabled = canEdit,
                                onClick = onRenameProject,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DuplicateProjectButton(
                                enabled = canEdit,
                                onClick = onDuplicateProject,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DeleteProjectButton(
                                enabled = canEdit,
                                onClick = onDeleteProject,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Button(
                    onClick = onAddRfPath,
                    enabled = canEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("add_rf_path_button"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Add RF Path")
                }
                Text("Studies", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${projectCountLabel(project.studies.size, "study", "studies")} in the catalog; " +
                        "large results will become immutable, hash-addressed artifacts in future stages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RenameProjectButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("rename_project_button"),
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null)
        Text("Rename")
    }
}

@Composable
private fun DuplicateProjectButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("duplicate_project_button"),
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
        Text("Duplicate")
    }
}

@Composable
private fun DeleteProjectButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
        border = BorderStroke(1.dp, errorColor),
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("delete_project_button"),
    ) {
        Icon(Icons.Outlined.DeleteForever, contentDescription = null)
        Text("Delete")
    }
}

@Composable
private fun EmptyProjectsCard() {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
@OptIn(ExperimentalLayoutApi::class)
private fun DeleteProjectDialog(
    sourceProject: PlannerProject?,
    confirmationDraft: String,
    reviewResetCount: Long,
    sourceProjectExists: Boolean,
    sourceProjectIsAvailable: Boolean,
    isCatalogLoading: Boolean,
    isCatalogWritable: Boolean,
    isSubmitting: Boolean,
    onConfirmationChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible
    val configuration = LocalConfiguration.current
    val useShortLandscapeLayout =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.height.toDp() <= 480.dp
            }
    val useCompactImeLayout = isImeVisible &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dialogContentState = rememberLazyListState()
    var compactImeWasUsed by remember { mutableStateOf(false) }
    val confirmationMatches = projectDeleteConfirmationMatches(confirmationDraft)
    val showMismatch = confirmationDraft.isNotEmpty() && !confirmationMatches
    LaunchedEffect(useCompactImeLayout) {
        if (useCompactImeLayout) compactImeWasUsed = true
        if (useCompactImeLayout || compactImeWasUsed) {
            dialogContentState.scrollToItem(index = 2)
        }
    }
    LaunchedEffect(reviewResetCount) {
        if (reviewResetCount > 0L) {
            keyboardController?.hide()
            compactImeWasUsed = false
            dialogContentState.scrollToItem(index = 0)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = if (useCompactImeLayout || useShortLandscapeLayout) {
            null
        } else {
            {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        title = if (useCompactImeLayout) {
            null
        } else {
            {
                Text(
                    text = "Delete Project",
                    modifier = Modifier.semantics { heading() },
                    style = if (useShortLandscapeLayout) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                )
            }
        },
        text = {
            DialogImeResizeEffect()
            LazyColumn(
                state = dialogContentState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (useCompactImeLayout) Modifier else Modifier.imePadding())
                    .testTag("project_delete_dialog_content"),
                contentPadding = PaddingValues(bottom = if (useShortLandscapeLayout) 2.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (useShortLandscapeLayout) 6.dp else 10.dp,
                ),
            ) {
                item(key = "delete_source") {
                    if (!useCompactImeLayout) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (!useShortLandscapeLayout) {
                                Text(
                                    text = "Project to Delete",
                                    modifier = Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                text = sourceProject?.name?.let(::boundedProjectNameForDisplay)
                                    ?: "Project snapshot unavailable",
                                modifier = Modifier.testTag("delete_project_source_name"),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sourceProject?.let(::projectDeletionImpactSummary)
                                    ?: "Impact counts are unavailable while the catalog snapshot is refreshed.",
                                modifier = Modifier.testTag("delete_project_impact_summary"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "delete_warning") {
                    if (!useCompactImeLayout) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = if (useShortLandscapeLayout) {
                                        "Permanently removes this project and its project-scoped RF data " +
                                            "from the local catalog. No in-app backup or undo."
                                    } else {
                                        "This permanently removes the project and its project-scoped " +
                                            "RF data from this app's local catalog. No in-app backup is " +
                                            "created, and there is no undo."
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                item(key = "delete_confirmation") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (useCompactImeLayout) {
                            Text(
                                text = if (showMismatch) {
                                    "Enter DELETE exactly (case-sensitive)."
                                } else {
                                    "Type DELETE exactly (case-sensitive)."
                                },
                                modifier = if (showMismatch) {
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                } else {
                                    Modifier
                                },
                                color = if (showMismatch) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(
                            value = confirmationDraft,
                            onValueChange = onConfirmationChange,
                            enabled = !isSubmitting && sourceProjectIsAvailable,
                            label = if (useCompactImeLayout) {
                                null
                            } else {
                                { Text("Type DELETE to confirm") }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() },
                            ),
                            isError = showMismatch,
                            supportingText = if (useCompactImeLayout) {
                                null
                            } else {
                                {
                                    Text(
                                        text = if (showMismatch) {
                                            "Type DELETE exactly to confirm permanent deletion."
                                        } else {
                                            "Confirmation is case-sensitive."
                                        },
                                        modifier = if (showMismatch) {
                                            Modifier.semantics {
                                                liveRegion = LiveRegionMode.Polite
                                            }
                                        } else {
                                            Modifier
                                        },
                                        color = if (showMismatch) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription =
                                        "Deletion confirmation. Type DELETE exactly, case-sensitive."
                                }
                                .testTag("delete_project_name_field"),
                        )
                    }
                }
                if (!sourceProjectIsAvailable) {
                    item(key = "delete_source_unavailable") {
                        Text(
                            text = when {
                                sourceProjectExists ->
                                    "Preparing the latest project snapshot."
                                isCatalogLoading ->
                                    "The local catalog is still loading."
                                else ->
                                    "The project cannot be verified in the current local catalog."
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (!isCatalogWritable) {
                    item(key = "delete_catalog_write_error") {
                        Text(
                            text = "The local catalog must be writable before this project can be deleted.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!useCompactImeLayout) {
                Button(
                    onClick = onConfirm,
                    enabled = sourceProjectIsAvailable &&
                        isCatalogWritable &&
                        confirmationMatches &&
                        !isSubmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("delete_project_confirm"),
                ) {
                    Text(
                        text = when {
                            isSubmitting -> "Deleting..."
                            useShortLandscapeLayout -> "Delete"
                            else -> "Delete Permanently"
                        },
                        modifier = if (isSubmitting) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        },
        dismissButton = {
            if (!useCompactImeLayout) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("delete_project_cancel"),
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DuplicateProjectDialog(
    sourceProject: PlannerProject,
    draftName: String,
    isCatalogWritable: Boolean,
    isSubmitting: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cleanName = draftName.trim()
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible
    val configuration = LocalConfiguration.current
    val useCompactImeLayout = isImeVisible &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dialogContentState = rememberLazyListState()
    var compactImeWasUsed by remember { mutableStateOf(false) }
    LaunchedEffect(useCompactImeLayout) {
        if (useCompactImeLayout) compactImeWasUsed = true
        if (useCompactImeLayout || compactImeWasUsed) {
            dialogContentState.scrollToItem(index = 1)
        }
    }
    val validationError = if (cleanName.length !in 2..PROJECT_NAME_LIMIT) {
        "Use a project name between 2 and 80 characters."
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = if (useCompactImeLayout) {
            null
        } else {
            { Icon(Icons.Outlined.ContentCopy, contentDescription = null) }
        },
        title = if (useCompactImeLayout) {
            null
        } else {
            {
                Text(
                    text = "Duplicate Project",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            DialogImeResizeEffect()
            LazyColumn(
                state = dialogContentState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (useCompactImeLayout) Modifier else Modifier.imePadding())
                    .testTag("project_duplicate_dialog_content"),
                contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "source_project") {
                    if (!useCompactImeLayout) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Source Project",
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = sourceProject.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${projectCountLabel(sourceProject.networks.size, "network", "networks")}, " +
                                    "${projectCountLabel(sourceProject.sites.size, "site", "sites")}, " +
                                    "${projectCountLabel(sourceProject.receivers.size, "receiver", "receivers")}, and " +
                                    projectCountLabel(
                                        sourceProject.studies.size,
                                        "study summary",
                                        "study summaries",
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "copy_project_name") {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = onNameChange,
                        enabled = !isSubmitting,
                        label = { Text("Copy project name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { keyboardController?.hide() },
                        ),
                        isError = validationError != null,
                        supportingText = validationError?.let { message ->
                            {
                                Text(
                                    text = message,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("duplicate_project_name_field"),
                    )
                }
                item(key = "copy_behavior") {
                    if (!useCompactImeLayout) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "The latest saved version is copied and selected with a new project " +
                                        "ID and timestamps; the source remains unchanged. Customer, notes, " +
                                        "demo status, RF assets, " +
                                        "project-scoped links, and study summaries remain unchanged.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                if (!isCatalogWritable) {
                    item(key = "catalog_write_error") {
                        Text(
                            text = "The local catalog must be writable before this project can be copied.",
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!useCompactImeLayout) {
                Button(
                    onClick = onConfirm,
                    enabled = isCatalogWritable && validationError == null && !isSubmitting,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("duplicate_project_confirm"),
                ) {
                    Text(
                        text = if (isSubmitting) "Duplicating..." else "Duplicate",
                        modifier = if (isSubmitting) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        },
        dismissButton = {
            if (!useCompactImeLayout) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
@Suppress("DEPRECATION")
private fun DialogImeResizeEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val previousSoftInputMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            previousSoftInputMode?.let { mode -> window?.setSoftInputMode(mode) }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.heightIn(min = 48.dp).testTag("create_project_confirm"),
            ) {
                Text(if (isSubmitting) "Saving..." else "Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}

internal fun projectDeleteConfirmationMatches(typedValue: String): Boolean =
    typedValue == DELETE_CONFIRMATION_KEYWORD

internal fun projectSavedStateKey(projectId: String): String =
    sha256Hex(projectId.toByteArray(Charsets.UTF_8))

internal fun projectSavedStateFingerprint(project: PlannerProject): String =
    sha256Hex(
        deleteFingerprintJson
            .encodeToString(PlannerProject.serializer(), project)
            .toByteArray(Charsets.UTF_8),
    )

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

internal fun boundedProjectNameForDisplay(name: String): String =
    if (name.length <= PROJECT_NAME_DISPLAY_LIMIT) {
        name
    } else {
        "${name.take(PROJECT_NAME_DISPLAY_LIMIT - 1).trimEnd()}…"
    }

internal fun projectDeletionImpactSummary(project: PlannerProject): String {
    val sectorCount = project.sites.sumOf { site -> site.sectors.size }
    return "Deleting this project removes " +
        "${projectCountLabel(project.networks.size, "network", "networks")}, " +
        "${projectCountLabel(project.sites.size, "site", "sites")}, " +
        "${projectCountLabel(sectorCount, "sector", "sectors")}, " +
        "${projectCountLabel(project.receivers.size, "receiver", "receivers")}, and " +
        "${projectCountLabel(project.studies.size, "study summary", "study summaries")} " +
        "from local storage."
}

internal fun suggestedDuplicateProjectName(
    sourceName: String,
    existingNames: Collection<String>,
): String {
    val sourceStem = sourceName.trim().ifBlank { "Project" }
    val occupiedNames = existingNames
        .mapTo(hashSetOf()) { name -> name.trim().lowercase(Locale.ROOT) }

    repeat(occupiedNames.size + 1) { zeroBasedIndex ->
        val copyNumber = zeroBasedIndex + 1
        val suffix = if (copyNumber == 1) " Copy" else " Copy $copyNumber"
        val maxStemLength = (PROJECT_NAME_LIMIT - suffix.length).coerceAtLeast(1)
        val boundedStem = sourceStem
            .take(maxStemLength)
            .trimEnd()
            .ifBlank { "Project".take(maxStemLength) }
        val candidate = "$boundedStem$suffix".take(PROJECT_NAME_LIMIT).trim()
        if (
            candidate.length in 2..PROJECT_NAME_LIMIT &&
            candidate.lowercase(Locale.ROOT) !in occupiedNames
        ) {
            return candidate
        }
    }

    // There are more candidate suffixes than occupied names, so this is defensive only.
    return "Project Copy"
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
