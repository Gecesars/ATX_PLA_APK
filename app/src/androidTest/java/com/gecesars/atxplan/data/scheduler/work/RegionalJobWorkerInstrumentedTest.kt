package com.gecesars.atxplan.data.scheduler.work

import android.app.Notification
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobProblemV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRunOutcome
import com.gecesars.atxplan.domain.dataset.RegionalJobRunner
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalJobWorkerInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun foregroundPrecedesRunnerAndHighWorkManagerAttemptCountDoesNotChangeOwnership() = runBlocking {
        val fixture = fixture()
        val order = mutableListOf<String>()
        val runner = RecordingRunner(
            outcome = RegionalJobRunOutcome.Rejected(
                jobId = fixture.input.jobId,
                problem = problem("stale-scheduler-generation"),
            ),
            order = order,
            emitProgress = fixture.progress,
        )
        val foreground = RecordingForegroundController(order)
        val worker = buildWorker(
            input = fixture.input,
            runner = runner,
            foreground = foreground,
            runAttemptCount = 99,
        )

        val result = worker.doWork()

        assertEquals(Result.success().javaClass, result.javaClass)
        assertEquals(listOf("can-run", "foreground", "runner", "progress"), order)
        assertEquals(1, foreground.progressUpdates)
        assertEquals(1, runner.calls)
        assertEquals(
            RegionalJobExecutionRequestV1(
                jobId = fixture.input.jobId,
                planFingerprintSha256 = fixture.input.planFingerprintSha256,
                schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
                schedulerGeneration = fixture.input.schedulerGeneration,
                schedulerIdentity = RegionalWorkContractV1.deterministicWorkId(fixture.input).toString(),
            ),
            runner.request,
        )
        assertFalse(runner.stoppedAtEntry)
        assertEquals("stale", result.outputData.getString("atx.regional.work.v1.result"))
        assertEquals(
            "stale-scheduler-generation",
            result.outputData.getString("atx.regional.work.v1.detail"),
        )
    }

    @Test
    fun terminalOutcomeCompletesWithoutWorkManagerRetry() = runBlocking {
        val fixture = fixture()
        val canceled = fixture.record
            .requestCancellation(1_010L)
            .transitionTo(RegionalJobState.CANCELED, 1_020L)
        val worker = buildWorker(
            input = fixture.input,
            runner = RecordingRunner(
                outcome = RegionalJobRunOutcome.Terminal(canceled, alreadyTerminal = true),
            ),
            foreground = RecordingForegroundController(),
        )

        val result = worker.doWork()

        assertEquals(Result.success().javaClass, result.javaClass)
        assertEquals("terminal", result.outputData.getString("atx.regional.work.v1.result"))
        assertEquals("canceled", result.outputData.getString("atx.regional.work.v1.detail"))
        assertTrue(result.javaClass != Result.retry().javaClass)
    }

    @Test
    fun reconciliationOutcomeFailsWithoutWorkManagerRetry() = runBlocking {
        val fixture = fixture()
        val runner = RecordingRunner(
            outcome = RegionalJobRunOutcome.ReconciliationRequired(
                jobId = fixture.input.jobId,
                problem = problem("scheduler-stopped"),
            ),
        )
        val worker = buildWorker(
            input = fixture.input,
            runner = runner,
            foreground = RecordingForegroundController(),
        )

        val result = worker.doWork()

        assertEquals(Result.failure().javaClass, result.javaClass)
        assertEquals(
            "reconciliation-required",
            result.outputData.getString("atx.regional.work.v1.result"),
        )
        assertEquals("scheduler-stopped", result.outputData.getString("atx.regional.work.v1.detail"))
        assertTrue(result.javaClass != Result.retry().javaClass)
        assertEquals(1, runner.calls)
    }

    @Test
    fun malformedInputAndUnavailableForegroundNeverReachRunner() = runBlocking {
        val fixture = fixture()
        val malformedRunner = RecordingRunner(
            outcome = RegionalJobRunOutcome.Rejected(fixture.input.jobId, problem("must-not-run")),
        )
        val malformedRequest = RegionalWorkRequestFactoryV1.create(fixture.input).let { valid ->
            androidx.work.OneTimeWorkRequest.Builder(RegionalJobWorker::class.java)
                .setId(valid.id)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString("untrusted", "value")
                        .build(),
                )
                .build()
        }
        val malformedWorker = buildWorker(
            request = malformedRequest,
            runner = malformedRunner,
            foreground = RecordingForegroundController(),
        )
        val unavailableRunner = RecordingRunner(
            outcome = RegionalJobRunOutcome.Rejected(fixture.input.jobId, problem("must-not-run")),
        )
        val unavailableWorker = buildWorker(
            input = fixture.input,
            runner = unavailableRunner,
            foreground = RecordingForegroundController(canRun = false),
        )

        val malformedResult = malformedWorker.doWork()
        val unavailableResult = unavailableWorker.doWork()

        assertEquals(Result.failure().javaClass, malformedResult.javaClass)
        assertEquals(Result.failure().javaClass, unavailableResult.javaClass)
        assertEquals(0, malformedRunner.calls)
        assertEquals(0, unavailableRunner.calls)
        assertEquals(
            "worker-input-invalid",
            malformedResult.outputData.getString("atx.regional.work.v1.detail"),
        )
        assertEquals(
            "foreground-notification-unavailable",
            unavailableResult.outputData.getString("atx.regional.work.v1.detail"),
        )
    }

    private fun buildWorker(
        input: RegionalWorkInputV1,
        runner: RegionalJobRunner,
        foreground: RegionalJobForegroundController,
        runAttemptCount: Int = 0,
    ): RegionalJobWorker = buildWorker(
        request = RegionalWorkRequestFactoryV1.create(input),
        runner = runner,
        foreground = foreground,
        runAttemptCount = runAttemptCount,
    )

    private fun buildWorker(
        request: androidx.work.OneTimeWorkRequest,
        runner: RegionalJobRunner,
        foreground: RegionalJobForegroundController,
        runAttemptCount: Int = 0,
    ): RegionalJobWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = if (workerClassName == RegionalJobWorker::class.java.name) {
                RegionalJobWorker(
                    appContext = appContext,
                    workerParameters = workerParameters,
                    runner = runner,
                    foregroundController = foreground,
                )
            } else {
                null
            }
        }
        return TestListenableWorkerBuilder.from(context, request)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .build() as RegionalJobWorker
    }

    private fun fixture(): WorkerFixture {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                reason = "WorkManager worker instrumented test",
            ),
        )
        val record = RegionalJobRecordV1.enqueuePending(
            jobId = "123e4567-e89b-42d3-a456-426614174000",
            plan = plan,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            acceptedAtEpochMillis = 1_000L,
            createdAtEpochMillis = 1_000L,
        )
        val input = RegionalWorkInputV1(
            jobId = record.jobId,
            planFingerprintSha256 = record.planFingerprintSha256,
            schedulerGeneration = record.schedulerGeneration,
        )
        return WorkerFixture(
            record = record,
            input = input,
            progress = RegionalDownloadProgress(
                artifact = plan.artifacts.single(),
                status = RegionalTransferStatus.DOWNLOADING,
                completedBytes = 512L,
                totalBytes = 1_024L,
            ),
        )
    }

    private fun problem(code: String) = RegionalJobProblemV1(
        code = code,
        message = "Instrumented worker outcome.",
        retryableByUser = false,
    )
}

