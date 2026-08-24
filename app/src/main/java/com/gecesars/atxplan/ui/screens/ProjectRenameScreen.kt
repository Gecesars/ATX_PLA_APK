package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.application.RenameProjectCommand
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.ui.components.ScreenHeader

private const val PROJECT_NAME_LIMIT = 80

private data class PendingProjectRename(
    val expectedName: String,
    val targetName: String,
    val completionCount: Long,
)

@Composable
fun ProjectRenameScreen(
    project: PlannerProject?,
    isLoadingCatalog: Boolean,
    isCatalogWritable: Boolean,
    isSaving: Boolean,
    catalogMutationCompletionCount: Long,
    onSave: (RenameProjectCommand) -> Unit,
    onDirtyStateChange: (Boolean) -> Unit,
    onSavePendingChange: (Boolean) -> Unit,
    onSaveSucceeded: () -> Unit,
    onBack: () -> Unit,
) {
    if (project == null && isLoadingCatalog) {
        LoadingProjectForRename()
        return
    }
    if (project == null) {
        MissingProjectForRename(onBack)
        return
    }

    val normalizedDurableName = project.name.trim()
    var draftName by rememberSaveable(project.id) { mutableStateOf(normalizedDurableName) }
    var pendingRename by remember(project.id) { mutableStateOf<PendingProjectRename?>(null) }
    val cleanName = draftName.trim()
    val isDirty = cleanName != normalizedDurableName
    val validationError = if (isDirty && cleanName.length !in 2..PROJECT_NAME_LIMIT) {
        "Use a project name between 2 and 80 characters."
    } else {
        null
    }

    LaunchedEffect(project.id, isDirty) {
        onDirtyStateChange(isDirty)
    }
    LaunchedEffect(project.id, pendingRename) {
        onSavePendingChange(pendingRename != null)
    }
    LaunchedEffect(project.name, catalogMutationCompletionCount, pendingRename) {
        val pending = pendingRename ?: return@LaunchedEffect
        when {
            project.name == pending.targetName -> {
                pendingRename = null
                draftName = project.name.trim()
                onDirtyStateChange(false)
                onSavePendingChange(false)
                onSaveSucceeded()
            }
            project.name != pending.expectedName -> {
                pendingRename = null
                onSavePendingChange(false)
            }
            catalogMutationCompletionCount != pending.completionCount -> {
                pendingRename = null
                onSavePendingChange(false)
            }
        }
    }
    DisposableEffect(project.id) {
        onDispose {
            onDirtyStateChange(false)
            onSavePendingChange(false)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 400.dp) 12.dp else 16.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .testTag("project_rename_list")
                .padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Project Name",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Only the project name will change. The project ID, customer, " +
                                "notes, RF assets, and studies will remain unchanged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(PROJECT_NAME_LIMIT) },
                    label = { Text("Project name") },
                    singleLine = true,
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
                        .testTag("rename_project_name_field"),
                )
            }
            item {
                Button(
                    onClick = {
                        pendingRename = PendingProjectRename(
                            expectedName = project.name,
                            targetName = cleanName,
                            completionCount = catalogMutationCompletionCount,
                        )
                        onSavePendingChange(true)
                        onSave(
                            RenameProjectCommand(
                                projectId = project.id,
                                expectedName = project.name,
                                newName = cleanName,
                            ),
                        )
                    },
                    enabled = isCatalogWritable &&
                        validationError == null &&
                        cleanName != normalizedDurableName &&
                        pendingRename == null &&
                        !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("save_project_name_button"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Text(
                        if (isSaving || pendingRename != null) {
                            "Saving..."
                        } else {
                            "Save Project Name"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingProjectForRename(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Project Unavailable",
            subtitle = "The project name editor could not resolve this project from local storage.",
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text("Return to Projects")
        }
    }
}

@Composable
private fun LoadingProjectForRename() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = "Opening Project Name Editor",
            subtitle = "Resolving the project from validated local storage.",
        )
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}
