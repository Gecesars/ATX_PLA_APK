package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.application.RfAssetKind
import com.gecesars.atxplan.domain.application.RfAssetMutationCommand
import com.gecesars.atxplan.domain.application.RfAssetMutationReceipt
import com.gecesars.atxplan.domain.application.RfAssetMutationStatus
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.ContourPurpose
import com.gecesars.atxplan.domain.contour.ContourStatus
import com.gecesars.atxplan.domain.contour.ServiceContourOverlay
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.ui.theme.AtxPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineeringMapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactMapDisclosesOfflineOverlayAndSelectsProjectElevationStates() {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 560.dp)
                            .testTag("compact_map_host"),
                    ) {
                        EngineeringMapScreen(
                            project = mapProject,
                            isCatalogWritable = true,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_map_host").assertIsDisplayed()
        composeRule.onNodeWithTag("engineering_map_canvas").assertIsDisplayed()
        composeRule.onNodeWithText("No basemap installed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Terrain, clutter, GIS features, and coverage results are not rendered in this view.",
        ).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("map_site_ridge").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Selected site",
            ),
        )
        composeRule.onNodeWithTag("edit_map_site_location").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("map_selected_site_panel") and hasAnyDescendant(
                hasText(
                    "Elevation: Project value | 742.5 m (stored, not DEM-derived)",
                    substring = true,
                ),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("edit_map_site_location").assertIsEnabled()

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_valley"))
        composeRule.onNodeWithTag("map_site_valley").performClick()
        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_selected_site_panel"))
        composeRule.onNode(
            hasTestTag("map_selected_site_panel") and hasAnyDescendant(
                hasText("Elevation: NoData | no stored project elevation", substring = true),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun compactMapExposesProtectedScreeningIncompleteAndNoDataContourStates() {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 560.dp)
                            .testTag("compact_contour_map_host"),
                    ) {
                        EngineeringMapScreen(
                            project = mapProject,
                            serviceContours = serviceContours,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_contour_map_host").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "3 service contour records",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "1 complete geometry, 1 incomplete geometry, 1 NoData",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("export_service_contours_kmz").assertIsDisplayed()

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("service_contour_legend"))
        composeRule.onNodeWithTag("service_contour_legend").assertIsDisplayed()
        composeRule.onNodeWithText("Protected — solid; complete geometry filled").assertIsDisplayed()
        composeRule.onNodeWithText("Statistical screening — dashed").assertIsDisplayed()
        composeRule.onNodeWithText("Complete geometry: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Incomplete geometry: 1").assertIsDisplayed()
        composeRule.onNodeWithText("NoData: 1").assertIsDisplayed()
        composeRule.onNodeWithText("FM Protected").assertDoesNotExist()

        composeRule.onNodeWithTag("service_contour_details_toggle")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithText("FM Protected").assertIsDisplayed()

        composeRule.onNodeWithText(
            "NoData: no contour geometry is rendered for this result.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("service_contour_details_toggle")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Show details (3)").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Service-contour geometry is rendered only from supplied local results",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fittedCanvasMarkerCanBeSelectedByTouch() {
        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(project = mapProject.copy(sites = mapProject.sites.take(1)))
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("engineering_map_canvas")
            .performTouchInput { click(center) }
        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_selected_site_panel"))
        composeRule.onNodeWithTag("map_selected_site_panel").assertIsDisplayed()
    }

    @Test
    fun failedMoveRemainsRetryableAndCorrelatedSuccessClosesTheDialog() {
        val projectState = mutableStateOf(mapProject)
        val saving = mutableStateOf(false)
        val activeRequestId = mutableStateOf<String?>(null)
        val completionCount = mutableLongStateOf(0L)
        val receipt = mutableStateOf<RfAssetMutationReceipt?>(null)
        var submittedCommand: RfAssetMutationCommand.MoveSite? = null
        var requestCount = 0

        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = projectState.value,
                    isCatalogWritable = true,
                    isSaving = saving.value,
                    catalogMutationCompletionCount = completionCount.longValue,
                    activeMutationRequestId = activeRequestId.value,
                    lastMutationReceipt = receipt.value,
                    onMoveSite = { command ->
                        submittedCommand = command
                        requestCount += 1
                        activeRequestId.value = command.requestId
                        saving.value = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("edit_map_site_location"))
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.onNodeWithTag("map_site_location_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("map_site_latitude_field").performTextReplacement("-23.600000")
        composeRule.onNodeWithTag("map_site_longitude_field").performTextReplacement("-46.700000")
        composeRule.onNodeWithTag("save_map_site_location").performClick().assertIsNotEnabled()

        composeRule.runOnIdle {
            val command = checkNotNull(submittedCommand)
            assertEquals(mapProject.sites.first(), command.expected)
            assertEquals(GeoPoint(-23.6, -46.7), command.location)
            activeRequestId.value = null
            saving.value = false
            completionCount.longValue += 1L
        }

        composeRule.onNodeWithTag("map_site_location_editor").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The location was not saved. Review the coordinates and retry.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("save_map_site_location").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            val command = checkNotNull(submittedCommand)
            projectState.value = mapProject.copy(
                updatedAtEpochMillis = 2L,
                sites = mapProject.sites.map { site ->
                    if (site.id == command.expected.id) site.copy(location = command.location) else site
                },
            )
            receipt.value = RfAssetMutationReceipt(
                requestId = command.requestId,
                kind = RfAssetKind.SITE,
                status = RfAssetMutationStatus.UPDATED,
                entityId = command.expected.id,
            )
            activeRequestId.value = null
            saving.value = false
            completionCount.longValue += 1L
        }

        composeRule.onNodeWithTag("map_site_location_editor").assertDoesNotExist()
        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").assert(
            hasText("23.60000\u00B0 S  46.70000\u00B0 W", substring = true),
        )
        composeRule.runOnIdle { assertEquals(2, requestCount) }
    }

    @Test
    fun unchangedHighPrecisionCoordinatesRoundTripWithoutRounding() {
        val preciseLocation = GeoPoint(
            latitude = -23.5505201234567,
            longitude = -46.6333098765432,
        )
        val preciseProject = mapProject.copy(
            sites = listOf(mapProject.sites.first().copy(location = preciseLocation)),
        )
        var submittedCommand: RfAssetMutationCommand.MoveSite? = null

        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = preciseProject,
                    isCatalogWritable = true,
                    onMoveSite = { submittedCommand = it },
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("edit_map_site_location"))
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.onNodeWithTag("map_site_latitude_field")
            .assert(hasText("-23.5505201234567"))
        composeRule.onNodeWithTag("map_site_longitude_field")
            .assert(hasText("-46.6333098765432"))
        composeRule.onNodeWithContentDescription("Change latitude sign").performClick()
        composeRule.onNodeWithTag("map_site_latitude_field")
            .assert(hasText("23.5505201234567"))
        composeRule.onNodeWithContentDescription("Change latitude sign").performClick()
        composeRule.onNodeWithTag("save_map_site_location").performClick()

        composeRule.runOnIdle {
            assertEquals(preciseLocation, checkNotNull(submittedCommand).location)
        }
    }

    @Test
    fun commaDecimalDraftsAreAcceptedDeterministically() {
        var submittedCommand: RfAssetMutationCommand.MoveSite? = null
        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = mapProject.copy(sites = mapProject.sites.take(1)),
                    isCatalogWritable = true,
                    onMoveSite = { submittedCommand = it },
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.onNodeWithTag("map_site_latitude_field").performTextReplacement("-23,5")
        composeRule.onNodeWithTag("map_site_longitude_field").performTextReplacement("-46,6")
        composeRule.onNodeWithTag("save_map_site_location").performClick()

        composeRule.runOnIdle {
            assertEquals(
                GeoPoint(latitude = -23.5, longitude = -46.6),
                checkNotNull(submittedCommand).location,
            )
        }
    }

    @Test
    fun concurrentSiteChangeRefreshesDraftBeforeACommandCanBeSubmitted() {
        val projectState = mutableStateOf(mapProject)
        var submittedCommand: RfAssetMutationCommand.MoveSite? = null
        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = projectState.value,
                    isCatalogWritable = true,
                    onMoveSite = { submittedCommand = it },
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.onNodeWithTag("map_site_latitude_field").performTextReplacement("-23.7")

        val currentSite = mapProject.sites.first().copy(
            name = "Externally Updated Ridge",
            location = GeoPoint(latitude = -23.71, longitude = -46.81),
        )
        composeRule.runOnIdle {
            projectState.value = mapProject.copy(sites = listOf(currentSite, mapProject.sites.last()))
        }

        composeRule.onNodeWithText(
            "This site changed while its location was being edited. The latest coordinates are shown; review and retry.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("map_site_latitude_field").assert(hasText("-23.71"))
        composeRule.onNodeWithTag("map_site_longitude_field").assert(hasText("-46.81"))
        composeRule.runOnIdle { assertEquals(null, submittedCommand) }

        composeRule.onNodeWithTag("map_site_latitude_field").performTextReplacement("-23.72")
        composeRule.onNodeWithTag("save_map_site_location").performClick()
        composeRule.runOnIdle {
            assertEquals(currentSite, checkNotNull(submittedCommand).expected)
            assertEquals(-23.72, checkNotNull(submittedCommand).location.latitude, 0.0)
        }
    }

    @Test
    fun savedDraftSurvivesTransientProjectReloadAndOrphanedRequestIsRetryable() {
        val restorationTester = StateRestorationTester(composeRule)
        val projectState = mutableStateOf<PlannerProject?>(mapProject)
        val saving = mutableStateOf(false)
        val activeRequestId = mutableStateOf<String?>(null)
        val mutationSessionId = mutableStateOf("session-one")
        restorationTester.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = projectState.value,
                    isCatalogWritable = true,
                    isSaving = saving.value,
                    mutationSessionId = mutationSessionId.value,
                    activeMutationRequestId = activeRequestId.value,
                    onMoveSite = { command ->
                        activeRequestId.value = command.requestId
                        saving.value = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.onNodeWithTag("map_site_latitude_field")
            .performTextReplacement("-23.6000012345")
        composeRule.onNodeWithTag("save_map_site_location").performClick().assertIsNotEnabled()

        composeRule.runOnIdle {
            activeRequestId.value = null
            saving.value = false
            projectState.value = null
        }
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            mutationSessionId.value = "session-two"
            projectState.value = mapProject
        }

        composeRule.onNodeWithTag("map_site_location_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("map_site_latitude_field")
            .assert(hasText("-23.6000012345"))
        composeRule.onNodeWithTag("save_map_site_location").assertIsEnabled()
    }

    @Test
    fun importedSiteIdsCannotCollideWithTheSelectedPanelKey() {
        val collisionProject = mapProject.copy(
            sites = listOf(
                mapProject.sites.first().copy(id = "x"),
                mapProject.sites.last().copy(id = "selected-x"),
            ),
        )
        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(project = collisionProject)
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_x"))
        composeRule.onNodeWithTag("map_site_x").performClick()
        composeRule.onNodeWithTag("map_selected_site_panel").assertIsDisplayed()
    }

    @Test
    fun unrelatedRfRequestIsNotAdoptedByAnOpenLocationEditor() {
        val saving = mutableStateOf(false)
        val activeRequestId = mutableStateOf<String?>(null)
        val completionCount = mutableLongStateOf(0L)
        val receipt = mutableStateOf<RfAssetMutationReceipt?>(null)
        composeRule.setContent {
            AtxPlanTheme {
                EngineeringMapScreen(
                    project = mapProject,
                    isCatalogWritable = true,
                    isSaving = saving.value,
                    catalogMutationCompletionCount = completionCount.longValue,
                    activeMutationRequestId = activeRequestId.value,
                    lastMutationReceipt = receipt.value,
                )
            }
        }

        composeRule.onNodeWithTag("engineering_map_screen")
            .performScrollToNode(hasTestTag("map_site_ridge"))
        composeRule.onNodeWithTag("map_site_ridge").performClick()
        composeRule.onNodeWithTag("edit_map_site_location").performClick()
        composeRule.runOnIdle {
            activeRequestId.value = "unrelated-network-request"
            saving.value = true
        }
        composeRule.onNodeWithTag("save_map_site_location").assertIsNotEnabled()

        composeRule.runOnIdle {
            receipt.value = RfAssetMutationReceipt(
                requestId = "unrelated-network-request",
                kind = RfAssetKind.NETWORK,
                status = RfAssetMutationStatus.CREATED,
                entityId = "network-unrelated",
            )
            activeRequestId.value = null
            saving.value = false
            completionCount.longValue += 1L
        }

        composeRule.onNodeWithTag("map_site_location_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("save_map_site_location").assertIsEnabled()
        composeRule.onNodeWithText(
            "The location result could not be correlated. Retry the move.",
        ).assertDoesNotExist()
    }

    private val mapProject = PlannerProject(
        id = "project-engineering-map",
        name = "Engineering Map Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        sites = listOf(
            RadioSite(
                id = "ridge",
                name = "Ridge Site",
                location = GeoPoint(latitude = -23.55, longitude = -46.63),
                groundElevationM = 742.5,
                towerHeightM = 36.0,
                notes = "Imported field that the move must preserve.",
            ),
            RadioSite(
                id = "valley",
                name = "Valley Site",
                location = GeoPoint(latitude = -23.60, longitude = -46.72),
            ),
        ),
    )

    private val serviceContours = listOf(
        ServiceContourOverlay(
            id = "fm-protected",
            siteId = "ridge",
            sectorId = "ridge-fm",
            service = BroadcastService.FM,
            purpose = ContourPurpose.PROTECTED,
            statisticalBasis = "E(50,50)",
            thresholdDbuvPerM = 66.0,
            points = listOf(
                GeoPoint(-23.48, -46.70),
                GeoPoint(-23.48, -46.56),
                GeoPoint(-23.62, -46.56),
                GeoPoint(-23.62, -46.70),
                GeoPoint(-23.48, -46.70),
            ),
            status = ContourStatus.COMPLETE,
            model = "Validated broadcast contour fixture",
            rulesetId = "anatel-fm-fixture-v1",
            warnings = emptyList(),
        ),
        ServiceContourOverlay(
            id = "tv-screening",
            siteId = "ridge",
            sectorId = "ridge-tv",
            service = BroadcastService.DIGITAL_TV,
            purpose = ContourPurpose.SCREENING,
            statisticalBasis = "E(50,90) = 2 × E(50,50) − E(50,10)",
            thresholdDbuvPerM = 51.0,
            points = listOf(
                GeoPoint(-23.50, -46.68),
                GeoPoint(-23.45, -46.61),
                GeoPoint(-23.54, -46.55),
                GeoPoint(-23.50, -46.68),
            ),
            status = ContourStatus.INCOMPLETE,
            model = "Validated broadcast contour fixture",
            rulesetId = "anatel-tvd-fixture-v1",
            warnings = listOf("One radial reached the supported model boundary."),
        ),
        ServiceContourOverlay(
            id = "tv-nodata",
            siteId = "valley",
            sectorId = "valley-tv",
            service = BroadcastService.DIGITAL_TV,
            purpose = ContourPurpose.PROTECTED,
            statisticalBasis = "E(50,50)",
            thresholdDbuvPerM = null,
            points = emptyList(),
            status = ContourStatus.NO_DATA,
            model = "Validated broadcast contour fixture",
            rulesetId = "anatel-tvd-fixture-v1",
            warnings = listOf("The digital TV channel is unavailable."),
        ),
    )
}