private data class WorkerFixture(
    val record: RegionalJobRecordV1,
    val input: RegionalWorkInputV1,
    val progress: RegionalDownloadProgress,
)

private class RecordingRunner(
    private val outcome: RegionalJobRunOutcome,
    private val order: MutableList<String> = mutableListOf(),
    private val emitProgress: RegionalDownloadProgress? = null,
) : RegionalJobRunner {
    var calls: Int = 0
        private set
    var request: RegionalJobExecutionRequestV1? = null
        private set
    var stoppedAtEntry: Boolean = true
        private set

    override suspend fun run(
        request: RegionalJobExecutionRequestV1,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isStopped: () -> Boolean,
    ): RegionalJobRunOutcome {
        calls += 1
        this.request = request
        stoppedAtEntry = isStopped()
        order += "runner"
        emitProgress?.let(onProgress)
        return outcome
    }
}

private class RecordingForegroundController(
    private val order: MutableList<String> = mutableListOf(),
    private val canRun: Boolean = true,
) : RegionalJobForegroundController {
    var progressUpdates: Int = 0
        private set

    override fun canRun(): Boolean {
        order += "can-run"
        return canRun
    }

    override fun initial(request: RegionalJobExecutionRequestV1): ForegroundInfo {
        order += "foreground"
        return ForegroundInfo(7, Notification())
    }

    override fun update(
        request: RegionalJobExecutionRequestV1,
        progress: RegionalDownloadProgress,
    ): ForegroundInfo {
        progressUpdates += 1
        order += "progress"
        return ForegroundInfo(7, Notification())
    }
}
