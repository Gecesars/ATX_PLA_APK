package com.gecesars.atxplan.data.scheduler.work

import com.gecesars.atxplan.data.dataset.RegionalJobConflictException
import com.gecesars.atxplan.data.dataset.RegionalJobRepository
import com.gecesars.atxplan.data.dataset.RegionalJobStorageException
import com.gecesars.atxplan.data.dataset.RegionalJobStoreSnapshot
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.testRegionalJob
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalJobCancellationControllerTest {
    @Test
    fun `exact owner persists intent before cancel and awaits terminal evidence`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob())
        var canceledWorkId: UUID? = null
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway { workId ->
                assertTrue(repository.record.cancelRequested)
                assertFalse(repository.record.state.isTerminal)
                canceledWorkId = workId
                RegionalJobExactCancelResult.Confirmed
            },
            nowEpochMillis = { 2_000L },
        )

        val result = controller.cancel(cancellationRequest(repository.record))

        assertTrue(result is RegionalJobCancellationResult.CancellationPending)
        assertEquals(WORK_ID, canceledWorkId)
        assertEquals(RegionalJobState.QUEUED, repository.record.state)
        assertTrue(repository.record.cancelRequested)
        assertEquals(listOf(false), repository.preUpdateCancelIntent)
    }

    @Test
    fun `enqueue pending owner is matched only by its deterministic physical UUID`() = runTest {
        val pending = testRegionalJob()
        val deterministicWorkId = RegionalWorkContractV1.deterministicWorkId(
            RegionalWorkInputV1(
                jobId = pending.jobId,
                planFingerprintSha256 = pending.planFingerprintSha256,
                schedulerGeneration = pending.schedulerGeneration,
            ),
        )
        val repository = TestRegionalJobRepository(pending)
        var canceledWorkId: UUID? = null
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway { workId ->
                canceledWorkId = workId
                RegionalJobExactCancelResult.Confirmed
            },
            nowEpochMillis = { 2_000L },
        )
        val request = RegionalJobCancellationRequestV1(
            RegionalJobExecutionRequestV1(
                jobId = pending.jobId,
                planFingerprintSha256 = pending.planFingerprintSha256,
                schedulerKind = pending.schedulerKind,
                schedulerGeneration = pending.schedulerGeneration,
                schedulerIdentity = deterministicWorkId.toString(),
            ),
        )

        val result = controller.cancel(request)

        assertTrue(result is RegionalJobCancellationResult.CancellationPending)
        assertEquals(deterministicWorkId, canceledWorkId)
        assertEquals(RegionalJobState.ENQUEUE_PENDING, repository.record.state)
    }

    @Test
    fun `stale generation performs no durable write and never calls scheduler`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob())
        var gatewayCalls = 0
        val staleRequest = cancellationRequest(repository.record).let { request ->
            RegionalJobCancellationRequestV1(
                request.executionRequest.copy(schedulerGeneration = request.executionRequest.schedulerGeneration + 1),
            )
        }
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway {
                gatewayCalls += 1
                RegionalJobExactCancelResult.Confirmed
            },
        )

        val result = controller.cancel(staleRequest)

        assertTrue(result is RegionalJobCancellationResult.Stale)
        assertEquals(0, gatewayCalls)
        assertEquals(0, repository.updateCalls)
        assertFalse(repository.record.cancelRequested)
    }

    @Test
    fun `notification identity is derived from the exact physical work UUID`() {
        val physicalWorkId = WORK_ID.toString()
        val differentPhysicalWorkId = "b279589f-8d2a-820e-9868-06140ecb2b88"

        assertEquals(stableNotificationId(physicalWorkId), stableNotificationId(physicalWorkId))
        assertNotEquals(stableNotificationId(physicalWorkId), stableNotificationId(differentPhysicalWorkId))
        assertTrue(stableNotificationId(physicalWorkId) > 0)
    }

    @Test
    fun `indeterminate scheduler cancel retains confirmed durable intent`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob())
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway { RegionalJobExactCancelResult.Indeterminate },
            nowEpochMillis = { 2_000L },
        )

        val result = controller.cancel(cancellationRequest(repository.record))

        assertTrue(result is RegionalJobCancellationResult.ReconciliationRequired)
        result as RegionalJobCancellationResult.ReconciliationRequired
        assertEquals(RegionalJobCancellationUncertainStage.SCHEDULER_CANCEL, result.uncertainStage)
        assertTrue(result.durableIntentConfirmed)
        assertFalse(result.schedulerCancellationConfirmed)
        assertTrue(repository.record.cancelRequested)
        assertEquals(RegionalJobState.QUEUED, repository.record.state)
    }

    @Test
    fun `indeterminate post cancel observation retains confirmed intent`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob()).apply {
            failGetCall = 2
        }
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway { RegionalJobExactCancelResult.Confirmed },
            nowEpochMillis = { 2_000L },
        )

        val result = controller.cancel(cancellationRequest(repository.record))

        assertTrue(result is RegionalJobCancellationResult.ReconciliationRequired)
        result as RegionalJobCancellationResult.ReconciliationRequired
        assertEquals(RegionalJobCancellationUncertainStage.POST_CANCEL_OBSERVATION, result.uncertainStage)
        assertTrue(result.durableIntentConfirmed)
        assertTrue(result.schedulerCancellationConfirmed)
        assertTrue(repository.record.cancelRequested)
        assertEquals(RegionalJobState.QUEUED, repository.record.state)
    }

    @Test
    fun `runner terminalization observed after scheduler cancel is returned`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob())
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway {
                repository.replaceExternally(
                    repository.record.transitionTo(
                        nextState = RegionalJobState.CANCELED,
                        nowEpochMillis = 2_100L,
                    ),
                )
                RegionalJobExactCancelResult.Confirmed
            },
            nowEpochMillis = { 2_000L },
        )

        val result = controller.cancel(cancellationRequest(repository.record))

        assertTrue(result is RegionalJobCancellationResult.Canceled)
        assertEquals(RegionalJobState.CANCELED, repository.record.state)
        assertTrue(repository.record.cancelRequested)
    }

    @Test
    fun `generation change during exact cancel is not finalized as newer work`() = runTest {
        val repository = TestRegionalJobRepository(queuedJob())
        val originalRequest = cancellationRequest(repository.record)
        var canceledWorkId: UUID? = null
        val controller = RegionalJobCancellationController(
            repository = repository,
            cancelGateway = RegionalJobExactCancelGateway { workId ->
                canceledWorkId = workId
                repository.replaceExternally(repository.record.prepareForReenqueue(2_100L))
                RegionalJobExactCancelResult.Confirmed
            },
            nowEpochMillis = { 2_000L },
        )

        val result = controller.cancel(originalRequest)

        assertTrue(result is RegionalJobCancellationResult.Stale)
        assertEquals(WORK_ID, canceledWorkId)
        assertEquals(1, repository.record.schedulerGeneration)
        assertEquals(null, repository.record.schedulerIdentity)
        assertEquals(RegionalJobState.ENQUEUE_PENDING, repository.record.state)
        assertTrue(repository.record.cancelRequested)
    }

    private fun queuedJob(): RegionalJobRecordV1 = testRegionalJob().transitionTo(
        nextState = RegionalJobState.QUEUED,
        nowEpochMillis = 1_010L,
        schedulerIdentity = WORK_ID.toString(),
    )

    private fun cancellationRequest(record: RegionalJobRecordV1) = RegionalJobCancellationRequestV1(
        RegionalJobExecutionRequestV1(
            jobId = record.jobId,
            planFingerprintSha256 = record.planFingerprintSha256,
            schedulerKind = record.schedulerKind,
            schedulerGeneration = record.schedulerGeneration,
            schedulerIdentity = requireNotNull(record.schedulerIdentity),
        ),
    )

    private companion object {
        val WORK_ID: UUID = UUID.fromString("a96d7f41-dfd5-4c0a-bb1b-6b286e1d3bd8")
    }
}

private class TestRegionalJobRepository(
    initial: RegionalJobRecordV1,
) : RegionalJobRepository {
    var record: RegionalJobRecordV1 = initial
        private set
    var updateCalls: Int = 0
        private set
    var getCalls: Int = 0
        private set
    var failGetCall: Int? = null
    val preUpdateCancelIntent = mutableListOf<Boolean>()

    override suspend fun loadSnapshot(): RegionalJobStoreSnapshot = RegionalJobStoreSnapshot(listOf(record), emptyList())

    override suspend fun get(jobId: String): RegionalJobRecordV1? {
        getCalls += 1
        if (failGetCall == getCalls) {
            throw RegionalJobStorageException("Injected indeterminate storage outcome.")
        }
        return record.takeIf { it.jobId == jobId }
    }

    override suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1 = error("Not used")

    override suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1 {
        updateCalls += 1
        if (record.jobId != jobId || record.revision != expectedRevision) {
            throw RegionalJobConflictException("Injected compare-and-set conflict.")
        }
        preUpdateCancelIntent += record.cancelRequested
        return transform(record).also { record = it }
    }

    fun replaceExternally(updated: RegionalJobRecordV1) {
        record = updated
    }
}
