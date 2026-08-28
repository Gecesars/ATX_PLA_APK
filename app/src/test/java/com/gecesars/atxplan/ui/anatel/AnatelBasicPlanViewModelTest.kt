package com.gecesars.atxplan.ui.anatel

import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalog
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogStatus
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanImportReport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQueryPage
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecordProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRefreshResult
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanStatus
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelResolvedFrequency
import com.gecesars.atxplan.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnatelBasicPlanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `on-demand refresh requires review and then publishes verified records`() =
        runTest(mainDispatcherRule.dispatcher) {
            val catalog = FakeAnatelCatalog()
            val viewModel = AnatelBasicPlanViewModel(catalog, mainDispatcherRule.dispatcher)
            advanceUntilIdle()

            assertEquals(AnatelBasicPlanUiPhase.NOT_ACQUIRED, viewModel.state.value.phase)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(0, catalog.refreshCount)
            assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("acknowledge"))

            viewModel.setLicenseReviewAcknowledged(true)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, catalog.refreshCount)
            assertEquals(AnatelBasicPlanUiPhase.READY, viewModel.state.value.phase)
            assertEquals(listOf(record), viewModel.state.value.records)
            assertTrue(viewModel.state.value.notice.orEmpty().contains("integrity-checked"))
            assertFalse(viewModel.state.value.isSearching)
        }

    @Test
    fun `search maps bounded filters and service without mutating records automatically`() =
        runTest(mainDispatcherRule.dispatcher) {
            val catalog = FakeAnatelCatalog(initialStatus = AnatelBasicPlanCatalogStatus.ready(snapshot))
            val viewModel = AnatelBasicPlanViewModel(catalog, mainDispatcherRule.dispatcher)
            advanceUntilIdle()

            viewModel.setService(AnatelBroadcastService.TELEVISION)
            viewModel.setStateCode("sp")
            viewModel.setChannelText("31")
            viewModel.setQueryText("Campinas")
            viewModel.search()
            advanceUntilIdle()

            val query = catalog.queries.last()
            assertEquals(AnatelBroadcastService.TELEVISION, query.service)
            assertEquals("SP", query.stateCode)
            assertEquals(31, query.channel)
            assertEquals("Campinas", query.text)
            assertEquals(25, query.pageSize)
            assertEquals(0, query.offset)
        }

    @Test
    fun `edited filters invalidate old rows and paging replaces rather than accumulates`() =
        runTest(mainDispatcherRule.dispatcher) {
            val catalog = FakeAnatelCatalog(
                initialStatus = AnatelBasicPlanCatalogStatus.ready(snapshot),
                queryResult = { query ->
                    if (query.offset == 0) {
                        List(query.pageSize) { index ->
                            record.copy(sourceRowId = "row-$index")
                        } to true
                    } else {
                        listOf(record.copy(sourceRowId = "row-${query.offset}")) to false
                    }
                },
            )
            val viewModel = AnatelBasicPlanViewModel(catalog, mainDispatcherRule.dispatcher)
            advanceUntilIdle()

            assertEquals(25, viewModel.state.value.records.size)
            assertEquals("row-0", viewModel.state.value.records.first().sourceRowId)
            viewModel.setQueryText("Campinas")

            assertTrue(viewModel.state.value.filtersDirty)
            assertTrue(viewModel.state.value.records.isEmpty())
            val queryCountBeforeIgnoredPaging = catalog.queries.size
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(queryCountBeforeIgnoredPaging, catalog.queries.size)

            viewModel.search()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.filtersDirty)
            assertEquals(0, viewModel.state.value.resultOffset)
            assertTrue(viewModel.state.value.hasMore)

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(25, viewModel.state.value.resultOffset)
            assertEquals(listOf("row-25"), viewModel.state.value.records.map { it.sourceRowId })

            viewModel.loadPrevious()
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.resultOffset)
            assertEquals(25, viewModel.state.value.records.size)
            assertEquals("row-0", viewModel.state.value.records.first().sourceRowId)
        }
}

