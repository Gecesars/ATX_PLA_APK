package com.gecesars.atxplan.ui.dataset

import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeDatasetException
import com.gecesars.atxplan.domain.dataset.IbgeDatasetFailure
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationProgress
import com.gecesars.atxplan.domain.dataset.IbgeDatasetRepository
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataCatalogViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial preparation publishes verified descriptor and bounded municipality results`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeIbgeDatasetRepository()
            val viewModel = DataCatalogViewModel(repository)

            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(IbgeCatalogStatus.READY, state.ibgeStatus)
            assertEquals(descriptor, state.ibgeDescriptor)
            assertEquals(listOf(saoPaulo), state.municipalityResults)
            assertFalse(state.isSearchingMunicipalities)
            assertNull(state.datasetErrorMessage)
            assertEquals(listOf("" to 6), repository.searches)
        }

    @Test
    fun `query is debounced and a returned municipality can be selected`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeIbgeDatasetRepository()
            val viewModel = DataCatalogViewModel(repository)
            advanceUntilIdle()

            viewModel.updateMunicipalityQuery("São Paulo")
            advanceTimeBy(219L)
            assertEquals(1, repository.searches.size)
            advanceUntilIdle()

            assertEquals("São Paulo" to 12, repository.searches.last())
            assertEquals(listOf(saoPaulo), viewModel.state.value.municipalityResults)

            viewModel.selectMunicipality("3550308")

            assertEquals(saoPaulo, viewModel.state.value.selectedMunicipality)
        }

    @Test
    fun `preparation failure exposes no synthetic data and retry recovers`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeIbgeDatasetRepository().apply {
                prepareError = IbgeDatasetException(
                    failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                    message = "The embedded IBGE package failed verification.",
                )
            }
            val viewModel = DataCatalogViewModel(repository)
            advanceUntilIdle()

            assertEquals(IbgeCatalogStatus.FAILED, viewModel.state.value.ibgeStatus)
            assertTrue(viewModel.state.value.municipalityResults.isEmpty())
            assertEquals(
                "The embedded IBGE package failed verification.",
                viewModel.state.value.datasetErrorMessage,
            )

            repository.prepareError = null
            viewModel.retryDataset()
            advanceUntilIdle()

            assertEquals(IbgeCatalogStatus.READY, viewModel.state.value.ibgeStatus)
            assertEquals(listOf(saoPaulo), viewModel.state.value.municipalityResults)
            assertEquals(2, repository.prepareCalls)
        }
}

private class FakeIbgeDatasetRepository : IbgeDatasetRepository {
    var prepareCalls = 0
    var prepareError: Exception? = null
    val searches = mutableListOf<Pair<String, Int>>()

    override suspend fun prepare(
        onProgress: (IbgeDatasetPreparationProgress) -> Unit,
    ): IbgeDatasetDescriptor {
        prepareCalls++
        onProgress(IbgeDatasetPreparationProgress(IbgeDatasetPreparationPhase.CHECKING))
        onProgress(
            IbgeDatasetPreparationProgress(
                phase = IbgeDatasetPreparationPhase.INSTALLING,
                completedBytes = 50L,
                totalBytes = 100L,
            ),
        )
        prepareError?.let { throw it }
        onProgress(
            IbgeDatasetPreparationProgress(
                phase = IbgeDatasetPreparationPhase.VALIDATING,
                completedBytes = 100L,
                totalBytes = 100L,
            ),
        )
        return descriptor
    }

    override suspend fun searchMunicipalities(
        query: String,
        limit: Int,
    ): List<IbgeMunicipalitySummary> {
        searches += query to limit
        return if (query == "missing") emptyList() else listOf(saoPaulo)
    }
}

private val descriptor = IbgeDatasetDescriptor(
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
    compressedByteCount = 23_234_011L,
    installedByteCount = 76_042_240L,
    databaseSha256 = "2871233bf11b4aa6bd6d5c067e0000eb13ea68a451d002f97e04c1df46102952",
)

private val saoPaulo = IbgeMunicipalitySummary(
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
