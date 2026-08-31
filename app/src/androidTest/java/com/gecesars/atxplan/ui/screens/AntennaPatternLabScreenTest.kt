package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import com.gecesars.atxplan.ui.antenna.AntennaPrnConventionChoicePreview
import com.gecesars.atxplan.ui.antenna.AntennaPrnValueInterpretation
import com.gecesars.atxplan.ui.antenna.AntennaArrayTaper
import com.gecesars.atxplan.ui.antenna.AntennaArrayTopology
import com.gecesars.atxplan.ui.antenna.AntennaPatternExportFormat
import com.gecesars.atxplan.ui.antenna.AntennaPatternExportPreview
import com.gecesars.atxplan.ui.antenna.AntennaPatternImportPreview
import com.gecesars.atxplan.ui.antenna.AntennaPatternLabUiState
import com.gecesars.atxplan.ui.theme.AtxPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AntennaPatternLabScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactLargeTextLayoutKeepsEveryWorkflowReachable() {
        var synthesisCount = 0
        var lastRequestName: String? = null
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 520.dp)
                            .testTag("compact_antenna_lab_host"),
                    ) {
                        AntennaPatternLabScreen(
                            project = emptyProject,
                            state = AntennaPatternLabUiState(),
                            isCatalogWritable = true,
                            onImportUri = {},
                            onImportPairUris = {},
                            onConfirmImport = {},
                            onDismissImport = {},
                            onResolvePrnConvention = { _, _ -> },
                            onDismissPrnConvention = { _ -> },
                            onSynthesize = { request ->
                                synthesisCount += 1
                                lastRequestName = request.name
                            },
                            onPrepareExport = { _, _ -> },
                            onExportUri = { _, _, _ -> },
                            onDismissExport = { _ -> },
                            onAssignTransmitPattern = { _, _, _ -> },
                            onDeletePattern = {},
                            onDismissMessage = {},
                            onBack = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_antenna_lab_host").assertIsDisplayed()
        composeRule.onNodeWithTag("antenna_pattern_lab").assertIsDisplayed()
        composeRule.onNodeWithTag("import_pattern_pair").assertIsEnabled()
        composeRule.onNodeWithText("No antenna patterns are stored.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Composer").performClick()
        composeRule.onNodeWithText("Coherent Array Composer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("synthesize_pattern")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, synthesisCount)
            assertEquals("Synthesized Array", lastRequestName)
        }

        composeRule.onNodeWithText("Assignments").performScrollTo().performClick()
        composeRule.onNodeWithText("Add a transmitter sector before assigning a pattern.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun composerExposesMultipanelTopologyAndCosineTaper() {
        var selectedTopology: AntennaArrayTopology? = null
        var selectedTaper: AntennaArrayTaper? = null
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = emptyProject,
                    state = AntennaPatternLabUiState(),
                    isCatalogWritable = true,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { _, _ -> },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = { request ->
                        selectedTopology = request.topology
                        selectedTaper = request.taper
                    },
                    onPrepareExport = { _, _ -> },
                    onExportUri = { _, _, _ -> },
                    onDismissExport = { _ -> },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Composer").performClick()
        composeRule.onNodeWithText("Planar").performScrollTo().performClick()
        composeRule.onNodeWithText("Multipanel").performClick()
        composeRule.onNodeWithText("Panels").assertIsDisplayed()
        composeRule.onNodeWithText("Elements/panel").assertIsDisplayed()
        composeRule.onNodeWithText("Uniform").performScrollTo().performClick()
        composeRule.onNodeWithText("Cosine").performClick()
        composeRule.onNodeWithTag("synthesize_pattern")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(AntennaArrayTopology.MULTIPANEL, selectedTopology)
            assertEquals(AntennaArrayTaper.COSINE, selectedTaper)
        }
    }

    @Test
    fun readOnlyCatalogDisablesImportAndSynthesis() {
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = emptyProject,
                    state = AntennaPatternLabUiState(),
                    isCatalogWritable = false,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { _, _ -> },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = { error("A read-only catalog must not synthesize a pattern.") },
                    onPrepareExport = { _, _ -> },
                    onExportUri = { _, _, _ -> },
                    onDismissExport = { _ -> },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("import_pattern").assertIsNotEnabled()
        composeRule.onNodeWithTag("import_pattern_pair").assertIsNotEnabled()
        composeRule.onNodeWithText("Composer").performClick()
        composeRule.onNodeWithTag("synthesize_pattern").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun pairedImportReviewDisclosesBothPreservedSources() {
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = emptyProject,
                    state = AntennaPatternLabUiState(
                        pendingImport = AntennaPatternImportPreview(
                            token = "pair-review",
                            displayName = "Station Antenna",
                            detectedFormat = "Paired ADT HRP + ADT VRP",
                            sourceSha256 = "a".repeat(64),
                            sourceByteCount = 4_096L,
                            horizontalSampleCount = 360,
                            verticalSampleCount = 181,
                            nominalFrequencyHz = 100_100_000.0,
                            peakGainDbi = 8.25,
                            isCalculationReady = true,
                            warnings = listOf("Both exact source files are preserved in one bundle."),
                            componentDisplayNames = listOf("station.hrp", "station.vrp"),
                        ),
                    ),
                    isCatalogWritable = true,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { _, _ -> },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = { _ -> },
                    onPrepareExport = { _, _ -> },
                    onExportUri = { _, _, _ -> },
                    onDismissExport = { _ -> },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Review Antenna Import").assertIsDisplayed()
        composeRule.onNodeWithText("Preserved sources: station.hrp + station.vrp")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Import Pattern").assertIsEnabled()
    }

    @Test
    fun ambiguousPrnDialogRequiresAnExplicitTokenBoundInterpretation() {
        var resolvedToken: String? = null
        var resolvedInterpretation: AntennaPrnValueInterpretation? = null
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = emptyProject,
                    state = AntennaPatternLabUiState(
                        pendingPrnConventionChoice = AntennaPrnConventionChoicePreview(
                            token = "prn-choice-token",
                            sourceDisplayNames = listOf("station.h.prn", "station.v.prn"),
                            ambiguousPlaneLabels = listOf("Horizontal (HRP)", "Vertical (VRP)"),
                        ),
                    ),
                    isCatalogWritable = true,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { token, interpretation ->
                        resolvedToken = token
                        resolvedInterpretation = interpretation
                    },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = {},
                    onPrepareExport = { _, _ -> },
                    onExportUri = { _, _, _ -> },
                    onDismissExport = { _ -> },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("prn_convention_choice").assertIsDisplayed()
        composeRule.onNodeWithText("Affected cut(s): Horizontal (HRP), Vertical (VRP)")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "This one selection applies to every ambiguous unmarked PRN in the pair. " +
                "Cancel if the source files use different conventions.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("prn_positive_attenuation").assertIsEnabled()
        composeRule.onNodeWithTag("prn_normalized_linear").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("prn-choice-token", resolvedToken)
            assertEquals(
                AntennaPrnValueInterpretation.NORMALIZED_LINEAR_FIELD,
                resolvedInterpretation,
            )
        }
    }

    @Test
    fun formatSelectionRequestsPreflightBeforeDestinationCreation() {
        var requestedPatternId: String? = null
        var requestedFormat: AntennaPatternExportFormat? = null
        var destinationCount = 0
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = projectWithPattern,
                    state = AntennaPatternLabUiState(),
                    isCatalogWritable = true,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { _, _ -> },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = {},
                    onPrepareExport = { patternId, format ->
                        requestedPatternId = patternId
                        requestedFormat = format
                    },
                    onExportUri = { _, _, _ -> destinationCount += 1 },
                    onDismissExport = { _ -> },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Export").performScrollTo().performClick()
        composeRule.onNodeWithText("ATX Planner desktop JSON v1").performClick()
        composeRule.runOnIdle {
            assertEquals(exportPattern.id, requestedPatternId)
            assertEquals(AntennaPatternExportFormat.ATX_DESKTOP_JSON, requestedFormat)
            assertEquals(0, destinationCount)
        }
    }

    @Test
    fun preparedExportShowsSelectedMimeAndEveryFormatWarning() {
        var dismissedToken: String? = null
        val warnings = listOf(
            "The first conversion warning is retained.",
            "The second conversion warning is retained.",
            "The third conversion warning is retained.",
            "The final conversion warning is retained.",
        )
        composeRule.setContent {
            AtxPlanTheme {
                AntennaPatternLabScreen(
                    project = projectWithPattern,
                    state = AntennaPatternLabUiState(
                        pendingExport = AntennaPatternExportPreview(
                            token = "prepared-export",
                            patternId = exportPattern.id,
                            format = AntennaPatternExportFormat.ATX_DESKTOP_JSON,
                            suggestedFileName = "reference.atxpat.json",
                            mediaType = "application/json",
                            byteCount = 8_192,
                            sha256 = "b".repeat(64),
                            warnings = warnings,
                        ),
                    ),
                    isCatalogWritable = true,
                    onImportUri = {},
                    onImportPairUris = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onResolvePrnConvention = { _, _ -> },
                    onDismissPrnConvention = { _ -> },
                    onSynthesize = {},
                    onPrepareExport = { _, _ -> },
                    onExportUri = { _, _, _ -> },
                    onDismissExport = { token -> dismissedToken = token },
                    onAssignTransmitPattern = { _, _, _ -> },
                    onDeletePattern = {},
                    onDismissMessage = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("export_preflight_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Destination type: application/json").assertIsDisplayed()
        composeRule.onNodeWithText("Show all 4 warning(s)").performClick()
        warnings.forEach { warning ->
            composeRule.onNodeWithText("• $warning").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("choose_export_destination").assertIsEnabled()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals("prepared-export", dismissedToken) }
    }

    private val emptyProject = PlannerProject(
        id = "project-antenna-ui",
        name = "Compact Antenna Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private val exportPattern = CanonicalAntennaPattern
        .isotropic(nominalFrequencyHz = 100_100_000.0)
        .toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-export-ui",
                name = "Reference Pattern",
                peakGainDbi = 7.5,
                sourceFormat = "ATX Antenna JSON v2",
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = "artifact-export-ui",
                origin = AntennaPatternOrigin.SYNTHESIZED,
            ),
        )

    private val projectWithPattern = emptyProject.copy(
        antennaPatterns = listOf(exportPattern),
        artifacts = listOf(
            ProjectArtifactReference(
                id = "artifact-export-ui",
                role = ProjectArtifactRole.ANTENNA_PATTERN,
                fileName = "reference.atx-antenna.json",
                mediaType = "application/vnd.atx-plan.antenna+json;version=2",
                sha256 = "c".repeat(64),
                byteCount = 1_024L,
                createdAtEpochMillis = 1L,
            ),
        ),
    )
}
