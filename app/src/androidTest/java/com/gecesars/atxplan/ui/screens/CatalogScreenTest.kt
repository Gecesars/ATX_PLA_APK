package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationProgress
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.ui.dataset.DataCatalogUiState
import com.gecesars.atxplan.ui.dataset.IbgeCatalogStatus
import com.gecesars.atxplan.ui.theme.AtxPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactReadyDatasetKeepsSearchSelectionAndLimitationsReachableAtLargeText() {
        val query = mutableStateOf("")
        val selection = mutableStateOf<IbgeMunicipalitySummary?>(null)
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("compact_catalog_host"),
                    ) {
                        CatalogScreen(
                            state = readyState.copy(
                                municipalityQuery = query.value,
                                selectedMunicipality = selection.value,
                            ),
                            onMunicipalityQueryChange = { query.value = it },
                            onMunicipalitySelected = { code ->
                                selection.value = readyState.municipalityResults
                                    .firstOrNull { municipality -> municipality.code == code }
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_catalog_host").assertIsDisplayed()
        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("ibge_dataset_ready"))
        composeRule.onNodeWithTag("ibge_dataset_ready").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Attributes and portable bounding-box records are included; census-sector polygons are not.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("ibge_municipality_search"))
        composeRule.onNodeWithTag("ibge_municipality_search").assertIsDisplayed()
        composeRule.onNodeWithTag("ibge_municipality_search").performTextReplacement("São Paulo")
        assertEquals("São Paulo", query.value)

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("ibge_municipality_3550308"))
        composeRule.onNodeWithTag("ibge_municipality_3550308").performClick()
        composeRule.onNodeWithTag("ibge_municipality_3550308").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        composeRule.onNodeWithText(
            "This envelope is not an official municipal boundary.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun unavailableDatasetShowsNoSyntheticResultAndProvidesReachableRetry() {
        var retries = 0
        composeRule.setContent {
            AtxPlanTheme {
                CatalogScreen(
                    state = DataCatalogUiState(
                        ibgeStatus = IbgeCatalogStatus.FAILED,
                        datasetErrorMessage = "The embedded package failed SHA-256 verification.",
                    ),
                    onRetryDataset = { retries++ },
                )
            }
        }

        composeRule.onNodeWithTag("ibge_dataset_failure").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "No municipality or population result is substituted",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("retry_ibge_dataset").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun installationProgressReportsPrivateOfflineExtraction() {
        composeRule.setContent {
            AtxPlanTheme {
                CatalogScreen(
                    state = DataCatalogUiState(
                        ibgeStatus = IbgeCatalogStatus.INSTALLING,
                        ibgeProgress = IbgeDatasetPreparationProgress(
                            phase = IbgeDatasetPreparationPhase.INSTALLING,
                            completedBytes = 35_463_168L,
                            totalBytes = 70_926_336L,
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("ibge_dataset_preparing").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ibge_install_progress").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The operation runs in private storage and does not use the network.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("33.8 MiB / 67.6 MiB").assertIsDisplayed()
    }

    @Test
    fun queryFailureDoesNotAlsoClaimThatTheResultSetHasNoMatches() {
        composeRule.setContent {
            AtxPlanTheme {
                CatalogScreen(
                    state = readyState.copy(
                        municipalityQuery = "Sao Paulo",
                        municipalityResults = emptyList(),
                        searchErrorMessage = "The offline municipality query failed.",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasText("The offline municipality query failed."))
        composeRule.onNodeWithText("The offline municipality query failed.").assertIsDisplayed()
        composeRule.onNodeWithText("No recognized municipality matches this local query.")
            .assertDoesNotExist()
    }
}

private val readyState = DataCatalogUiState(
    ibgeStatus = IbgeCatalogStatus.READY,
    ibgeDescriptor = IbgeDatasetDescriptor(
        datasetId = "ibge-census-sectors-2022-brazil",
        title = "IBGE 2022 Census Sector Index — Brazil",
        provider = "Instituto Brasileiro de Geografia e Estatística (IBGE)",
        censusYear = 2022,
        sourceCrs = "EPSG:4674",
        sourceCrsName = "SIRGAS 2000 geographic",
        sourceUrl = "https://example.test/source.zip",
        sourcePageUrl = "https://example.test/product",
        sourceAccessedOn = "2026-08-27",
        attribution = "Source: IBGE — 2022 Census Sector Mesh and sector aggregates.",
        licenseStatus = "Public source; redistribution review required.",
        geometryIncluded = false,
        sectorBoundsDescription = "Portable sector bounding-box table; not polygon geometry",
        populationField = "v0001",
        sectorCount = 468_099,
        municipalityCount = 5_570,
        unassignedSectorCount = 2,
        missingPopulationSectorCount = 0,
        populationTotal = 203_080_756L,
        compressedByteCount = 22_133_986L,
        installedByteCount = 70_926_336L,
        databaseSha256 = "fd116b30b8d95abd7203ec5f013f820ea6bbd33022d2f979de7b8892f925d22b",
    ),
    municipalityResults = listOf(
        IbgeMunicipalitySummary(
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
        ),
    ),
)
