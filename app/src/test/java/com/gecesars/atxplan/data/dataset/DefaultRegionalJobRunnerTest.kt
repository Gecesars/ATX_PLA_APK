package com.gecesars.atxplan.data.dataset

import com.gecesars.atxplan.domain.dataset.RegionalArtifact
import com.gecesars.atxplan.domain.dataset.RegionalArtifactAcquisition
import com.gecesars.atxplan.domain.dataset.RegionalArtifactResult
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalInventory
import com.gecesars.atxplan.domain.dataset.RegionalInventoryEntryFingerprint
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalJobArtifactOutcomeKind
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRunOutcome
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import com.gecesars.atxplan.domain.dataset.testMultiArtifactPlan
import com.gecesars.atxplan.domain.dataset.testProcessingRegionalJob
import com.gecesars.atxplan.domain.dataset.testRasterPlan
import com.gecesars.atxplan.domain.dataset.testRegionalJob
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRegionalJobRunnerTest {
    @Test
    fun `stale scheduler generation is rejected before dataset access`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository()
        val runner = runner(jobs, datasets)
        val stale = request(initial).copy(schedulerGeneration = initial.schedulerGeneration + 1)

        val outcome = runner.run(stale, onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Rejected)
        assertEquals("stale-scheduler-generation", (outcome as RegionalJobRunOutcome.Rejected).problem.code)
        assertTrue(datasets.acquireIndexes.isEmpty())
        assertTrue(datasets.findIndexes.isEmpty())
        assertEquals(initial, jobs.current)
    }

    @Test
    fun `runner executes artifacts sequentially and succeeds only with inventory outcomes`() = runTest {
        val plan = testMultiArtifactPlan()
        val initial = testRegionalJob(plan = plan)
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.SUCCEEDED, terminal.state)
        assertEquals(plan.artifacts.indices.toList(), datasets.acquireIndexes)
        assertEquals(List(plan.artifacts.size) { 1 }, terminal.artifactAttemptCounts)
        assertEquals(plan.artifacts.size, terminal.artifactOutcomes.size)
        terminal.artifactOutcomes.forEachIndexed { index, artifactOutcome ->
            val inventoryRecord = datasets.committed.getValue(index)
            assertEquals(index, artifactOutcome.artifactIndex)
            assertEquals(
                RegionalInventoryEntryFingerprint.calculate(inventoryRecord),
                artifactOutcome.inventoryEntrySha256,
            )
        }
        assertTrue(jobs.history.any { it.state == RegionalJobState.RUNNING_PROCESS })
        assertEquals(plan.artifacts.sumOf { 64L }, terminal.networkBytesTransferred)
    }

    @Test
    fun `provider permit is durable before transport and remaining retry budget is clamped`() = runTest {
        val plan = testRasterPlan()
        val pending = testRegionalJob(plan = plan)
        val firstAttempt = pending.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_010L,
            schedulerIdentity = TEST_SCHEDULER_IDENTITY,
            artifactAttemptCounts = listOf(1),
        )
        val running = firstAttempt.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_020L,
            artifactAttemptCounts = listOf(2),
        )
        val jobs = InMemoryRegionalJobRepository(running)
        var attemptsObservedAtProvider = -1
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
            providerStarted = {
                attemptsObservedAtProvider = jobs.current.artifactAttemptCounts.single()
            },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(running), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        assertEquals(RegionalJobState.SUCCEEDED, (outcome as RegionalJobRunOutcome.Terminal).record.state)
        assertEquals(3, attemptsObservedAtProvider)
        assertEquals(listOf(1), datasets.maximumAttemptAllowances)
        assertEquals(listOf(3), jobs.current.artifactAttemptCounts)
    }

    @Test
    fun `exhausted provider budget still permits complete local recovery`() = runTest {
        val plan = testRasterPlan()
        var running = testRegionalJob(plan = plan).transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_010L,
            schedulerIdentity = TEST_SCHEDULER_IDENTITY,
            artifactAttemptCounts = listOf(1),
        )
        repeat(2) { offset ->
            running = running.transitionTo(
                nextState = RegionalJobState.RUNNING_DOWNLOAD,
                nowEpochMillis = 1_020L + offset,
                artifactAttemptCounts = listOf(running.artifactAttemptCounts.single() + 1),
            )
        }
        val jobs = InMemoryRegionalJobRepository(running)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 0) },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(running), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        assertEquals(RegionalJobState.SUCCEEDED, (outcome as RegionalJobRunOutcome.Terminal).record.state)
        assertEquals(listOf(0), datasets.maximumAttemptAllowances)
        assertEquals(listOf(3), jobs.current.artifactAttemptCounts)
    }

    @Test
    fun `inventory commit is adopted after an outcome CAS failure without a second transfer`() = runTest {
        val plan = testRasterPlan()
        val initial = testRegionalJob(plan = plan)
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
        )
        val runner = runner(jobs, datasets)
        jobs.failNextUpdateWhen = { _, updated -> updated.state == RegionalJobState.RUNNING_PROCESS }

        val interrupted = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(interrupted is RegionalJobRunOutcome.ReconciliationRequired)
        assertEquals(
            "inventory-outcome-cas-conflict",
            (interrupted as RegionalJobRunOutcome.ReconciliationRequired).problem.code,
        )
        assertEquals(listOf(0), datasets.acquireIndexes)
        assertTrue(datasets.committed.containsKey(0))

        val recovered = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(recovered is RegionalJobRunOutcome.Terminal)
        assertEquals(RegionalJobState.SUCCEEDED, (recovered as RegionalJobRunOutcome.Terminal).record.state)
        assertEquals(listOf(0), datasets.acquireIndexes)
        assertEquals(listOf(0, 0), datasets.findIndexes)
    }

    @Test
    fun `durable cancellation wins before provider IO`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val canceledIntent = initial.requestCancellation(nowEpochMillis = 1_010L)
        val jobs = InMemoryRegionalJobRepository(canceledIntent)
        val datasets = FakeArtifactDatasetRepository()
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.CANCELED, terminal.state)
        assertTrue(terminal.cancelRequested)
        assertTrue(datasets.acquireIndexes.isEmpty())
        assertTrue(datasets.findIndexes.isEmpty())
    }

    @Test
    fun `durable cancellation arriving during transfer is observed before inventory publication`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
            providerStarted = {
                jobs.requestCancellation()
            },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        assertEquals(RegionalJobState.CANCELED, (outcome as RegionalJobRunOutcome.Terminal).record.state)
        assertTrue(datasets.committed.isEmpty())
    }

    @Test
    fun `granted permit may remain unused when cancellation wins before provider IO`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        var providerStarted = false
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
            afterPermitGranted = {
                jobs.requestCancellation()
            },
            providerStarted = {
                providerStarted = true
            },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.CANCELED, terminal.state)
        assertEquals(listOf(1), terminal.artifactAttemptCounts)
        assertEquals(0L, terminal.networkBytesTransferred)
        assertFalse(providerStarted)
        assertTrue(datasets.committed.isEmpty())
    }

    @Test
    fun `accepted network bytes are persisted before cancellation becomes terminal`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
            providerStarted = {
                jobs.requestCancellation()
            },
            acceptedBytesBeforeCancellation = 37L,
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.CANCELED, terminal.state)
        assertEquals(37L, terminal.networkBytesTransferred)
        assertEquals(listOf(1), terminal.artifactAttemptCounts)
        assertTrue(datasets.committed.isEmpty())
    }

    @Test
    fun `retry bytes survive cancellation after the next permit but before its provider IO`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        var grantedPermits = 0
        var providerStarts = 0
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 2) },
            afterPermitGranted = {
                grantedPermits += 1
                if (grantedPermits == 2) jobs.requestCancellation()
            },
            providerStarted = {
                providerStarts += 1
            },
            acceptedBytesBeforeCancellation = 37L,
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.CANCELED, terminal.state)
        assertEquals(2, grantedPermits)
        assertEquals(1, providerStarts)
        assertEquals(listOf(2), terminal.artifactAttemptCounts)
        assertEquals(37L, terminal.networkBytesTransferred)
        assertTrue(datasets.committed.isEmpty())
    }

    @Test
    fun `coroutine cancellation propagates without publishing a failed job`() = runTest {
        val initial = testRegionalJob(plan = testRasterPlan())
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(acquisitionFailure = CancellationException("Injected stop."))
        val runner = runner(jobs, datasets)

        var propagated = false
        try {
            runner.run(request(initial), onProgress = {}, isStopped = { false })
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertFalse(jobs.current.state.isTerminal)
    }

    @Test
    fun `system stop pauses a queued job without claiming user cancellation`() = runTest {
        val pending = testRegionalJob(plan = testRasterPlan())
        val queued = pending.transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = TEST_SCHEDULER_IDENTITY,
        )
        val jobs = InMemoryRegionalJobRepository(queued)
        val datasets = FakeArtifactDatasetRepository()
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(queued), onProgress = {}, isStopped = { true })

        assertTrue(outcome is RegionalJobRunOutcome.ReconciliationRequired)
        assertEquals("scheduler-stopped", (outcome as RegionalJobRunOutcome.ReconciliationRequired).problem.code)
        assertEquals(RegionalJobState.PAUSED_CONSTRAINT, jobs.current.state)
        assertFalse(jobs.current.cancelRequested)
        assertTrue(datasets.acquireIndexes.isEmpty())
    }

    @Test
    fun `optional immutable NoData becomes an explicit successful outcome`() = runTest {
        val plan = testRasterPlan()
        assertTrue(plan.artifacts.single().source.optionalWhenNotPublished)
        val initial = testRegionalJob(plan = plan)
        val jobs = InMemoryRegionalJobRepository(initial)
        val datasets = FakeArtifactDatasetRepository(
            acquisitionFactory = { artifact, index -> notFoundAcquisition(artifact, index) },
        )
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(initial), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.Terminal)
        val terminal = (outcome as RegionalJobRunOutcome.Terminal).record
        assertEquals(RegionalJobState.SUCCEEDED, terminal.state)
        assertEquals(RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND, terminal.artifactOutcomes.single().kind)
    }

    @Test
    fun `inventory outcome resolver uses historical evidence and rejects a changed exact fingerprint`() = runTest {
        val plan = testRasterPlan()
        val initial = testRegionalJob(plan = plan)
        val committed = readyAcquisition(plan.artifacts.single(), 0, providerAttempts = 0)
            .committedInventoryRecord
        val datasets = FakeArtifactDatasetRepository(reusableCommitted = false).apply {
            this.committed[0] = committed
        }
        val resolver = RegionalJobInventoryOutcomeResolver(datasets)
        val resolved = resolver.resolve(initial, 0)
        assertNotNull(resolved)
        assertTrue(datasets.findIndexes.isEmpty())
        assertEquals(listOf(0), datasets.evidenceIndexes)
        val processing = initial.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_010L,
            schedulerIdentity = TEST_SCHEDULER_IDENTITY,
        ).transitionTo(
            nextState = RegionalJobState.RUNNING_PROCESS,
            nowEpochMillis = 1_020L,
            checkpointReferences = listOf(
                com.gecesars.atxplan.domain.dataset.RegionalJobCheckpointReferenceV1(
                    artifactIndex = 0,
                    kind = com.gecesars.atxplan.domain.dataset.RegionalJobCheckpointKind.VERIFIED_RAW,
                    relativePath = committed.relativePath,
                    bytes = committed.bytes!!,
                    sha256 = committed.sha256,
                ),
            ),
        )
        val succeeded = processing.transitionTo(
            nextState = RegionalJobState.SUCCEEDED,
            nowEpochMillis = 1_030L,
            currentArtifactIndex = 1,
            artifactOutcomes = listOf(resolved!!),
        )

        val valid = resolver.createValidator(listOf(succeeded))
        assertTrue(valid.isValid(succeeded, succeeded.canonicalPlan.artifacts.single(), resolved))

        datasets.committed[0] = committed.copy(checkedAt = "2026-08-28T12:00:01.000Z")
        val changed = resolver.createValidator(listOf(succeeded))
        assertFalse(changed.isValid(succeeded, succeeded.canonicalPlan.artifacts.single(), resolved))
    }

    @Test
    fun `stale verified checkpoint cannot authorize a changed inventory outcome`() = runTest {
        val plan = testRasterPlan()
        val processing = testProcessingRegionalJob(plan)
        val jobs = InMemoryRegionalJobRepository(processing)
        val committed = readyAcquisition(plan.artifacts.single(), 0, providerAttempts = 0)
            .committedInventoryRecord
        val datasets = FakeArtifactDatasetRepository().apply { this.committed[0] = committed }
        val runner = runner(jobs, datasets)

        val outcome = runner.run(request(processing), onProgress = {}, isStopped = { false })

        assertTrue(outcome is RegionalJobRunOutcome.ReconciliationRequired)
        assertFalse(jobs.current.state.isTerminal)
        assertTrue(datasets.acquireIndexes.isEmpty())
    }

    private fun runner(
        jobs: InMemoryRegionalJobRepository,
        datasets: FakeArtifactDatasetRepository,
    ): DefaultRegionalJobRunner {
        val ticks = AtomicLong(2_000L)
        return DefaultRegionalJobRunner(
            jobRepository = jobs,
            datasetRepository = datasets,
            clock = ticks::getAndIncrement,
        )
    }

    private fun request(record: RegionalJobRecordV1) = RegionalJobExecutionRequestV1(
        jobId = record.jobId,
        planFingerprintSha256 = record.planFingerprintSha256,
        schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
        schedulerGeneration = record.schedulerGeneration,
        schedulerIdentity = TEST_SCHEDULER_IDENTITY,
    )

    private companion object {
        const val TEST_SCHEDULER_IDENTITY = "work-regional-1"
    }
}

