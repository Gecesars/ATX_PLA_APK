package com.gecesars.atxplan.data.scheduler.work

import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.gecesars.atxplan.data.dataset.RegionalJobConflictException
import com.gecesars.atxplan.data.dataset.RegionalDataComposition
import com.gecesars.atxplan.data.dataset.RegionalJobRepository
import com.gecesars.atxplan.domain.dataset.MAXIMUM_REGIONAL_SCHEDULER_ENTRIES
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalScheduledJobState
import com.gecesars.atxplan.domain.dataset.RegionalScheduledJobV1
import com.gecesars.atxplan.domain.dataset.RegionalSchedulerSnapshotAvailability
import com.gecesars.atxplan.domain.dataset.RegionalSchedulerSnapshotV1
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stage whose cross-store outcome could not be proven without reconciliation. */
enum class RegionalWorkIndeterminateStage {
    ENQUEUE_OPERATION,
    RETENTION_VERIFICATION,
    RETAINED_WORK_FINISHED,
    DURABLE_ACKNOWLEDGEMENT,
    EXACT_CANCELLATION,
}

sealed interface RegionalWorkScheduleOutcome {
    val jobId: String

    /** WorkManager retained the exact request and the durable record owns the same UUID. */
    data class Scheduled(
        val record: RegionalJobRecordV1,
        val workRequestId: UUID,
        val workerAdvancedBeforeAcknowledgement: Boolean,
    ) : RegionalWorkScheduleOutcome {
        init {
            require(record.schedulerIdentity == workRequestId.toString())
            require(record.schedulerKind == RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND)
            require(record.state != RegionalJobState.ENQUEUE_PENDING)
        }

        override val jobId: String = record.jobId
    }

    /** The passed enqueue intent no longer exactly matches the durable record. */
    data class Stale(
        override val jobId: String,
        val inactiveWorkRequestId: UUID? = null,
    ) : RegionalWorkScheduleOutcome

    /** API 34+ requires the separate user-initiated data-transfer adapter. */
    data class Unsupported(
        override val jobId: String,
        val sdkInt: Int,
    ) : RegionalWorkScheduleOutcome

    /** A visible foreground notification cannot currently be guaranteed. */
    data class NotificationUnavailable(
        override val jobId: String,
    ) : RegionalWorkScheduleOutcome

    /** External or durable state must be reconciled before another enqueue attempt. */
    data class Indeterminate(
        override val jobId: String,
        val schedulerIdentity: String,
        val stage: RegionalWorkIndeterminateStage,
    ) : RegionalWorkScheduleOutcome
}

sealed interface RegionalWorkInspectionOutcome {
    data class Present(val scheduledJob: RegionalScheduledJobV1) : RegionalWorkInspectionOutcome
    data object Absent : RegionalWorkInspectionOutcome
    data object Unavailable : RegionalWorkInspectionOutcome
}

sealed interface RegionalWorkExactCancellationOutcome {
    /** WorkManager accepted cancellation for this exact UUID; worker execution drain is not implied. */
    data object CancellationRequested : RegionalWorkExactCancellationOutcome
    data object AlreadyFinished : RegionalWorkExactCancellationOutcome
    data object Absent : RegionalWorkExactCancellationOutcome
    data object Indeterminate : RegionalWorkExactCancellationOutcome
}

/**
 * API 23-33 WorkManager scheduler for one exact persisted enqueue intent.
 *
 * The durable record and WorkManager database are independent stores. This adapter therefore does
 * not claim cross-store atomicity: it awaits and verifies the physical request, then publishes its
 * exact UUID with a guarded compare-and-set update. Any uncertain boundary is returned for the
 * reconciler; the adapter never retries an enqueue and never cancels by tag or unique-work name.
 */
