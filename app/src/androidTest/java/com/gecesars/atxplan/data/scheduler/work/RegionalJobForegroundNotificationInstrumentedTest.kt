package com.gecesars.atxplan.data.scheduler.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobSchedulerKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalJobForegroundNotificationInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun initialForegroundInfoUsesStablePhysicalWorkIdentityAndDataSyncType() {
        val execution = executionRequest()
        val controller = RegionalJobForegroundNotification(context)

        val first = controller.initial(execution)
        val repeated = controller.initial(execution)
        val nextGeneration = execution.copy(schedulerGeneration = execution.schedulerGeneration + 1)
        val second = controller.initial(
            nextGeneration.copy(
                schedulerIdentity = RegionalWorkContractV1.deterministicWorkId(
                    RegionalWorkInputV1(
                        jobId = nextGeneration.jobId,
                        planFingerprintSha256 = nextGeneration.planFingerprintSha256,
                        schedulerGeneration = nextGeneration.schedulerGeneration,
                    ),
                ).toString(),
            ),
        )

        val fullPracticalHash = UUID.fromString(execution.schedulerIdentity).hashCode() and Int.MAX_VALUE
        assertEquals(fullPracticalHash, first.notificationId)
        assertEquals(first.notificationId, repeated.notificationId)
        assertNotEquals(first.notificationId, second.notificationId)
        assertEquals(execution.jobId, nextGeneration.jobId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, first.foregroundServiceType)
        }
        assertNotNull(first.notification.contentIntent)
        assertEquals(1, first.notification.actions.size)
        assertEquals(context.packageName, first.notification.actions.single().actionIntent.creatorPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(first.notification.actions.single().actionIntent.isImmutable)
            assertTrue(first.notification.contentIntent.isImmutable)
        }
    }

    @Test
    fun channelIsStableLowImportanceAndCancelIntentFailsClosed() {
        val execution = executionRequest()
        val controller = RegionalJobForegroundNotification(context)
        controller.initial(execution)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel("atx_regional_data_acquisition_v1")
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertFalse(channel.canShowBadge())
        }

        val request = RegionalJobCancellationRequestV1(execution)
        val exactIntent = RegionalJobCancelIntentContract.intent(context, request)
        assertEquals(request, RegionalJobCancelIntentContract.decode(exactIntent))
        assertNotEquals(null, exactIntent.component)
        assertEquals(context.packageName, exactIntent.component?.packageName)

        val missingIdentity = RegionalJobCancelIntentContract.intent(context, request).apply { data = null }
        assertEquals(null, RegionalJobCancelIntentContract.decode(missingIdentity))
        val unknownExtra = RegionalJobCancelIntentContract.intent(context, request).apply {
            putExtra("unexpected", "rejected")
        }
        assertEquals(null, RegionalJobCancelIntentContract.decode(unknownExtra))
    }

    private fun executionRequest(): RegionalJobExecutionRequestV1 {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                reason = "foreground notification instrumented test",
            ),
        )
        val record = RegionalJobRecordV1.enqueuePending(
            jobId = "123e4567-e89b-42d3-a456-426614174000",
            plan = plan,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            acceptedAtEpochMillis = 1_000L,
            createdAtEpochMillis = 1_000L,
        )
        val input = RegionalWorkInputV1(
            jobId = record.jobId,
            planFingerprintSha256 = record.planFingerprintSha256,
            schedulerGeneration = record.schedulerGeneration,
        )
        return RegionalJobExecutionRequestV1(
            jobId = input.jobId,
            planFingerprintSha256 = input.planFingerprintSha256,
            schedulerKind = RegionalJobSchedulerKind.WORK_MANAGER_FOREGROUND,
            schedulerGeneration = input.schedulerGeneration,
            schedulerIdentity = RegionalWorkContractV1.deterministicWorkId(input).toString(),
        )
    }
}