private class InMemoryRegionalJobRepository(
    initial: RegionalJobRecordV1,
) : RegionalJobRepository {
    var current: RegionalJobRecordV1 = initial
        private set
    val history = mutableListOf(initial)
    var failNextUpdateWhen: ((RegionalJobRecordV1, RegionalJobRecordV1) -> Boolean)? = null

    suspend fun requestCancellation() {
        update(current.jobId, current.revision) { record ->
            record.requestCancellation(record.updatedAtEpochMillis + 1L)
        }
    }

    override suspend fun loadSnapshot(): RegionalJobStoreSnapshot = RegionalJobStoreSnapshot(
        jobs = listOf(current),
        unreadableJobIds = emptyList(),
    )

    override suspend fun get(jobId: String): RegionalJobRecordV1? = current.takeIf { it.jobId == jobId }

    override suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1 {
        throw RegionalJobConflictException("The in-memory runner fixture already has a job.")
    }

    override suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1 {
        if (jobId != current.jobId || expectedRevision != current.revision) {
            throw RegionalJobConflictException("Injected compare-and-set conflict.")
        }
        val updated = transform(current)
        val fail = failNextUpdateWhen
        if (fail != null && fail(current, updated)) {
            failNextUpdateWhen = null
            throw RegionalJobStorageException("Injected post-inventory job write failure.")
        }
        current = updated
        history += updated
        return updated
    }
}

