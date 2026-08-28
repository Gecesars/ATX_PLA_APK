package com.gecesars.atxplan.data.dataset

import com.gecesars.atxplan.domain.dataset.RegionalArtifactCachePolicy
import com.gecesars.atxplan.domain.dataset.RegionalCanonicalArtifactV1
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalHttpMethod
import com.gecesars.atxplan.domain.dataset.RegionalInventoryEntryFingerprint
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalJobArtifactOutcomeKind
import com.gecesars.atxplan.domain.dataset.RegionalJobArtifactOutcomeV1
import com.gecesars.atxplan.domain.dataset.RegionalJobArtifactOutcomeValidator
import com.gecesars.atxplan.domain.dataset.RegionalJobCheckpointKind
import com.gecesars.atxplan.domain.dataset.RegionalJobCheckpointReferenceV1
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobProblemV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRunOutcome
import com.gecesars.atxplan.domain.dataset.RegionalJobRunner
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalPlanFingerprint
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared scheduler-neutral execution core for one previously persisted regional job.
 *
 * The dataset repository remains the only provider-retry owner. Its one-way permit callback runs
 * while the dataset mutex is held and may update only the job repository; runner code never holds
 * a job-repository operation while entering the dataset repository. This explicit dataset -> job
 * lock order prevents an inverse nested lock. One application-wide runner mutex also prevents two
 * in-process scheduler callbacks from publishing the same inventory outcome out of order.
 *
 * The API 23-33 WorkManager worker invokes this class for an exact durable generation. The
 * production Data screen and application-start reconciliation path do not submit or observe it yet.
 */
