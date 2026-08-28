package com.gecesars.atxplan.data.dataset

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileRegionalJobRepositoryTest {
    @Test
    fun recordSurvivesRepositoryReopenAndAtomicBackupRecovery() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            targetContext.cacheDir,
            "regional-job-store-${System.nanoTime()}",
        ).canonicalFile
        val cacheRoot = targetContext.cacheDir.canonicalFile
        assertTrue(root.path.startsWith(cacheRoot.path + File.separator))

        try {
            val plan = RegionalDatasetPlanner().plan(
                RegionalDatasetRequest(
                    bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
                    selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                    reason = "Android AtomicFile recovery test",
                ),
            )
            val record = RegionalJobRecordV1.enqueuePending(
                jobId = "123e4567-e89b-42d3-a456-426614174000",
                plan = plan,
                schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
                acceptedAtEpochMillis = 1_000L,
                createdAtEpochMillis = 1_000L,
            )

            FileRegionalJobRepository(root).create(record)
            assertEquals(record, FileRegionalJobRepository(root).get(record.jobId))

            val target = File(root, "${record.jobId}.json")
            val backup = File("${target.path}.bak")
            val committedBytes = target.readBytes()
            val interruptedWrite = AtomicFile(target)
            interruptedWrite.startWrite().let { output ->
                output.write("{\"interrupted\":true}".toByteArray(Charsets.UTF_8))
                interruptedWrite.failWrite(output)
            }
            assertArrayEquals(committedBytes, target.readBytes())
            assertFalse(backup.exists())

            assertTrue(target.renameTo(backup))
            target.writeText("{\"schemaVersion\":1", Charsets.UTF_8)

            val recovered = FileRegionalJobRepository(root).get(record.jobId)

            assertEquals(record, recovered)
            assertFalse(backup.exists())
            assertArrayEquals(committedBytes, target.readBytes())

            val repositories = listOf(
                FileRegionalJobRepository(root),
                FileRegionalJobRepository(root),
            )
            val outcomes = repositories.mapIndexed { index, repository ->
                async {
                    runCatching {
                        repository.update(record.jobId, record.revision) { current ->
                            current.requestCancellation(2_000L + index)
                        }
                    }
                }
            }.awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            assertTrue(outcomes.first { it.isFailure }.exceptionOrNull() is RegionalJobConflictException)
            assertEquals(1L, FileRegionalJobRepository(root).get(record.jobId)?.revision)
        } finally {
            root.deleteRecursively()
        }
    }
}
