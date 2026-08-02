package dev.supermux.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.supermux.update.ClientPlatform
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android app self-update: polls versions.json / GitHub latest, downloads the
 * release APK, and hands it to the system package installer.
 *
 * Distinct from broker System-settings updates (POST /api/update/run).
 *
 * Download/install posts a status-bar notification with progress (and an alert
 * on failure) via [AppUpdateNotifier].
 */
object AppUpdate {
    private const val PREFS = "app_update"
    private const val KEY_DISMISSED = "dismissed_latest"

    fun currentVersionName(context: Context): String =
        runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pi.versionName ?: "0"
        }.getOrDefault("0")

    fun currentVersionCode(context: Context): Int =
        runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        }.getOrDefault(0)

    suspend fun check(http: HttpClient, context: Context): ClientUpdateStatus {
        val checker = ClientUpdateChecker(http)
        return checker.check(
            platform = ClientPlatform.ANDROID,
            currentVersion = currentVersionName(context),
            currentVersionCode = currentVersionCode(context),
        )
    }

    /**
     * Download the APK and launch the system installer. Returns an error string,
     * or null on success (install UI shown). May return "need-permission" when
     * unknown-sources install is blocked — caller should open [openInstallPermissionSettings].
     *
     * [onProgress] receives `(bytesReceived, contentLength?)` on the main dispatcher
     * (throttled to whole-percent changes when length is known).
     */
    suspend fun downloadAndInstall(
        http: HttpClient,
        context: Context,
        downloadUrl: String,
        onProgress: ((bytesReceived: Long, contentLength: Long?) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appCtx.packageManager.canRequestPackageInstalls()
        ) {
            AppUpdateNotifier.showError(
                appCtx,
                "Allow installing apps from this source, then try again.",
            )
            return@withContext "need-permission"
        }

        AppUpdateNotifier.ensureChannels(appCtx)
        AppUpdateNotifier.showProgress(appCtx, 0L, null)
        emitProgress(onProgress, 0L, null)

        var lastReportedPct = -1
        var lastReportedBytes = -1L
        val bytes = try {
            ClientUpdateChecker(http).download(downloadUrl) { received, total ->
                val pct = AppUpdateNotifier.progressPercent(received, total)
                // Throttle status-bar + UI updates: every whole percent, or every
                // 256 KiB when Content-Length is unknown.
                val shouldEmit = when {
                    pct != null -> pct != lastReportedPct
                    else -> received - lastReportedBytes >= 256 * 1024 || lastReportedBytes < 0
                }
                if (shouldEmit) {
                    if (pct != null) lastReportedPct = pct
                    lastReportedBytes = received
                    AppUpdateNotifier.showProgress(appCtx, received, total)
                    emitProgress(onProgress, received, total)
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: "Download failed"
            AppUpdateNotifier.showError(appCtx, msg)
            return@withContext msg
        }

        // Final 100% tick when length was known (or a last size tick when not).
        AppUpdateNotifier.showProgress(appCtx, bytes.size.toLong(), bytes.size.toLong())
        emitProgress(onProgress, bytes.size.toLong(), bytes.size.toLong())

        val dir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "supermux-update.apk")
        try {
            apk.writeBytes(bytes)
        } catch (e: Throwable) {
            val msg = e.message ?: "Could not write APK"
            AppUpdateNotifier.showError(appCtx, msg)
            return@withContext msg
        }

        withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(
                    appCtx,
                    "${appCtx.packageName}.fileprovider",
                    apk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(intent)
                AppUpdateNotifier.showInstalling(appCtx)
                null
            } catch (e: Throwable) {
                val msg = e.message ?: "Could not open installer"
                AppUpdateNotifier.showError(appCtx, msg)
                msg
            }
        }
    }

    private suspend fun emitProgress(
        onProgress: ((bytesReceived: Long, contentLength: Long?) -> Unit)?,
        bytesReceived: Long,
        contentLength: Long?,
    ) {
        if (onProgress == null) return
        withContext(Dispatchers.Main.immediate) {
            onProgress(bytesReceived, contentLength)
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun openNotes(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun isDismissed(context: Context, latestVersion: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISMISSED, null) == latestVersion
    }

    fun dismiss(context: Context, latestVersion: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED, latestVersion)
            .apply()
    }

    /** Convenience for one-shot checks (creates + closes its own client). */
    suspend fun checkOnce(context: Context): ClientUpdateStatus {
        val http = HttpClient(CIO)
        return try {
            check(http, context)
        } finally {
            http.close()
        }
    }
}