class DefaultRegionalJobRunner(
    private val jobRepository: RegionalJobRepository,
    private val datasetRepository: RegionalDatasetRepository,
    private val planner: RegionalDatasetPlanner = RegionalDatasetPlanner(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RegionalJobRunner {
    override suspend fun run(
        request: RegionalJobExecutionRequestV1,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isStopped: () -> Boolean,
    ): RegionalJobRunOutcome = try {
        APPLICATION_RUNNER_MUTEX.withLock {
            runOwned(request, onProgress, isStopped)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: RegionalJobStorageException) {
        reconciliationRequired(
            request,
            code = "job-storage-indeterminate",
            message = "The durable regional job outcome is indeterminate and must be reloaded.",
        )
    }

    private suspend fun runOwned(
        request: RegionalJobExecutionRequestV1,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isStopped: () -> Boolean,
    ): RegionalJobRunOutcome {
        val initial = loadOwnedRecord(request) ?: return rejected(
            request,
            code = "job-record-missing",
            message = "The durable regional job record is missing.",
        )
        if (!requestOwnsRecord(request, initial)) return staleRequest(request)
        if (initial.state.isTerminal) {
            return RegionalJobRunOutcome.Terminal(initial, alreadyTerminal = true)
        }

        val plan = rebuildPlan(initial) ?: return terminalizeProblem(
            request = request,
            state = RegionalJobState.ORPHANED,
            problem = problem(
                code = "catalog-plan-incompatible",
                message = "The persisted regional plan no longer matches the installed fixed catalog.",
                retryable = true,
            ),
        )

        while (true) {
            var record = loadOwnedRecord(request) ?: return missingDuringExecution(request)
            if (!requestOwnsRecord(request, record)) return staleRequest(request)
            if (record.state.isTerminal) {
                return RegionalJobRunOutcome.Terminal(record, alreadyTerminal = false)
            }
            if (record.cancelRequested) return cancelDurably(request, record)
            if (isStopped()) return pauseOrReconcile(request, record)

            val artifactIndex = record.currentArtifactIndex
            if (artifactIndex !in plan.artifacts.indices) {
                return terminalizeProblem(
                    request = request,
                    state = RegionalJobState.ORPHANED,
                    problem = problem(
                        code = "artifact-index-invalid",
                        message = "The durable regional job points outside its canonical artifact plan.",
                        retryable = false,
                    ),
                )
            }

            record = enterDownloadState(request, record) ?: return staleRequest(request)
            if (record.state.isTerminal) {
                return RegionalJobRunOutcome.Terminal(record, alreadyTerminal = false)
            }

            val committedBeforeTransfer = try {
                datasetRepository.findCommittedArtifact(
                    plan = plan,
                    artifactIndex = artifactIndex,
                    minimumAcquiredAtEpochMillis = minimumRecoveryTime(record, artifactIndex),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return terminalizeProblem(
                    request = request,
                    state = RegionalJobState.FAILED,
                    problem = problem(
                        code = "inventory-validation-failed",
                        message = "The committed regional inventory could not be validated safely.",
                        retryable = true,
                    ),
                )
            }
            if (committedBeforeTransfer != null) {
                val committed = commitInventoryOutcome(
                    request = request,
                    plan = plan,
                    artifactIndex = artifactIndex,
                    inventoryRecord = committedBeforeTransfer,
                )
                when (committed) {
                    is MutationResult.Updated -> continue
                    is MutationResult.Terminal -> return RegionalJobRunOutcome.Terminal(
                        committed.record,
                        alreadyTerminal = false,
                    )
                    MutationResult.Stale -> return reconciliationRequired(
                        request,
                        "inventory-outcome-cas-conflict",
                        "The inventory result was committed, but the durable job changed before it could be linked.",
                    )
                }
            }

            val maximumAttempts = maximumProviderAttempts(record.canonicalPlan.artifacts[artifactIndex])
            val remainingAttempts = maximumAttempts - record.artifactAttemptCounts[artifactIndex]

            val permitFailure = AtomicReference<PermitFailure?>(null)
            var permittedAttempts = 0
            val acquisition = try {
                datasetRepository.acquireArtifact(
                    plan = plan,
                    artifactIndex = artifactIndex,
                    maximumProviderAttempts = remainingAttempts,
                    beforeProviderAttempt = { _ ->
                        when (
                            val permit = persistProviderAttemptPermit(
                                request = request,
                                artifactIndex = artifactIndex,
                                isStopped = isStopped,
                            )
                        ) {
                            AttemptPermit.Granted -> {
                                permittedAttempts += 1
                                true
                            }

                            is AttemptPermit.Denied -> {
                                permitFailure.compareAndSet(null, permit.failure)
                                false
                            }
                        }
                    },
                    onProgress = { progress ->
                        try {
                            onProgress(progress)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Progress is a projection; it cannot own or abort durable execution.
                        }
                    },
                    isCancelled = {
                        val failure = observeExecutionStop(
                            request = request,
                            artifactIndex = artifactIndex,
                            isStopped = isStopped,
                        )
                        if (failure != null) permitFailure.compareAndSet(null, failure)
                        failure != null
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return terminalizeProblem(
                    request = request,
                    state = RegionalJobState.FAILED,
                    problem = problem(
                        code = "artifact-operation-failed",
                        message = "The bounded regional artifact operation failed before a result was committed.",
                        retryable = true,
                    ),
                )
            }

            val executionFailure = permitFailure.get()
            val unusedReservationIsBounded =
                acquisition.result.status == RegionalTransferStatus.CANCELLED &&
                    acquisition.providerAttempts == permittedAttempts - 1
            val providerAttemptEvidenceMatches = when (executionFailure) {
                PermitFailure.CANCELED,
                PermitFailure.STOPPED,
                -> acquisition.providerAttempts == permittedAttempts || unusedReservationIsBounded

                else -> acquisition.providerAttempts == permittedAttempts
            }
            if (!providerAttemptEvidenceMatches) {
                return reconciliationRequired(
                    request,
                    code = "provider-attempt-accounting-mismatch",
                    message = "Provider-attempt evidence does not match the durable job permits.",
                )
            }

            if (acquisition.networkBytesTransferred > 0L) {
                when (
                    recordNetworkBytes(
                        request = request,
                        artifactIndex = artifactIndex,
                        additionalBytes = acquisition.networkBytesTransferred,
                    )
                ) {
                    is MutationResult.Terminal -> {
                        val latest = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                        return RegionalJobRunOutcome.Terminal(latest, alreadyTerminal = false)
                    }

                    MutationResult.Stale -> return reconciliationRequired(
                        request,
                        code = "network-byte-cas-conflict",
                        message = "Regional bytes were accepted, but their durable accounting needs reconciliation.",
                    )

                    is MutationResult.Updated -> Unit
                }
            }

            when (executionFailure) {
                PermitFailure.CANCELED -> {
                    val latest = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                    return cancelDurably(request, latest)
                }

                PermitFailure.STOPPED -> {
                    val latest = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                    return pauseOrReconcile(request, latest)
                }

                PermitFailure.STALE,
                PermitFailure.STORAGE,
                -> return reconciliationRequired(
                    request,
                    code = "provider-attempt-permit-unconfirmed",
                    message = "A provider request was withheld because its durable attempt permit could not be confirmed.",
                )

                null -> Unit
            }

            val latest = loadOwnedRecord(request) ?: return missingDuringExecution(request)
            if (!requestOwnsRecord(request, latest)) return staleRequest(request)
            if (latest.cancelRequested) return cancelDurably(request, latest)
            if (isStopped()) return pauseOrReconcile(request, latest)

            when (acquisition.result.status) {
                RegionalTransferStatus.READY,
                RegionalTransferStatus.EXISTING,
                -> when (
                    commitInventoryOutcome(
                        request = request,
                        plan = plan,
                        artifactIndex = artifactIndex,
                        inventoryRecord = acquisition.committedInventoryRecord,
                    )
                ) {
                    is MutationResult.Updated -> Unit
                    is MutationResult.Terminal -> {
                        val terminal = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                        return RegionalJobRunOutcome.Terminal(terminal, alreadyTerminal = false)
                    }

                    MutationResult.Stale -> return reconciliationRequired(
                        request,
                        code = "inventory-outcome-cas-conflict",
                        message = "The inventory result was committed, but the durable job changed before it could be linked.",
                    )
                }

                RegionalTransferStatus.NOT_FOUND -> {
                    if (plan.artifacts[artifactIndex].source.optionalWhenNotPublished) {
                        when (
                            commitInventoryOutcome(
                                request = request,
                                plan = plan,
                                artifactIndex = artifactIndex,
                                inventoryRecord = acquisition.committedInventoryRecord,
                            )
                        ) {
                            is MutationResult.Updated -> Unit
                            is MutationResult.Terminal -> {
                                val terminal = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                                return RegionalJobRunOutcome.Terminal(terminal, alreadyTerminal = false)
                            }

                            MutationResult.Stale -> return reconciliationRequired(
                                request,
                                code = "inventory-outcome-cas-conflict",
                                message = "The optional-missing result was committed, but the durable job changed before it could be linked.",
                            )
                        }
                    } else {
                        return terminalizeProblem(
                            request = request,
                            state = RegionalJobState.FAILED,
                            problem = problem(
                                code = "required-artifact-not-found",
                                message = "The provider did not publish a required regional artifact.",
                                retryable = true,
                            ),
                        )
                    }
                }

                RegionalTransferStatus.CANCELLED -> {
                    val afterCancellation = loadOwnedRecord(request) ?: return missingDuringExecution(request)
                    return if (afterCancellation.cancelRequested) {
                        cancelDurably(request, afterCancellation)
                    } else {
                        pauseOrReconcile(request, afterCancellation)
                    }
                }

                RegionalTransferStatus.FAILED -> return terminalizeProblem(
                    request = request,
                    state = RegionalJobState.FAILED,
                    problem = problem(
                        code = if (remainingAttempts == 0) {
                            "provider-attempts-exhausted"
                        } else {
                            "artifact-transfer-failed"
                        },
                        message = if (remainingAttempts == 0) {
                            "No valid local recovery remained after the bounded provider attempts were exhausted."
                        } else {
                            "The regional artifact transfer or processing step failed."
                        },
                        retryable = true,
                    ),
                )

                RegionalTransferStatus.QUEUED,
                RegionalTransferStatus.DOWNLOADING,
                RegionalTransferStatus.VERIFYING,
                RegionalTransferStatus.PROCESSING,
                -> return reconciliationRequired(
                    request,
                    code = "artifact-result-incomplete",
                    message = "The artifact operation returned a nonterminal result and must be reconciled.",
                )
            }
        }
    }

    private suspend fun persistProviderAttemptPermit(
        request: RegionalJobExecutionRequestV1,
        artifactIndex: Int,
        isStopped: () -> Boolean,
    ): AttemptPermit {
        if (isStopped()) return AttemptPermit.Denied(PermitFailure.STOPPED)
        repeat(MAXIMUM_CAS_RELOADS) {
            val record = try {
                jobRepository.get(request.jobId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return AttemptPermit.Denied(PermitFailure.STORAGE)
            } ?: return AttemptPermit.Denied(PermitFailure.STALE)
            if (!requestOwnsRecord(request, record) || record.currentArtifactIndex != artifactIndex) {
                return AttemptPermit.Denied(PermitFailure.STALE)
            }
            if (record.cancelRequested || record.state == RegionalJobState.CANCELED) {
                return AttemptPermit.Denied(PermitFailure.CANCELED)
            }
            if (record.state != RegionalJobState.RUNNING_DOWNLOAD || record.state.isTerminal) {
                return AttemptPermit.Denied(PermitFailure.STALE)
            }
            val counts = record.artifactAttemptCounts.toMutableList()
            val maximum = maximumProviderAttempts(record.canonicalPlan.artifacts[artifactIndex])
            if (counts[artifactIndex] >= maximum) return AttemptPermit.Denied(PermitFailure.STALE)
            counts[artifactIndex] += 1
            try {
                jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = RegionalJobState.RUNNING_DOWNLOAD,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                        artifactAttemptCounts = counts,
                    )
                }
                return AttemptPermit.Granted
            } catch (_: RegionalJobConflictException) {
                // A durable cancel or another exact-generation runner won the CAS; reload.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return AttemptPermit.Denied(PermitFailure.STORAGE)
            }
        }
        return AttemptPermit.Denied(PermitFailure.STALE)
    }

    private suspend fun observeExecutionStop(
        request: RegionalJobExecutionRequestV1,
        artifactIndex: Int,
        isStopped: () -> Boolean,
    ): PermitFailure? {
        if (isStopped()) return PermitFailure.STOPPED
        val record = try {
            jobRepository.get(request.jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PermitFailure.STORAGE
        } ?: return PermitFailure.STALE
        return when {
            !requestOwnsRecord(request, record) || record.currentArtifactIndex != artifactIndex ->
                PermitFailure.STALE
            record.cancelRequested || record.state == RegionalJobState.CANCELED ->
                PermitFailure.CANCELED
            record.state.isTerminal -> PermitFailure.STALE
            else -> null
        }
    }

    private suspend fun recordNetworkBytes(
        request: RegionalJobExecutionRequestV1,
        artifactIndex: Int,
        additionalBytes: Long,
    ): MutationResult {
        require(additionalBytes > 0L) { "Regional network-byte accounting must advance." }
        repeat(MAXIMUM_CAS_RELOADS) {
            val record = loadOwnedRecord(request) ?: return MutationResult.Stale
            if (!requestOwnsRecord(request, record) || record.currentArtifactIndex != artifactIndex) {
                return MutationResult.Stale
            }
            if (record.state.isTerminal) return MutationResult.Terminal(record)
            if (record.state != RegionalJobState.RUNNING_DOWNLOAD) return MutationResult.Stale
            val total = try {
                Math.addExact(record.networkBytesTransferred, additionalBytes)
            } catch (_: ArithmeticException) {
                return MutationResult.Stale
            }
            try {
                val updated = jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = RegionalJobState.RUNNING_DOWNLOAD,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                        networkBytesTransferred = total,
                    )
                }
                return MutationResult.Updated(updated)
            } catch (_: RegionalJobConflictException) {
                // Reload the exact generation.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MutationResult.Stale
            }
        }
        return MutationResult.Stale
    }

    private suspend fun commitInventoryOutcome(
        request: RegionalJobExecutionRequestV1,
        plan: RegionalDownloadPlan,
        artifactIndex: Int,
        inventoryRecord: RegionalInventoryRecord,
    ): MutationResult {
        val outcome = inventoryOutcome(
            artifact = plan.artifacts[artifactIndex].source.optionalWhenNotPublished,
            artifactIndex = artifactIndex,
            record = inventoryRecord,
        ) ?: return MutationResult.Stale

        repeat(MAXIMUM_CAS_RELOADS) {
            var record = loadOwnedRecord(request) ?: return MutationResult.Stale
            if (!requestOwnsRecord(request, record)) return MutationResult.Stale
            if (record.state.isTerminal) return MutationResult.Terminal(record)
            if (record.cancelRequested) return cancelMutation(request, record)
            if (record.currentArtifactIndex > artifactIndex) {
                return if (outcome in record.artifactOutcomes) {
                    MutationResult.Updated(record)
                } else {
                    MutationResult.Stale
                }
            }
            if (record.currentArtifactIndex != artifactIndex) return MutationResult.Stale

            if (outcome.kind == RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND) {
                if (record.state != RegionalJobState.RUNNING_DOWNLOAD) {
                    record = enterDownloadState(request, record) ?: return MutationResult.Stale
                    if (record.state.isTerminal) return MutationResult.Terminal(record)
                }
                val nextState = if (artifactIndex == plan.artifacts.lastIndex) {
                    RegionalJobState.SUCCEEDED
                } else {
                    RegionalJobState.RUNNING_DOWNLOAD
                }
                try {
                    val updated = jobRepository.update(record.jobId, record.revision) { current ->
                        current.transitionTo(
                            nextState = nextState,
                            nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                            currentArtifactIndex = artifactIndex + 1,
                            artifactOutcomes = current.artifactOutcomes + outcome,
                        )
                    }
                    return if (updated.state.isTerminal) {
                        MutationResult.Terminal(updated)
                    } else {
                        MutationResult.Updated(updated)
                    }
                } catch (_: RegionalJobConflictException) {
                    return@repeat
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return MutationResult.Stale
                }
            }

            if (record.state !in setOf(
                    RegionalJobState.RUNNING_DOWNLOAD,
                    RegionalJobState.RUNNING_VERIFY,
                    RegionalJobState.RUNNING_PROCESS,
                )
            ) {
                record = enterDownloadState(request, record) ?: return MutationResult.Stale
            }
            val checkpoint = verifiedRawCheckpoint(artifactIndex, inventoryRecord)
                ?: return MutationResult.Stale
            val checkpoints = promoteToVerifiedRaw(record, inventoryRecord, checkpoint)
                ?: return MutationResult.Stale
            if (record.state != RegionalJobState.RUNNING_PROCESS) {
                try {
                    record = jobRepository.update(record.jobId, record.revision) { current ->
                        current.transitionTo(
                            nextState = RegionalJobState.RUNNING_PROCESS,
                            nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                            checkpointReferences = checkpoints,
                        )
                    }
                } catch (_: RegionalJobConflictException) {
                    return@repeat
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return MutationResult.Stale
                }
            }

            val nextState = if (artifactIndex == plan.artifacts.lastIndex) {
                RegionalJobState.SUCCEEDED
            } else {
                RegionalJobState.RUNNING_DOWNLOAD
            }
            try {
                val updated = jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = nextState,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                        currentArtifactIndex = artifactIndex + 1,
                        artifactOutcomes = current.artifactOutcomes + outcome,
                    )
                }
                return if (updated.state.isTerminal) {
                    MutationResult.Terminal(updated)
                } else {
                    MutationResult.Updated(updated)
                }
            } catch (_: RegionalJobConflictException) {
                // Reload; the outcome may already be durable.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MutationResult.Stale
            }
        }
        return MutationResult.Stale
    }

    private fun promoteToVerifiedRaw(
        record: RegionalJobRecordV1,
        inventoryRecord: RegionalInventoryRecord,
        verified: RegionalJobCheckpointReferenceV1,
    ): List<RegionalJobCheckpointReferenceV1>? {
        val sameArtifact = record.checkpointReferences.filter { it.artifactIndex == verified.artifactIndex }
        val existingVerified = sameArtifact.firstOrNull { it.kind == RegionalJobCheckpointKind.VERIFIED_RAW }
        if (existingVerified != null && existingVerified != verified) return null
        val existingProcessed = sameArtifact.firstOrNull {
            it.kind == RegionalJobCheckpointKind.PROCESSED_OUTPUT
        }
        if (existingProcessed != null) {
            val output = inventoryRecord.processedOutput ?: return null
            val outputSha256 = output.sha256 ?: return null
            if (output.bytes <= 0L) return null
            val expectedProcessed = RegionalJobCheckpointReferenceV1(
                artifactIndex = verified.artifactIndex,
                kind = RegionalJobCheckpointKind.PROCESSED_OUTPUT,
                relativePath = output.relativePath,
                bytes = output.bytes,
                sha256 = outputSha256,
            )
            if (existingProcessed != expectedProcessed) return null
            return record.checkpointReferences
        }
        return record.checkpointReferences
            .filterNot { checkpoint ->
                checkpoint.artifactIndex == verified.artifactIndex &&
                    checkpoint.kind in setOf(
                        RegionalJobCheckpointKind.TRANSFER_PARTIAL,
                        RegionalJobCheckpointKind.TRANSFER_COMPLETE,
                    )
            }
            .let { checkpoints ->
                if (verified in checkpoints) checkpoints else checkpoints + verified
            }
    }

    private fun verifiedRawCheckpoint(
        artifactIndex: Int,
        record: RegionalInventoryRecord,
    ): RegionalJobCheckpointReferenceV1? {
        val bytes = record.bytes ?: return null
        val sha256 = record.sha256 ?: return null
        if (bytes <= 0L) return null
        return RegionalJobCheckpointReferenceV1(
            artifactIndex = artifactIndex,
            kind = RegionalJobCheckpointKind.VERIFIED_RAW,
            relativePath = record.relativePath,
            bytes = bytes,
            sha256 = sha256,
        )
    }

    private suspend fun enterDownloadState(
        request: RegionalJobExecutionRequestV1,
        startingRecord: RegionalJobRecordV1,
    ): RegionalJobRecordV1? {
        var record = startingRecord
        repeat(MAXIMUM_CAS_RELOADS) {
            if (!requestOwnsRecord(request, record)) return null
            if (record.state.isTerminal || record.state == RegionalJobState.RUNNING_DOWNLOAD) return record
            if (record.cancelRequested) {
                return when (val canceled = cancelMutation(request, record)) {
                    is MutationResult.Terminal -> canceled.record
                    is MutationResult.Updated -> canceled.record
                    MutationResult.Stale -> null
                }
            }
            val transition = when (record.state) {
                RegionalJobState.ENQUEUE_PENDING -> Triple(
                    RegionalJobState.RUNNING_DOWNLOAD,
                    request.schedulerKind,
                    request.schedulerIdentity,
                )

                RegionalJobState.QUEUED,
                RegionalJobState.RUNNING_VERIFY,
                RegionalJobState.RUNNING_PROCESS,
                RegionalJobState.PAUSED_CONSTRAINT,
                -> Triple(
                    RegionalJobState.RUNNING_DOWNLOAD,
                    record.schedulerKind,
                    record.schedulerIdentity,
                )

                RegionalJobState.DRAFT -> return null
                RegionalJobState.RUNNING_DOWNLOAD,
                RegionalJobState.SUCCEEDED,
                RegionalJobState.FAILED,
                RegionalJobState.CANCELED,
                RegionalJobState.ORPHANED,
                -> return record
            }
            try {
                return jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = transition.first,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                        schedulerKind = transition.second,
                        schedulerIdentity = transition.third,
                    )
                }
            } catch (_: RegionalJobConflictException) {
                record = loadOwnedRecord(request) ?: return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: RegionalJobStorageException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private suspend fun cancelDurably(
        request: RegionalJobExecutionRequestV1,
        startingRecord: RegionalJobRecordV1,
    ): RegionalJobRunOutcome {
        return when (val result = cancelMutation(request, startingRecord)) {
            is MutationResult.Terminal -> RegionalJobRunOutcome.Terminal(result.record, alreadyTerminal = false)
            is MutationResult.Updated -> RegionalJobRunOutcome.Terminal(result.record, alreadyTerminal = false)
            MutationResult.Stale -> reconciliationRequired(
                request,
                code = "cancel-cas-conflict",
                message = "The durable cancellation could not be committed against the current job revision.",
            )
        }
    }

    private suspend fun cancelMutation(
        request: RegionalJobExecutionRequestV1,
        startingRecord: RegionalJobRecordV1,
    ): MutationResult {
        var record = startingRecord
        repeat(MAXIMUM_CAS_RELOADS) {
            if (!requestOwnsRecord(request, record)) return MutationResult.Stale
            if (record.state.isTerminal) return MutationResult.Terminal(record)
            try {
                val updated = jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = RegionalJobState.CANCELED,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                    )
                }
                return MutationResult.Terminal(updated)
            } catch (_: RegionalJobConflictException) {
                record = loadOwnedRecord(request) ?: return MutationResult.Stale
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MutationResult.Stale
            }
        }
        return MutationResult.Stale
    }

    private suspend fun pauseOrReconcile(
        request: RegionalJobExecutionRequestV1,
        startingRecord: RegionalJobRecordV1,
    ): RegionalJobRunOutcome {
        var record = startingRecord
        repeat(MAXIMUM_CAS_RELOADS) {
            if (!requestOwnsRecord(request, record)) return staleRequest(request)
            if (record.state.isTerminal) {
                return RegionalJobRunOutcome.Terminal(record, alreadyTerminal = false)
            }
            if (record.cancelRequested) return cancelDurably(request, record)
            if (record.state == RegionalJobState.PAUSED_CONSTRAINT) {
                return reconciliationRequired(
                    request,
                    code = "scheduler-stopped",
                    message = "The Android execution envelope stopped before the regional job completed.",
                )
            }
            if (RegionalJobState.PAUSED_CONSTRAINT !in allowedTargets(record.state)) {
                return reconciliationRequired(
                    request,
                    code = "scheduler-stopped",
                    message = "The Android execution envelope stopped before the job could publish a paused state.",
                )
            }
            try {
                record = jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = RegionalJobState.PAUSED_CONSTRAINT,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                    )
                }
                return reconciliationRequired(
                    request,
                    code = "scheduler-stopped",
                    message = "The Android execution envelope stopped before the regional job completed.",
                )
            } catch (_: RegionalJobConflictException) {
                record = loadOwnedRecord(request) ?: return missingDuringExecution(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return reconciliationRequired(
                    request,
                    code = "pause-state-unconfirmed",
                    message = "The stopped regional job must be reloaded before rescheduling.",
                )
            }
        }
        return reconciliationRequired(
            request,
            code = "pause-cas-conflict",
            message = "The stopped regional job changed repeatedly and must be reconciled.",
        )
    }

    private suspend fun terminalizeProblem(
        request: RegionalJobExecutionRequestV1,
        state: RegionalJobState,
        problem: RegionalJobProblemV1,
    ): RegionalJobRunOutcome {
        require(state in setOf(RegionalJobState.FAILED, RegionalJobState.ORPHANED))
        repeat(MAXIMUM_CAS_RELOADS) {
            val record = loadOwnedRecord(request) ?: return missingDuringExecution(request)
            if (!requestOwnsRecord(request, record)) return staleRequest(request)
            if (record.state.isTerminal) {
                return RegionalJobRunOutcome.Terminal(record, alreadyTerminal = false)
            }
            if (record.cancelRequested) return cancelDurably(request, record)
            try {
                val updated = jobRepository.update(record.jobId, record.revision) { current ->
                    current.transitionTo(
                        nextState = state,
                        nowEpochMillis = nowAtLeast(current.updatedAtEpochMillis),
                        terminalProblem = problem,
                    )
                }
                return RegionalJobRunOutcome.Terminal(updated, alreadyTerminal = false)
            } catch (_: RegionalJobConflictException) {
                // Reload and preserve cancellation priority.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return reconciliationRequired(
                    request,
                    code = "terminal-state-unconfirmed",
                    message = "The regional failure result could not be confirmed in durable storage.",
                )
            }
        }
        return reconciliationRequired(
            request,
            code = "terminal-cas-conflict",
            message = "The regional job changed repeatedly while publishing its terminal result.",
        )
    }

    private fun rebuildPlan(record: RegionalJobRecordV1): RegionalDownloadPlan? {
        if (!RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(record.canonicalPlan)) return null
        return try {
            planner.plan(record.canonicalPlan.toRequest()).takeIf { rebuilt ->
                RegionalPlanFingerprint.canonicalize(rebuilt) == record.canonicalPlan &&
                    RegionalPlanFingerprint.calculate(rebuilt) == record.planFingerprintSha256
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadOwnedRecord(request: RegionalJobExecutionRequestV1): RegionalJobRecordV1? =
        jobRepository.get(request.jobId)

    private fun requestOwnsRecord(
        request: RegionalJobExecutionRequestV1,
        record: RegionalJobRecordV1,
    ): Boolean {
        if (
            request.jobId != record.jobId ||
            request.planFingerprintSha256 != record.planFingerprintSha256 ||
            request.schedulerKind != record.schedulerKind ||
            request.schedulerGeneration != record.schedulerGeneration
        ) {
            return false
        }
        return when {
            record.state == RegionalJobState.ENQUEUE_PENDING -> record.schedulerIdentity == null
            record.state.isTerminal && record.schedulerIdentity == null -> true
            else -> record.schedulerIdentity == request.schedulerIdentity
        }
    }

    private fun minimumRecoveryTime(record: RegionalJobRecordV1, artifactIndex: Int): Long? =
        record.canonicalPlan.artifacts[artifactIndex]
            .takeIf { it.cachePolicy == RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH }
            ?.let { record.createdAtEpochMillis }

    private fun maximumProviderAttempts(artifact: RegionalCanonicalArtifactV1): Int = when (artifact.httpMethod) {
        RegionalHttpMethod.GET -> 3
        RegionalHttpMethod.POST -> 2
    }

    private fun nowAtLeast(previous: Long): Long = maxOf(previous, clock())

    private fun staleRequest(request: RegionalJobExecutionRequestV1): RegionalJobRunOutcome.Rejected = rejected(
        request,
        code = "stale-scheduler-generation",
        message = "This scheduler entry does not own the current durable regional job generation.",
    )

    private fun missingDuringExecution(
        request: RegionalJobExecutionRequestV1,
    ): RegionalJobRunOutcome.ReconciliationRequired = reconciliationRequired(
        request,
        code = "job-record-unavailable",
        message = "The durable regional job record became unavailable and must be reconciled.",
    )

    private fun rejected(
        request: RegionalJobExecutionRequestV1,
        code: String,
        message: String,
    ) = RegionalJobRunOutcome.Rejected(
        jobId = request.jobId,
        problem = problem(code, message, retryable = false),
    )

    private fun reconciliationRequired(
        request: RegionalJobExecutionRequestV1,
        code: String,
        message: String,
    ) = RegionalJobRunOutcome.ReconciliationRequired(
        jobId = request.jobId,
        problem = problem(code, message, retryable = true),
    )

    private fun problem(
        code: String,
        message: String,
        retryable: Boolean,
    ) = RegionalJobProblemV1(
        code = code,
        message = message,
        retryableByUser = retryable,
    )

    private fun allowedTargets(state: RegionalJobState): Set<RegionalJobState> = when (state) {
        RegionalJobState.QUEUED,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.RUNNING_VERIFY,
        RegionalJobState.RUNNING_PROCESS,
        -> setOf(RegionalJobState.PAUSED_CONSTRAINT)

        else -> emptySet()
    }

    private sealed interface MutationResult {
        data class Updated(val record: RegionalJobRecordV1) : MutationResult
        data class Terminal(val record: RegionalJobRecordV1) : MutationResult
        data object Stale : MutationResult
    }

    private sealed interface AttemptPermit {
        data object Granted : AttemptPermit
        data class Denied(val failure: PermitFailure) : AttemptPermit
    }

    private enum class PermitFailure {
        CANCELED,
        STOPPED,
        STALE,
        STORAGE,
    }

    private companion object {
        const val MAXIMUM_CAS_RELOADS = 4
        val APPLICATION_RUNNER_MUTEX = Mutex()
    }
}

/**
 * Loads file-verified inventory evidence before exposing the reconciler's synchronous validator.
 */
class RegionalJobInventoryOutcomeResolver(
    private val datasetRepository: RegionalDatasetRepository,
    private val planner: RegionalDatasetPlanner = RegionalDatasetPlanner(),
) {
    suspend fun resolve(
        record: RegionalJobRecordV1,
        artifactIndex: Int,
    ): RegionalJobArtifactOutcomeV1? {
        if (artifactIndex !in record.canonicalPlan.artifacts.indices) return null
        val plan = try {
            if (!RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(record.canonicalPlan)) return null
            planner.plan(record.canonicalPlan.toRequest()).takeIf { rebuilt ->
                RegionalPlanFingerprint.canonicalize(rebuilt) == record.canonicalPlan &&
                    RegionalPlanFingerprint.calculate(rebuilt) == record.planFingerprintSha256
            } ?: return null
        } catch (_: Exception) {
            return null
        }
        val inventoryRecord = try {
            datasetRepository.findCommittedArtifactEvidence(
                plan = plan,
                artifactIndex = artifactIndex,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        return inventoryOutcome(
            artifact = plan.artifacts[artifactIndex].source.optionalWhenNotPublished,
            artifactIndex = artifactIndex,
            record = inventoryRecord,
        )
    }

    suspend fun createValidator(
        records: Collection<RegionalJobRecordV1>,
    ): RegionalJobArtifactOutcomeValidator {
        val evidence = mutableMapOf<Pair<String, Int>, RegionalJobArtifactOutcomeV1>()
        records.forEach { record ->
            record.artifactOutcomes.forEach { outcome ->
                resolve(record, outcome.artifactIndex)?.let { resolved ->
                    evidence[record.jobId to outcome.artifactIndex] = resolved
                }
            }
        }
        return RegionalJobArtifactOutcomeValidator { record, artifact, outcome ->
            record.canonicalPlan.artifacts.getOrNull(outcome.artifactIndex) == artifact &&
                evidence[record.jobId to outcome.artifactIndex] == outcome
        }
    }
}

private fun inventoryOutcome(
    artifact: Boolean,
    artifactIndex: Int,
    record: RegionalInventoryRecord,
): RegionalJobArtifactOutcomeV1? {
    val kind = when (record.status) {
        RegionalTransferStatus.READY -> RegionalJobArtifactOutcomeKind.READY
        RegionalTransferStatus.EXISTING -> RegionalJobArtifactOutcomeKind.EXISTING
        RegionalTransferStatus.NOT_FOUND -> if (artifact) {
            RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND
        } else {
            return null
        }

        else -> return null
    }
    return RegionalJobArtifactOutcomeV1(
        artifactIndex = artifactIndex,
        kind = kind,
        inventoryEntrySha256 = RegionalInventoryEntryFingerprint.calculate(record),
    )
}
