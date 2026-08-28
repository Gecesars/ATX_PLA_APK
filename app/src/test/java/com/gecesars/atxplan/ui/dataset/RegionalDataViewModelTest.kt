package com.gecesars.atxplan.ui.dataset

import com.gecesars.atxplan.domain.dataset.RegionalArtifactAcquisition
import com.gecesars.atxplan.domain.dataset.RegionalArtifactResult
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalInventory
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import com.gecesars.atxplan.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegionalDataViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `review requires valid bounded coordinates and explicit license acceptance`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = RegionalDataViewModel(FakeRegionalDatasetRepository())
            advanceUntilIdle()

            viewModel.updateCoordinate(RegionalCoordinateField.WEST, "not-a-coordinate")
            viewModel.reviewPlan()

            assertEquals(RegionalDataUiPhase.FAILED, viewModel.state.value.phase)
            assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("West"))

            viewModel.updateCoordinate(RegionalCoordinateField.WEST, "-46.670000")
            viewModel.reviewPlan()

            assertEquals(RegionalDataUiPhase.REVIEW, viewModel.state.value.phase)
            assertNotNull(viewModel.state.value.plan)
            assertFalse(viewModel.state.value.canAcquire)

            viewModel.setLicensesAccepted(true)

            assertTrue(viewModel.state.value.canAcquire)
        }

    @Test
    fun `approved plan reports repository progress and completes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRegionalDatasetRepository()
            val viewModel = RegionalDataViewModel(repository)
            advanceUntilIdle()
            viewModel.reviewPlan()
            viewModel.setLicensesAccepted(true)

            viewModel.startAcquisition()
            advanceUntilIdle()

            assertEquals(1, repository.acquireCalls)
            assertEquals(RegionalDataUiPhase.COMPLETE, viewModel.state.value.phase)
            assertEquals(2, viewModel.state.value.result?.readyCount)
        }

    @Test
    fun `building option remains opt in and enforces its tiny area gate`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = RegionalDataViewModel(FakeRegionalDatasetRepository())
            advanceUntilIdle()

            assertFalse(
                RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL in
                    viewModel.state.value.selections,
            )
            viewModel.toggleSelection(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL)
            viewModel.updateCoordinate(RegionalCoordinateField.WEST, "-46.700000")
            viewModel.updateCoordinate(RegionalCoordinateField.SOUTH, "-23.600000")
            viewModel.updateCoordinate(RegionalCoordinateField.EAST, "-46.640000")
            viewModel.updateCoordinate(RegionalCoordinateField.NORTH, "-23.560000")

            viewModel.reviewPlan()

            assertEquals(RegionalDataUiPhase.FAILED, viewModel.state.value.phase)
            assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("building"))
        }

    @Test
    fun `live snapshot refresh is explicit and is encoded in the reviewed plan`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = RegionalDataViewModel(FakeRegionalDatasetRepository())
            advanceUntilIdle()

            viewModel.toggleSelection(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL)
            viewModel.setLiveSnapshotRefresh(true)
            viewModel.reviewPlan()

            assertTrue(viewModel.state.value.refreshLiveSnapshot)
            assertTrue(viewModel.state.value.plan?.request?.liveSnapshotRefresh == true)

            viewModel.editRequest()
            viewModel.toggleSelection(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL)

            assertFalse(viewModel.state.value.refreshLiveSnapshot)
        }
}

private class FakeRegionalDatasetRepository : RegionalDatasetRepository {
    var acquireCalls = 0

    override suspend fun acquire(
        plan: RegionalDownloadPlan,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalDownloadResult {
        acquireCalls++
        val results = plan.artifacts.map { artifact ->
            onProgress(
                RegionalDownloadProgress(
                    artifact = artifact,
                    status = RegionalTransferStatus.PROCESSING,
                    completedBytes = artifact.estimatedBytes,
                    totalBytes = artifact.estimatedBytes,
                ),
            )
            RegionalArtifactResult(
                artifact = artifact,
                status = RegionalTransferStatus.READY,
                bytes = artifact.estimatedBytes,
                sha256 = "a".repeat(64),
            )
        }
        return RegionalDownloadResult(results)
    }

    override suspend fun loadInventory(): RegionalInventory = RegionalInventory()

    override suspend fun acquireArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        maximumProviderAttempts: Int?,
        beforeProviderAttempt: suspend (attemptNumber: Int) -> Boolean,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalArtifactAcquisition = error("The ViewModel test fake does not use artifact-level acquisition.")

    override suspend fun findCommittedArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        minimumAcquiredAtEpochMillis: Long?,
    ): RegionalInventoryRecord? = null
}