private class FakeArtifactDatasetRepository(
    private val acquisitionFactory: (RegionalArtifact, Int) -> RegionalArtifactAcquisition =
        { artifact, index -> readyAcquisition(artifact, index, providerAttempts = 1) },
    private val afterPermitGranted: suspend () -> Unit = {},
    private val providerStarted: suspend () -> Unit = {},
    private val acceptedBytesBeforeCancellation: Long = 0L,
    private val acquisitionFailure: Throwable? = null,
    private val reusableCommitted: Boolean = true,
) : RegionalDatasetRepository {
    val acquireIndexes = mutableListOf<Int>()
    val findIndexes = mutableListOf<Int>()
    val evidenceIndexes = mutableListOf<Int>()
    val maximumAttemptAllowances = mutableListOf<Int>()
    val committed = mutableMapOf<Int, RegionalInventoryRecord>()

    override suspend fun acquire(
        plan: RegionalDownloadPlan,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalDownloadResult = RegionalDownloadResult(
        plan.artifacts.mapIndexed { index, _ ->
            acquireArtifact(plan, index, onProgress = onProgress, isCancelled = isCancelled).result
        },
    )

    override suspend fun acquireArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        maximumProviderAttempts: Int?,
        beforeProviderAttempt: suspend (attemptNumber: Int) -> Boolean,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: suspend () -> Boolean,
    ): RegionalArtifactAcquisition {
        acquisitionFailure?.let { throw it }
        acquireIndexes += artifactIndex
        maximumAttemptAllowances += maximumProviderAttempts ?: -1
        val configured = acquisitionFactory(plan.artifacts[artifactIndex], artifactIndex)
        require(maximumProviderAttempts == null || configured.providerAttempts <= maximumProviderAttempts)
        repeat(configured.providerAttempts) { attemptIndex ->
            if (isCancelled() || !beforeProviderAttempt(attemptIndex + 1)) {
                return canceledAcquisition(plan.artifacts[artifactIndex], providerAttempts = attemptIndex)
            }
            afterPermitGranted()
            if (isCancelled()) {
                return canceledAcquisition(
                    artifact = plan.artifacts[artifactIndex],
                    providerAttempts = attemptIndex,
                    networkBytesTransferred = acceptedBytesBeforeCancellation,
                )
            }
            providerStarted()
            if (isCancelled()) {
                return canceledAcquisition(
                    artifact = plan.artifacts[artifactIndex],
                    providerAttempts = attemptIndex + 1,
                    networkBytesTransferred = acceptedBytesBeforeCancellation,
                )
            }
        }
        committed[artifactIndex] = configured.committedInventoryRecord
        return configured
    }

    override suspend fun findCommittedArtifact(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        minimumAcquiredAtEpochMillis: Long?,
    ): RegionalInventoryRecord? {
        findIndexes += artifactIndex
        return committed[artifactIndex].takeIf { reusableCommitted }
    }

    override suspend fun findCommittedArtifactEvidence(
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
    ): RegionalInventoryRecord? {
        evidenceIndexes += artifactIndex
        return committed[artifactIndex]
    }

    override suspend fun loadInventory(): RegionalInventory = RegionalInventory(
        artifacts = committed.values.associateBy(RegionalInventoryRecord::relativePath),
    )
}

