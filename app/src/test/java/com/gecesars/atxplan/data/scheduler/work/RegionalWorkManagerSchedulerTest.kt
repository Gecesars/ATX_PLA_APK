package com.gecesars.atxplan.data.scheduler.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.gecesars.atxplan.data.dataset.RegionalJobConflictException
import com.gecesars.atxplan.data.dataset.RegionalJobRepository
import com.gecesars.atxplan.data.dataset.RegionalJobStoreSnapshot
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalScheduledJobState
import com.gecesars.atxplan.domain.dataset.RegionalSchedulerSnapshotAvailability
import com.gecesars.atxplan.domain.dataset.testRegionalJob
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalWorkManagerSchedulerTest {
    @Test
    fun `enqueue is gated outside API 23 through 33 without touching WorkManager`() = runTest {
        listOf(22, 34).forEach { api ->
            val record = testRegionalJob()
            val gateway = FakeWorkManagerGateway()
            val scheduler = scheduler(record, gateway, sdkInt = api)

            val outcome = assertType<RegionalWorkScheduleOutcome.Unsupported>(scheduler.enqueue(record))

            assertEquals(api, outcome.sdkInt)
            assertEquals(0, gateway.enqueueCalls)
        }
    }

    @Test
    fun `enqueue fails closed when foreground notification visibility is unavailable`() = runTest {
        val record = testRegionalJob()
        val gateway = FakeWorkManagerGateway()
        val scheduler = scheduler(record, gateway, notificationVisible = false)

        assertType<RegionalWorkScheduleOutcome.NotificationUnavailable>(scheduler.enqueue(record))
        assertEquals(0, gateway.enqueueCalls)
    }

    @Test
    fun `enqueue requires the exact passed durable revision before scheduling`() = runTest {
        val passed = testRegionalJob()
        val current = passed.requestCancellation(1_001L)
        val gateway = FakeWorkManagerGateway()
        val scheduler = scheduler(current, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Stale>(scheduler.enqueue(passed))

        assertNull(outcome.inactiveWorkRequestId)
        assertEquals(0, gateway.enqueueCalls)
    }

    @Test
    fun `enqueue retains KEEP request and acknowledges its deterministic UUID`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway()
        val scheduler = scheduler(repository, gateway)
        val expectedInput = RegionalWorkInputV1(
            jobId = record.jobId,
            planFingerprintSha256 = record.planFingerprintSha256,
            schedulerGeneration = record.schedulerGeneration,
        )
        val expectedId = RegionalWorkContractV1.deterministicWorkId(expectedInput)

        val outcome = assertType<RegionalWorkScheduleOutcome.Scheduled>(scheduler.enqueue(record))

        assertEquals(ExistingWorkPolicy.KEEP, gateway.lastPolicy)
        assertEquals(RegionalWorkContractV1.uniqueWorkName(expectedInput), gateway.lastUniqueName)
        assertEquals(expectedId, gateway.lastRequest?.id)
        assertEquals(expectedId, outcome.workRequestId)
        assertFalse(outcome.workerAdvancedBeforeAcknowledgement)
        assertEquals(RegionalJobState.QUEUED, outcome.record.state)
        assertEquals(expectedId.toString(), outcome.record.schedulerIdentity)
        assertEquals(outcome.record, repository.current)
    }

    @Test
    fun `same exact worker UUID may advance before queued acknowledgement`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway()
        gateway.afterEnqueue = { request ->
            repository.current = record.transitionTo(
                nextState = RegionalJobState.RUNNING_DOWNLOAD,
                nowEpochMillis = 1_001L,
                schedulerIdentity = request.id.toString(),
            )
        }
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Scheduled>(scheduler.enqueue(record))

        assertTrue(outcome.workerAdvancedBeforeAcknowledgement)
        assertEquals(RegionalJobState.RUNNING_DOWNLOAD, outcome.record.state)
        assertEquals(outcome.workRequestId.toString(), outcome.record.schedulerIdentity)
        assertTrue(gateway.canceledIds.isEmpty())
    }

    @Test
    fun `cancellation winner after enqueue cancels only the exact physical UUID`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway()
        gateway.afterEnqueue = {
            repository.current = record.requestCancellation(1_001L)
        }
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Indeterminate>(scheduler.enqueue(record))

        assertEquals(RegionalWorkIndeterminateStage.EXACT_CANCELLATION, outcome.stage)
        assertEquals(listOf(UUID.fromString(outcome.schedulerIdentity)), gateway.canceledIds)
        assertEquals(1, gateway.cancelCalls)
        assertNull(gateway.canceledTag)
        assertNull(gateway.canceledUniqueName)
    }

    @Test
    fun `retention mismatch is indeterminate and never publishes a scheduler identity`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway(retainEnqueuedRequest = false)
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Indeterminate>(scheduler.enqueue(record))

        assertEquals(RegionalWorkIndeterminateStage.RETENTION_VERIFICATION, outcome.stage)
        assertEquals(record, repository.current)
        assertEquals(0, repository.updateCalls)
        assertEquals(0, gateway.cancelCalls)
    }

    @Test
    fun `retention with a coherent foreign plan identity is indeterminate`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway().apply {
            retainedEntryTransform = { entry ->
                val otherFingerprint = "b".repeat(64)
                entry.copy(
                    id = RegionalWorkContractV1.deterministicWorkId(
                        RegionalWorkInputV1(
                            jobId = record.jobId,
                            planFingerprintSha256 = otherFingerprint,
                            schedulerGeneration = record.schedulerGeneration,
                        ),
                    ),
                    tags = entry.tags -
                        RegionalWorkContractV1.planFingerprintTag(record.planFingerprintSha256) +
                        RegionalWorkContractV1.planFingerprintTag(otherFingerprint),
                )
            }
        }
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Indeterminate>(scheduler.enqueue(record))

        assertEquals(RegionalWorkIndeterminateStage.RETENTION_VERIFICATION, outcome.stage)
        assertEquals(record, repository.current)
        assertEquals(0, repository.updateCalls)
    }

    @Test
    fun `finished retained work never acknowledges an unchanged intent as queued`() = runTest {
        listOf(
            RegionalWorkManagerState.SUCCEEDED,
            RegionalWorkManagerState.FAILED,
            RegionalWorkManagerState.CANCELLED,
        ).forEach { finishedState ->
            val record = testRegionalJob()
            val repository = FakeJobRepository(record)
            val gateway = FakeWorkManagerGateway(retainedState = finishedState)
            val scheduler = scheduler(repository, gateway)

            val outcome = assertType<RegionalWorkScheduleOutcome.Indeterminate>(
                scheduler.enqueue(record),
            )

            assertEquals(RegionalWorkIndeterminateStage.RETAINED_WORK_FINISHED, outcome.stage)
            assertEquals(record, repository.current)
            assertEquals(0, repository.updateCalls)
        }
    }

    @Test
    fun `finished retained work adopts exact durable worker progress`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record)
        val gateway = FakeWorkManagerGateway(retainedState = RegionalWorkManagerState.SUCCEEDED)
        gateway.afterEnqueue = { request ->
            repository.current = record.transitionTo(
                nextState = RegionalJobState.RUNNING_DOWNLOAD,
                nowEpochMillis = 1_001L,
                schedulerIdentity = request.id.toString(),
            )
        }
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Scheduled>(scheduler.enqueue(record))

        assertTrue(outcome.workerAdvancedBeforeAcknowledgement)
        assertEquals(repository.current, outcome.record)
        assertEquals(RegionalJobState.RUNNING_DOWNLOAD, outcome.record.state)
        assertEquals(0, repository.updateCalls)
    }

    @Test
    fun `uncertain acknowledgement leaves an exact retained request for reconciliation`() = runTest {
        val record = testRegionalJob()
        val repository = FakeJobRepository(record).apply { failUpdates = true }
        val gateway = FakeWorkManagerGateway()
        val scheduler = scheduler(repository, gateway)

        val outcome = assertType<RegionalWorkScheduleOutcome.Indeterminate>(scheduler.enqueue(record))

        assertEquals(RegionalWorkIndeterminateStage.DURABLE_ACKNOWLEDGEMENT, outcome.stage)
        assertEquals(record, repository.current)
        assertEquals(0, gateway.cancelCalls)
    }

    @Test
    fun `snapshot strictly maps all WorkManager states`() = runTest {
        val states = RegionalWorkManagerState.entries
        val entries = states.mapIndexed { index, state -> validEntry(index, state) }
        val gateway = FakeWorkManagerGateway().apply { globalEntries = entries }
        val scheduler = scheduler(testRegionalJob(), gateway)

        val snapshot = scheduler.snapshot()

        assertEquals(RegionalSchedulerSnapshotAvailability.COMPLETE, snapshot.availability)
        assertEquals(states.size, snapshot.jobs.size)
        assertEquals(
            mapOf(
                RegionalWorkManagerState.ENQUEUED to RegionalScheduledJobState.PENDING,
                RegionalWorkManagerState.BLOCKED to RegionalScheduledJobState.PENDING,
                RegionalWorkManagerState.RUNNING to RegionalScheduledJobState.RUNNING,
                RegionalWorkManagerState.SUCCEEDED to RegionalScheduledJobState.FINISHED,
                RegionalWorkManagerState.FAILED to RegionalScheduledJobState.FINISHED,
                RegionalWorkManagerState.CANCELLED to RegionalScheduledJobState.FINISHED,
            ),
            entries.zip(snapshot.jobs)
                .associate { (entry, job) -> entry.state to job.state },
        )
    }

    @Test
    fun `snapshot is unavailable for malformed oversized or failed queries`() = runTest {
        val malformedGateway = FakeWorkManagerGateway().apply {
            globalEntries = listOf(validEntry(0).copy(tags = setOf(RegionalWorkContractV1.GLOBAL_TAG)))
        }
        assertEquals(
            RegionalSchedulerSnapshotAvailability.UNAVAILABLE,
            scheduler(testRegionalJob(), malformedGateway).snapshot().availability,
        )

        val oversizedGateway = FakeWorkManagerGateway().apply {
            globalEntries = List(129) { validEntry(0) }
        }
        assertEquals(
            RegionalSchedulerSnapshotAvailability.UNAVAILABLE,
            scheduler(testRegionalJob(), oversizedGateway).snapshot().availability,
        )

        val failedGateway = FakeWorkManagerGateway().apply { failGlobalQuery = true }
        assertEquals(
            RegionalSchedulerSnapshotAvailability.UNAVAILABLE,
            scheduler(testRegionalJob(), failedGateway).snapshot().availability,
        )
    }

    @Test
    fun `inspect and cancel operate on one strict UUID only`() = runTest {
        val entry = validEntry(0)
        val gateway = FakeWorkManagerGateway().apply {
            entriesById[entry.id] = entry
        }
        val scheduler = scheduler(testRegionalJob(), gateway)

        val inspected = assertType<RegionalWorkInspectionOutcome.Present>(
            scheduler.inspectExact(entry.id.toString()),
        )
        assertEquals(entry.id.toString(), inspected.scheduledJob.schedulerIdentity)

        assertEquals(
            RegionalWorkExactCancellationOutcome.CancellationRequested,
            scheduler.cancelExact(entry.id.toString()),
        )
        assertEquals(listOf(entry.id), gateway.canceledIds)

        val alreadyCanceled = validEntry(1, RegionalWorkManagerState.CANCELLED)
        gateway.entriesById[alreadyCanceled.id] = alreadyCanceled
        assertEquals(
            RegionalWorkExactCancellationOutcome.CancellationRequested,
            scheduler.cancelExact(alreadyCanceled.id.toString()),
        )
        assertEquals(1, gateway.cancelCalls)

        val alreadyFinished = validEntry(2, RegionalWorkManagerState.SUCCEEDED)
        gateway.entriesById[alreadyFinished.id] = alreadyFinished
        assertEquals(
            RegionalWorkExactCancellationOutcome.AlreadyFinished,
            scheduler.cancelExact(alreadyFinished.id.toString()),
        )
        assertEquals(1, gateway.cancelCalls)

        val absentWorkId = validEntry(3).id
        assertEquals(
            RegionalWorkExactCancellationOutcome.Absent,
            scheduler.cancelExact(absentWorkId.toString()),
        )
        assertEquals(
            RegionalWorkExactCancellationOutcome.Indeterminate,
            scheduler.cancelExact(UUID.randomUUID().toString()),
        )
        assertEquals(1, gateway.cancelCalls)
    }

    private fun scheduler(
        initial: RegionalJobRecordV1,
        gateway: FakeWorkManagerGateway,
        sdkInt: Int = 33,
        notificationVisible: Boolean = true,
    ): RegionalWorkManagerScheduler = scheduler(
        repository = FakeJobRepository(initial),
        gateway = gateway,
        sdkInt = sdkInt,
        notificationVisible = notificationVisible,
    )

    private fun scheduler(
        repository: FakeJobRepository,
        gateway: FakeWorkManagerGateway,
        sdkInt: Int = 33,
        notificationVisible: Boolean = true,
    ): RegionalWorkManagerScheduler = RegionalWorkManagerScheduler(
        jobRepository = repository,
        gateway = gateway,
        canPostForegroundNotification = { notificationVisible },
        sdkInt = { sdkInt },
        nowEpochMillis = { 1_010L },
    )

    private fun validEntry(
        index: Int,
        state: RegionalWorkManagerState = RegionalWorkManagerState.ENQUEUED,
    ): RegionalWorkManagerEntry {
        val jobId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        val input = RegionalWorkInputV1(
            jobId = jobId,
            planFingerprintSha256 = "a".repeat(64),
            schedulerGeneration = index,
        )
        return RegionalWorkManagerEntry(
            id = RegionalWorkContractV1.deterministicWorkId(input),
            tags = RegionalWorkContractV1.expectedWorkInfoTags(
                RegionalWorkTagIdentityV1(
                    jobId = jobId,
                    planFingerprintSha256 = input.planFingerprintSha256,
                    schedulerGeneration = index,
                ),
            ),
            state = state,
        )
    }

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("Expected ${T::class.java.simpleName}, but was ${value?.javaClass?.simpleName}.", value is T)
        return value as T
    }
}

