package com.gecesars.atxplan

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.application.ArchiveProjectCommand
import com.gecesars.atxplan.domain.application.RestoreProjectCommand
import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.ui.AppUiState
import com.gecesars.atxplan.ui.navigation.AtxRoute
import com.gecesars.atxplan.ui.navigation.DashboardRoute
import com.gecesars.atxplan.ui.navigation.PROJECT_RENAME_PREFIX
import com.gecesars.atxplan.ui.navigation.RF_ASSETS_PREFIX
import com.gecesars.atxplan.ui.navigation.RF_PATH_EDITOR_PREFIX
import com.gecesars.atxplan.ui.navigation.ProjectRenameRoute
import com.gecesars.atxplan.ui.navigation.ProjectsRoute
import com.gecesars.atxplan.ui.navigation.RfAssetsRoute
import com.gecesars.atxplan.ui.navigation.RfPathEditorRoute
import com.gecesars.atxplan.ui.navigation.StudiesRoute
import com.gecesars.atxplan.ui.navigation.UnsupportedRoute
import com.gecesars.atxplan.ui.navigation.activeRoute
import com.gecesars.atxplan.ui.navigation.rememberAtxNavBackStack
import com.gecesars.atxplan.ui.navigation.replaceTopLevel
import com.gecesars.atxplan.ui.screens.ProjectRenameScreen
import com.gecesars.atxplan.ui.screens.ProjectsScreen
import com.gecesars.atxplan.ui.screens.RfPathEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtxNavigationStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typedBackStackSurvivesSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle { backStack.replaceTopLevel(StudiesRoute) }
        composeRule.onNodeWithText(routeMarker(StudiesRoute)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(routeMarker(StudiesRoute)).assertIsDisplayed()
    }

    @Test
    fun unknownRouteIdentifierFallsBackToDashboard() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.clear()
            backStack.add(AtxRoute.fromStableId("future-feature:v9"))
        }
        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(backStack.last() is UnsupportedRoute)
            assertEquals("future-feature:v9", backStack.last().stableId)
        }
    }

    @Test
    fun nestedEditorRouteSurvivesSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(RfPathEditorRoute("project-123"))
        }
        composeRule.onNodeWithText("Current route: ${RF_PATH_EDITOR_PREFIX}project-123")
            .assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Current route: ${RF_PATH_EDITOR_PREFIX}project-123")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            val route = backStack.last() as RfPathEditorRoute
            assertEquals("project-123", route.projectId)
        }
    }

    @Test
    fun projectRenameRouteAndParentSurviveSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(ProjectsRoute)
            backStack.add(ProjectRenameRoute("project-rename-123"))
        }
        composeRule.onNodeWithText(
            "Current route: ${PROJECT_RENAME_PREFIX}project-rename-123",
        ).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(
            "Current route: ${PROJECT_RENAME_PREFIX}project-rename-123",
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(2, backStack.size)
            assertEquals(ProjectsRoute, backStack.first())
            val route = backStack.last() as ProjectRenameRoute
            assertEquals("project-rename-123", route.projectId)
            backStack.removeLastOrNull()
        }
        composeRule.onNodeWithText(routeMarker(ProjectsRoute)).assertIsDisplayed()
    }

    @Test
    fun rfAssetsRouteAndParentSurviveSerializedSavedStateRoundTrip() {
        lateinit var backStack: NavBackStack<AtxRoute>
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(ProjectsRoute)
            backStack.add(RfAssetsRoute("project-rf-assets-123"))
        }
        composeRule.onNodeWithText(
            "Current route: ${RF_ASSETS_PREFIX}project-rf-assets-123",
        ).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(
            "Current route: ${RF_ASSETS_PREFIX}project-rf-assets-123",
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(2, backStack.size)
            assertEquals(ProjectsRoute, backStack.first())
            val route = backStack.last() as RfAssetsRoute
            assertEquals("project-rf-assets-123", route.projectId)
            backStack.removeLastOrNull()
        }
        composeRule.onNodeWithText(routeMarker(ProjectsRoute)).assertIsDisplayed()
    }

    @Test
    fun malformedNestedRouteFallsBackWithoutEnteringTheBackStack() {
        lateinit var backStack: NavBackStack<AtxRoute>
        composeRule.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(AtxRoute.fromStableId(RF_PATH_EDITOR_PREFIX))
        }

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(DashboardRoute, backStack.last()) }
    }

    @Test
    fun malformedProjectRenameRouteFallsBackWithoutEnteringTheBackStack() {
        lateinit var backStack: NavBackStack<AtxRoute>
        composeRule.setContent {
            backStack = rememberAtxNavBackStack()
            Text(routeMarker(backStack.activeRoute))
        }

        composeRule.runOnIdle {
            backStack.replaceTopLevel(AtxRoute.fromStableId(PROJECT_RENAME_PREFIX))
        }

        composeRule.onNodeWithText(routeMarker(DashboardRoute)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(DashboardRoute, backStack.last()) }
    }

    @Test
    fun renameDraftRestoresButUnobservedSavePendingDoesNot() {
        val restorationTester = StateRestorationTester(composeRule)
        var saveRequests = 0
        restorationTester.setContent {
            ProjectRenameScreen(
                project = restorationProject,
                isLoadingCatalog = false,
                isCatalogWritable = true,
                isSaving = false,
                catalogMutationCompletionCount = 0L,
                onSave = { saveRequests += 1 },
                onDirtyStateChange = {},
                onSavePendingChange = {},
                onSaveSucceeded = {},
                onBack = {},
            )
        }
        val restoredDraft = "Restored Pending Rename"
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement(restoredDraft)
        composeRule.onNodeWithTag("save_project_name_button").performClick()
        composeRule.onNodeWithText("Saving...").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, saveRequests) }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(restoredDraft))
        composeRule.onNodeWithTag("save_project_name_button").assertIsEnabled()
        composeRule.onNodeWithText("Save Project Name").assertIsDisplayed()
    }

    @Test
    fun competingRenameClearsPendingStateWithoutDiscardingTheDraft() {
        val projectState = mutableStateOf(restorationProject)
        var saveRequests = 0
        var successfulReturns = 0
        composeRule.setContent {
            ProjectRenameScreen(
                project = projectState.value,
                isLoadingCatalog = false,
                isCatalogWritable = true,
                isSaving = false,
                catalogMutationCompletionCount = 0L,
                onSave = { saveRequests += 1 },
                onDirtyStateChange = {},
                onSavePendingChange = {},
                onSaveSucceeded = { successfulReturns += 1 },
                onBack = {},
            )
        }
        val draftName = "Local Rename Draft"
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement(draftName)
        composeRule.onNodeWithTag("save_project_name_button").performClick()
        composeRule.onNodeWithText("Saving...").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, saveRequests)
            projectState.value = restorationProject.copy(name = "Peer Durable Rename")
        }

        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(draftName))
        composeRule.onNodeWithTag("save_project_name_button").assertIsEnabled()
        composeRule.onNodeWithText("Save Project Name").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, successfulReturns) }
    }

    @Test
    fun completedRenameAttemptClearsPendingWithoutAnObservedSavingFrame() {
        val completionState = mutableStateOf(0L)
        var saveRequests = 0
        composeRule.setContent {
            ProjectRenameScreen(
                project = restorationProject,
                isLoadingCatalog = false,
                isCatalogWritable = true,
                isSaving = false,
                catalogMutationCompletionCount = completionState.value,
                onSave = { saveRequests += 1 },
                onDirtyStateChange = {},
                onSavePendingChange = {},
                onSaveSucceeded = {},
                onBack = {},
            )
        }
        val draftName = "Retryable Rename Draft"
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement(draftName)
        composeRule.onNodeWithTag("save_project_name_button").performClick()
        composeRule.onNodeWithText("Saving...").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, saveRequests)
            completionState.value += 1L
        }

        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(draftName))
        composeRule.onNodeWithTag("save_project_name_button").assertIsEnabled()
        composeRule.onNodeWithText("Save Project Name").assertIsDisplayed()
    }

    @Test
    fun noncanonicalDurableNameDoesNotOpenAsAnUnsavedEdit() {
        var isDirty = true
        composeRule.setContent {
            ProjectRenameScreen(
                project = restorationProject.copy(name = "  Legacy Project Name  "),
                isLoadingCatalog = false,
                isCatalogWritable = true,
                isSaving = false,
                catalogMutationCompletionCount = 0L,
                onSave = {},
                onDirtyStateChange = { isDirty = it },
                onSavePendingChange = {},
                onSaveSucceeded = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("rename_project_name_field")
            .assert(hasText("Legacy Project Name"))
        composeRule.onNodeWithTag("save_project_name_button").assertIsNotEnabled()
        composeRule.runOnIdle { assertFalse(isDirty) }
    }

    @Test
    fun duplicateDialogRestoresItsDraftButNotTransientPendingState() {
        val restorationTester = StateRestorationTester(composeRule)
        var duplicateRequests = 0
        restorationTester.setContent {
            ProjectsScreen(
                uiState = duplicateUiState(),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = { duplicateRequests += 1 },
                onDeleteProject = {},
            )
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("duplicate_project_button"))
        composeRule.onNodeWithTag("duplicate_project_button").performClick()
        val restoredDraft = "Restored Project Copy"
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .performTextReplacement(restoredDraft)
        composeRule.onNodeWithTag("duplicate_project_confirm").performClick()
        composeRule.onNodeWithText("Duplicating...").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, duplicateRequests) }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("duplicate_project_name_field")
            .assert(hasText(restoredDraft))
        composeRule.onNodeWithTag("duplicate_project_confirm")
            .assertIsEnabled()
            .assert(hasText("Duplicate"))
    }

    @Test
    fun restoredDuplicateDialogClosesWhenDurableSelectedCopyBecomesObservable() {
        val restorationTester = StateRestorationTester(composeRule)
        val uiState = mutableStateOf(duplicateUiState())
        var requestedName = ""
        restorationTester.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = { command -> requestedName = command.newName },
                onDeleteProject = {},
            )
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("duplicate_project_button"))
        composeRule.onNodeWithTag("duplicate_project_button").performClick()
        val durableName = "Durable Project Copy"
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .performTextReplacement(durableName)
        composeRule.onNodeWithTag("duplicate_project_confirm").performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("duplicate_project_name_field").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(durableName, requestedName)
            val source = restorationProject
            val duplicate = source.copy(id = "project-restored-copy", name = durableName)
            uiState.value = duplicateUiState(
                projects = listOf(source, duplicate),
                selectedProjectId = duplicate.id,
                completionCount = 8L,
            )
        }

        composeRule.onNodeWithTag("duplicate_project_name_field").assertDoesNotExist()
        composeRule.onNodeWithTag("projects_list").assertIsDisplayed()
    }

    @Test
    fun rejectedDuplicateAttemptRetainsDraftAndClearsPendingState() {
        val uiState = mutableStateOf(duplicateUiState())
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("duplicate_project_button"))
        composeRule.onNodeWithTag("duplicate_project_button").performClick()
        val retainedDraft = "Retry This Copy"
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .performTextReplacement(retainedDraft)
        composeRule.onNodeWithTag("duplicate_project_confirm").performClick()

        composeRule.runOnIdle {
            uiState.value = duplicateUiState(completionCount = 8L)
        }

        composeRule.onNodeWithTag("duplicate_project_name_field")
            .assert(hasText(retainedDraft))
        composeRule.onNodeWithTag("duplicate_project_confirm")
            .assertIsEnabled()
            .assert(hasText("Duplicate"))
    }

    @Test
    fun archiveDialogShowsRetainedImpactAndSendsTheReviewedSnapshot() {
        var requestedCommand: ArchiveProjectCommand? = null
        composeRule.setContent {
            ProjectsScreen(
                uiState = duplicateUiState(),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
                onArchiveProject = { requestedCommand = it },
            )
        }

        openArchiveDialog()
        composeRule.onNodeWithTag("archive_project_source_name")
            .assert(hasText(restorationProject.name))
        composeRule.onNodeWithTag("archive_project_impact_summary").assert(
            hasText(
                "Archiving this project retains 0 networks, 0 sites, 0 sectors, " +
                    "0 receivers, and 0 study summaries in the local catalog.",
            ),
        )
        composeRule.onNodeWithTag("archive_project_disclosure")
            .assert(hasText("not a backup", substring = true, ignoreCase = true))
        composeRule.onNodeWithTag("archive_project_confirm")
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithText("Archiving...").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(restorationProject, requestedCommand?.expectedProject)
        }
    }

    @Test
    fun restoredArchiveDialogRebasesAChangedSnapshotForFreshReview() {
        val restorationTester = StateRestorationTester(composeRule)
        val changedProject = restorationProject.copy(
            notes = "Changed before archive restoration.",
            updatedAtEpochMillis = 2L,
        )
        var useChangedProject = false
        restorationTester.setContent {
            DisposableEffect(Unit) {
                onDispose { useChangedProject = true }
            }
            ProjectsScreen(
                uiState = duplicateUiState(
                    projects = listOf(
                        if (useChangedProject) changedProject else restorationProject,
                    ),
                ),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openArchiveDialog()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("archive_project_source_name")
            .assert(hasText(changedProject.name))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("archive_project_snapshot_refreshed").assertIsDisplayed()
        composeRule.onNodeWithTag("archive_project_confirm").assertIsEnabled()
    }

    @Test
    fun archiveDialogClosesOnlyAfterTheDurableArchiveEntryIsObservable() {
        val uiState = mutableStateOf(duplicateUiState())
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openArchiveDialog()
        composeRule.onNodeWithTag("archive_project_confirm").performClick()
        composeRule.onNodeWithTag("project_archive_dialog_content").assertIsDisplayed()

        val archived = ArchivedProject(
            project = restorationProject,
            archivedAtEpochMillis = 2L,
            originalProjectIndex = 0,
        )
        composeRule.runOnIdle {
            uiState.value = duplicateUiState(
                projects = emptyList(),
                selectedProjectId = null,
                archivedProjects = listOf(archived),
                completionCount = 8L,
            )
        }

        composeRule.onNodeWithTag("project_archive_dialog_content").assertDoesNotExist()
        composeRule.onNodeWithText("Archived Projects (1)").assertIsDisplayed()
        composeRule.onNodeWithTag("archived_project_card").assertIsDisplayed()
    }

    @Test
    fun rejectedArchiveCompletionReenablesConfirmationWithoutClosingTheDialog() {
        val uiState = mutableStateOf(duplicateUiState())
        var archiveRequests = 0
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
                onArchiveProject = { archiveRequests += 1 },
            )
        }
        openArchiveDialog()
        composeRule.onNodeWithTag("archive_project_confirm").performClick()
        composeRule.onNodeWithText("Archiving...").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, archiveRequests)
            uiState.value = duplicateUiState(completionCount = 8L)
        }

        composeRule.onNodeWithTag("project_archive_dialog_content").assertIsDisplayed()
        composeRule.onNodeWithTag("archive_project_confirm")
            .assertIsEnabled()
            .assert(hasText("Archive Project"))
    }

    @Test
    fun restoreUsesTheCompleteArchiveSnapshotAndDoesNotRestoreTransientPendingState() {
        val archived = ArchivedProject(
            project = restorationProject,
            archivedAtEpochMillis = 2L,
            originalProjectIndex = 0,
        )
        val restorationTester = StateRestorationTester(composeRule)
        var requestedCommand: RestoreProjectCommand? = null
        restorationTester.setContent {
            ProjectsScreen(
                uiState = duplicateUiState(
                    projects = emptyList(),
                    selectedProjectId = null,
                    archivedProjects = listOf(archived),
                ),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
                onRestoreProject = { requestedCommand = it },
            )
        }

        composeRule.onNodeWithContentDescription(
            "Restore archived project Restoration Project",
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Restoring...").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Restore archived project Restoration Project",
        ).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Restoring",
            ),
        )
        composeRule.runOnIdle {
            assertEquals(archived, requestedCommand?.expectedArchivedProject)
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithContentDescription(
            "Restore archived project Restoration Project",
        ).assertIsEnabled()
        composeRule.onNodeWithText("Restore").assertIsDisplayed()
    }

    @Test
    fun rejectedRestoreCompletionReenablesTheArchivedProjectAction() {
        val archived = ArchivedProject(
            project = restorationProject,
            archivedAtEpochMillis = 2L,
            originalProjectIndex = 0,
        )
        val uiState = mutableStateOf(
            duplicateUiState(
                projects = emptyList(),
                selectedProjectId = null,
                archivedProjects = listOf(archived),
            ),
        )
        var restoreRequests = 0
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
                onRestoreProject = { restoreRequests += 1 },
            )
        }
        composeRule.onNodeWithContentDescription(
            "Restore archived project Restoration Project",
        ).performClick()
        composeRule.onNodeWithText("Restoring...").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, restoreRequests)
            uiState.value = duplicateUiState(
                projects = emptyList(),
                selectedProjectId = null,
                archivedProjects = listOf(archived),
                completionCount = 8L,
            )
        }

        composeRule.onNodeWithContentDescription(
            "Restore archived project Restoration Project",
        ).assertIsEnabled()
        composeRule.onNodeWithText("Restore").assertIsDisplayed()
    }

    @Test
    fun deleteDialogRequiresTheExactKeywordAndShowsItsLocalImpact() {
        composeRule.setContent {
            ProjectsScreen(
                uiState = duplicateUiState(),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithContentDescription(
            "Deletion confirmation. Type DELETE exactly, case-sensitive.",
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("delete_project_impact_summary").assert(
            hasText(
            "Deleting this project removes 0 networks, 0 sites, 0 sectors, " +
                "0 receivers, and 0 study summaries from the local catalog.",
            ),
        )
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("delete")
        composeRule.onNodeWithText("Type DELETE exactly to confirm permanent deletion.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("delete_project_confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").assertIsEnabled()
    }

    @Test
    fun deleteDialogRestoresItsDraftButNotTransientPendingState() {
        val restorationTester = StateRestorationTester(composeRule)
        var deleteRequests = 0
        restorationTester.setContent {
            ProjectsScreen(
                uiState = duplicateUiState(),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = { deleteRequests += 1 },
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").performClick()
        composeRule.onNodeWithText("Deleting...").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, deleteRequests) }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("delete_project_name_field")
            .assert(hasText("DELETE"))
        composeRule.onNodeWithTag("delete_project_confirm")
            .assertIsEnabled()
    }

    @Test
    fun restoredDeleteDialogClearsConfirmationWhenTheReviewedSnapshotChanged() {
        val restorationTester = StateRestorationTester(composeRule)
        val changedProject = restorationProject.copy(
            name = "Changed Restored Project",
            notes = "Changed before the restored composition.",
            updatedAtEpochMillis = 2L,
        )
        var useChangedProject = false
        restorationTester.setContent {
            DisposableEffect(Unit) {
                onDispose { useChangedProject = true }
            }
            ProjectsScreen(
                uiState = duplicateUiState(
                    projects = listOf(
                        if (useChangedProject) changedProject else restorationProject,
                    ),
                ),
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").assertIsEnabled()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("delete_project_source_name")
            .assert(hasText(changedProject.name))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("delete_project_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").assertIsEnabled()
    }

    @Test
    fun rejectedDeleteAttemptRetainsDraftAndReenablesConfirmation() {
        val uiState = mutableStateOf(duplicateUiState())
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").performClick()
        composeRule.onNodeWithText("Deleting...").assertIsDisplayed()

        composeRule.runOnIdle {
            uiState.value = duplicateUiState(completionCount = 8L)
        }

        composeRule.onNodeWithTag("delete_project_name_field")
            .assert(hasText("DELETE"))
        composeRule.onNodeWithTag("delete_project_confirm")
            .assertIsEnabled()
    }

    @Test
    fun deleteDialogClosesWhenTheLoadedWritableCatalogShowsDurableAbsence() {
        val uiState = mutableStateOf(duplicateUiState())
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").performClick()

        composeRule.runOnIdle {
            uiState.value = duplicateUiState(
                projects = emptyList(),
                selectedProjectId = "missing-project",
            )
        }

        composeRule.onNodeWithTag("delete_project_name_field").assertDoesNotExist()
        composeRule.onNodeWithTag("projects_list").assertIsDisplayed()
    }

    @Test
    fun staleDeleteCompletionRebasesTheSnapshotAndRequiresFreshConfirmation() {
        val uiState = mutableStateOf(duplicateUiState())
        composeRule.setContent {
            ProjectsScreen(
                uiState = uiState.value,
                onCreateProject = { _, _ -> },
                onSelectProject = {},
                onAddRfPath = {},
                onRenameProject = {},
                onDuplicateProject = {},
                onDeleteProject = {},
            )
        }
        openDeleteDialog()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").performClick()

        composeRule.runOnIdle {
            uiState.value = duplicateUiState(
                projects = listOf(
                    restorationProject.copy(
                        notes = "Changed by a concurrent catalog transaction.",
                        updatedAtEpochMillis = 2L,
                    ),
                ),
                completionCount = 8L,
            )
        }

        composeRule.onNodeWithTag("delete_project_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm").assertIsEnabled()
    }

    @Test
    fun rfDraftRestoresButUnobservedSavePendingDoesNot() {
        val restorationTester = StateRestorationTester(composeRule)
        var saveRequests = 0
        restorationTester.setContent {
            RfPathEditorScreen(
                project = restorationProject,
                isLoadingCatalog = false,
                isCatalogWritable = true,
                isSaving = false,
                catalogMutationCompletionCount = 0L,
                onSave = { saveRequests += 1 },
                onDirtyStateChange = {},
                onSavePendingChange = {},
                onSaveSucceeded = {},
                onBack = {},
            )
        }
        val restoredDraft = "Restored Pending Network"
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("network_name_field").performTextReplacement(restoredDraft)
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(5)
        composeRule.onNodeWithTag("save_rf_path_button").performClick()
        composeRule.onNodeWithText("Saving RF Path...").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, saveRequests) }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("network_name_field").assert(hasText(restoredDraft))
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(5)
        composeRule.onNodeWithTag("save_rf_path_button").assertIsEnabled()
        composeRule.onNodeWithText("Save RF Path Locally").assertIsDisplayed()
    }

    private fun routeMarker(route: AtxRoute) = "Current route: ${route.stableId}"

    private fun openDeleteDialog() {
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("delete_project_button"))
        composeRule.onNodeWithTag("delete_project_button").performClick()
        composeRule.onNodeWithTag("delete_project_name_field").assertIsDisplayed()
    }

    private fun openArchiveDialog() {
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("archive_project_button"))
        composeRule.onNodeWithTag("archive_project_button").performClick()
        composeRule.onNodeWithTag("project_archive_dialog_content").assertIsDisplayed()
    }

    private val restorationProject = PlannerProject(
        id = "project-restoration",
        name = "Restoration Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun duplicateUiState(
        projects: List<PlannerProject> = listOf(restorationProject),
        selectedProjectId: String? = restorationProject.id,
        archivedProjects: List<ArchivedProject> = emptyList(),
        completionCount: Long = 7L,
    ) = AppUiState(
        isLoading = false,
        isCatalogWritable = true,
        catalogMutationCompletionCount = completionCount,
        catalog = ProjectCatalog(
            selectedProjectId = selectedProjectId,
            projects = projects,
            archivedProjects = archivedProjects,
        ),
    )
}
