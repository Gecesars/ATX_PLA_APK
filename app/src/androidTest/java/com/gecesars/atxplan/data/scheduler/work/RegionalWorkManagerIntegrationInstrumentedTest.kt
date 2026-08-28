package com.gecesars.atxplan.data.scheduler.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.await
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API 36 logic evidence for WorkManager's physical KEEP/constraint contract, not API 23/33 evidence. */
@RunWith(AndroidJUnit4::class)
class RegionalWorkManagerIntegrationInstrumentedTest {
    @Test
    fun duplicateGenerationKeepsOneDeterministicPhysicalRequestWithExactConstraints() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        val input = RegionalWorkInputV1(
            jobId = UUID.randomUUID().toString(),
            planFingerprintSha256 = "a".repeat(64),
            schedulerGeneration = 4,
        )
        val first = RegionalWorkRequestFactoryV1.create(input)
        val second = RegionalWorkRequestFactoryV1.create(input)
        val uniqueName = RegionalWorkContractV1.uniqueWorkName(input)

        try {
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, first).await()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, second).await()

            val infos = workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
            assertEquals(RegionalWorkContractV1.deterministicWorkId(input), first.id)
            assertEquals(first.id, second.id)
            assertEquals(1, infos.size)
            assertEquals(setOf(first.id), infos.mapTo(linkedSetOf()) { it.id })
            assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
            assertTrue(infos.single().constraints.requiresStorageNotLow())
            assertEquals(
                RegionalWorkInfoIdentityV1(
                    workRequestId = first.id,
                    jobId = input.jobId,
                    planFingerprintSha256 = input.planFingerprintSha256,
                    schedulerGeneration = input.schedulerGeneration,
                ),
                RegionalWorkContractV1.decodeWorkInfo(infos.single()),
            )
        } finally {
            workManager.cancelWorkById(first.id).await()
        }
    }
}