private fun readyAcquisition(
    artifact: RegionalArtifact,
    index: Int,
    providerAttempts: Int,
): RegionalArtifactAcquisition {
    val bytes = 128L + index
    val sha256 = (index + 1).toString(16).single().toString().repeat(64)
    val result = RegionalArtifactResult(
        artifact = artifact,
        status = RegionalTransferStatus.READY,
        effectiveUrl = artifact.url,
        acquiredAt = "2026-08-28T12:00:00.000Z",
        bytes = bytes,
        sha256 = sha256,
        notes = "Verified by the runner fixture.",
    )
    return RegionalArtifactAcquisition(
        result = result,
        committedInventoryRecord = inventoryRecord(result, RegionalProcessingState.READY),
        providerAttempts = providerAttempts,
        networkBytesTransferred = if (providerAttempts == 0) 0L else 64L,
    )
}

private fun notFoundAcquisition(
    artifact: RegionalArtifact,
    index: Int,
): RegionalArtifactAcquisition {
    val result = RegionalArtifactResult(
        artifact = artifact,
        status = RegionalTransferStatus.NOT_FOUND,
        notes = "The optional fixture is not published.",
    )
    return RegionalArtifactAcquisition(
        result = result,
        committedInventoryRecord = inventoryRecord(result, RegionalProcessingState.PENDING),
        providerAttempts = 1,
        networkBytesTransferred = 0L,
    )
}