private class FakeAnatelCatalog(
    initialStatus: AnatelBasicPlanCatalogStatus = AnatelBasicPlanCatalogStatus.noData(
        AnatelBasicPlanNoDataReason.NOT_ACQUIRED,
    ),
    private val queryResult: (AnatelBasicPlanQuery) -> Pair<List<AnatelBasicPlanRecord>, Boolean> =
        { listOf(record) to false },
) : AnatelBasicPlanCatalog {
    private var currentStatus = initialStatus
    var refreshCount = 0
    val queries = mutableListOf<AnatelBasicPlanQuery>()

    override fun refresh(): AnatelBasicPlanRefreshResult {
        refreshCount += 1
        currentStatus = AnatelBasicPlanCatalogStatus.ready(snapshot)
        return AnatelBasicPlanRefreshResult(snapshot, reusedRawArchive = false, reusedIndex = false)
    }

    override fun status(): AnatelBasicPlanCatalogStatus = currentStatus

    override fun query(query: AnatelBasicPlanQuery): AnatelBasicPlanQueryPage {
        queries += query
        val (records, hasMore) = queryResult(query)
        return AnatelBasicPlanQueryPage(
            status = currentStatus,
            records = if (currentStatus.snapshot != null) records else emptyList(),
            offset = query.offset,
            pageSize = query.pageSize,
            hasMore = currentStatus.snapshot != null && hasMore,
        )
    }
}

private val archiveProvenance = AnatelBasicPlanArchiveProvenance(
    acquiredAtEpochMillis = 1_000L,
    archiveSha256 = "a".repeat(64),
    archiveByteCount = 1_024L,
)

private val report = AnatelBasicPlanImportReport(
    provenance = archiveProvenance,
    verifiedArchiveSha256 = archiveProvenance.archiveSha256,
    verifiedArchiveByteCount = archiveProvenance.archiveByteCount,
    archiveEntryCount = 3,
    ignoredArchiveEntryCount = 0,
    entryReports = emptyList(),
    sourceRowCount = 1L,
    emittedRecordCount = 1L,
    latestGenerationDate = "2026-08-28",
    frequencyOriginCounts = mapOf(AnatelFrequencyOrigin.SOURCE_ATTRIBUTE to 1L),
    warnings = emptyList(),
)

private val snapshot = AnatelBasicPlanCatalogSnapshot(
    report = report,
    rawArchiveArtifactName = "canais-${archiveProvenance.archiveSha256}.zip",
    indexArtifactName = "basic-plan-${archiveProvenance.archiveSha256}-v1.sqlite",
    indexedAtEpochMillis = 2_000L,
)

private val record = AnatelBasicPlanRecord(
    sourceRowId = "row-1",
    basicPlanId = "pb-1",
    itemNumber = 1L,
    origin = AnatelBasicPlanOrigin.BASIC_PLAN,
    service = AnatelBroadcastService.FM,
    rawService = "FM",
    status = AnatelBasicPlanStatus("A"),
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
    characterRaw = "",
    purposeRaw = "",
    entityName = "Test Broadcaster",
    cnpjRaw = "",
    stationCategoryRaw = "",
    latitudeDegrees = -23.55,
    longitudeDegrees = -46.63,
    erpKw = 10.0,
    antennaHeightMeters = 80.0,
    antennaLimitationsRaw = "",
    antennaPatternDbdRaw = "",
    observationsRaw = "",
    fistelRaw = "",
    generatorFistelRaw = "",
    dicRaw = "",
    provenance = AnatelBasicPlanRecordProvenance(
        archive = archiveProvenance,
        entryName = AnatelBasicPlanOrigin.BASIC_PLAN.officialArchiveEntryName,
        origin = AnatelBasicPlanOrigin.BASIC_PLAN,
        generationDate = "2026-08-28",
        sourceRowNumber = 1L,
    ),
)
