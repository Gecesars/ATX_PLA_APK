package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.application.RfAssetKind
import com.gecesars.atxplan.domain.application.RfAssetMutationCommand
import com.gecesars.atxplan.domain.application.RfAssetMutationReceipt
import com.gecesars.atxplan.domain.application.RfAssetMutationStatus
import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.ReceiverNetworkProfile
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.TiltDegrees
import com.gecesars.atxplan.ui.theme.AtxPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RfAssetsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactScreenKeepsReferencedDeleteImpactAndLongReceiverEditorReachable() {
        var mutationRequests = 0
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("compact_rf_assets_host"),
                    ) {
                        RfAssetsScreen(
                            project = rfAssetsProject,
                            isLoadingCatalog = false,
                            isCatalogWritable = true,
                            isSaving = false,
                            lastMutationReceipt = null,
                            onMutate = { mutationRequests += 1 },
                            onBack = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_rf_assets_host").assertIsDisplayed()
        composeRule.onNodeWithTag("rf_assets_screen").assertIsDisplayed()
        composeRule.onNodeWithText("RF Assets").assertIsDisplayed()
        composeRule.onNodeWithText("Add Network").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Delete Primary Network")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Delete Network?").assertIsDisplayed()
        composeRule.onNode(
            hasText(
                "Deletion is blocked because 1 sector and 1 receiver reference this network. " +
                    "Resolve those references first.",
            ),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText(
                "1 receiver includes preserved read-only compatibility references to this network.",
                substring = true,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Receivers").performScrollTo().performClick()
        composeRule.onNodeWithText("Preserved profiles (read-only): Primary Network")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit Field Receiver A").performClick()
        composeRule.onNodeWithText("Preserved compatibility references (read-only)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(
            hasText("they cannot be reassigned in this Android editor", substring = true),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Add Receiver").performScrollTo().performClick()
        composeRule.onNodeWithTag("rf_asset_editor").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("save_rf_asset_button").assertIsDisplayed()

        composeRule.runOnIdle { assertEquals(0, mutationRequests) }
    }

    @Test
    fun failedDurableMutationKeepsTheReviewedDialogOpenAndRetryable() {
        val saving = mutableStateOf(false)
        val completionCount = mutableLongStateOf(0L)
        var mutationRequests = 0
        composeRule.setContent {
            AtxPlanTheme {
                RfAssetsScreen(
                    project = rfAssetsProject,
                    isLoadingCatalog = false,
                    isCatalogWritable = true,
                    isSaving = saving.value,
                    catalogMutationCompletionCount = completionCount.longValue,
                    lastMutationReceipt = null,
                    onMutate = {
                        mutationRequests += 1
                        saving.value = true
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Receivers").performClick()
        composeRule.onNodeWithContentDescription("Delete Field Receiver A").performClick()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").performClick()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").assertIsNotEnabled()

        composeRule.runOnIdle {
            saving.value = false
            completionCount.longValue += 1L
        }

        composeRule.onNodeWithText("Delete Receiver?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(1, mutationRequests) }
    }

    @Test
    fun missingPrerequisitesExplainWhySectorAndReceiverCreationAreDisabled() {
        val emptyProject = rfAssetsProject.copy(
            networks = emptyList(),
            sites = emptyList(),
            receivers = emptyList(),
        )
        composeRule.setContent {
            AtxPlanTheme {
                RfAssetsScreen(
                    project = emptyProject,
                    isLoadingCatalog = false,
                    isCatalogWritable = true,
                    isSaving = false,
                    lastMutationReceipt = null,
                    onMutate = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Sectors").performClick()
        composeRule.onNodeWithText("Add Sector").assertIsNotEnabled()
        composeRule.onNodeWithText("Add a transmitter site before adding a sector.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Receivers").performClick()
        composeRule.onNodeWithText("Add Receiver").assertIsNotEnabled()
        composeRule.onNodeWithText("Add a network before adding a receiver.")
            .assertIsDisplayed()
    }

    @Test
    fun removedEditTargetShowsUnavailableStateInsteadOfSubmittingCreate() {
        val isolatedProject = rfAssetsProject.copy(sites = emptyList(), receivers = emptyList())
        val project = mutableStateOf(isolatedProject)
        var mutationRequests = 0
        composeRule.setContent {
            AtxPlanTheme {
                RfAssetsScreen(
                    project = project.value,
                    isLoadingCatalog = false,
                    isCatalogWritable = true,
                    isSaving = false,
                    lastMutationReceipt = null,
                    onMutate = { mutationRequests += 1 },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Edit Primary Network").performClick()
        composeRule.onNodeWithText("Edit Network").assertIsDisplayed()
        composeRule.runOnIdle { project.value = isolatedProject.copy(networks = emptyList()) }

        composeRule.onNodeWithText("Network Unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This network no longer exists in the latest project document. " +
                "Close the editor and review the RF asset list.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("save_rf_asset_button").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, mutationRequests) }
    }

    @Test
    fun compactEditorKeepsValidationLiveRegionVisibleAndConfirmsDraftDiscard() {
        val emptyProject = rfAssetsProject.copy(
            networks = emptyList(),
            sites = emptyList(),
            receivers = emptyList(),
        )
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.3f)) {
                AtxPlanTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 420.dp)) {
                        RfAssetsScreen(
                            project = emptyProject,
                            isLoadingCatalog = false,
                            isCatalogWritable = true,
                            isSaving = false,
                            lastMutationReceipt = null,
                            onMutate = {},
                            onBack = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Add Network").performClick()
        composeRule.onNodeWithTag("rf_network_name_field").performTextReplacement("Local Draft")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep Editing").performClick()
        composeRule.onNodeWithTag("rf_asset_editor").assertIsDisplayed()

        composeRule.onNodeWithTag("save_rf_asset_button").performClick()
        composeRule.onNodeWithTag("rf_form_error")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText("Frequency requires a finite number.").assertIsDisplayed()
    }

    @Test
    fun activeMutationAndSaveableTokenKeepDeleteCorrelatedAcrossRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val saving = mutableStateOf(false)
        val activeRequestId = mutableStateOf<String?>(null)
        val completionCount = mutableLongStateOf(0L)
        val receipt = mutableStateOf<RfAssetMutationReceipt?>(null)
        var submittedCommand: RfAssetMutationCommand? = null
        restorationTester.setContent {
            AtxPlanTheme {
                RfAssetsScreen(
                    project = rfAssetsProject,
                    isLoadingCatalog = false,
                    isCatalogWritable = true,
                    isSaving = saving.value,
                    catalogMutationCompletionCount = completionCount.longValue,
                    activeMutationRequestId = activeRequestId.value,
                    lastMutationReceipt = receipt.value,
                    onMutate = { command ->
                        submittedCommand = command
                        activeRequestId.value = command.requestId
                        saving.value = true
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Receivers").performClick()
        composeRule.onNodeWithContentDescription("Delete Field Receiver A").performClick()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").performClick()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").assertIsNotEnabled()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Delete Receiver?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_delete_rf_asset").assertIsNotEnabled()
        composeRule.runOnIdle {
            val command = checkNotNull(submittedCommand)
            receipt.value = RfAssetMutationReceipt(
                requestId = command.requestId,
                kind = RfAssetKind.RECEIVER,
                status = RfAssetMutationStatus.DELETED,
                entityId = "receiver-field-a",
            )
            activeRequestId.value = null
            saving.value = false
            completionCount.longValue += 1L
        }

        composeRule.onNodeWithText("Delete Receiver?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1L, completionCount.longValue) }
    }

    private val rfAssetsProject = PlannerProject(
        id = "project-rf-assets-ui",
        name = "Compact RF Inventory",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        networks = listOf(
            RfNetwork(
                id = "network-primary",
                name = "Primary Network",
                system = RadioSystem.LTE,
                downlinkFrequencyMHz = 758.0,
                bandwidthMHz = 10.0,
            ),
        ),
        sites = listOf(
            RadioSite(
                id = "site-ridge",
                name = "Ridge Site",
                location = GeoPoint(latitude = -23.55, longitude = -46.63),
                sectors = listOf(
                    Sector(
                        id = "sector-ridge-a",
                        name = "Ridge Sector A",
                        azimuthDegrees = 45.0,
                        antennaHeightM = 30.0,
                        transmitPowerDbm = 43.0,
                        antennaGainDbi = 15.0,
                        feederLossDb = 2.0,
                        frequencyMHz = 758.0,
                        networkId = "network-primary",
                    ),
                ),
            ),
        ),
        receivers = listOf(
            Receiver(
                id = "receiver-field-a",
                name = "Field Receiver A",
                networkId = "network-primary",
                location = GeoCoordinate(
                    latitude = LatitudeDegrees(-23.56),
                    longitude = LongitudeDegrees(-46.64),
                ),
                antennaHeightM = HeightM(2.0),
                antennaGainDbi = GainDbi(0.0),
                systemLossDb = LossDb(0.0),
                sensitivityDbm = PowerDbm(-100.0),
                noiseFigureDb = LossDb(5.0),
                azimuthDegrees = AzimuthDegrees(0.0),
                electricalTiltDegrees = TiltDegrees(0.0),
                networkProfiles = listOf(
                    ReceiverNetworkProfile(networkId = "network-primary"),
                ),
            ),
        ),
    )
}
