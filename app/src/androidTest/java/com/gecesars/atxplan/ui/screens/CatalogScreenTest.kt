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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecordProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanStatus
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelResolvedFrequency
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationProgress
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import com.gecesars.atxplan.ui.dataset.DataCatalogUiState
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanUiPhase
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanUiState
import com.gecesars.atxplan.ui.dataset.IbgeCatalogStatus
import com.gecesars.atxplan.ui.dataset.RegionalDataUiPhase
import com.gecesars.atxplan.ui.dataset.RegionalDataUiState
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

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("ibge_dataset_failure"))
        composeRule.onNodeWithTag("ibge_dataset_failure").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No municipality or population result is substituted",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("retry_ibge_dataset").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun compactAnatelNoDataRequiresExplicitSourceReviewBeforeOnDemandDownload() {
        val acknowledged = mutableStateOf(false)
        var refreshes = 0
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 480.dp)) {
                        CatalogScreen(
                            anatelState = AnatelBasicPlanUiState(
                                phase = AnatelBasicPlanUiPhase.NOT_ACQUIRED,
                                noDataReason = AnatelBasicPlanNoDataReason.NOT_ACQUIRED,
                                licenseReviewAcknowledged = acknowledged.value,
                            ),
                            onAnatelLicenseReviewAcknowledged = { acknowledged.value = it },
                            onRefreshAnatelCatalog = { refreshes += 1 },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("anatel_refresh"))
        composeRule.onNodeWithTag("anatel_refresh").assertIsNotEnabled()
        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("anatel_source_review"))
        composeRule.onNodeWithTag("anatel_source_review").performClick()
        composeRule.onNodeWithTag("anatel_refresh").assertIsEnabled().performClick()

        assertEquals(true, acknowledged.value)
        assertEquals(1, refreshes)
        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("anatel_no_data"))
        composeRule.onNodeWithTag("anatel_no_data").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No locally indexed Basic Plan snapshot is available",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun compactReadyAnatelPageExposesBoundedPagingAndSourceDetails() {
        var nextPages = 0
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 480.dp)) {
                        CatalogScreen(
                            anatelState = AnatelBasicPlanUiState(
                                phase = AnatelBasicPlanUiPhase.READY,
                                records = listOf(anatelUiRecord),
                                hasMore = true,
                            ),
                            onLoadMoreAnatelRecords = { nextPages += 1 },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasText("Show Source Details"))
        composeRule.onNodeWithText("Show Source Details").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("catalog_list").performScrollToNode(
            hasText("Antenna limitations: bearing=90|max=5.0"),
        )
        composeRule.onNodeWithText("Antenna limitations: bearing=90|max=5.0")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("anatel_load_more"))
        composeRule.onNodeWithTag("anatel_load_more").assertIsEnabled().performClick()

        assertEquals(1, nextPages)
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

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("ibge_dataset_preparing"))
        composeRule.onNodeWithTag("ibge_dataset_preparing").assertIsDisplayed()
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

    @Test
    fun regionalReviewRequiresLicenseAcceptanceAndInvokesStartExplicitly() {
        val plan = regionalPlan()
        val accepted = mutableStateOf(false)
        var reviews = 0
        var starts = 0
        var selectionToggles = 0
        composeRule.setContent {
            AtxPlanTheme {
                CatalogScreen(
                    regionalState = RegionalDataUiState(
                        phase = RegionalDataUiPhase.REVIEW,
                        plan = plan,
                        licensesAccepted = accepted.value,
                        isLoadingInventory = false,
                    ),
                    onRegionalSelectionToggle = { selectionToggles++ },
                    onReviewRegionalPlan = { reviews++ },
                    onRegionalLicensesAccepted = { accepted.value = it },
                    onStartRegionalAcquisition = { starts++ },
                )
            }
        }

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_selection_esa_worldcover_2021"))
        composeRule.onNodeWithTag("regional_selection_esa_worldcover_2021").performClick()
        assertEquals(1, selectionToggles)

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_review"))
        composeRule.onNodeWithTag("regional_review").performClick()
        assertEquals(1, reviews)

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_start"))
        composeRule.onNodeWithTag("regional_start").assertIsNotEnabled()
        assertEquals(0, starts)

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_license_acceptance"))
        composeRule.onNodeWithTag("regional_license_acceptance").performClick()
        composeRule.onNodeWithTag("regional_start").assertIsEnabled().performClick()

        assertEquals(true, accepted.value)
        assertEquals(1, starts)
    }

    @Test
    fun experimentalBuildingsExposeExplicitLiveSnapshotRefresh() {
        var refreshRequested = false
        composeRule.setContent {
            AtxPlanTheme {
                CatalogScreen(
                    regionalState = RegionalDataUiState(
                        selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                        isLoadingInventory = false,
                    ),
                    onRegionalLiveSnapshotRefreshChange = { refreshRequested = it },
                )
            }
        }

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_refresh_live_snapshot"))
        composeRule.onNodeWithTag("regional_refresh_live_snapshot").performClick()

        assertEquals(true, refreshRequested)
    }

    @Test
    fun compactRunningRegionalPackageKeepsWarningsCancelAndReadinessReachableAtLargeText() {
        val plan = regionalPlan(RegionalDatasetSelection.entries.toSet())
        var cancellations = 0
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.3f),
            ) {
                AtxPlanTheme {
                    Box(
                        modifier = Modifier
                            .size(width = 360.dp, height = 480.dp)
                            .testTag("compact_regional_host"),
                    ) {
                        CatalogScreen(
                            regionalState = RegionalDataUiState(
                                selections = RegionalDatasetSelection.entries.toSet(),
                                phase = RegionalDataUiPhase.RUNNING,
                                plan = plan,
                                progress = RegionalDownloadProgress(
                                    artifact = plan.artifacts.first(),
                                    status = RegionalTransferStatus.DOWNLOADING,
                                    completedBytes = 16L * 1024L * 1024L,
                                    totalBytes = 65_000_000L,
                                    bytesPerSecond = 2.5 * 1024.0 * 1024.0,
                                    message = "Streaming to a bounded partial file.",
                                ),
                                isLoadingInventory = false,
                            ),
                            onCancelRegionalAcquisition = { cancellations++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("compact_regional_host").assertIsDisplayed()
        composeRule.onNodeWithTag("catalog_list").performScrollToNode(
            hasText("Buildings use a best-effort bounded Overpass snapshot", substring = true),
        )
        composeRule.onNodeWithText(
            "Buildings use a best-effort bounded Overpass snapshot",
            substring = true,
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_cancel"))
        composeRule.onNodeWithTag("regional_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("regional_cancel").assertIsDisplayed().performClick()
        assertEquals(1, cancellations)

        composeRule.onNodeWithTag("catalog_list")
            .performScrollToNode(hasTestTag("regional_readiness_limitations"))
        composeRule.onNodeWithTag("regional_readiness_limitations").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Copernicus DSM terrain to HNMT and P.526 paths",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "does not generate a DTM or apply WorldCover, buildings, clutter, or vegetation losses",
            substring = true,
        ).assertIsDisplayed()
    }
}

private fun regionalPlan(
    selections: Set<RegionalDatasetSelection> = setOf(
        RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
        RegionalDatasetSelection.ESA_WORLDCOVER_2021,
    ),
) = RegionalDatasetPlanner().plan(
    RegionalDatasetRequest(
        bounds = RegionalBounds(
            west = -46.67,
            south = -23.57,
            east = -46.64,
            north = -23.54,
        ),
        selections = selections,
        reason = "instrumented catalog UI test",
    ),
)

private val anatelUiRecord = AnatelBasicPlanRecord(
    sourceRowId = "ui-row-1",
    basicPlanId = "PB-1001",
    itemNumber = 1L,
    origin = AnatelBasicPlanOrigin.BASIC_PLAN,
    service = AnatelBroadcastService.FM,
    rawService = "FM",
    status = AnatelBasicPlanStatus("SOURCE_ACTIVE"),
    channelRaw = "258",
    channel = 258,
    frequency = AnatelResolvedFrequency(
        frequencyMHz = 99.5,
        origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
        sourceFrequencyRaw = "99.5",
        explanation = "The exact source frequency was used.",
    ),
    countryCode = "BR",
    stateCode = "SP",
    ibgeMunicipalityCode = "3550308",
    municipalityName = "São Paulo",
    channelOffsetRaw = "",
    stationClassRaw = "A1",
    characterRaw = "P",
    purposeRaw = "COMMERCIAL",
    entityName = "Example Broadcaster",
    cnpjRaw = "",
    stationCategoryRaw = "PRIMARY",
    latitudeDegrees = -23.55,
    longitudeDegrees = -46.63,
    erpKw = 10.0,
    antennaHeightMeters = 80.0,
    antennaLimitationsRaw = "bearing=90|max=5.0",
    antennaPatternDbdRaw = "0|-3|-6",
    observationsRaw = "Source fixture for compact UI testing.",
    fistelRaw = "F-100",
    generatorFistelRaw = "",
    dicRaw = "D-100",
    provenance = AnatelBasicPlanRecordProvenance(
        archive = AnatelBasicPlanArchiveProvenance(
            acquiredAtEpochMillis = 1_000L,
            archiveSha256 = "a".repeat(64),
            archiveByteCount = 1_024L,
        ),
        entryName = AnatelBasicPlanOrigin.BASIC_PLAN.officialArchiveEntryName,
        origin = AnatelBasicPlanOrigin.BASIC_PLAN,
        generationDate = "2026-08-28",
        sourceRowNumber = 1L,
    ),
)

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
