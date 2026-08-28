package com.gecesars.atxplan.data.dataset

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.domain.dataset.RegionalArtifactAcquisition
import com.gecesars.atxplan.domain.dataset.RegionalArtifactResult
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalInventory
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRunOutcome
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultRegionalJobRunnerInstrumentedTest {
    @Test
    fun providerAttemptIsPersistedBeforeTheInjectedTransportBoundary() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheRoot = targetContext.cacheDir.canonicalFile
        val root = File(cacheRoot, "regional-runner-${System.nanoTime()}").canonicalFile
        assertTrue(root.path.startsWith(cacheRoot.path + File.separator))

        try {
            val plan = RegionalDatasetPlanner().plan(
                RegionalDatasetRequest(
                    bounds = RegionalBounds(-46.70, -23.60, -46.60, -23.50),
                    selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
                    reason = "Android runner persistence ordering test",
                ),
            )
            val initial = RegionalJobRecordV1.enqueuePending(
                jobId = "223e4567-e89b-42d3-a456-426614174000",
                plan = plan,
                schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
                acceptedAtEpochMillis = 1_000L,
                createdAtEpochMillis = 1_000L,
            )
            val jobRepository = FileRegionalJobRepository(root)
            jobRepository.create(initial)
            val attemptsObservedBeforeProvider = AtomicInteger(-1)
            val datasetRepository = OrderingDatasetRepository(
                beforeProvider = {
                    val reopened = FileRegionalJobRepository(root).get(initial.jobId)
                    attemptsObservedBeforeProvider.set(reopened?.artifactAttemptCounts?.single() ?: -1)
                },
            )
            val ticks = AtomicLong(2_000L)
            val runner = DefaultRegionalJobRunner(
                jobRepository = jobRepository,
                datasetRepository = datasetRepository,
                clock = ticks::getAndIncrement,
            )

            val outcome = runner.run(
                request = RegionalJobExecutionRequestV1(
                    jobId = initial.jobId,
                    planFingerprintSha256 = initial.planFingerprintSha256,
                    schedulerKind = initial.schedulerKind,
                    schedulerGeneration = initial.schedulerGeneration,
                    schedulerIdentity = "instrumented-work-1",
                ),
                onProgress = {},
                isStopped = { false },
            )

            assertTrue(outcome is RegionalJobRunOutcome.Terminal)
            assertEquals(1, attemptsObservedBeforeProvider.get())
            assertEquals(1, datasetRepository.providerCalls)
            val reopenedTerminal = FileRegionalJobRepository(root).get(initial.jobId)
            assertEquals(RegionalJobState.SUCCEEDED, reopenedTerminal?.state)
            assertEquals(listOf(1), reopenedTerminal?.artifactAttemptCounts)
            assertEquals(16L, reopenedTerminal?.networkBytesTransferred)
            assertEquals(1, reopenedTerminal?.artifactOutcomes?.size)
        } finally {
            root.deleteRecursively()
        }
    }
}

private class OrderingDatasetRepository(
    private val beforeProvider: suspend () -> Unit,
) : RegionalDatasetRepository {
    var providerCalls: Int = 0
        private set

    override suspend fun acquire(
        plan: RegionalDownloadPlan,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalDownloadResult = error("The runner instrumented fixture uses one-artifact acquisition only.")

    override suspend fun acquireArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        maximumProviderAttempts: Int?,
        beforeProviderAttempt: suspend (attemptNumber: Int) -> Boolean,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalArtifactAcquisition {
        assertEquals(0, artifactIndex)
        assertEquals(3, maximumProviderAttempts)
        assertTrue(beforeProviderAttempt(1))
        beforeProvider()
        providerCalls += 1
        val artifact = plan.artifacts.single()
        val result = RegionalArtifactResult(
            artifact = artifact,
            status = RegionalTransferStatus.READY,
            effectiveUrl = artifact.url,
            acquiredAt = "2026-08-28T12:00:00.000Z",
            bytes = 128L,
            sha256 = "a".repeat(64),
            notes = "Verified by the Android runner fixture.",
        )
        return RegionalArtifactAcquisition(
            result = result,
            committedInventoryRecord = RegionalInventoryRecord(
                datasetId = artifact.source.datasetId,
                relativePath = artifact.relativePath,
                requestedUrl = result.requestedUrl,
                effectiveUrl = result.effectiveUrl,
                routeId = result.routeId,
                routePolicyVersion = result.routePolicyVersion,
                acquiredAt = result.acquiredAt,
                sourceSnapshot = result.sourceSnapshot,
                status = result.status,
                bytes = result.bytes,
                sha256 = result.sha256,
                checkedAt = "2026-08-28T12:00:00.000Z",
                bounds = artifact.requestBounds,
                processingState = RegionalProcessingState.READY,
                notes = result.notes,
            ),
            providerAttempts = 1,
            networkBytesTransferred = 16L,
        )
    }

    override suspend fun findCommittedArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        minimumAcquiredAtEpochMillis: Long?,
    ): RegionalInventoryRecord? = null

    override suspend fun loadInventory(): RegionalInventory = RegionalInventory()
}
