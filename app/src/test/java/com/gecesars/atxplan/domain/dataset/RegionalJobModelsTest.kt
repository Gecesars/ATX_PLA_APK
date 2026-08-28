package com.gecesars.atxplan.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalJobModelsTest {
    @Test
    fun `enqueue factory binds exact semantic execution and license snapshots`() {
        val plan = testBuildingPlan()
        val record = testRegionalJob(plan = plan)

        assertEquals(RegionalPlanFingerprint.semantic(plan), record.semanticFingerprintSha256)
        assertEquals(RegionalPlanFingerprint.calculate(plan), record.planFingerprintSha256)
        assertEquals(plan.licenses, record.acceptedLicenseSnapshots.map(RegionalAcceptedLicenseSnapshotV1::license))
        assertEquals(RegionalJobState.ENQUEUE_PENDING, record.state)
        assertEquals(List(plan.artifacts.size) { 0 }, record.artifactAttemptCounts)
    }

    @Test
    fun `fingerprint and reviewed license tampering are rejected during passive decode construction`() {
        val record = testRegionalJob()

        assertThrows(IllegalArgumentException::class.java) {
            record.copy(planFingerprintSha256 = "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(semanticFingerprintSha256 = "f".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(acceptedLicenseSnapshots = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(
                acceptedLicenseSnapshots = record.acceptedLicenseSnapshots.map { acceptance ->
                    acceptance.copy(
                        license = acceptance.license.copy(attribution = "Changed after review"),
                    )
                },
            )
        }
    }

    @Test
    fun `valid lifecycle reaches success and terminal state is immutable`() {
        val pending = testRegionalJob()
        val queued = pending.transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val downloading = queued.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_020L,
            networkBytesTransferred = 100L,
            artifactAttemptCounts = listOf(1),
        )
        val transferCheckpoint = RegionalJobCheckpointReferenceV1(
            artifactIndex = 0,
            kind = RegionalJobCheckpointKind.TRANSFER_COMPLETE,
            relativePath = "${downloading.canonicalPlan.artifacts.single().logicalRelativePath}.part",
            bytes = 100L,
            sha256 = "a".repeat(64),
        )
        val verifying = downloading.transitionTo(
            nextState = RegionalJobState.RUNNING_VERIFY,
            nowEpochMillis = 1_030L,
            checkpointReferences = listOf(transferCheckpoint),
        )
        val rawCheckpoint = transferCheckpoint.copy(
            kind = RegionalJobCheckpointKind.VERIFIED_RAW,
            relativePath = downloading.canonicalPlan.artifacts.single().logicalRelativePath,
        )
        val processing = verifying.transitionTo(
            nextState = RegionalJobState.RUNNING_PROCESS,
            nowEpochMillis = 1_040L,
            checkpointReferences = listOf(rawCheckpoint),
        )
        val succeeded = processing.transitionTo(
            nextState = RegionalJobState.SUCCEEDED,
            nowEpochMillis = 1_050L,
            currentArtifactIndex = processing.canonicalPlan.artifacts.size,
            artifactOutcomes = listOf(
                RegionalJobArtifactOutcomeV1(
                    artifactIndex = 0,
                    kind = RegionalJobArtifactOutcomeKind.READY,
                    inventoryEntrySha256 = "b".repeat(64),
                ),
            ),
        )

        assertEquals(RegionalJobState.SUCCEEDED, succeeded.state)
        assertEquals(5L, succeeded.revision)
        assertThrows(IllegalArgumentException::class.java) {
            succeeded.transitionTo(RegionalJobState.CANCELED, 1_060L)
        }
        assertSame(succeeded, succeeded.requestCancellation(1_060L))
    }

    @Test
    fun `invalid state jumps and regressing durable counters are rejected`() {
        val pending = testRegionalJob()
        assertThrows(IllegalArgumentException::class.java) {
            pending.transitionTo(
                nextState = RegionalJobState.SUCCEEDED,
                nowEpochMillis = 1_010L,
                currentArtifactIndex = pending.canonicalPlan.artifacts.size,
            )
        }
        val queued = pending.transitionTo(
            nextState = RegionalJobState.QUEUED,
            nowEpochMillis = 1_010L,
            schedulerIdentity = "work-regional-1",
        )
        val progressed = queued.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_020L,
            networkBytesTransferred = 200L,
            artifactAttemptCounts = listOf(1),
        )
        val regressed = progressed.copy(
            revision = progressed.revision + 1L,
            updatedAtEpochMillis = 1_030L,
            networkBytesTransferred = 100L,
            artifactAttemptCounts = listOf(0),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateRegionalJobMutation(progressed, regressed)
        }
    }

    @Test
    fun `draft selects its scheduler once and cancellation is monotonic and idempotent`() {
        val pending = testRegionalJob()
        val draft = pending.copy(
            schedulerKind = RegionalJobSchedulerKind.UNASSIGNED,
            state = RegionalJobState.DRAFT,
        )
        val selected = draft.transitionTo(
            nextState = RegionalJobState.ENQUEUE_PENDING,
            nowEpochMillis = 1_010L,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
        )
        validateRegionalJobMutation(draft, selected)
        val cancellationRequested = selected.requestCancellation(1_020L)

        assertTrue(cancellationRequested.cancelRequested)
        assertEquals(selected.revision + 1L, cancellationRequested.revision)
        assertSame(cancellationRequested, cancellationRequested.requestCancellation(1_030L))
        assertThrows(IllegalArgumentException::class.java) {
            validateRegionalJobMutation(
                cancellationRequested,
                cancellationRequested.copy(
                    revision = cancellationRequested.revision + 1L,
                    updatedAtEpochMillis = 1_040L,
                    cancelRequested = false,
                ),
            )
        }
    }

    @Test
    fun `success without a committed artifact outcome is rejected`() {
        val processing = testProcessingRegionalJob()

        assertThrows(IllegalArgumentException::class.java) {
            processing.transitionTo(
                nextState = RegionalJobState.SUCCEEDED,
                nowEpochMillis = 1_050L,
                currentArtifactIndex = processing.canonicalPlan.artifacts.size,
            )
        }
    }

    @Test
    fun `partial checkpoint removal and byte regression are rejected`() {
        val downloading = testRunningRegionalJob()
        val partial = RegionalJobCheckpointReferenceV1(
            artifactIndex = 0,
            kind = RegionalJobCheckpointKind.TRANSFER_PARTIAL,
            relativePath = "${downloading.canonicalPlan.artifacts.single().logicalRelativePath}.part",
            bytes = 100L,
        )
        val checkpointed = downloading.copy(
            revision = downloading.revision + 1L,
            updatedAtEpochMillis = 1_030L,
            checkpointReferences = listOf(partial),
        ).also { updated -> validateRegionalJobMutation(downloading, updated) }

        val removed = checkpointed.copy(
            revision = checkpointed.revision + 1L,
            updatedAtEpochMillis = 1_040L,
            checkpointReferences = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateRegionalJobMutation(checkpointed, removed)
        }

        val regressed = checkpointed.copy(
            revision = checkpointed.revision + 1L,
            updatedAtEpochMillis = 1_040L,
            checkpointReferences = listOf(partial.copy(bytes = 99L)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateRegionalJobMutation(checkpointed, regressed)
        }
    }

    @Test
    fun `record construction rejects a checkpoint beyond the current artifact`() {
        val record = testRegionalJob(plan = testMultiArtifactPlan())
        val futureArtifactIndex = record.currentArtifactIndex + 1
        val futureArtifact = record.canonicalPlan.artifacts[futureArtifactIndex]
        val futureCheckpoint = RegionalJobCheckpointReferenceV1(
            artifactIndex = futureArtifactIndex,
            kind = RegionalJobCheckpointKind.TRANSFER_PARTIAL,
            relativePath = "${futureArtifact.logicalRelativePath}.part",
            bytes = 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            record.copy(checkpointReferences = listOf(futureCheckpoint))
        }
    }

    @Test
    fun `mutation rejects a newly injected checkpoint for the next artifact`() {
        val processing = testProcessingRegionalJob(plan = testMultiArtifactPlan())
        val completedArtifactIndex = processing.currentArtifactIndex
        val advanced = processing.transitionTo(
            nextState = RegionalJobState.RUNNING_DOWNLOAD,
            nowEpochMillis = 1_050L,
            currentArtifactIndex = completedArtifactIndex + 1,
            artifactOutcomes = listOf(
                RegionalJobArtifactOutcomeV1(
                    artifactIndex = completedArtifactIndex,
                    kind = RegionalJobArtifactOutcomeKind.READY,
                    inventoryEntrySha256 = "c".repeat(64),
                ),
            ),
        )
        val futureArtifactIndex = advanced.currentArtifactIndex
        val futureArtifact = advanced.canonicalPlan.artifacts[futureArtifactIndex]
        val futureCheckpoint = RegionalJobCheckpointReferenceV1(
            artifactIndex = futureArtifactIndex,
            kind = RegionalJobCheckpointKind.TRANSFER_PARTIAL,
            relativePath = "${futureArtifact.logicalRelativePath}.part",
            bytes = 1L,
        )
        val injected = advanced.copy(
            checkpointReferences = advanced.checkpointReferences + futureCheckpoint,
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateRegionalJobMutation(processing, injected)
        }
    }

    @Test
    fun `persisted transfer attempts are capped at three GET and two POST attempts`() {
        val getJob = testRegionalJob(plan = testRasterPlan())
        assertEquals(listOf(3), getJob.copy(artifactAttemptCounts = listOf(3)).artifactAttemptCounts)
        assertThrows(IllegalArgumentException::class.java) {
            getJob.copy(artifactAttemptCounts = listOf(4))
        }

        val postJob = testRegionalJob()
        assertEquals(listOf(2), postJob.copy(artifactAttemptCounts = listOf(2)).artifactAttemptCounts)
        assertThrows(IllegalArgumentException::class.java) {
            postJob.copy(artifactAttemptCounts = listOf(3))
        }
    }

    @Test
    fun `passive plans reject extra selections licenses duplicate paths and cache contradictions`() {
        val plan = RegionalPlanFingerprint.canonicalize(testBuildingPlan())
        val artifact = plan.artifacts.single()

        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(
                selections = listOf(
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(
                licenseSnapshots = (
                    plan.licenseSnapshots +
                        plan.licenseSnapshots.single().copy(id = "unrelated-license")
                    ).sortedBy(RegionalDatasetLicense::id),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(
                artifacts = listOf(artifact, artifact),
                estimatedBytes = Math.multiplyExact(plan.estimatedBytes, 2L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            artifact.copy(cachePolicy = RegionalArtifactCachePolicy.IMMUTABLE_RELEASE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            artifact.copy(maximumArtifactBytes = DEFAULT_MAXIMUM_BATCH_BYTES + 1L)
        }
    }

    @Test
    fun `an optional unpublished raster can complete with an inventory outcome but buildings cannot`() {
        val rasterPending = testRegionalJob(plan = testRasterPlan())
        val rasterRunning = rasterPending.transitionTo(
            RegionalJobState.RUNNING_DOWNLOAD,
            1_010L,
            schedulerIdentity = "work-raster-1",
            artifactAttemptCounts = listOf(1),
        )
        val optionalOutcome = RegionalJobArtifactOutcomeV1(
            artifactIndex = 0,
            kind = RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND,
            inventoryEntrySha256 = "d".repeat(64),
        )

        val succeeded = rasterRunning.transitionTo(
            nextState = RegionalJobState.SUCCEEDED,
            nowEpochMillis = 1_020L,
            currentArtifactIndex = 1,
            artifactOutcomes = listOf(optionalOutcome),
        )
        assertEquals(RegionalJobState.SUCCEEDED, succeeded.state)

        val buildingRunning = testRegionalJob().transitionTo(
            RegionalJobState.RUNNING_DOWNLOAD,
            1_010L,
            schedulerIdentity = "work-buildings-1",
            artifactAttemptCounts = listOf(1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            buildingRunning.transitionTo(
                nextState = RegionalJobState.SUCCEEDED,
                nowEpochMillis = 1_020L,
                currentArtifactIndex = 1,
                artifactOutcomes = listOf(optionalOutcome),
            )
        }
    }
}

internal fun testRegionalJob(
    jobId: String = "123e4567-e89b-42d3-a456-426614174000",
    plan: RegionalDownloadPlan = testBuildingPlan(),
    createdAtEpochMillis: Long = 1_000L,
): RegionalJobRecordV1 = RegionalJobRecordV1.enqueuePending(
    jobId = jobId,
    plan = plan,
    schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
    acceptedAtEpochMillis = createdAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun testRunningRegionalJob(
    plan: RegionalDownloadPlan = testBuildingPlan(),
): RegionalJobRecordV1 {
    val queued = testRegionalJob(plan = plan).transitionTo(
        nextState = RegionalJobState.QUEUED,
        nowEpochMillis = 1_010L,
        schedulerIdentity = "work-regional-1",
    )
    return queued.transitionTo(
        nextState = RegionalJobState.RUNNING_DOWNLOAD,
        nowEpochMillis = 1_020L,
        networkBytesTransferred = 100L,
        artifactAttemptCounts = List(plan.artifacts.size) { artifactIndex ->
            if (artifactIndex == 0) 1 else 0
        },
    )
}

internal fun testProcessingRegionalJob(
    plan: RegionalDownloadPlan = testBuildingPlan(),
): RegionalJobRecordV1 {
    val downloading = testRunningRegionalJob(plan)
    val artifact = downloading.canonicalPlan.artifacts[downloading.currentArtifactIndex]
    val transferComplete = RegionalJobCheckpointReferenceV1(
        artifactIndex = 0,
        kind = RegionalJobCheckpointKind.TRANSFER_COMPLETE,
        relativePath = "${artifact.logicalRelativePath}.part",
        bytes = 100L,
        sha256 = "a".repeat(64),
    )
    val verifying = downloading.transitionTo(
        nextState = RegionalJobState.RUNNING_VERIFY,
        nowEpochMillis = 1_030L,
        checkpointReferences = listOf(transferComplete),
    )
    val verifiedRaw = RegionalJobCheckpointReferenceV1(
        artifactIndex = 0,
        kind = RegionalJobCheckpointKind.VERIFIED_RAW,
        relativePath = artifact.logicalRelativePath,
        bytes = 100L,
        sha256 = "b".repeat(64),
    )
    return verifying.transitionTo(
        nextState = RegionalJobState.RUNNING_PROCESS,
        nowEpochMillis = 1_040L,
        checkpointReferences = listOf(verifiedRaw),
    )
}

internal fun testSuccessfulRegionalJob(): RegionalJobRecordV1 {
    val processing = testProcessingRegionalJob()
    return processing.transitionTo(
        nextState = RegionalJobState.SUCCEEDED,
        nowEpochMillis = 1_050L,
        currentArtifactIndex = processing.canonicalPlan.artifacts.size,
        artifactOutcomes = listOf(
            RegionalJobArtifactOutcomeV1(
                artifactIndex = 0,
                kind = RegionalJobArtifactOutcomeKind.READY,
                inventoryEntrySha256 = "c".repeat(64),
            ),
        ),
    )
}

internal fun testBuildingPlan(forceRefresh: Boolean = false): RegionalDownloadPlan =
    RegionalDatasetPlanner().plan(
        RegionalDatasetRequest(
            bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
            selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            reason = "regional job test",
            liveSnapshotRefresh = forceRefresh,
        ),
    )

internal fun testRasterPlan(): RegionalDownloadPlan = RegionalDatasetPlanner().plan(
    RegionalDatasetRequest(
        bounds = RegionalBounds(-46.70, -23.60, -46.60, -23.50),
        selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
        reason = "regional GET job test",
    ),
)

internal fun testMultiArtifactPlan(): RegionalDownloadPlan = RegionalDatasetPlanner().plan(
    RegionalDatasetRequest(
        bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
        selections = setOf(
            RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
            RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL,
        ),
        reason = "multi-artifact regional job test",
    ),
)
