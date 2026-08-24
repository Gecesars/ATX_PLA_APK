package com.gecesars.atxplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardShowsEngineeringEntryPoint() {
        composeRule.onNodeWithText("Engineering Center").assertIsDisplayed()
        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Link Budget").assertIsDisplayed()
    }

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Link Budget").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Link Budget").assertIsDisplayed()
    }

    @Test
    fun nestedRfEditorAndDraftSurviveActivityRecreation() {
        composeRule.onNodeWithText("Projects").performClick()
        scrollToAddRfPathButton()
        composeRule.onNodeWithTag("add_rf_path_button").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("rf_path_editor_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("network_name_field").performTextReplacement("Restored Network")

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Restored Network").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Restored Network").assertIsDisplayed()
        composeRule.onNodeWithText("Network").assertIsDisplayed()
    }

    @Test
    fun dirtyRfDraftProtectsAppBarDestinationAndSystemBackNavigation() {
        composeRule.onNodeWithText("Projects").performClick()
        scrollToAddRfPathButton()
        composeRule.onNodeWithTag("add_rf_path_button").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("rf_path_editor_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("network_name_field").performTextReplacement("Protected Draft")

        composeRule.onNodeWithContentDescription("Back to Projects").performClick()
        composeRule.onNodeWithText("Discard unsaved RF path?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep Editing").performClick()
        composeRule.onNodeWithText("Protected Draft").assertIsDisplayed()

        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Discard unsaved RF path?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep Editing").performClick()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("Discard unsaved RF path?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard Draft").performClick()
        scrollToAddRfPathButton()
    }

    @Test
    fun rfEditorExposesLabeledSwitchAndPoliteValidationError() {
        composeRule.onNodeWithText("Projects").performClick()
        scrollToAddRfPathButton()
        composeRule.onNodeWithTag("add_rf_path_button").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("rf_path_editor_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(3)
        composeRule.onNodeWithText("Active sector").assertHasClickAction()

        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("network_name_field").performTextReplacement("")
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(5)
        composeRule.onNodeWithTag("save_rf_path_button").performDirectClick()
        composeRule.onNodeWithText("Network name must contain between 2 and 80 characters.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun completeRfPathIsPersistedAndVisibleAfterActivityRecreation() {
        composeRule.onNodeWithText("Projects").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("new_project_button"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("new_project_button").performClick()
        composeRule.onNodeWithTag("project_name_field").performTextReplacement("Device RF Path")
        composeRule.onNodeWithTag("create_project_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("create_project_confirm"))
                .fetchSemanticsNodes().isEmpty()
        }
        val createdNotice = "Project \"Device RF Path\" was created in local storage."
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(createdNotice).fetchSemanticsNodes().isNotEmpty()
        }
        scrollToAddRfPathButton()

        composeRule.onNodeWithTag("add_rf_path_button").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("rf_path_editor_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_path_editor_list").performScrollToIndex(5)
        composeRule.onNodeWithTag("save_rf_path_button").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText(
                "1 transmitter site and 1 receiver are linked to this project.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_asset_summary").performScrollTo().assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("rf_asset_summary"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rf_asset_summary").performScrollTo().assertIsDisplayed()
    }

    private fun scrollToAddRfPathButton() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("add_rf_path_button"))
        composeRule.onNodeWithTag("add_rf_path_button").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performDirectClick() {
    performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
}