private fun canceledAcquisition(
    artifact: RegionalArtifact,
    providerAttempts: Int,
    networkBytesTransferred: Long = 0L,
): RegionalArtifactAcquisition {
    val result = RegionalArtifactResult(
        artifact = artifact,
        status = RegionalTransferStatus.CANCELLED,
        notes = "The fixture provider permit was denied.",
    )
    return RegionalArtifactAcquisition(
        result = result,
        committedInventoryRecord = inventoryRecord(result, RegionalProcessingState.PENDING),
        providerAttempts = providerAttempts,
        networkBytesTransferred = networkBytesTransferred,
    )
}

private fun inventoryRecord(
    result: RegionalArtifactResult,
    processingState: RegionalProcessingState,
): RegionalInventoryRecord = RegionalInventoryRecord(
    datasetId = result.artifact.source.datasetId,
    relativePath = result.artifact.relativePath,
    requestedUrl = result.requestedUrl,
    effectiveUrl = result.effectiveUrl,
    routeId = result.routeId,
    routePolicyVersion = result.routePolicyVersion,
    acquiredAt = result.acquiredAt,
    sourceSnapshot = result.sourceSnapshot,
    status = result.status,
    bytes = result.bytes,
    sha256 = result.sha256,
    etag = result.etag,
    lastModified = result.lastModified,
    checkedAt = "2026-08-28T12:00:00.000Z",
    bounds = result.artifact.requestBounds,
    processingState = processingState,
    processedOutput = result.processedOutput,
    notes = result.notes,
    error = result.error,
)
