package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.application.RunProjectLinkStudyCommand
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
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
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.StudyStatus
import com.gecesars.atxplan.domain.model.StudySummary
import com.gecesars.atxplan.domain.model.StudyType
import com.gecesars.atxplan.domain.study.ProjectLinkStudyEngine
import com.gecesars.atxplan.ui.theme.AtxPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudiesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactProjectStudyKeepsSelectorsActionAndLimitsReachableAtLargeText() {
        val project = project()
        val submitted = mutableStateOf<RunProjectLinkStudyCommand?>(null)
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.3f)) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("compact_studies_host"),
                    ) {
                        StudiesScreen(
                            project = project,
                            resultInput = null,
                            result = null,
                            calculatorError = null,
                            isCalculating = false,
                            isRunningProjectLinkStudy = false,
                            canSaveProjectStudy = true,
                            onCalculate = {},
                            onRunProjectLinkStudy = { submitted.value = it },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_studies_host").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("project_sector_selector"))
        composeRule.onNodeWithText("Origin Site / East Sector").assertIsDisplayed()
        composeRule.onNodeWithText("East Receiver").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("run_project_link_study"))
        composeRule.onNodeWithTag("run_project_link_study").performClick()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("Terrain profile is NoData", substring = true))
        composeRule.onNodeWithText("Terrain profile is NoData", substring = true).assertIsDisplayed()

        assertNotNull(submitted.value)
        assertEquals(PROJECT_ID, submitted.value?.expectedProject?.id)
        assertEquals(SITE_ID, submitted.value?.siteId)
        assertEquals(SECTOR_ID, submitted.value?.sectorId)
        assertEquals(RECEIVER_ID, submitted.value?.receiverId)
    }

    @Test
    fun savedProjectStudyExposesScalarTermsFingerprintAndNoDataWarnings() {
        val base = project()
        val site = base.sites.single()
        val record = ProjectLinkStudyEngine.calculate(
            id = "saved-study",
            name = "Saved East Link",
            createdAtEpochMillis = 2_000L,
            projectId = base.id,
            projectName = base.name,
            network = base.networks.single(),
            site = site,
            sector = site.sectors.single(),
            receiver = base.receivers.single(),
        )
        val project = base.copy(
            studies = listOf(
                StudySummary(
                    id = record.id,
                    name = record.name,
                    type = StudyType.POINT_TO_POINT,
                    status = StudyStatus.COMPLETED,
                    updatedAtEpochMillis = record.createdAtEpochMillis,
                ),
            ),
            linkStudies = listOf(record),
        )
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.3f)) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("saved_study_compact_host"),
                    ) {
                        StudiesScreen(
                            project = project,
                            resultInput = null,
                            result = null,
                            calculatorError = null,
                            isCalculating = false,
                            isRunningProjectLinkStudy = false,
                            canSaveProjectStudy = true,
                            onCalculate = {},
                            onRunProjectLinkStudy = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("saved_study_compact_host").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("saved_project_study_saved-study"))
        composeRule.onNodeWithText("Saved East Link").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("FSPL", substring = true))
        composeRule.onAllNodesWithText("FSPL", substring = true)[0].assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("fingerprint", substring = true))
        composeRule.onAllNodesWithText("fingerprint", substring = true)[0].assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("• ${record.warnings.first()}"))
        composeRule.onNodeWithText("• ${record.warnings.first()}").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("saved_project_study_details_saved-study"))
        composeRule.onNodeWithTag("saved_project_study_details_saved-study").performClick()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("EIRP", substring = true))
        composeRule.onNodeWithText("EIRP", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText(record.inputFingerprintSha256))
        composeRule.onNodeWithText(record.inputFingerprintSha256).assertIsDisplayed()
    }

    @Test
    fun olderSavedStudiesAreSortedByTimestampAndRemainReachableLazily() {
        val base = project()
        val site = base.sites.single()
        val older = ProjectLinkStudyEngine.calculate(
            id = "older-study",
            name = "Older Link",
            createdAtEpochMillis = 2_000L,
            projectId = base.id,
            projectName = base.name,
            network = base.networks.single(),
            site = site,
            sector = site.sectors.single(),
            receiver = base.receivers.single(),
        )
        val latest = ProjectLinkStudyEngine.calculate(
            id = "latest-study",
            name = "Latest Link",
            createdAtEpochMillis = 3_000L,
            projectId = base.id,
            projectName = base.name,
            network = base.networks.single(),
            site = site,
            sector = site.sectors.single(),
            receiver = base.receivers.single(),
        )
        val project = base.copy(
            studies = listOf(latest, older).map { record ->
                StudySummary(
                    id = record.id,
                    name = record.name,
                    type = StudyType.POINT_TO_POINT,
                    status = StudyStatus.COMPLETED,
                    updatedAtEpochMillis = record.createdAtEpochMillis,
                )
            },
            // Deliberately place the newest record first to reject append-order assumptions.
            linkStudies = listOf(latest, older),
        )
        composeRule.setContent {
            AtxPlanTheme {
                StudiesScreen(
                    project = project,
                    resultInput = null,
                    result = null,
                    calculatorError = null,
                    isCalculating = false,
                    isRunningProjectLinkStudy = false,
                    canSaveProjectStudy = true,
                    onCalculate = {},
                    onRunProjectLinkStudy = {},
                )
            }
        }

        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("saved_project_study_latest-study"))
        composeRule.onNodeWithText("Latest Link").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("saved_study_history_toggle"))
        composeRule.onNodeWithTag("saved_study_history_toggle").performClick()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("saved_project_study_older-study"))
        composeRule.onNodeWithText("Older Link").assertIsDisplayed()
    }

    @Test
    fun sectorSelectionUsesCollisionSafeStructuredIdentity() {
        val base = project()
        val network = base.networks.single()
        val firstSector = base.sites.single().sectors.single().copy(
            id = "b/c",
            name = "First Sector",
        )
        val secondSector = firstSector.copy(id = "c", name = "Second Sector")
        val collisionProject = base.copy(
            sites = listOf(
                base.sites.single().copy(
                    id = "a",
                    name = "First Site",
                    location = GeoPoint(0.0, 0.0),
                    sectors = listOf(firstSector.copy(networkId = network.id)),
                ),
                base.sites.single().copy(
                    id = "a/b",
                    name = "Second Site",
                    location = GeoPoint(1.0, 2.0),
                    sectors = listOf(secondSector.copy(networkId = network.id)),
                ),
            ),
        )
        val submitted = mutableStateOf<RunProjectLinkStudyCommand?>(null)
        composeRule.setContent {
            AtxPlanTheme {
                StudiesScreen(
                    project = collisionProject,
                    resultInput = null,
                    result = null,
                    calculatorError = null,
                    isCalculating = false,
                    isRunningProjectLinkStudy = false,
                    canSaveProjectStudy = true,
                    onCalculate = {},
                    onRunProjectLinkStudy = { submitted.value = it },
                )
            }
        }

        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("project_sector_selector"))
        composeRule.onNodeWithTag("project_sector_selector").performClick()
        composeRule.onNodeWithText("Second Site / Second Sector").performClick()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(androidx.compose.ui.test.hasText("1.000000, 2.000000"))
        composeRule.onNodeWithText("1.000000, 2.000000").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("run_project_link_study"))
        composeRule.onNodeWithTag("run_project_link_study").performClick()

        assertEquals("a/b", submitted.value?.siteId)
        assertEquals("c", submitted.value?.sectorId)
    }

    @Test
    fun projectWithoutCompatibleReceiverShowsAnExplicitNonSyntheticState() {
        composeRule.setContent {
            AtxPlanTheme {
                StudiesScreen(
                    project = project().copy(receivers = emptyList()),
                    resultInput = null,
                    result = null,
                    calculatorError = null,
                    isCalculating = false,
                    isRunningProjectLinkStudy = false,
                    canSaveProjectStudy = true,
                    onCalculate = {},
                    onRunProjectLinkStudy = {},
                )
            }
        }

        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(
                androidx.compose.ui.test.hasText(
                    "No receiver supports the selected sector network.",
                ),
            )
        composeRule.onNodeWithText("No receiver supports the selected sector network.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("run_project_link_study").assertDoesNotExist()
    }

    @Test
    fun compactFmProjectExposesCurrentRegulatoryMethodAndRunsIndependently() {
        val base = project()
        val fmNetwork = base.networks.single().copy(
            name = "Independent FM Network",
            system = RadioSystem.FM_BROADCAST,
            downlinkFrequencyMHz = 98.1,
            bandwidthMHz = 0.2,
        )
        val fmProject = base.copy(
            networks = listOf(fmNetwork),
            sites = listOf(
                base.sites.single().copy(
                    sectors = listOf(
                        base.sites.single().sectors.single().copy(
                            name = "FM Channel 251",
                            frequencyMHz = 98.1,
                            networkId = fmNetwork.id,
                        ),
                    ),
                ),
            ),
            receivers = emptyList(),
        )
        val submittedRadius = mutableStateOf<Double?>(null)
        val municipality = IbgeMunicipalitySummary(
            code = "3550308",
            stateCode = "35",
            stateAbbreviation = "SP",
            stateName = "São Paulo",
            name = "São Paulo",
            sectorCount = 27_301,
            urbanSectorCount = 27_000,
            ruralSectorCount = 301,
            unspecifiedSectorCount = 0,
            missingPopulationSectorCount = 0,
            populationTotal = 11_451_999L,
            urbanPopulation = 11_400_000L,
            ruralPopulation = 51_999L,
            unspecifiedPopulation = 0L,
            areaTotalKm2 = 1_521.2,
            urbanAreaKm2 = 900.0,
            ruralAreaKm2 = 621.2,
            unspecifiedAreaKm2 = 0.0,
            west = -46.83,
            south = -24.01,
            east = -46.36,
            north = -23.35,
        )
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.3f)) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("compact_fm_study_host"),
                    ) {
                        StudiesScreen(
                            project = fmProject,
                            resultInput = null,
                            result = null,
                            calculatorError = null,
                            isCalculating = false,
                            isRunningProjectLinkStudy = false,
                            canSaveProjectStudy = true,
                            onCalculate = {},
                            onRunProjectLinkStudy = {},
                            municipalityResults = listOf(municipality),
                            selectedMunicipality = municipality,
                            isMunicipalityCatalogReady = true,
                            onRunBrazilDigitalTvStudy = { radius, _ -> submittedRadius.value = radius },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_fm_study_host").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("brazil_dtv_regulatory_study"))
        composeRule.onNodeWithText("Brazil Broadcast Regulatory Study").assertIsDisplayed()
        composeRule.onNodeWithText("Current FM viability.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("P.526-15 Deygout–Assis").assertIsDisplayed()
        composeRule.onNodeWithText("E(50,50)").assertIsDisplayed()
        composeRule.onNodeWithText("Nationwide Basic Plan ±1").assertIsDisplayed()
        composeRule.onNodeWithText("Bidirectional D/U").assertIsDisplayed()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("regulatory_sources_reviewed"))
        composeRule.onNodeWithTag("regulatory_sources_reviewed").performClick()
        composeRule.onNodeWithTag("studies_list")
            .performScrollToNode(hasTestTag("run_brazil_dtv_study"))
        composeRule.onNodeWithTag("run_brazil_dtv_study").performClick()

        assertEquals(30.0, submittedRadius.value)
    }

    private fun project(): PlannerProject {
        val network = RfNetwork(
            id = NETWORK_ID,
            name = "Study Network",
            system = RadioSystem.GENERIC,
            downlinkFrequencyMHz = 900.0,
            bandwidthMHz = 10.0,
        )
        val sector = Sector(
            id = SECTOR_ID,
            name = "East Sector",
            azimuthDegrees = 90.0,
            antennaHeightM = 30.0,
            transmitPowerDbm = 43.0,
            antennaGainDbi = 15.0,
            feederLossDb = 2.0,
            frequencyMHz = 900.0,
            networkId = network.id,
        )
        val site = RadioSite(
            id = SITE_ID,
            name = "Origin Site",
            location = GeoPoint(0.0, 0.0),
            sectors = listOf(sector),
        )
        val receiver = Receiver(
            id = RECEIVER_ID,
            name = "East Receiver",
            networkId = network.id,
            location = GeoCoordinate(LatitudeDegrees(0.0), LongitudeDegrees(0.008_993_2)),
            antennaHeightM = HeightM(30.0),
            antennaGainDbi = GainDbi(2.0),
            systemLossDb = LossDb(1.0),
            sensitivityDbm = PowerDbm(-95.0),
            noiseFigureDb = LossDb(6.0),
            azimuthDegrees = AzimuthDegrees(270.0),
        )
        return PlannerProject(
            id = PROJECT_ID,
            name = "Project Study Test",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
            networks = listOf(network),
            sites = listOf(site),
            receivers = listOf(receiver),
        )
    }

    private companion object {
        const val PROJECT_ID = "project-study-test"
        const val NETWORK_ID = "network-study-test"
        const val SITE_ID = "site-study-test"
        const val SECTOR_ID = "sector-study-test"
        const val RECEIVER_ID = "receiver-study-test"
    }
}
