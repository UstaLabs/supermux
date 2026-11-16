package dev.supermux.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.supermux.android.AppFleet
import dev.supermux.android.MainActivity
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps downloads going when the app's screen goes (#157). The fleet owns the downloads (#95), and the fleet lives
 * while someone holds it: with only the activity holding it, Back from Home closed the fleet and stopped every
 * download half way. While any download runs, this foreground service holds the fleet too, so leaving the app
 * leaves them running, and Android keeps the process for them. It says so in a quiet notification, and stops
 * itself when the last one ends.
 */
class DownloadKeepService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shared: AppFleet.Shared? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val held = AppFleet.acquire(this, this)
        shared = held
        startInForeground(held.fleet.downloads.items.value)
        scope.launch {
            held.fleet.downloads.items
                .map { all -> all.filter { it.state is DownloadState.Running } }
                .distinctUntilChanged()
                .collect { running ->
                    if (running.isEmpty()) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(running))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        shared = null
        AppFleet.release(this)
        super.onDestroy()
    }

    private fun startInForeground(items: List<Download>) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, localized(Str.host_app_android_downloads_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        val running = items.filter { it.state is DownloadState.Running }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(running), type) }
            .onFailure { stopSelf() }
    }

    /** One line: the file's name and how far it is, or how many run. Opens the app (its downloads list). */
    private fun notification(running: List<Download>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val only = running.singleOrNull()
        val title = if (only != null) {
            localized(Str.host_app_android_downloads_one, only.name)
        } else {
            localized(Str.host_app_android_downloads_many, running.size)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        val progress = only?.state as? DownloadState.Running
        val total = progress?.total
        if (progress != null && total != null && total > 0) {
            builder.setProgress(1000, ((progress.received * 1000) / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 157

        /**
         * Starts the service once [downloads] has a running download, for as long as the process lives: the fleet
         * calls this when it is built. The service itself stops when the last one ends.
         */
        fun keepWhileRunning(context: Context, downloads: DownloadCenter, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                downloads.items
                    .map { all -> all.any { it.state is DownloadState.Running } }
                    .distinctUntilChanged()
                    .collect { running ->
                        if (running) runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadKeepService::class.java)) }
                    }
            }
        }
    }
}
