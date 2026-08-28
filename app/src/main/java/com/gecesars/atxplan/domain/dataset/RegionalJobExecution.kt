package com.gecesars.atxplan.domain.dataset

/**
 * Exact scheduler generation that is allowed to invoke a persisted regional job.
 *
 * The execution fingerprint and physical scheduler identity are both required so an obsolete
 * Android scheduler entry cannot execute a newer generation or a differently reviewed plan.
 */
data class RegionalJobExecutionRequestV1(
    val jobId: String,
    val planFingerprintSha256: String,
    val schedulerKind: RegionalJobSchedulerKind,
    val schedulerGeneration: Int,
    val schedulerIdentity: String,
) {
    init {
        require(JOB_EXECUTION_SHA256_PATTERN.matches(planFingerprintSha256)) {
            "A regional job execution request requires a lowercase plan SHA-256."
        }
        RegionalScheduledJobV1(
            jobId = jobId,
            planFingerprintSha256 = planFingerprintSha256,
            schedulerKind = schedulerKind,
            schedulerGeneration = schedulerGeneration,
            schedulerIdentity = schedulerIdentity,
            state = RegionalScheduledJobState.PENDING,
        )
    }
}

sealed interface RegionalJobRunOutcome {
    val jobId: String

    /** The runner reached or observed an immutable terminal record. */
    data class Terminal(
        val record: RegionalJobRecordV1,
        val alreadyTerminal: Boolean,
    ) : RegionalJobRunOutcome {
        init {
            require(record.state.isTerminal) { "A terminal runner outcome requires a terminal job record." }
        }

        override val jobId: String = record.jobId
    }

    /** The scheduler request did not own the requested durable job generation. */
    data class Rejected(
        override val jobId: String,
        val problem: RegionalJobProblemV1,
    ) : RegionalJobRunOutcome

    /** Durable state must be reloaded and reconciled before another external attempt. */
    data class ReconciliationRequired(
        override val jobId: String,
        val problem: RegionalJobProblemV1,
    ) : RegionalJobRunOutcome
}

/** Scheduler-neutral execution boundary. Android scheduler adapters remain separate. */
fun interface RegionalJobRunner {
    suspend fun run(
        request: RegionalJobExecutionRequestV1,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isStopped: () -> Boolean,
    ): RegionalJobRunOutcome
}

private val JOB_EXECUTION_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
