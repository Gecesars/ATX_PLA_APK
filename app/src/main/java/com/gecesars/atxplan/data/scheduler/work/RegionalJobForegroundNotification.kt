package com.gecesars.atxplan.data.scheduler.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.gecesars.atxplan.MainActivity
import com.gecesars.atxplan.R
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalJobExecutionRequestV1
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Synchronous foreground surface used by a CoroutineWorker and its non-suspending progress callback. */
interface RegionalJobForegroundController {
    fun canRun(): Boolean

    fun initial(request: RegionalJobExecutionRequestV1): ForegroundInfo

    /** Publishes an update under the ID derived from the physical WorkRequest before returning. */
    fun update(request: RegionalJobExecutionRequestV1, progress: RegionalDownloadProgress): ForegroundInfo
}

class RegionalJobForegroundNotification(
    context: Context,
) : RegionalJobForegroundController {
    private val applicationContext = context.applicationContext
    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationManagerCompat = NotificationManagerCompat.from(applicationContext)

    override fun canRun(): Boolean {
        createChannel()
        if (!notificationManagerCompat.areNotificationsEnabled()) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun initial(request: RegionalJobExecutionRequestV1): ForegroundInfo {
        createChannel()
        val cancellation = RegionalJobCancellationRequestV1(request)
        return foregroundInfo(
            schedulerIdentity = request.schedulerIdentity,
            notification = notification(
                cancellation = cancellation,
                contentText = applicationContext.getString(R.string.regional_work_notification_preparing),
                progress = null,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    override fun update(
        request: RegionalJobExecutionRequestV1,
        progress: RegionalDownloadProgress,
    ): ForegroundInfo {
        check(canRun()) { "The foreground notification is no longer visible." }
        val cancellation = RegionalJobCancellationRequestV1(request)
        val info = foregroundInfo(
            schedulerIdentity = request.schedulerIdentity,
            notification = notification(
                cancellation = cancellation,
                contentText = progress.notificationText(),
                progress = progress,
            ),
        )
        notificationManager.notify(info.notificationId, info.notification)
        return info
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.regional_work_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.regional_work_channel_description)
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun notification(
        cancellation: RegionalJobCancellationRequestV1,
        contentText: String,
        progress: RegionalDownloadProgress?,
    ): Notification {
        val notificationId = stableNotificationId(cancellation.executionRequest.schedulerIdentity)
        val cancelIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId,
            RegionalJobCancelIntentContract.intent(applicationContext, cancellation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_regional_data_notification)
            .setContentTitle(applicationContext.getString(R.string.regional_work_notification_title))
            .setContentText(contentText.take(MAXIMUM_NOTIFICATION_TEXT_CHARACTERS))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgressFrom(progress)
            .addAction(
                R.drawable.ic_regional_data_notification,
                applicationContext.getString(R.string.regional_work_notification_cancel),
                cancelIntent,
            )
            .build()
    }

    private fun foregroundInfo(schedulerIdentity: String, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                stableNotificationId(schedulerIdentity),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(stableNotificationId(schedulerIdentity), notification)
        }

    private fun NotificationCompat.Builder.setProgressFrom(
        progress: RegionalDownloadProgress?,
    ): NotificationCompat.Builder {
        if (progress == null) return setProgress(PROGRESS_MAXIMUM, 0, true)
        val fraction = progress.fraction
        return if (fraction == null) {
            setProgress(PROGRESS_MAXIMUM, 0, true)
        } else {
            setProgress(
                PROGRESS_MAXIMUM,
                (fraction.coerceIn(0f, 1f) * PROGRESS_MAXIMUM).roundToInt(),
                false,
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "atx_regional_data_acquisition_v1"
        const val PROGRESS_MAXIMUM = 1_000
        const val MAXIMUM_NOTIFICATION_TEXT_CHARACTERS = 120
    }
}

internal fun stableNotificationId(schedulerIdentity: String): Int {
    val physicalWorkId = UUID.fromString(schedulerIdentity)
    require(physicalWorkId.toString() == schedulerIdentity) {
        "A notification requires a canonical physical WorkRequest UUID."
    }
    val mixed = physicalWorkId.mostSignificantBits xor physicalWorkId.leastSignificantBits
    val folded = (mixed xor (mixed ushr Integer.SIZE)).toInt()
    return (folded and Int.MAX_VALUE).takeUnless { it == 0 } ?: MINIMUM_NOTIFICATION_ID
}

private fun RegionalDownloadProgress.notificationText(): String {
    val title = artifact.source.title.take(MAXIMUM_DATASET_TITLE_CHARACTERS)
    return when (status) {
        RegionalTransferStatus.QUEUED -> "Queued: $title"
        RegionalTransferStatus.DOWNLOADING -> buildString {
            append("Downloading: ")
            append(title)
            append(" - ")
            append(formatBytes(completedBytes))
            totalBytes?.let { total ->
                append(" / ")
                append(formatBytes(total))
            }
        }
        RegionalTransferStatus.VERIFYING -> "Verifying: $title"
        RegionalTransferStatus.PROCESSING -> "Processing: $title"
        RegionalTransferStatus.READY -> "Ready: $title"
        RegionalTransferStatus.EXISTING -> "Verified local data: $title"
        RegionalTransferStatus.NOT_FOUND -> "Optional data was not published: $title"
        RegionalTransferStatus.FAILED -> "Acquisition failed: $title"
        RegionalTransferStatus.CANCELLED -> "Canceling: $title"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < KIBIBYTE -> "$bytes B"
    bytes < MEBIBYTE -> String.format(Locale.US, "%.1f KiB", bytes.toDouble() / KIBIBYTE)
    bytes < GIBIBYTE -> String.format(Locale.US, "%.1f MiB", bytes.toDouble() / MEBIBYTE)
    else -> String.format(Locale.US, "%.1f GiB", bytes.toDouble() / GIBIBYTE)
}

private const val MAXIMUM_DATASET_TITLE_CHARACTERS = 52
private const val MINIMUM_NOTIFICATION_ID = 1
private const val KIBIBYTE = 1_024L
private const val MEBIBYTE = KIBIBYTE * 1_024L
private const val GIBIBYTE = MEBIBYTE * 1_024L
