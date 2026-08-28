package com.gecesars.atxplan.data.scheduler.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import androidx.work.await
import com.gecesars.atxplan.data.dataset.FileRegionalJobRepository
import com.gecesars.atxplan.data.dataset.RegionalJobConflictException
import com.gecesars.atxplan.data.dataset.RegionalJobRepository
import com.gecesars.atxplan.data.dataset.RegionalJobStorageException
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Exact durable and physical ownership carried by a foreground-notification cancel action. */
data class RegionalJobCancellationRequestV1(
    val executionRequest: RegionalJobExecutionRequestV1,
) {
    val workId: UUID

    init {
        require(executionRequest.schedulerKind == RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND) {
            "A foreground cancellation request must target WorkManager."
        }
        val parsedWorkId = try {
            UUID.fromString(executionRequest.schedulerIdentity)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("A foreground cancellation request requires a WorkManager UUID.", error)
        }
        require(parsedWorkId.toString() == executionRequest.schedulerIdentity) {
            "A foreground cancellation request requires a canonical WorkManager UUID."
        }
        workId = parsedWorkId
    }
}

enum class RegionalJobCancellationUncertainStage {
    DURABLE_INTENT,
    SCHEDULER_CANCEL,
    POST_CANCEL_OBSERVATION,
}

sealed interface RegionalJobCancellationResult {
    val jobId: String

    data class Canceled(val record: RegionalJobRecordV1) : RegionalJobCancellationResult {
        init {
            require(record.state == RegionalJobState.CANCELED)
        }

        override val jobId: String = record.jobId
    }

    data class AlreadyTerminal(val record: RegionalJobRecordV1) : RegionalJobCancellationResult {
        init {
            require(record.state.isTerminal)
        }

        override val jobId: String = record.jobId
    }

    /** WorkManager accepted exact cancellation; durable terminalization awaits worker/reconciliation evidence. */
    data class CancellationPending(val record: RegionalJobRecordV1) : RegionalJobCancellationResult {
        init {
            require(record.cancelRequested)
            require(!record.state.isTerminal)
        }

        override val jobId: String = record.jobId
    }

    /** The action no longer owns this durable generation. No scheduler call was made for newer work. */
    data class Stale(override val jobId: String) : RegionalJobCancellationResult

    /** Reconciliation must reload durable state; confirmed intent is never cleared by this flow. */
    data class ReconciliationRequired(
        override val jobId: String,
        val uncertainStage: RegionalJobCancellationUncertainStage,
        val durableIntentConfirmed: Boolean,
        val schedulerCancellationConfirmed: Boolean,
    ) : RegionalJobCancellationResult
}

sealed interface RegionalJobExactCancelResult {
    /** WorkManager accepted the exact cancellation operation; execution drain is not implied. */
    data object Confirmed : RegionalJobExactCancelResult
    data object Indeterminate : RegionalJobExactCancelResult
}

/** Cancels one physical scheduler entry, never a tag, unique-work family, or logical job ID. */
fun interface RegionalJobExactCancelGateway {
    suspend fun cancelExact(workId: UUID): RegionalJobExactCancelResult
}

class WorkManagerRegionalJobExactCancelGateway(
    context: Context,
) : RegionalJobExactCancelGateway {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun cancelExact(workId: UUID): RegionalJobExactCancelResult = try {
        workManager.cancelWorkById(workId).await()
        RegionalJobExactCancelResult.Confirmed
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        RegionalJobExactCancelResult.Indeterminate
    }
}

/**
 * Executes a notification cancellation without allowing a delayed action to affect newer work.
 *
 * A confirmed durable intent always precedes the physical scheduler call. WorkManager operation
 * completion proves scheduler cancellation, not that execution has drained. The runner or a future
 * reconciliation executor therefore owns terminalization after cooperative/drain evidence.
 */
