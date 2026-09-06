package dev.supermux.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.supermux.update.ClientPlatform
import dev.supermux.update.ClientUpdateChecker
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

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
