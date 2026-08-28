package com.gecesars.atxplan.data.scheduler.work

import androidx.work.Data
import androidx.work.NetworkType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalWorkContractTest {
    @Test
    fun `input codec round trips exactly three versioned fields`() {
        val encoded = RegionalWorkContractV1.encodeInput(INPUT)

        assertEquals(
            setOf(
                "atx.regional.work.v1.jobId",
                "atx.regional.work.v1.planFingerprintSha256",
                "atx.regional.work.v1.schedulerGeneration",
            ),
            encoded.keyValueMap.keys,
        )
        assertEquals(INPUT, RegionalWorkContractV1.decodeInput(encoded))
    }

    @Test
    fun `input decoder rejects missing extra wrong-type and malformed fields`() {
        assertNull(
            RegionalWorkContractV1.decodeInput(
                Data.Builder()
                    .putString(RegionalWorkContractV1.JOB_ID_KEY, INPUT.jobId)
                    .putString(
                        RegionalWorkContractV1.PLAN_FINGERPRINT_KEY,
                        INPUT.planFingerprintSha256,
                    )
                    .build(),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeInput(
                exactDataBuilder()
                    .putString("atx.regional.work.v1.untrusted", "value")
                    .build(),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeInput(
                Data.Builder()
                    .putString(RegionalWorkContractV1.JOB_ID_KEY, INPUT.jobId)
                    .putString(
                        RegionalWorkContractV1.PLAN_FINGERPRINT_KEY,
                        INPUT.planFingerprintSha256,
                    )
                    .putLong(RegionalWorkContractV1.SCHEDULER_GENERATION_KEY, 7L)
                    .build(),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeInput(
                exactDataBuilder(jobId = INPUT.jobId.uppercase()).build(),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeInput(
                exactDataBuilder(fingerprint = "A".repeat(64)).build(),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeInput(
                exactDataBuilder(generation = 1_001).build(),
            ),
        )
    }

    @Test
    fun `deterministic ID is a stable custom UUID and changes with every identity field`() {
        val id = RegionalWorkContractV1.deterministicWorkId(INPUT)

        assertEquals(8, id.version())
        assertEquals(2, id.variant())
        assertEquals("b279589f-8d2a-820e-9868-06140ecb2b88", id.toString())
        assertEquals(id, RegionalWorkContractV1.deterministicWorkId(INPUT))
        assertNotEquals(
            id,
            RegionalWorkContractV1.deterministicWorkId(INPUT.copy(jobId = ALTERNATE_JOB_ID)),
        )
        assertNotEquals(
            id,
            RegionalWorkContractV1.deterministicWorkId(
                INPUT.copy(planFingerprintSha256 = "b".repeat(64)),
            ),
        )
        assertNotEquals(
            id,
            RegionalWorkContractV1.deterministicWorkId(INPUT.copy(schedulerGeneration = 8)),
        )
    }

    @Test
    fun `unique name and application tags are generation scoped and bounded`() {
        assertEquals(
            "atx.regional.work.v1.unique.${INPUT.jobId}.g7",
            RegionalWorkContractV1.uniqueWorkName(INPUT),
        )
        assertEquals(
            setOf(
                "atx.regional.work.v1",
                "atx.regional.work.v1.job.${INPUT.jobId}",
                "atx.regional.work.v1.plan.${INPUT.planFingerprintSha256}",
                "atx.regional.work.v1.generation.${INPUT.jobId}.g7",
            ),
            RegionalWorkContractV1.customTags(INPUT),
        )
        assertTrue(RegionalWorkContractV1.uniqueWorkName(INPUT).length < 128)
        assertTrue(RegionalWorkContractV1.customTags(INPUT).all { it.length < 128 })
    }

    @Test
    fun `tag decoder accepts only the exact worker global job plan and generation tags`() {
        val identity = INPUT.toTagIdentity()
        val tags = RegionalWorkContractV1.expectedWorkInfoTags(identity)

        assertEquals(identity, RegionalWorkContractV1.decodeTags(tags))
        assertNull(RegionalWorkContractV1.decodeTags(tags - RegionalWorkContractV1.GLOBAL_TAG))
        assertNull(RegionalWorkContractV1.decodeTags(tags + "untrusted"))
        assertEquals(
            identity.copy(schedulerGeneration = 8),
            RegionalWorkContractV1.decodeTags(
                tags - RegionalWorkContractV1.generationTag(INPUT.jobId, 7) +
                    RegionalWorkContractV1.generationTag(INPUT.jobId, 8),
            ),
        )
        assertEquals(
            identity.copy(planFingerprintSha256 = "b".repeat(64)),
            RegionalWorkContractV1.decodeTags(
                tags - RegionalWorkContractV1.planFingerprintTag(INPUT.planFingerprintSha256) +
                    RegionalWorkContractV1.planFingerprintTag("b".repeat(64)),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeTags(
                tags - RegionalWorkContractV1.jobTag(INPUT.jobId) +
                    RegionalWorkContractV1.jobTag(ALTERNATE_JOB_ID),
            ),
        )
        assertNull(
            RegionalWorkContractV1.decodeTags(
                tags - RegionalJobWorker::class.java.name + "foreign.Worker",
            ),
        )
    }

    @Test
    fun `work info helper fails closed for non-contract UUIDs and tags`() {
        val id = RegionalWorkContractV1.deterministicWorkId(INPUT)
        val tags = RegionalWorkContractV1.expectedWorkInfoTags(
            INPUT.toTagIdentity(),
        )

        val decoded = RegionalWorkContractV1.decodeWorkInfoIdentity(id, tags)
        assertNotNull(decoded)
        assertEquals(id, decoded?.workRequestId)
        assertEquals(INPUT.jobId, decoded?.jobId)
        assertEquals(INPUT.planFingerprintSha256, decoded?.planFingerprintSha256)
        assertEquals(INPUT.schedulerGeneration, decoded?.schedulerGeneration)
        assertNull(RegionalWorkContractV1.decodeWorkInfoIdentity(UUID.randomUUID(), tags))
        assertNull(
            RegionalWorkContractV1.decodeWorkInfoIdentity(
                RegionalWorkContractV1.deterministicWorkId(
                    INPUT.copy(planFingerprintSha256 = "b".repeat(64)),
                ),
                tags,
            ),
        )
        assertNull(RegionalWorkContractV1.decodeWorkInfoIdentity(id, tags + "untrusted"))
    }

    @Test
    fun `request uses exact deterministic identity payload tags and constraints`() {
        val request = RegionalWorkRequestFactoryV1.create(INPUT)

        assertEquals(RegionalWorkContractV1.deterministicWorkId(INPUT), request.id)
        assertEquals(INPUT, RegionalWorkContractV1.decodeInput(request.workSpec.input))
        assertEquals(
            RegionalWorkContractV1.expectedWorkInfoTags(
                INPUT.toTagIdentity(),
            ),
            request.tags,
        )
        assertEquals(RegionalJobWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertFalse(request.workSpec.constraints.requiresCharging())
        assertFalse(request.workSpec.constraints.requiresDeviceIdle())
    }

    private fun exactDataBuilder(
        jobId: String = INPUT.jobId,
        fingerprint: String = INPUT.planFingerprintSha256,
        generation: Int = INPUT.schedulerGeneration,
    ): Data.Builder = Data.Builder()
        .putString(RegionalWorkContractV1.JOB_ID_KEY, jobId)
        .putString(RegionalWorkContractV1.PLAN_FINGERPRINT_KEY, fingerprint)
        .putInt(RegionalWorkContractV1.SCHEDULER_GENERATION_KEY, generation)

    private fun RegionalWorkInputV1.toTagIdentity(): RegionalWorkTagIdentityV1 =
        RegionalWorkTagIdentityV1(
            jobId = jobId,
            planFingerprintSha256 = planFingerprintSha256,
            schedulerGeneration = schedulerGeneration,
        )

    private companion object {
        const val JOB_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val ALTERNATE_JOB_ID = "123e4567-e89b-42d3-a456-426614174001"
        val INPUT = RegionalWorkInputV1(
            jobId = JOB_ID,
            planFingerprintSha256 = "a".repeat(64),
            schedulerGeneration = 7,
        )
    }
}
