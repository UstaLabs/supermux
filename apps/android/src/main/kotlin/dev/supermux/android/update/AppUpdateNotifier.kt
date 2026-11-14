package dev.supermux.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.supermux.android.MainActivity
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.ui.update.formatUpdateBytes
import dev.supermux.ui.update.formatUpdateProgress

/**
 * Status-bar notifications for APK self-update: indeterminate/percent progress while
 * downloading, a "tap to install" state once the APK is on disk, and a high-importance alert on failure.
 *
 * Progress posts go to a low-importance channel (silent ongoing bar). Errors use a
 * separate default-importance channel so the user is actually alerted.
 */
object AppUpdateNotifier {
    const val CHANNEL_PROGRESS_ID = "app_update_progress"
    const val CHANNEL_ALERT_ID = "app_update_alerts"
    const val NOTIFICATION_ID = 71_001

    private const val CHANNEL_PROGRESS_NAME = "App update download"
    private const val CHANNEL_ALERT_NAME = "App update alerts"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_PROGRESS_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS_ID,
                    CHANNEL_PROGRESS_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows download progress when installing an app update"
                    setShowBadge(false)
                },
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ALERT_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    CHANNEL_ALERT_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alerts when an app update is ready to install, or fails"
                },
            )
        }
    }

    fun showProgress(
        context: Context,
        bytesReceived: Long,
        contentLength: Long?,
    ) {
        if (!canPost(context)) return
        ensureChannels(context)
        val total = contentLength?.takeIf { it > 0 }
        val known = total != null
        val pct = if (total != null) {
            ((bytesReceived * 100) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val text = if (total != null) {
            "$pct% · ${formatBytes(bytesReceived)} / ${formatBytes(total)}"
        } else if (bytesReceived > 0) {
            formatBytes(bytesReceived)
        } else {
            "Starting download…"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update")
            .setContentText(text)
            .setProgress(if (known) 100 else 0, pct, !known)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Replace any prior error on the same id.
            .setAutoCancel(false)
        notify(context, builder)
    }

    /**
     * The APK is on disk: tapping opens the system installer ([installIntent]). It outlives a dismissed or
     * never-shown installer, so the update is never more than one tap away (#140). [alert] when the user is
     * elsewhere and Android would not let us open the installer ourselves; silent when it just opened in front.
     */
    fun showReadyToInstall(context: Context, installIntent: Intent, alert: Boolean) {
        if (!canPost(context)) return
        ensureChannels(context)
        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID + 1,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, if (alert) CHANNEL_ALERT_ID else CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update downloaded")
            .setContentText("Tap to install")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (alert) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
        notify(context, builder)
    }

    /**
     * Replace the progress notification with a user-visible failure alert.
     * [message] is shown as the body (and BigText for long errors).
     */
    fun showError(context: Context, message: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val body = message.ifBlank { "Download or install failed" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Update failed")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        notify(context, builder)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, builder: NotificationCompat.Builder) {
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun canPost(context: Context): Boolean =
        SupermuxMessagingService.hasPostPermission(context)

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cluster G5: the shared screen shows the same byte count, so the wording lives in `:ui`. */
    internal fun formatBytes(bytes: Long): String = formatUpdateBytes(bytes)

    /** 0–100 when length known; null when indeterminate. */
    internal fun progressPercent(bytesReceived: Long, contentLength: Long?): Int? {
        if (contentLength == null || contentLength <= 0) return null
        return ((bytesReceived * 100) / contentLength).toInt().coerceIn(0, 100)
    }

    /**
     * In-app button label while an APK download is in flight. Cluster G5 moved the wording to the
     * shared `formatUpdateProgress` (the shared CTA shows it); this stays as the notifier's name
     * for it so the status bar and the button can never drift apart.
     */
    fun formatDownloadProgress(bytesReceived: Long, contentLength: Long?): String =
        formatUpdateProgress(bytesReceived, contentLength)
}