class RegionalWorkManagerScheduler internal constructor(
    private val jobRepository: RegionalJobRepository,
    private val gateway: RegionalWorkManagerGateway,
    private val canPostForegroundNotification: () -> Boolean,
    private val sdkInt: () -> Int,
    private val nowEpochMillis: () -> Long,
) {
    constructor(
        jobRepository: RegionalJobRepository,
        workManager: WorkManager,
        canPostForegroundNotification: () -> Boolean,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        jobRepository = jobRepository,
        gateway = AndroidRegionalWorkManagerGateway(workManager),
        canPostForegroundNotification = canPostForegroundNotification,
        sdkInt = { Build.VERSION.SDK_INT },
        nowEpochMillis = nowEpochMillis,
    )

    companion object {
        /** Builds the process-reconstructible adapter without scheduling or mutating a job. */
        fun create(context: Context): RegionalWorkManagerScheduler {
            val applicationContext = context.applicationContext
            val foreground = RegionalJobForegroundNotification(applicationContext)
            return RegionalWorkManagerScheduler(
                jobRepository = RegionalDataComposition.jobRepository(applicationContext),
                workManager = WorkManager.getInstance(applicationContext),
                canPostForegroundNotification = foreground::canRun,
            )
        }
    }

    suspend fun enqueue(record: RegionalJobRecordV1): RegionalWorkScheduleOutcome {
        val currentSdk = sdkInt()
        if (currentSdk !in WORK_MANAGER_MINIMUM_API..WORK_MANAGER_MAXIMUM_API) {
            return RegionalWorkScheduleOutcome.Unsupported(record.jobId, currentSdk)
        }
        if (!record.isWorkManagerEnqueueIntent()) {
            return RegionalWorkScheduleOutcome.Stale(record.jobId)
        }
        val notificationVisible = try {
            canPostForegroundNotification()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!notificationVisible) {
            return RegionalWorkScheduleOutcome.NotificationUnavailable(record.jobId)
        }

        return REGIONAL_WORK_MANAGER_OPERATION_MUTEX.withLock {
            enqueueLocked(record)
        }
    }

    /** Strict, bounded view used by the durable-job reconciler. */
    suspend fun snapshot(): RegionalSchedulerSnapshotV1 =
        REGIONAL_WORK_MANAGER_OPERATION_MUTEX.withLock { snapshotLocked() }

    private suspend fun snapshotLocked(): RegionalSchedulerSnapshotV1 {
        val entries = try {
            gateway.listByGlobalTag(RegionalWorkContractV1.GLOBAL_TAG)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return unavailableSnapshot()
        }
        if (entries.size > MAXIMUM_REGIONAL_SCHEDULER_ENTRIES) return unavailableSnapshot()

        val jobs = entries.map { entry -> entry.toScheduledJobOrNull() ?: return unavailableSnapshot() }
        return try {
            RegionalSchedulerSnapshotV1(
                availability = RegionalSchedulerSnapshotAvailability.COMPLETE,
                jobs = jobs.sortedWith(
                    compareBy<RegionalScheduledJobV1>(
                        RegionalScheduledJobV1::jobId,
                        RegionalScheduledJobV1::schedulerGeneration,
                        RegionalScheduledJobV1::schedulerIdentity,
                    ),
                ),
            )
        } catch (_: IllegalArgumentException) {
            unavailableSnapshot()
        }
    }

    /** Inspects one canonical WorkManager UUID without broadening it to a logical job family. */
    suspend fun inspectExact(schedulerIdentity: String): RegionalWorkInspectionOutcome {
        val workId = schedulerIdentity.toCanonicalRegionalWorkIdOrNull()
            ?: return RegionalWorkInspectionOutcome.Unavailable
        return REGIONAL_WORK_MANAGER_OPERATION_MUTEX.withLock {
            inspectExactLocked(workId)
        }
    }

    /** Cancels one verified ATX regional WorkManager UUID, never a tag or unique-work family. */
    suspend fun cancelExact(schedulerIdentity: String): RegionalWorkExactCancellationOutcome {
        val workId = schedulerIdentity.toCanonicalRegionalWorkIdOrNull()
            ?: return RegionalWorkExactCancellationOutcome.Indeterminate
        return REGIONAL_WORK_MANAGER_OPERATION_MUTEX.withLock {
            val before = try {
                gateway.getById(workId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock RegionalWorkExactCancellationOutcome.Indeterminate
            } ?: return@withLock RegionalWorkExactCancellationOutcome.Absent
            if (before.id != workId || before.toScheduledJobOrNull() == null) {
                return@withLock RegionalWorkExactCancellationOutcome.Indeterminate
            }
            when (before.state) {
                RegionalWorkManagerState.SUCCEEDED,
                RegionalWorkManagerState.FAILED,
                -> RegionalWorkExactCancellationOutcome.AlreadyFinished

                RegionalWorkManagerState.CANCELLED ->
                    RegionalWorkExactCancellationOutcome.CancellationRequested

                RegionalWorkManagerState.ENQUEUED,
                RegionalWorkManagerState.BLOCKED,
                RegionalWorkManagerState.RUNNING,
                -> when (cancelAndObserveLocked(workId)) {
                    ExactCancellationObservation.CANCELLATION_REQUESTED ->
                        RegionalWorkExactCancellationOutcome.CancellationRequested

                    ExactCancellationObservation.FINISHED ->
                        RegionalWorkExactCancellationOutcome.AlreadyFinished

                    ExactCancellationObservation.ABSENT,
                    ExactCancellationObservation.INDETERMINATE,
                    -> RegionalWorkExactCancellationOutcome.Indeterminate
                }
            }
        }
    }

    private suspend fun enqueueLocked(record: RegionalJobRecordV1): RegionalWorkScheduleOutcome {
        val currentBeforeEnqueue = try {
            jobRepository.get(record.jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = RegionalWorkContractV1.deterministicWorkId(record.toWorkInput()).toString(),
                stage = RegionalWorkIndeterminateStage.DURABLE_ACKNOWLEDGEMENT,
            )
        }
        if (currentBeforeEnqueue != record || !currentBeforeEnqueue.isWorkManagerEnqueueIntent()) {
            return RegionalWorkScheduleOutcome.Stale(record.jobId)
        }

        val input = record.toWorkInput()
        val request = RegionalWorkRequestFactoryV1.create(input)
        val uniqueWorkName = RegionalWorkContractV1.uniqueWorkName(input)
        try {
            gateway.enqueueUnique(
                uniqueWorkName = uniqueWorkName,
                existingWorkPolicy = ExistingWorkPolicy.KEEP,
                request = request,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = request.id.toString(),
                stage = RegionalWorkIndeterminateStage.ENQUEUE_OPERATION,
            )
        }

        val retained = try {
            gateway.listForUniqueWork(uniqueWorkName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = request.id.toString(),
                stage = RegionalWorkIndeterminateStage.RETENTION_VERIFICATION,
            )
        }
        val retainedRequest = retained.exactRetainedRequestOrNull(request.id, record)
        if (retainedRequest == null) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = request.id.toString(),
                stage = RegionalWorkIndeterminateStage.RETENTION_VERIFICATION,
            )
        }
        if (retainedRequest.state.isFinished) {
            return resolveFinishedRetainedWorkLocked(record, request.id)
        }

        return acknowledgeOrResolveRaceLocked(record, request.id)
    }

    private suspend fun resolveFinishedRetainedWorkLocked(
        record: RegionalJobRecordV1,
        workId: UUID,
    ): RegionalWorkScheduleOutcome {
        val current = try {
            jobRepository.get(record.jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (current != null && current.isSameAdvancedWork(record, workId)) {
            return RegionalWorkScheduleOutcome.Scheduled(
                record = current,
                workRequestId = workId,
                workerAdvancedBeforeAcknowledgement = true,
            )
        }
        return RegionalWorkScheduleOutcome.Indeterminate(
            jobId = record.jobId,
            schedulerIdentity = workId.toString(),
            stage = RegionalWorkIndeterminateStage.RETAINED_WORK_FINISHED,
        )
    }

    private suspend fun acknowledgeOrResolveRaceLocked(
        record: RegionalJobRecordV1,
        workId: UUID,
    ): RegionalWorkScheduleOutcome {
        try {
            val acknowledged = jobRepository.update(record.jobId, record.revision) { current ->
                if (current != record || !current.isWorkManagerEnqueueIntent()) {
                    throw RegionalJobConflictException(
                        "The regional enqueue intent changed before scheduler acknowledgement.",
                    )
                }
                current.transitionTo(
                    nextState = RegionalJobState.QUEUED,
                    nowEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis()),
                    schedulerIdentity = workId.toString(),
                )
            }
            return RegionalWorkScheduleOutcome.Scheduled(
                record = acknowledged,
                workRequestId = workId,
                workerAdvancedBeforeAcknowledgement = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The WorkManager request may have started before the durable acknowledgement CAS.
        }

        val current = try {
            jobRepository.get(record.jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = workId.toString(),
                stage = RegionalWorkIndeterminateStage.DURABLE_ACKNOWLEDGEMENT,
            )
        }
        if (current != null && current.isSameAdvancedWork(record, workId) && !current.cancelRequested) {
            return RegionalWorkScheduleOutcome.Scheduled(
                record = current,
                workRequestId = workId,
                workerAdvancedBeforeAcknowledgement = true,
            )
        }
        if (current == record && current.isWorkManagerEnqueueIntent()) {
            return RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = workId.toString(),
                stage = RegionalWorkIndeterminateStage.DURABLE_ACKNOWLEDGEMENT,
            )
        }

        return when (cancelAndObserveLocked(workId)) {
            ExactCancellationObservation.FINISHED -> RegionalWorkScheduleOutcome.Stale(
                jobId = record.jobId,
                inactiveWorkRequestId = workId,
            )

            ExactCancellationObservation.CANCELLATION_REQUESTED,
            ExactCancellationObservation.ABSENT,
            ExactCancellationObservation.INDETERMINATE,
            -> RegionalWorkScheduleOutcome.Indeterminate(
                jobId = record.jobId,
                schedulerIdentity = workId.toString(),
                stage = RegionalWorkIndeterminateStage.EXACT_CANCELLATION,
            )
        }
    }

    private suspend fun inspectExactLocked(workId: UUID): RegionalWorkInspectionOutcome {
        val entry = try {
            gateway.getById(workId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegionalWorkInspectionOutcome.Unavailable
        } ?: return RegionalWorkInspectionOutcome.Absent
        val scheduled = entry.toScheduledJobOrNull() ?: return RegionalWorkInspectionOutcome.Unavailable
        if (entry.id != workId) return RegionalWorkInspectionOutcome.Unavailable
        return RegionalWorkInspectionOutcome.Present(scheduled)
    }

    private suspend fun cancelAndObserveLocked(workId: UUID): ExactCancellationObservation {
        try {
            gateway.cancelExact(workId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ExactCancellationObservation.INDETERMINATE
        }
        val after = try {
            gateway.getById(workId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ExactCancellationObservation.INDETERMINATE
        } ?: return ExactCancellationObservation.ABSENT
        if (after.id != workId || after.toScheduledJobOrNull() == null) {
            return ExactCancellationObservation.INDETERMINATE
        }
        return when (after.state) {
            RegionalWorkManagerState.CANCELLED -> ExactCancellationObservation.CANCELLATION_REQUESTED
            RegionalWorkManagerState.SUCCEEDED,
            RegionalWorkManagerState.FAILED,
            -> ExactCancellationObservation.FINISHED

            RegionalWorkManagerState.ENQUEUED,
            RegionalWorkManagerState.BLOCKED,
            RegionalWorkManagerState.RUNNING,
            -> ExactCancellationObservation.INDETERMINATE
        }
    }
}

internal interface RegionalWorkManagerGateway {
    suspend fun enqueueUnique(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    suspend fun listForUniqueWork(uniqueWorkName: String): List<RegionalWorkManagerEntry>

    suspend fun listByGlobalTag(globalTag: String): List<RegionalWorkManagerEntry>

    suspend fun getById(workId: UUID): RegionalWorkManagerEntry?

    suspend fun cancelExact(workId: UUID)
}

internal data class RegionalWorkManagerEntry(
    val id: UUID,
    val tags: Set<String>,
    val state: RegionalWorkManagerState,
)

internal enum class RegionalWorkManagerState {
    ENQUEUED,
    BLOCKED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

private enum class ExactCancellationObservation {
    CANCELLATION_REQUESTED,
    FINISHED,
    ABSENT,
    INDETERMINATE,
}

private class AndroidRegionalWorkManagerGateway(
    private val workManager: WorkManager,
) : RegionalWorkManagerGateway {
    override suspend fun enqueueUnique(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, request).await()
    }

    override suspend fun listForUniqueWork(uniqueWorkName: String): List<RegionalWorkManagerEntry> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first().map(WorkInfo::toEntry)

    override suspend fun listByGlobalTag(globalTag: String): List<RegionalWorkManagerEntry> =
        workManager.getWorkInfosByTagFlow(globalTag).first().map(WorkInfo::toEntry)

    override suspend fun getById(workId: UUID): RegionalWorkManagerEntry? =
        workManager.getWorkInfoByIdFlow(workId).first()?.toEntry()

    override suspend fun cancelExact(workId: UUID) {
        workManager.cancelWorkById(workId).await()
    }
}

private fun WorkInfo.toEntry(): RegionalWorkManagerEntry = RegionalWorkManagerEntry(
    id = id,
    tags = tags,
    state = when (state) {
        WorkInfo.State.ENQUEUED -> RegionalWorkManagerState.ENQUEUED
        WorkInfo.State.BLOCKED -> RegionalWorkManagerState.BLOCKED
        WorkInfo.State.RUNNING -> RegionalWorkManagerState.RUNNING
        WorkInfo.State.SUCCEEDED -> RegionalWorkManagerState.SUCCEEDED
        WorkInfo.State.FAILED -> RegionalWorkManagerState.FAILED
        WorkInfo.State.CANCELLED -> RegionalWorkManagerState.CANCELLED
    },
)

private fun RegionalWorkManagerEntry.toScheduledJobOrNull(): RegionalScheduledJobV1? {
    val identity = RegionalWorkContractV1.decodeWorkInfoIdentity(id, tags) ?: return null
    return try {
        RegionalScheduledJobV1(
            jobId = identity.jobId,
            planFingerprintSha256 = identity.planFingerprintSha256,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            schedulerGeneration = identity.schedulerGeneration,
            schedulerIdentity = identity.workRequestId.toString(),
            state = when (state) {
                RegionalWorkManagerState.ENQUEUED,
                RegionalWorkManagerState.BLOCKED,
                -> RegionalScheduledJobState.PENDING

                RegionalWorkManagerState.RUNNING -> RegionalScheduledJobState.RUNNING
                RegionalWorkManagerState.SUCCEEDED,
                RegionalWorkManagerState.FAILED,
                RegionalWorkManagerState.CANCELLED,
                -> RegionalScheduledJobState.FINISHED
            },
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun List<RegionalWorkManagerEntry>.exactRetainedRequestOrNull(
    workId: UUID,
    record: RegionalJobRecordV1,
): RegionalWorkManagerEntry? {
    if (size != 1) return null
    val retained = single()
    val decoded = RegionalWorkContractV1.decodeWorkInfoIdentity(retained.id, retained.tags)
        ?: return null
    return retained.takeIf {
        retained.id == workId &&
        decoded.jobId == record.jobId &&
        decoded.planFingerprintSha256 == record.planFingerprintSha256 &&
        decoded.schedulerGeneration == record.schedulerGeneration &&
        decoded.workRequestId == workId
    }
}

private val RegionalWorkManagerState.isFinished: Boolean
    get() = when (this) {
        RegionalWorkManagerState.SUCCEEDED,
        RegionalWorkManagerState.FAILED,
        RegionalWorkManagerState.CANCELLED,
        -> true

        RegionalWorkManagerState.ENQUEUED,
        RegionalWorkManagerState.BLOCKED,
        RegionalWorkManagerState.RUNNING,
        -> false
    }

private fun RegionalJobRecordV1.isWorkManagerEnqueueIntent(): Boolean =
    state == RegionalJobState.ENQUEUE_PENDING &&
        schedulerKind == RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND &&
        schedulerIdentity == null &&
        !cancelRequested

private fun RegionalJobRecordV1.toWorkInput(): RegionalWorkInputV1 = RegionalWorkInputV1(
    jobId = jobId,
    planFingerprintSha256 = planFingerprintSha256,
    schedulerGeneration = schedulerGeneration,
)

private fun RegionalJobRecordV1.isSameAdvancedWork(
    original: RegionalJobRecordV1,
    workId: UUID,
): Boolean =
    jobId == original.jobId &&
        revision > original.revision &&
        semanticFingerprintSha256 == original.semanticFingerprintSha256 &&
        planFingerprintSha256 == original.planFingerprintSha256 &&
        catalogRevision == original.catalogRevision &&
        canonicalPlan == original.canonicalPlan &&
        acceptedLicenseSnapshots == original.acceptedLicenseSnapshots &&
        createdAtEpochMillis == original.createdAtEpochMillis &&
        schedulerKind == original.schedulerKind &&
        schedulerGeneration == original.schedulerGeneration &&
        schedulerIdentity == workId.toString() &&
        state != RegionalJobState.ENQUEUE_PENDING

private fun String.toCanonicalRegionalWorkIdOrNull(): UUID? {
    val parsed = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parsed.takeIf {
        it.toString() == this &&
            it.version() == REGIONAL_WORK_UUID_VERSION &&
            it.variant() == RFC_4122_UUID_VARIANT
    }
}

private fun unavailableSnapshot(): RegionalSchedulerSnapshotV1 = RegionalSchedulerSnapshotV1(
    availability = RegionalSchedulerSnapshotAvailability.UNAVAILABLE,
    jobs = emptyList(),
)

private val REGIONAL_WORK_MANAGER_OPERATION_MUTEX = Mutex()

private const val WORK_MANAGER_MINIMUM_API = 23
private const val WORK_MANAGER_MAXIMUM_API = 33
private const val REGIONAL_WORK_UUID_VERSION = 8
private const val RFC_4122_UUID_VARIANT = 2
