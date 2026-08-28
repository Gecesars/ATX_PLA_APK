package com.gecesars.atxplan.data.dataset

import com.gecesars.atxplan.domain.dataset.MAXIMUM_REGIONAL_JOBS
import com.gecesars.atxplan.domain.dataset.RegionalJobProblemV1
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalPlanFingerprint
import com.gecesars.atxplan.domain.dataset.testBuildingPlan
import com.gecesars.atxplan.domain.dataset.testRegionalJob
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalJobRepositoryTest {
    @Test
    fun `strict record round trip and same ID plan create are idempotent`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val firstRepository = RegionalJobStorePersistence(storage)
        val record = testRegionalJob()

        assertEquals(record, firstRepository.create(record))
        assertEquals(record, firstRepository.create(record))

        val reopened = RegionalJobStorePersistence(storage)
        assertEquals(record, reopened.get(record.jobId))
        assertEquals(listOf(record), reopened.loadSnapshot().jobs)
        assertTrue(reopened.loadSnapshot().unreadableJobIds.isEmpty())
    }

    @Test
    fun `same job ID with another plan and duplicate active fingerprint are rejected`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val repository = RegionalJobStorePersistence(storage)
        val record = testRegionalJob()
        repository.create(record)

        assertTrue(
            runCatching {
                repository.create(
                    testRegionalJob(
                        jobId = record.jobId,
                        plan = com.gecesars.atxplan.domain.dataset.testBuildingPlan(forceRefresh = true),
                    ),
                )
            }.exceptionOrNull() is RegionalJobConflictException,
        )
        assertTrue(
            runCatching {
                repository.create(
                    testRegionalJob(jobId = "123e4567-e89b-42d3-a456-426614174001"),
                )
            }.exceptionOrNull() is RegionalJobConflictException,
        )
    }

    @Test
    fun `terminal job permits an explicit new job for the same plan`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val repository = RegionalJobStorePersistence(storage)
        val first = repository.create(testRegionalJob())
        repository.update(first.jobId, first.revision) { current ->
            current.transitionTo(
                nextState = RegionalJobState.FAILED,
                nowEpochMillis = 1_010L,
                terminalProblem = RegionalJobProblemV1(
                    code = "provider-failed",
                    message = "The bounded provider request failed.",
                    retryableByUser = true,
                ),
            )
        }

        val retry = testRegionalJob(
            jobId = "123e4567-e89b-42d3-a456-426614174001",
            createdAtEpochMillis = 2_000L,
        )
        assertEquals(retry, repository.create(retry))
        assertEquals(2, repository.loadSnapshot().jobs.size)
    }

    @Test
    fun `compare and set permits one concurrent revision winner`() = runTest {
        val repository = RegionalJobStorePersistence(InMemoryRegionalJobStorage())
        val record = repository.create(testRegionalJob())

        val outcomes = listOf(1_010L, 1_020L).map { timestamp ->
            async {
                runCatching {
                    repository.update(record.jobId, record.revision) { current ->
                        current.requestCancellation(timestamp)
                    }
                }
            }
        }.awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertTrue(outcomes.first { it.isFailure }.exceptionOrNull() is RegionalJobConflictException)
        assertEquals(1L, repository.get(record.jobId)?.revision)
    }

    @Test
    fun `failed atomic replacement leaves the prior revision readable`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val repository = RegionalJobStorePersistence(storage)
        val record = repository.create(testRegionalJob())
        storage.failReplacement = true

        assertTrue(
            runCatching {
                repository.update(record.jobId, record.revision) { current ->
                    current.requestCancellation(1_010L)
                }
            }.exceptionOrNull() is RegionalJobStorageException,
        )

        storage.failReplacement = false
        assertEquals(record, repository.get(record.jobId))
    }

    @Test
    fun `corrupt future and unknown-key records do not hide a valid peer`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val repository = RegionalJobStorePersistence(storage)
        val valid = repository.create(testRegionalJob())
        val corruptId = "123e4567-e89b-42d3-a456-426614174010"
        val futureId = "123e4567-e89b-42d3-a456-426614174011"
        val unknownId = "123e4567-e89b-42d3-a456-426614174012"
        storage.putRaw(corruptId, byteArrayOf(0xc3.toByte(), 0x28))
        storage.putRaw(futureId, "{\"schemaVersion\":2}".toByteArray())
        val unknownPayload = storage.raw(valid.jobId).toString(Charsets.UTF_8).dropLast(1) +
            ",\"unexpected\":true}"
        storage.putRaw(
            unknownId,
            unknownPayload.replace(valid.jobId, unknownId).toByteArray(Charsets.UTF_8),
        )

        val snapshot = repository.loadSnapshot()

        assertEquals(listOf(valid), snapshot.jobs)
        assertEquals(listOf(corruptId, futureId, unknownId), snapshot.unreadableJobIds)
        assertEquals(byteArrayOf(0xc3.toByte(), 0x28).toList(), storage.raw(corruptId).toList())
    }

    @Test
    fun `new job creation stops when any durable peer is unreadable`() = runTest {
        val storage = InMemoryRegionalJobStorage()
        val repository = RegionalJobStorePersistence(storage)
        val corruptId = "123e4567-e89b-42d3-a456-426614174030"
        storage.putRaw(corruptId, byteArrayOf(0xc3.toByte(), 0x28))

        val failure = runCatching {
            repository.create(
                testRegionalJob(jobId = "123e4567-e89b-42d3-a456-426614174031"),
            )
        }.exceptionOrNull()

        assertTrue(failure is RegionalJobStorageException)
        assertTrue(failure?.message.orEmpty().contains(corruptId))
        assertEquals(listOf(corruptId), repository.loadSnapshot().unreadableJobIds)
    }

    @Test
    fun `create rejects incompatible catalog plans and noninitial lifecycle records`() = runTest {
        val base = testRegionalJob()
        val changedCanonical = base.canonicalPlan.copy(
            artifacts = base.canonicalPlan.artifacts.map { artifact ->
                artifact.copy(routePolicyVersion = artifact.routePolicyVersion + 1)
            },
        )
        val incompatible = base.copy(
            semanticFingerprintSha256 = RegionalPlanFingerprint.semantic(changedCanonical),
            planFingerprintSha256 = RegionalPlanFingerprint.calculate(changedCanonical),
            canonicalPlan = changedCanonical,
        )
        val incompatibleFailure = runCatching {
            RegionalJobStorePersistence(InMemoryRegionalJobStorage()).create(incompatible)
        }.exceptionOrNull()
        assertTrue(incompatibleFailure is RegionalJobConflictException)

        val alreadyFailed = base.transitionTo(
            nextState = RegionalJobState.FAILED,
            nowEpochMillis = 1_010L,
            terminalProblem = RegionalJobProblemV1(
                code = "provider-failed",
                message = "The provider request already failed.",
                retryableByUser = true,
            ),
        )
        val lifecycleFailure = runCatching {
            RegionalJobStorePersistence(InMemoryRegionalJobStorage()).create(alreadyFailed)
        }.exceptionOrNull()
        assertTrue(lifecycleFailure is RegionalJobConflictException)
    }

    @Test
    fun `active jobs with different fingerprints cannot claim the same logical artifact path`() = runTest {
        val repository = RegionalJobStorePersistence(InMemoryRegionalJobStorage())
        val reusable = repository.create(testRegionalJob())
        val forced = testRegionalJob(
            jobId = "123e4567-e89b-42d3-a456-426614174040",
            plan = testBuildingPlan(forceRefresh = true),
        )

        assertTrue(reusable.planFingerprintSha256 != forced.planFingerprintSha256)
        assertEquals(
            reusable.canonicalPlan.artifacts.map { it.logicalRelativePath },
            forced.canonicalPlan.artifacts.map { it.logicalRelativePath },
        )
        assertTrue(
            runCatching { repository.create(forced) }.exceptionOrNull() is RegionalJobConflictException,
        )
    }

    @Test
    fun `oversized record and record count limits fail without overwriting peers`() = runTest {
        val oversizedStorage = InMemoryRegionalJobStorage()
        val validRepository = RegionalJobStorePersistence(oversizedStorage)
        val valid = validRepository.create(testRegionalJob())
        val oversizedId = "123e4567-e89b-42d3-a456-426614174020"
        oversizedStorage.putRaw(oversizedId, ByteArray(256 * 1024 + 1))

        val snapshot = validRepository.loadSnapshot()
        assertEquals(listOf(valid), snapshot.jobs)
        assertEquals(listOf(oversizedId), snapshot.unreadableJobIds)

        val excessiveStorage = InMemoryRegionalJobStorage()
        repeat(MAXIMUM_REGIONAL_JOBS + 1) { index ->
            val id = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
            excessiveStorage.putRaw(id, "{\"schemaVersion\":1}".toByteArray())
        }
        val excessiveRepository = RegionalJobStorePersistence(excessiveStorage)
        assertTrue(
            runCatching { excessiveRepository.loadSnapshot() }.exceptionOrNull() is RegionalJobStorageException,
        )
    }
}

private class InMemoryRegionalJobStorage : RegionalJobRecordStorage {
    private val records = linkedMapOf<String, ByteArray>()
    var failReplacement: Boolean = false

    override fun listIds(maximumRecords: Int): List<String> = records.keys.sorted().take(maximumRecords)

    override fun exists(jobId: String): Boolean = jobId in records

    override fun read(jobId: String, maximumBytes: Int): ByteArray {
        val payload = records[jobId] ?: throw IOException("Missing test record.")
        if (payload.size > maximumBytes) throw IOException("Oversized test record.")
        return payload.copyOf()
    }

    override fun createAtomically(jobId: String, payload: ByteArray) {
        if (jobId in records) throw IOException("Duplicate test record.")
        records[jobId] = payload.copyOf()
    }

    override fun replaceAtomically(jobId: String, payload: ByteArray) {
        if (failReplacement) throw IOException("Injected replacement failure.")
        if (jobId !in records) throw IOException("Missing test record.")
        records[jobId] = payload.copyOf()
    }

    fun putRaw(jobId: String, payload: ByteArray) {
        records[jobId] = payload.copyOf()
    }

    fun raw(jobId: String): ByteArray = checkNotNull(records[jobId]).copyOf()
}