private class FakeJobRepository(
    var current: RegionalJobRecordV1,
) : RegionalJobRepository {
    var updateCalls: Int = 0
    var failUpdates: Boolean = false

    override suspend fun loadSnapshot(): RegionalJobStoreSnapshot = RegionalJobStoreSnapshot(
        jobs = listOf(current),
        unreadableJobIds = emptyList(),
    )

    override suspend fun get(jobId: String): RegionalJobRecordV1? = current.takeIf { it.jobId == jobId }

    override suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1 {
        current = record
        return record
    }

    override suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1 {
        updateCalls += 1
        if (current.jobId != jobId || current.revision != expectedRevision) {
            throw RegionalJobConflictException("The test regional job changed.")
        }
        if (failUpdates) throw IllegalStateException("The test durable update failed.")
        return transform(current).also { current = it }
    }
}

private class FakeWorkManagerGateway(
    private val retainEnqueuedRequest: Boolean = true,
    private val retainedState: RegionalWorkManagerState = RegionalWorkManagerState.ENQUEUED,
) : RegionalWorkManagerGateway {
    var enqueueCalls: Int = 0
    var cancelCalls: Int = 0
    var lastPolicy: ExistingWorkPolicy? = null
    var lastUniqueName: String? = null
    var lastRequest: OneTimeWorkRequest? = null
    var afterEnqueue: (OneTimeWorkRequest) -> Unit = {}
    var retainedEntryTransform: (RegionalWorkManagerEntry) -> RegionalWorkManagerEntry = { it }
    var globalEntries: List<RegionalWorkManagerEntry> = emptyList()
    var failGlobalQuery: Boolean = false
    val entriesById = linkedMapOf<UUID, RegionalWorkManagerEntry>()
    val canceledIds = mutableListOf<UUID>()
    var canceledTag: String? = null
    var canceledUniqueName: String? = null

    private val entriesByUniqueName = linkedMapOf<String, List<RegionalWorkManagerEntry>>()

    override suspend fun enqueueUnique(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueueCalls += 1
        lastPolicy = existingWorkPolicy
        lastUniqueName = uniqueWorkName
        lastRequest = request
        if (retainEnqueuedRequest) {
            val entry = retainedEntryTransform(
                RegionalWorkManagerEntry(
                    id = request.id,
                    tags = request.tags,
                    state = retainedState,
                ),
            )
            entriesByUniqueName[uniqueWorkName] = listOf(entry)
            entriesById[entry.id] = entry
        }
        afterEnqueue(request)
    }

    override suspend fun listForUniqueWork(uniqueWorkName: String): List<RegionalWorkManagerEntry> =
        entriesByUniqueName[uniqueWorkName].orEmpty()

    override suspend fun listByGlobalTag(globalTag: String): List<RegionalWorkManagerEntry> {
        if (failGlobalQuery) throw IllegalStateException("The test WorkManager query failed.")
        return globalEntries
    }

    override suspend fun getById(workId: UUID): RegionalWorkManagerEntry? = entriesById[workId]

    override suspend fun cancelExact(workId: UUID) {
        cancelCalls += 1
        canceledIds += workId
        entriesById[workId] = entriesById[workId]?.copy(state = RegionalWorkManagerState.CANCELLED)
            ?: return
    }
}
