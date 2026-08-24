package com.gecesars.atxplan

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.ui.navigation.AtxRoute
import com.gecesars.atxplan.ui.navigation.DashboardRoute
import com.gecesars.atxplan.ui.navigation.PROJECT_RENAME_PREFIX
import com.gecesars.atxplan.ui.navigation.RF_PATH_EDITOR_PREFIX
import com.gecesars.atxplan.ui.navigation.ProjectRenameRoute
import com.gecesars.atxplan.ui.navigation.ProjectsRoute
import com.gecesars.atxplan.ui.navigation.RfPathEditorRoute
import com.gecesars.atxplan.ui.navigation.StudiesRoute
import com.gecesars.atxplan.ui.navigation.UnsupportedRoute
import com.gecesars.atxplan.ui.navigation.activeRoute
import com.gecesars.atxplan.ui.navigation.rememberAtxNavBackStack
import com.gecesars.atxplan.ui.navigation.replaceTopLevel
import com.gecesars.atxplan.ui.screens.ProjectRenameScreen
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

    private val restorationProject = PlannerProject(
        id = "project-restoration",
        name = "Restoration Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