class RegionalJobCancellationController(
    private val repository: RegionalJobRepository,
    private val cancelGateway: RegionalJobExactCancelGateway,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun cancel(request: RegionalJobCancellationRequestV1): RegionalJobCancellationResult {
        val intentResult = persistIntent(request)
        val intentRecord = when (intentResult) {
            is IntentResult.Owned -> intentResult.record
            is IntentResult.Complete -> return intentResult.result
        }

        when (cancelGateway.cancelExact(request.workId)) {
            RegionalJobExactCancelResult.Confirmed -> Unit
            RegionalJobExactCancelResult.Indeterminate -> return RegionalJobCancellationResult.ReconciliationRequired(
                jobId = request.executionRequest.jobId,
                uncertainStage = RegionalJobCancellationUncertainStage.SCHEDULER_CANCEL,
                durableIntentConfirmed = true,
                schedulerCancellationConfirmed = false,
            )
        }

        return observeAfterSchedulerCancellation(request, intentRecord)
    }

    private suspend fun persistIntent(request: RegionalJobCancellationRequestV1): IntentResult {
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val record = try {
                repository.get(request.executionRequest.jobId)
            } catch (_: RegionalJobStorageException) {
                return IntentResult.Complete(
                    RegionalJobCancellationResult.ReconciliationRequired(
                        jobId = request.executionRequest.jobId,
                        uncertainStage = RegionalJobCancellationUncertainStage.DURABLE_INTENT,
                        durableIntentConfirmed = false,
                        schedulerCancellationConfirmed = false,
                    ),
                )
            }
            if (record == null || !record.isOwnedBy(request)) {
                return IntentResult.Complete(RegionalJobCancellationResult.Stale(request.executionRequest.jobId))
            }
            if (record.state.isTerminal) {
                return IntentResult.Complete(RegionalJobCancellationResult.AlreadyTerminal(record))
            }
            if (record.cancelRequested) return IntentResult.Owned(record)

            try {
                val updated = repository.update(record.jobId, record.revision) { current ->
                    current.requestCancellation(current.safeNow())
                }
                return IntentResult.Owned(updated)
            } catch (_: RegionalJobConflictException) {
                // A runner or reconciler advanced the record. Reload and re-check exact ownership.
            } catch (_: RegionalJobStorageException) {
                return IntentResult.Complete(
                    RegionalJobCancellationResult.ReconciliationRequired(
                        jobId = record.jobId,
                        uncertainStage = RegionalJobCancellationUncertainStage.DURABLE_INTENT,
                        durableIntentConfirmed = false,
                        schedulerCancellationConfirmed = false,
                    ),
                )
            }
        }
        return IntentResult.Complete(
            RegionalJobCancellationResult.ReconciliationRequired(
                jobId = request.executionRequest.jobId,
                uncertainStage = RegionalJobCancellationUncertainStage.DURABLE_INTENT,
                durableIntentConfirmed = false,
                schedulerCancellationConfirmed = false,
            ),
        )
    }

    private suspend fun observeAfterSchedulerCancellation(
        request: RegionalJobCancellationRequestV1,
        intentRecord: RegionalJobRecordV1,
    ): RegionalJobCancellationResult {
        val record = try {
            repository.get(request.executionRequest.jobId)
        } catch (_: RegionalJobStorageException) {
            return postCancelObservationUncertain(request)
        } ?: return postCancelObservationUncertain(request)
        if (!record.isOwnedBy(request)) {
            return RegionalJobCancellationResult.Stale(request.executionRequest.jobId)
        }
        if (record.state.isTerminal) {
            return if (record.state == RegionalJobState.CANCELED) {
                RegionalJobCancellationResult.Canceled(record)
            } else {
                RegionalJobCancellationResult.AlreadyTerminal(record)
            }
        }
        if (!record.cancelRequested || record.revision < intentRecord.revision) {
            return postCancelObservationUncertain(request)
        }
        return RegionalJobCancellationResult.CancellationPending(record)
    }

    private fun RegionalJobRecordV1.safeNow(): Long = maxOf(updatedAtEpochMillis, nowEpochMillis())

    private fun postCancelObservationUncertain(
        request: RegionalJobCancellationRequestV1,
    ): RegionalJobCancellationResult.ReconciliationRequired =
        RegionalJobCancellationResult.ReconciliationRequired(
            jobId = request.executionRequest.jobId,
            uncertainStage = RegionalJobCancellationUncertainStage.POST_CANCEL_OBSERVATION,
            durableIntentConfirmed = true,
            schedulerCancellationConfirmed = true,
        )

    private sealed interface IntentResult {
        data class Owned(val record: RegionalJobRecordV1) : IntentResult
        data class Complete(val result: RegionalJobCancellationResult) : IntentResult
    }

    private companion object {
        const val MAXIMUM_CAS_ATTEMPTS = 4
    }
}

