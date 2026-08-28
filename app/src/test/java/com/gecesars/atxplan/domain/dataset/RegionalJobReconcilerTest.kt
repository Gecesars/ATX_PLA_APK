package com.gecesars.atxplan.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalJobReconcilerTest {
    @Test
    fun `unavailable scheduler observation is a strict no-op`() {
        val actions = RegionalJobReconciler.reconcile(
            records = listOf(testRegionalJob()),
            schedulerSnapshot = RegionalSchedulerSnapshotV1(
                availability = RegionalSchedulerSnapshotAvailability.UNAVAILABLE,
                jobs = emptyList(),
            ),
            unreadableJobIds = emptySet(),
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `scheduler snapshot rejects one physical target reused across generations`() {
        val target = scheduled(testRegionalJob()).copy(
            schedulerIdentity = "work-shared-target",
        )

        assertThrows(IllegalArgumentException::class.java) {
            completeSnapshot(
                target,
                target.copy(schedulerGeneration = target.schedulerGeneration + 1),
            )
        }
    }

    @Test
    fun `scheduler snapshot rejects one physical target reused across job IDs`() {
        val target = scheduled(testRegionalJob()).copy(
            schedulerIdentity = "work-shared-target",
        )

        assertThrows(IllegalArgumentException::class.java) {
            completeSnapshot(
                target,
                target.copy(jobId = "123e4567-e89b-42d3-a456-426614174099"),
            )
        }
    }

    @Test
    fun `scheduler snapshot allows the same identity in different scheduler kinds`() {
        val workManagerTarget = scheduled(testRegionalJob()).copy(
            schedulerIdentity = "scheduler-local-identity",
        )
        val userInitiatedTarget = workManagerTarget.copy(
            schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
        )

        val snapshot = completeSnapshot(workManagerTarget, userInitiatedTarget)

        assertEquals(2, snapshot.jobs.size)
        assertEquals(
            setOf(
                RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
                RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
            ),
            snapshot.jobs.map(RegionalScheduledJobV1::schedulerKind).toSet(),
        )
    }

    @Test
    fun `unreadable job IDs suppress external cancellation and invalidate absence decisions`() {
        val record = testRegionalJob()
        val externalTarget = scheduled(record)
        val absenceAction = RegionalJobReconciler.reconcile(
            records = emptyList(),
            schedulerSnapshot = completeSnapshot(externalTarget),
            unreadableJobIds = emptySet(),
        ).single()

        assertEquals(RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY, absenceAction.kind)
        assertTrue(absenceAction.expectedRecordAbsent)
        assertTrue(absenceAction.isCurrentForAbsentRecord(emptyList(), emptySet()))
        assertTrue(!absenceAction.isCurrentForAbsentRecord(listOf(record), emptySet()))
        assertTrue(!absenceAction.isCurrentForAbsentRecord(emptyList(), setOf(record.jobId)))

        val protectedActions = RegionalJobReconciler.reconcile(
            records = emptyList(),
            schedulerSnapshot = completeSnapshot(externalTarget),
            unreadableJobIds = setOf(record.jobId),
        )

        assertTrue(protectedActions.isEmpty())
    }

    @Test
    fun `unreadable job IDs must be valid bounded and disjoint from readable records`() {
        val record = testRegionalJob()
        assertThrows(IllegalArgumentException::class.java) {
            RegionalJobReconciler.reconcile(
                records = listOf(record),
                schedulerSnapshot = completeSnapshot(),
                unreadableJobIds = setOf(record.jobId),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalJobReconciler.reconcile(
                records = emptyList(),
                schedulerSnapshot = completeSnapshot(),
                unreadableJobIds = setOf("not-a-job-id"),
            )
        }

        val excessiveUnreadableIds = (0..64).map { index ->
            "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"
        }.toSet()
        assertThrows(IllegalArgumentException::class.java) {
            RegionalJobReconciler.reconcile(
                records = emptyList(),
                schedulerSnapshot = completeSnapshot(),
                unreadableJobIds = excessiveUnreadableIds,
            )
        }
    }

    @Test
    fun `pending intent is adopted when present and enqueued when absent`() {
        val record = testRegionalJob()
        val present = RegionalJobReconciler.reconcile(
            records = listOf(record),
            schedulerSnapshot = completeSnapshot(scheduled(record)),
            unreadableJobIds = emptySet(),
        )
        val absent = RegionalJobReconciler.reconcile(
            records = listOf(record),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
        )

        assertEquals(listOf(RegionalJobReconciliationActionKind.ADOPT_AS_QUEUED), present.map { it.kind })
        assertEquals(record.schedulerKind, present.single().targetSchedulerKind)
        assertEquals(record.schedulerGeneration, present.single().targetSchedulerGeneration)
        assertEquals("work-regional-1", present.single().targetSchedulerIdentity)
        assertEquals(listOf(RegionalJobReconciliationActionKind.ENQUEUE), absent.map { it.kind })
    }

    @Test
    fun `durable cancellation cancels scheduler and marks the record canceled with stale decision guards`() {
        val canceled = testRegionalJob().requestCancellation(1_010L)
        val present = RegionalJobReconciler.reconcile(
            records = listOf(canceled),
            schedulerSnapshot = completeSnapshot(scheduled(canceled)),
            unreadableJobIds = emptySet(),
        )
        val absent = RegionalJobReconciler.reconcile(
            records = listOf(canceled),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
        )

        assertEquals(
            listOf(
                RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
                RegionalJobReconciliationActionKind.MARK_CANCELED,
            ),
            present.map { it.kind },
        )
        assertEquals(listOf(RegionalJobReconciliationActionKind.MARK_CANCELED), absent.map { it.kind })
        (present + absent).forEach { action ->
            assertEquals(canceled.revision, action.expectedRevision)
            assertEquals(canceled.planFingerprintSha256, action.expectedPlanFingerprintSha256)
            assertEquals(canceled.schedulerGeneration, action.expectedRecordSchedulerGeneration)
            assertTrue(!action.expectedRecordAbsent)
        }
        val cancel = present.first()
        assertEquals(canceled.schedulerKind, cancel.targetSchedulerKind)
        assertEquals(canceled.schedulerGeneration, cancel.targetSchedulerGeneration)
        assertEquals("work-regional-1", cancel.targetSchedulerIdentity)
    }

    @Test
    fun `an enqueue decision retains its old expected revision after cancellation wins`() {
        val pending = testRegionalJob()
        val enqueue = RegionalJobReconciler.reconcile(
            records = listOf(pending),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
        ).single()
        val canceled = pending.requestCancellation(1_010L)

        assertEquals(RegionalJobReconciliationActionKind.ENQUEUE, enqueue.kind)
        assertEquals(pending.revision, enqueue.expectedRevision)
        assertEquals(pending.planFingerprintSha256, enqueue.expectedPlanFingerprintSha256)
        assertEquals(pending.schedulerGeneration, enqueue.expectedRecordSchedulerGeneration)
        assertTrue(!enqueue.expectedRecordAbsent)
        assertTrue(enqueue.isCurrentFor(pending))
        assertTrue(!enqueue.isCurrentFor(canceled))
        assertTrue(enqueue.expectedRevision != canceled.revision)
    }

    @Test
    fun `cancellation prevents resurrection from queued running processing and paused states`() {
        val pending = testRegionalJob()
        val queued = pending.transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val running = queued.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_020L,
            artifactAttemptCounts = listOf(1),
        )
        val processing = testProcessingRegionalJob()
        val paused = running.transitionTo(RegionalJobState.PAUSED_CONSTRAINT, 1_030L)

        listOf(pending, queued, running, processing, paused).forEach { record ->
            val canceled = record.requestCancellation(record.updatedAtEpochMillis + 1L)
            val present = RegionalJobReconciler.reconcile(
                records = listOf(canceled),
                schedulerSnapshot = completeSnapshot(scheduled(canceled)),
                unreadableJobIds = emptySet(),
            )
            val absent = RegionalJobReconciler.reconcile(
                records = listOf(canceled),
                schedulerSnapshot = completeSnapshot(),
                unreadableJobIds = emptySet(),
            )

            assertEquals(
                listOf(
                    RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
                    RegionalJobReconciliationActionKind.MARK_CANCELED,
                ),
                present.map { it.kind },
            )
            assertEquals(
                listOf(RegionalJobReconciliationActionKind.MARK_CANCELED),
                absent.map { it.kind },
            )
        }
    }

    @Test
    fun `scheduler entry without a record and terminal entry are canceled without inferred success`() {
        val external = RegionalScheduledJobV1(
            jobId = "123e4567-e89b-42d3-a456-426614174099",
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            schedulerGeneration = 0,
            schedulerIdentity = "external-work",
            state = RegionalScheduledJobState.PENDING,
        )
        val succeeded = successfulRecord()
        val actions = RegionalJobReconciler.reconcile(
            records = listOf(succeeded),
            schedulerSnapshot = completeSnapshot(external, scheduled(succeeded)),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> true },
        )

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY })
    }

    @Test
    fun `terminal outcome validation reports invalid inventory without suppressing scheduler cancellation`() {
        val terminal = successfulRecord()
        val schedulerTargets = listOf(
            scheduled(terminal).copy(schedulerIdentity = "work-terminal-primary"),
            scheduled(terminal).copy(
                schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
                schedulerGeneration = terminal.schedulerGeneration + 1,
                schedulerIdentity = "uidt-terminal-stale",
            ),
        )
        val validatedContexts = mutableListOf<Triple<RegionalJobRecordV1, RegionalCanonicalArtifactV1, RegionalJobArtifactOutcomeV1>>()
        val invalidActions = RegionalJobReconciler.reconcile(
            records = listOf(terminal),
            schedulerSnapshot = completeSnapshot(*schedulerTargets.toTypedArray()),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { record, artifact, outcome ->
                validatedContexts += Triple(record, artifact, outcome)
                false
            },
        )

        assertEquals(terminal.artifactOutcomes.size, validatedContexts.size)
        validatedContexts.forEach { (record, artifact, outcome) ->
            assertEquals(terminal, record)
            assertEquals(terminal.canonicalPlan.artifacts[outcome.artifactIndex], artifact)
        }
        assertEquals(
            schedulerTargets.map(RegionalScheduledJobV1::schedulerIdentity).toSet(),
            invalidActions.filter { it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY }
                .mapNotNull { it.targetSchedulerIdentity }
                .toSet(),
        )
        val report = invalidActions.single {
            it.kind == RegionalJobReconciliationActionKind.REPORT_TERMINAL_OUTCOME_INVALID
        }
        assertTrue(report.isCurrentFor(terminal))
        assertTrue(!report.expectedRecordAbsent)
        assertTrue(report.problem != null)
        assertEquals(null, report.targetSchedulerIdentity)

        val validActions = RegionalJobReconciler.reconcile(
            records = listOf(terminal),
            schedulerSnapshot = completeSnapshot(*schedulerTargets.toTypedArray()),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { record, artifact, outcome ->
                record == terminal && artifact == terminal.canonicalPlan.artifacts[outcome.artifactIndex]
            },
        )

        assertTrue(validActions.none {
            it.kind == RegionalJobReconciliationActionKind.REPORT_TERMINAL_OUTCOME_INVALID
        })
        assertEquals(2, validActions.count {
            it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY
        })
    }

    @Test
    fun `interrupted running job re-enqueues only with valid required checkpoints`() {
        val pending = testRegionalJob()
        val queued = pending.transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val downloading = queued.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_020L,
            artifactAttemptCounts = listOf(1),
        )
        val raw = RegionalJobCheckpointReferenceV1(
            artifactIndex = 0,
            kind = RegionalJobCheckpointKind.VERIFIED_RAW,
            relativePath = downloading.canonicalPlan.artifacts.single().logicalRelativePath,
            bytes = 10L,
            sha256 = "b".repeat(64),
        )
        val verifying = downloading.transitionTo(
            nextState = RegionalJobState.RUNNING_VERIFY,
            nowEpochMillis = 1_030L,
            checkpointReferences = listOf(raw),
        )

        val valid = RegionalJobReconciler.reconcile(
            records = listOf(verifying),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
            checkpointValidator = RegionalJobCheckpointValidator { true },
        )
        val invalid = RegionalJobReconciler.reconcile(
            records = listOf(verifying),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
            checkpointValidator = RegionalJobCheckpointValidator { false },
        )
        assertThrows(IllegalArgumentException::class.java) {
            downloading.transitionTo(RegionalJobState.RUNNING_VERIFY, 1_030L)
        }

        assertEquals(RegionalJobReconciliationActionKind.PREPARE_REENQUEUE, valid.single().kind)
        assertEquals(RegionalJobReconciliationActionKind.MARK_ORPHANED, invalid.single().kind)
        assertEquals("checkpoint-invalid", invalid.single().problem?.code)
    }

    @Test
    fun `recoverable missing scheduler prepares a new generation without resetting attempts`() {
        val running = testRunningRegionalJob()
        val action = RegionalJobReconciler.reconcile(
            records = listOf(running),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
        ).single()

        assertEquals(RegionalJobReconciliationActionKind.PREPARE_REENQUEUE, action.kind)
        assertEquals(running.revision, action.expectedRevision)
        assertEquals(running.planFingerprintSha256, action.expectedPlanFingerprintSha256)
        assertEquals(running.schedulerGeneration, action.expectedRecordSchedulerGeneration)

        val prepared = running.prepareForReenqueue(1_030L)
        assertEquals(RegionalJobState.ENQUEUE_PENDING, prepared.state)
        assertEquals(running.revision + 1L, prepared.revision)
        assertEquals(running.schedulerGeneration + 1, prepared.schedulerGeneration)
        assertEquals(null, prepared.schedulerIdentity)
        assertEquals(running.artifactAttemptCounts, prepared.artifactAttemptCounts)
        assertEquals(running.checkpointReferences, prepared.checkpointReferences)
    }

    @Test
    fun `recoverable work at the scheduler generation limit is orphaned without re-enqueue`() {
        val exhausted = testRunningRegionalJob().copy(
            schedulerGeneration = 1_000,
        )

        val actions = RegionalJobReconciler.reconcile(
            records = listOf(exhausted),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
        )

        assertTrue(actions.none { it.kind == RegionalJobReconciliationActionKind.PREPARE_REENQUEUE })
        val orphan = actions.single()
        assertEquals(RegionalJobReconciliationActionKind.MARK_ORPHANED, orphan.kind)
        assertEquals("scheduler-generation-exhausted", orphan.problem?.code)
        assertEquals(exhausted.schedulerGeneration, orphan.expectedRecordSchedulerGeneration)
        assertTrue(orphan.isCurrentFor(exhausted))
    }

    @Test
    fun `finished scheduler without a terminal record is orphaned rather than adopted`() {
        val record = testRegionalJob()
        val actions = RegionalJobReconciler.reconcile(
            records = listOf(record),
            schedulerSnapshot = completeSnapshot(
                scheduled(record).copy(state = RegionalScheduledJobState.FINISHED),
            ),
            unreadableJobIds = emptySet(),
        )

        assertEquals(listOf(RegionalJobReconciliationActionKind.MARK_ORPHANED), actions.map { it.kind })
        assertEquals("scheduler-finished-without-result", actions.single().problem?.code)
        assertEquals(record.revision, actions.single().expectedRevision)
        assertEquals(record.planFingerprintSha256, actions.single().expectedPlanFingerprintSha256)
    }

    @Test
    fun `committed prior artifact outcomes must match durable inventory before recovery`() {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
                selections = setOf(
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL,
                ),
                reason = "inventory outcome reconciliation test",
            ),
        )
        val pending = testRegionalJob(plan = plan)
        val queued = pending.transitionTo(
            RegionalJobState.QUEUED,
            1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val downloading = queued.transitionTo(
            RegionalJobState.RUNNING_DOWNLOAD,
            1_020L,
            artifactAttemptCounts = listOf(1, 0),
        )
        val artifact = downloading.canonicalPlan.artifacts.first()
        val complete = RegionalJobCheckpointReferenceV1(
            artifactIndex = 0,
            kind = RegionalJobCheckpointKind.TRANSFER_COMPLETE,
            relativePath = "${artifact.logicalRelativePath}.part",
            bytes = 100L,
            sha256 = "a".repeat(64),
        )
        val verifying = downloading.transitionTo(
            RegionalJobState.RUNNING_VERIFY,
            1_030L,
            checkpointReferences = listOf(complete),
        )
        val processing = verifying.transitionTo(
            RegionalJobState.RUNNING_PROCESS,
            1_040L,
            checkpointReferences = listOf(
                complete.copy(
                    kind = RegionalJobCheckpointKind.VERIFIED_RAW,
                    relativePath = artifact.logicalRelativePath,
                ),
            ),
        )
        val nextArtifact = processing.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_050L,
            currentArtifactIndex = 1,
            artifactAttemptCounts = listOf(1, 1),
            checkpointReferences = emptyList(),
            artifactOutcomes = listOf(
                RegionalJobArtifactOutcomeV1(
                    artifactIndex = 0,
                    kind = RegionalJobArtifactOutcomeKind.READY,
                    inventoryEntrySha256 = "b".repeat(64),
                ),
            ),
        )

        val valid = RegionalJobReconciler.reconcile(
            records = listOf(nextArtifact),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> true },
        )
        val invalid = RegionalJobReconciler.reconcile(
            records = listOf(nextArtifact),
            schedulerSnapshot = completeSnapshot(),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> false },
        )

        assertEquals(RegionalJobReconciliationActionKind.PREPARE_REENQUEUE, valid.single().kind)
        assertEquals(RegionalJobReconciliationActionKind.MARK_ORPHANED, invalid.single().kind)
        assertEquals("artifact-outcome-invalid", invalid.single().problem?.code)
    }

    @Test
    fun `incompatible terminal record is never rewritten as orphaned`() {
        val succeeded = successfulRecord()
        val changedCanonical = succeeded.canonicalPlan.copy(
            artifacts = succeeded.canonicalPlan.artifacts.map { artifact ->
                artifact.copy(routePolicyVersion = artifact.routePolicyVersion + 1)
            },
        )
        val incompatibleTerminal = succeeded.copy(
            semanticFingerprintSha256 = RegionalPlanFingerprint.semantic(changedCanonical),
            planFingerprintSha256 = RegionalPlanFingerprint.calculate(changedCanonical),
            canonicalPlan = changedCanonical,
        )

        assertTrue(
            RegionalJobReconciler.reconcile(
                records = listOf(incompatibleTerminal),
                schedulerSnapshot = completeSnapshot(),
                unreadableJobIds = emptySet(),
                artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> true },
            ).isEmpty(),
        )
        val withScheduler = RegionalJobReconciler.reconcile(
            records = listOf(incompatibleTerminal),
            schedulerSnapshot = completeSnapshot(scheduled(incompatibleTerminal)),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> true },
        )
        assertEquals(listOf(RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY), withScheduler.map { it.kind })
    }

    @Test
    fun `catalog or scheduler identity mismatch cancels projection and marks record orphaned`() {
        val record = testRegionalJob()
        val changedCanonical = record.canonicalPlan.copy(
            artifacts = record.canonicalPlan.artifacts.map { artifact ->
                artifact.copy(routePolicyVersion = artifact.routePolicyVersion + 1)
            },
        )
        val incompatible = record.copy(
            semanticFingerprintSha256 = RegionalPlanFingerprint.semantic(changedCanonical),
            planFingerprintSha256 = RegionalPlanFingerprint.calculate(changedCanonical),
            canonicalPlan = changedCanonical,
        )
        val catalogActions = RegionalJobReconciler.reconcile(
            records = listOf(incompatible),
            schedulerSnapshot = completeSnapshot(scheduled(incompatible)),
            unreadableJobIds = emptySet(),
        )

        assertEquals(
            listOf(
                RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
                RegionalJobReconciliationActionKind.MARK_ORPHANED,
            ),
            catalogActions.map { it.kind },
        )
        assertEquals("catalog-plan-incompatible", catalogActions.last().problem?.code)

        val mismatch = RegionalJobReconciler.reconcile(
            records = listOf(record),
            schedulerSnapshot = completeSnapshot(
                scheduled(record).copy(schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER),
            ),
            unreadableJobIds = emptySet(),
        )
        assertEquals(
            listOf(
                RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
                RegionalJobReconciliationActionKind.MARK_ORPHANED,
            ),
            mismatch.map { it.kind },
        )
        assertEquals("scheduler-identity-mismatch", mismatch.last().problem?.code)
    }

    @Test
    fun `stale generation is canceled while the current scheduler target is adopted`() {
        val record = testRunningRegionalJob().prepareForReenqueue(1_030L)
        val stale = scheduled(record).copy(
            schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
            schedulerGeneration = record.schedulerGeneration - 1,
            schedulerIdentity = "uidt-stale-generation",
        )
        val current = scheduled(record).copy(
            schedulerIdentity = "work-current-generation",
        )

        val actions = RegionalJobReconciler.reconcile(
            records = listOf(record),
            schedulerSnapshot = completeSnapshot(stale, current),
            unreadableJobIds = emptySet(),
        )

        val cancel = actions.single { it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY }
        assertTrue(cancel.isCurrentFor(record))
        assertEquals(record.schedulerGeneration, cancel.expectedRecordSchedulerGeneration)
        assertEquals(stale.schedulerKind, cancel.targetSchedulerKind)
        assertEquals(stale.schedulerGeneration, cancel.targetSchedulerGeneration)
        assertEquals(stale.schedulerIdentity, cancel.targetSchedulerIdentity)

        val adopt = actions.single { it.kind == RegionalJobReconciliationActionKind.ADOPT_AS_QUEUED }
        assertTrue(adopt.isCurrentFor(record))
        assertEquals(record.schedulerGeneration, adopt.expectedRecordSchedulerGeneration)
        assertEquals(current.schedulerKind, adopt.targetSchedulerKind)
        assertEquals(current.schedulerGeneration, adopt.targetSchedulerGeneration)
        assertEquals(current.schedulerIdentity, adopt.targetSchedulerIdentity)
    }

    @Test
    fun `terminal and cancellation reconciliation cancel every scheduler target`() {
        val terminal = successfulRecord()
        val terminalTargets = listOf(
            scheduled(terminal).copy(schedulerIdentity = "work-terminal-current"),
            scheduled(terminal).copy(
                schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
                schedulerGeneration = terminal.schedulerGeneration + 1,
                schedulerIdentity = "uidt-terminal-stale",
            ),
        )
        val terminalActions = RegionalJobReconciler.reconcile(
            records = listOf(terminal),
            schedulerSnapshot = completeSnapshot(*terminalTargets.toTypedArray()),
            unreadableJobIds = emptySet(),
            artifactOutcomeValidator = RegionalJobArtifactOutcomeValidator { _, _, _ -> true },
        )

        assertEquals(2, terminalActions.size)
        assertTrue(terminalActions.all { it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY })
        assertTrue(terminalActions.all { it.isCurrentFor(terminal) })
        assertEquals(
            terminalTargets.map(RegionalScheduledJobV1::schedulerIdentity).toSet(),
            terminalActions.mapNotNull { it.targetSchedulerIdentity }.toSet(),
        )

        val canceled = testRegionalJob().requestCancellation(1_010L)
        val cancellationTargets = listOf(
            scheduled(canceled).copy(schedulerIdentity = "work-canceled-current"),
            scheduled(canceled).copy(
                schedulerKind = RegionalJobSchedulerKind.USER_INITIATED_DATA_TRANSFER,
                schedulerGeneration = canceled.schedulerGeneration + 1,
                schedulerIdentity = "uidt-canceled-stale",
            ),
        )
        val cancellationActions = RegionalJobReconciler.reconcile(
            records = listOf(canceled),
            schedulerSnapshot = completeSnapshot(*cancellationTargets.toTypedArray()),
            unreadableJobIds = emptySet(),
        )
        val cancellationCancels = cancellationActions.filter {
            it.kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY
        }

        assertEquals(2, cancellationCancels.size)
        assertTrue(cancellationCancels.all { it.isCurrentFor(canceled) })
        assertEquals(
            cancellationTargets.map(RegionalScheduledJobV1::schedulerIdentity).toSet(),
            cancellationCancels.mapNotNull { it.targetSchedulerIdentity }.toSet(),
        )
        assertEquals(
            1,
            cancellationActions.count { it.kind == RegionalJobReconciliationActionKind.MARK_CANCELED },
        )
    }

    private fun successfulRecord(): RegionalJobRecordV1 {
        val queued = testRegionalJob().transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val downloading = queued.transitionTo(RegionalJobState.RUNNING_DOWNLOAD, 1_020L)
        val artifact = downloading.canonicalPlan.artifacts.single()
        val complete = RegionalJobCheckpointReferenceV1(
            artifactIndex = 0,
            kind = RegionalJobCheckpointKind.TRANSFER_COMPLETE,
            relativePath = "${artifact.logicalRelativePath}.part",
            bytes = 10L,
            sha256 = "c".repeat(64),
        )
        val verifying = downloading.transitionTo(
            nextState = RegionalJobState.RUNNING_VERIFY,
            nowEpochMillis = 1_030L,
            checkpointReferences = listOf(complete),
        )
        val verified = complete.copy(
            kind = RegionalJobCheckpointKind.VERIFIED_RAW,
            relativePath = artifact.logicalRelativePath,
        )
        val processing = verifying.transitionTo(
            nextState = RegionalJobState.RUNNING_PROCESS,
            nowEpochMillis = 1_040L,
            checkpointReferences = listOf(verified),
        )
        return processing.transitionTo(
            nextState = RegionalJobState.SUCCEEDED,
            nowEpochMillis = 1_050L,
            currentArtifactIndex = processing.canonicalPlan.artifacts.size,
            artifactOutcomes = listOf(
                RegionalJobArtifactOutcomeV1(
                    artifactIndex = 0,
                    kind = RegionalJobArtifactOutcomeKind.READY,
                    inventoryEntrySha256 = "d".repeat(64),
                ),
            ),
        )
    }

    private fun scheduled(record: RegionalJobRecordV1): RegionalScheduledJobV1 = RegionalScheduledJobV1(
        jobId = record.jobId,
        schedulerKind = record.schedulerKind,
        schedulerGeneration = record.schedulerGeneration,
        schedulerIdentity = record.schedulerIdentity ?: "work-regional-1",
        state = RegionalScheduledJobState.PENDING,
    )

    private fun completeSnapshot(
        vararg jobs: RegionalScheduledJobV1,
    ): RegionalSchedulerSnapshotV1 = RegionalSchedulerSnapshotV1(
        availability = RegionalSchedulerSnapshotAvailability.COMPLETE,
        jobs = jobs.toList(),
    )
}
