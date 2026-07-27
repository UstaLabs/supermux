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
     */
    suspend fun downloadAndInstall(
        http: HttpClient,
        context: Context,
        downloadUrl: String,
    ): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return@withContext "need-permission"
        }
        val bytes = try {
            ClientUpdateChecker(http).download(downloadUrl)
        } catch (e: Throwable) {
            return@withContext e.message ?: "Download failed"
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "supermux-update.apk")
        try {
            apk.writeBytes(bytes)
        } catch (e: Throwable) {
            return@withContext e.message ?: "Could not write APK"
        }
        withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                null
            } catch (e: Throwable) {
                e.message ?: "Could not open installer"
            }
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
