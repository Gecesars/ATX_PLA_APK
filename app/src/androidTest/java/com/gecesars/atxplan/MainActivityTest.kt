package com.gecesars.atxplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.unit.dp
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
        composeRule.onNodeWithText("Link Studies").assertIsDisplayed()
    }

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Link Studies").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Link Studies").assertIsDisplayed()
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
        composeRule.onNodeWithTag("network_name_field").assert(hasText("Restored Network"))
        composeRule.waitForIdle()

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
    fun projectRenameRouteDraftAndDirtyGuardSurviveActivityRecreation() {
        val suffix = System.nanoTime().toString()
        createSelectedProject("Rename Guard $suffix")
        openProjectRename()
        val draftName = "Restored Rename $suffix"
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement(draftName)
        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(draftName))
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("project_rename_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(draftName))

        composeRule.onNodeWithContentDescription("Back to Projects").performClick()
        composeRule.onNodeWithText("Discard project name changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep Editing").performClick()
        composeRule.onNodeWithTag("rename_project_name_field").assert(hasText(draftName))

        composeRule.onNodeWithText("Studies").performClick()
        composeRule.onNodeWithText("Discard project name changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep Editing").performClick()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("Discard project name changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard Changes").performClick()
        composeRule.waitForIdle()
        scrollToRenameProjectButton()
    }

    @Test
    fun normalizedProjectNameDoesNotTriggerDirtyGuard() {
        val projectName = "Whitespace Guard ${System.nanoTime()}"
        createSelectedProject(projectName)
        openProjectRename()
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement("  $projectName  ")

        composeRule.onNodeWithContentDescription("Back to Projects").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("project_rename_list"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("projects_list").assertIsDisplayed()
    }

    @Test
    fun renamedProjectIsPersistedAndVisibleAfterActivityRecreation() {
        val suffix = System.nanoTime().toString()
        createSelectedProject("Rename Save $suffix")
        openProjectRename()
        val renamedProject = "Persisted Rename $suffix"
        composeRule.onNodeWithTag("rename_project_name_field")
            .performTextReplacement(renamedProject)
        composeRule.onNodeWithTag("save_project_name_button")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("project_rename_list"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasText(renamedProject))
        composeRule.onNodeWithText(renamedProject).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasText(renamedProject))
        composeRule.onNodeWithText(renamedProject).assertIsDisplayed()
    }

    @Test
    fun duplicateProjectDialogDraftSurvivesActivityRecreation() {
        val suffix = System.nanoTime().toString()
        createSelectedProject("Duplicate Draft $suffix")
        openProjectDuplicate()
        val restoredDraft = "Restored Duplicate $suffix"
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .performTextReplacement(restoredDraft)
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .assert(hasText(restoredDraft))
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("duplicate_project_name_field"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .assert(hasText(restoredDraft))
        composeRule.onNodeWithTag("duplicate_project_confirm")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("duplicate_project_name_field").assertDoesNotExist()
    }

    @Test
    fun duplicatedProjectIsSelectedPersistedAndVisibleAfterActivityRecreation() {
        val suffix = System.nanoTime().toString()
        val sourceName = "Duplicate Source $suffix"
        val duplicateName = "Durable Duplicate $suffix"
        createSelectedProject(sourceName)
        openProjectDuplicate()
        composeRule.onNodeWithTag("duplicate_project_name_field")
            .performTextReplacement(duplicateName)
        composeRule.onNodeWithTag("duplicate_project_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("duplicate_project_name_field"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(duplicateName))
            .assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(duplicateName))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasText(sourceName))
        composeRule.onNodeWithText(sourceName).assertIsDisplayed()
    }

    @Test
    fun archivedProjectCanBeRestoredAndRemainsSelectedAcrossActivityRecreation() {
        val suffix = System.nanoTime()
        val fallbackProjectName = "Archive Fallback $suffix"
        val archivedProjectName = "Durable Archive $suffix"
        createSelectedProject(fallbackProjectName)
        createSelectedProject(archivedProjectName)
        openProjectArchive()
        composeRule.onNodeWithTag("archive_project_impact_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("archive_project_confirm")
            .assertHeightIsAtLeast(48.dp)

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("archive_project_confirm"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("archive_project_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("project_archive_dialog_content"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(fallbackProjectName))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasText(archivedProjectName))
        composeRule.onNodeWithText(archivedProjectName).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(archivedProjectName)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(
                hasContentDescription("Restore archived project $archivedProjectName"),
            )
        composeRule.onNodeWithContentDescription(
            "Restore archived project $archivedProjectName",
        ).assertHeightIsAtLeast(48.dp).performDirectClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(
                hasTestTag("selected_project_card") and hasText(archivedProjectName),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(archivedProjectName))
            .assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("selected_project_card"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(archivedProjectName))
            .assertIsDisplayed()
    }

    @Test
    fun deleteProjectDialogDraftSurvivesActivityRecreation() {
        val suffix = System.nanoTime().toString()
        createSelectedProject("Delete Draft $suffix")
        openProjectDelete()
        val restoredDraft = "DELE"
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement(restoredDraft)
        composeRule.onNodeWithTag("delete_project_name_field").assert(hasText(restoredDraft))
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("delete_project_name_field"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("delete_project_name_field")
            .assert(hasText(restoredDraft))
        composeRule.onNodeWithTag("delete_project_confirm")
            .assertHeightIsAtLeast(48.dp)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("delete_project_cancel")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("delete_project_name_field").assertDoesNotExist()
    }

    @Test
    fun deletedProjectRemainsAbsentAfterActivityRecreation() {
        val suffix = System.nanoTime()
        val fallbackProjectName = "Delete Fallback $suffix"
        val deletedProjectName = "Durable Delete $suffix"
        createSelectedProject(fallbackProjectName)
        createSelectedProject(deletedProjectName)
        openProjectDelete()
        composeRule.onNodeWithTag("delete_project_impact_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("delete_project_name_field")
            .performTextReplacement("DELETE")
        composeRule.onNodeWithTag("delete_project_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("delete_project_name_field"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(deletedProjectName).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(fallbackProjectName))
            .assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("selected_project_card"))
        composeRule.onNodeWithTag("selected_project_card")
            .assert(hasText(fallbackProjectName))
            .assertIsDisplayed()
        composeRule.onNodeWithText(deletedProjectName).assertDoesNotExist()
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
        composeRule.onNodeWithTag("create_project_confirm").performDirectClick()
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

    private fun createSelectedProject(name: String) {
        composeRule.onNode(
            hasText("Projects") and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("new_project_button"))
        composeRule.onNodeWithTag("new_project_button").performClick()
        composeRule.onNodeWithTag("project_name_field").performTextReplacement(name)
        composeRule.onNodeWithTag("create_project_confirm").performDirectClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("create_project_confirm"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openProjectRename() {
        scrollToRenameProjectButton()
        composeRule.onNodeWithTag("rename_project_button")
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("project_rename_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("save_project_name_button").assertHeightIsAtLeast(48.dp)
    }

    private fun openProjectDuplicate() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("duplicate_project_button"))
        composeRule.onNodeWithTag("duplicate_project_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("duplicate_project_name_field"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openProjectDelete() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("delete_project_button"))
        composeRule.onNodeWithTag("delete_project_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("delete_project_name_field"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openProjectArchive() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("projects_list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("archive_project_button"))
        composeRule.onNodeWithTag("archive_project_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performDirectClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasTestTag("project_archive_dialog_content"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToRenameProjectButton() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onAllNodes(hasTestTag("projects_list"))
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("projects_list")
            .performScrollToNode(hasTestTag("rename_project_button"))
        composeRule.onNodeWithTag("rename_project_button").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performDirectClick() {
    performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
}