private fun RegionalJobRecordV1.isOwnedBy(request: RegionalJobCancellationRequestV1): Boolean =
    planFingerprintSha256 == request.executionRequest.planFingerprintSha256 &&
        schedulerKind == RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND &&
        schedulerGeneration == request.executionRequest.schedulerGeneration &&
        (
            schedulerIdentity == request.workId.toString() ||
                state == RegionalJobState.ENQUEUE_PENDING &&
                schedulerIdentity == null &&
                RegionalWorkContractV1.deterministicWorkId(
                    RegionalWorkInputV1(
                        jobId = jobId,
                        planFingerprintSha256 = planFingerprintSha256,
                        schedulerGeneration = schedulerGeneration,
                    ),
                ) == request.workId
            )

/** Manifest-private receiver for the explicit immutable notification action. */
class RegionalJobCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = RegionalJobCancelIntentContract.decode(intent) ?: return
        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(RECEIVER_TIMEOUT_MILLIS) {
                    RegionalJobCancellationController(
                        repository = FileRegionalJobRepository(applicationContext),
                        cancelGateway = WorkManagerRegionalJobExactCancelGateway(applicationContext),
                    ).cancel(request)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val RECEIVER_TIMEOUT_MILLIS = 8_000L
    }
}

internal object RegionalJobCancelIntentContract {
    const val ACTION_CANCEL = "com.gecesars.atxplan.action.CANCEL_REGIONAL_WORK"
    private const val EXTRA_JOB_ID = "regional_job_id"
    private const val EXTRA_PLAN_FINGERPRINT = "regional_plan_fingerprint"
    private const val EXTRA_SCHEDULER_GENERATION = "regional_scheduler_generation"
    private const val EXTRA_WORK_ID = "regional_work_id"
    private const val DATA_SCHEME = "atx-plan"
    private const val DATA_AUTHORITY = "regional-work-cancel"

    fun intent(context: Context, request: RegionalJobCancellationRequestV1): Intent =
        Intent(context, RegionalJobCancelReceiver::class.java).apply {
            action = ACTION_CANCEL
            `package` = context.packageName
            data = actionData(request.workId)
            putExtra(EXTRA_JOB_ID, request.executionRequest.jobId)
            putExtra(EXTRA_PLAN_FINGERPRINT, request.executionRequest.planFingerprintSha256)
            putExtra(EXTRA_SCHEDULER_GENERATION, request.executionRequest.schedulerGeneration)
            putExtra(EXTRA_WORK_ID, request.workId.toString())
        }

    fun decode(intent: Intent): RegionalJobCancellationRequestV1? = try {
        decodeStrict(intent)
    } catch (_: RuntimeException) {
        null
    }

    private fun decodeStrict(intent: Intent): RegionalJobCancellationRequestV1? {
        if (intent.action != ACTION_CANCEL) return null
        if (intent.component?.className != RegionalJobCancelReceiver::class.java.name) return null
        val extras = intent.extras ?: return null
        if (extras.keySet() != EXACT_EXTRA_KEYS) return null
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return null
        val fingerprint = intent.getStringExtra(EXTRA_PLAN_FINGERPRINT) ?: return null
        val generation = intent.getIntExtra(EXTRA_SCHEDULER_GENERATION, INVALID_GENERATION)
        if (generation == INVALID_GENERATION) return null
        val workIdText = intent.getStringExtra(EXTRA_WORK_ID) ?: return null
        val workId = try {
            UUID.fromString(workIdText)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (workId.toString() != workIdText || intent.data != actionData(workId)) return null
        return try {
            RegionalJobCancellationRequestV1(
                RegionalJobExecutionRequestV1(
                    jobId = jobId,
                    planFingerprintSha256 = fingerprint,
                    schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
                    schedulerGeneration = generation,
                    schedulerIdentity = workIdText,
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun actionData(workId: UUID): Uri = Uri.Builder()
        .scheme(DATA_SCHEME)
        .authority(DATA_AUTHORITY)
        .appendPath(workId.toString())
        .build()

    private const val INVALID_GENERATION = -1
    private val EXACT_EXTRA_KEYS = setOf(
        EXTRA_JOB_ID,
        EXTRA_PLAN_FINGERPRINT,
        EXTRA_SCHEDULER_GENERATION,
        EXTRA_WORK_ID,
    )
}
