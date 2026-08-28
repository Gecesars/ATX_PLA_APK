package com.gecesars.atxplan.data.scheduler.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.gecesars.atxplan.data.dataset.RegionalDataComposition
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRunOutcome
import com.gecesars.atxplan.domain.dataset.RegionalJobRunner
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Foreground WorkManager execution envelope for one exact durable job generation.
 *
 * New work is admitted by the API 23-33 scheduler adapter. An already persisted request is still
 * allowed to finish after an operating-system upgrade. WorkManager owns only the Android execution
 * envelope. The injected [RegionalJobRunner] remains the sole lifecycle orchestrator and the
 * dataset repository remains the sole provider-retry owner. This worker never returns
 * [Result.retry].
 */
class RegionalJobWorker private constructor(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val runner: RegionalJobRunner,
    private val foregroundController: RegionalJobForegroundController,
) : CoroutineWorker(appContext, workerParameters) {
    /** Reflective WorkManager constructor; dependencies are rebuilt after process death. */
    constructor(
        appContext: Context,
        workerParameters: WorkerParameters,
    ) : this(
        appContext = appContext,
        workerParameters = workerParameters,
        runner = RegionalDataComposition.jobExecutionDependencies(appContext).runner,
        foregroundController = RegionalJobForegroundNotification(appContext),
    )

    internal constructor(
        appContext: Context,
        workerParameters: WorkerParameters,
        runner: RegionalJobRunner,
        foregroundController: RegionalJobForegroundController,
        @Suppress("UNUSED_PARAMETER") testInjection: Unit = Unit,
    ) : this(appContext, workerParameters, runner, foregroundController)

    override suspend fun doWork(): Result {
        val input = RegionalWorkContractV1.decodeInput(inputData)
            ?: return failure("worker-input-invalid")
        val expectedWorkId = RegionalWorkContractV1.deterministicWorkId(input)
        if (id != expectedWorkId) return failure("worker-identity-invalid")

        val request = RegionalJobExecutionRequestV1(
            jobId = input.jobId,
            planFingerprintSha256 = input.planFingerprintSha256,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            schedulerGeneration = input.schedulerGeneration,
            schedulerIdentity = id.toString(),
        )
        val foregroundAvailable = try {
            foregroundController.canRun()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!foregroundAvailable) {
            return failure("foreground-notification-unavailable")
        }

        try {
            setForeground(foregroundController.initial(request))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure("foreground-start-failed")
        }

        val notificationFailed = AtomicBoolean(false)
        val outcome = try {
            runner.run(
                request = request,
                onProgress = { progress ->
                    try {
                        foregroundController.update(request, progress)
                    } catch (_: Exception) {
                        notificationFailed.set(true)
                    }
                },
                isStopped = { isStopped || notificationFailed.get() },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure("worker-execution-failed")
        }

        return when (outcome) {
            is RegionalJobRunOutcome.Terminal -> Result.success(
                output(
                    disposition = "terminal",
                    detail = outcome.record.state.name.lowercase(Locale.ROOT),
                ),
            )

            is RegionalJobRunOutcome.Rejected -> Result.success(
                output(
                    disposition = "stale",
                    detail = outcome.problem.code,
                ),
            )

            is RegionalJobRunOutcome.ReconciliationRequired -> Result.failure(
                output(
                    disposition = "reconciliation-required",
                    detail = outcome.problem.code,
                ),
            )
        }
    }

    private fun failure(code: String): Result = Result.failure(
        output(disposition = "worker-failure", detail = code),
    )

    private fun output(disposition: String, detail: String): Data = Data.Builder()
        .putString(OUTPUT_DISPOSITION_KEY, disposition.take(MAXIMUM_OUTPUT_VALUE_CHARACTERS))
        .putString(OUTPUT_DETAIL_KEY, detail.take(MAXIMUM_OUTPUT_VALUE_CHARACTERS))
        .build()

    private companion object {
        const val OUTPUT_DISPOSITION_KEY = "atx.regional.work.v1.result"
        const val OUTPUT_DETAIL_KEY = "atx.regional.work.v1.detail"
        const val MAXIMUM_OUTPUT_VALUE_CHARACTERS = 96
    }
}
