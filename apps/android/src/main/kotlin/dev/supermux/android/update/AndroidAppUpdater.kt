// Cluster G1: Android's actual behind `Platform.updates`. The feed poll, the APK download and the
// PackageInstaller hand-off still live in [AppUpdate] (FileProvider, ACTION_VIEW, SharedPreferences)
// and the status-bar progress/alert notifications in [AppUpdateNotifier]; this is the state machine
// over both that a SHARED update screen (cluster G5) drives.
package dev.supermux.android.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.DownloadedInstaller
import dev.supermux.ui.platform.UpdatePhase
import dev.supermux.ui.platform.UpdateStatus
import dev.supermux.update.ClientUpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android app updating itself: polls versions.json / GitHub latest, downloads the release APK,
 * and hands it to the system package installer. Distinct from the broker's own System-settings
 * update (`POST /api/update/run`).
 *
 * Every phase also posts to the status bar through [AppUpdateNotifier] exactly where it always did:
 * a silent ongoing progress bar while downloading, a short "installing" state, and a
 * default-importance alert on failure — including the "install unknown apps is off" refusal, which
 * additionally raises [UpdateStatus.needsInstallPermission] so the screen can offer the settings
 * jump.
 */
class AndroidAppUpdater(
    context: Context,
    private val http: HttpClient = HttpClient(CIO),
) : AppUpdater {
    private val appContext = context.applicationContext

    private val state = MutableStateFlow(UpdateStatus())
    override val status: StateFlow<UpdateStatus> = state.asStateFlow()

    override val currentVersion: String get() = AppUpdate.currentVersionName(appContext)
    override val currentVersionCode: Int? get() = AppUpdate.currentVersionCode(appContext)

    override suspend fun check(): UpdateStatus {
        state.value = state.value.copy(phase = UpdatePhase.Checking, error = null)
        val result = runCatching { AppUpdate.check(http, appContext) }
        val next = result.fold(
            onSuccess = { s ->
                UpdateStatus(
                    phase = if (s.updateAvailable) UpdatePhase.Available else UpdatePhase.UpToDate,
                    release = s,
                    dismissed = s.latestVersion?.let { AppUpdate.isDismissed(appContext, it) } == true,
                )
            },
            onFailure = { e -> UpdateStatus(phase = UpdatePhase.Failed, error = e.message) },
        )
        state.value = next
        return next
    }

    override suspend fun download(
        onProgress: (received: Long, total: Long?) -> Unit,
    ): DownloadedInstaller? = withContext(Dispatchers.IO) {
        val url = state.value.release?.downloadUrl ?: return@withContext null

        // The OS refuses an install from a source the user has not allowed; ask BEFORE spending a
        // download on it (what `downloadAndInstall` returned "need-permission" for).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            AppUpdateNotifier.showError(appContext, NEEDS_PERMISSION_TEXT)
            state.value = state.value.copy(
                phase = UpdatePhase.Failed,
                error = NEEDS_PERMISSION_TEXT,
                needsInstallPermission = true,
            )
            return@withContext null
        }

        state.value = state.value.copy(
            phase = UpdatePhase.Downloading,
            error = null,
            needsInstallPermission = false,
            bytesReceived = 0L,
            contentLength = null,
        )
        AppUpdateNotifier.ensureChannels(appContext)
        AppUpdateNotifier.showProgress(appContext, 0L, null)
        emit(onProgress, 0L, null)

        var lastReportedPct = -1
        var lastReportedBytes = -1L
        val bytes = try {
            ClientUpdateChecker(http).download(url) { received, total ->
                val pct = AppUpdateNotifier.progressPercent(received, total)
                // Throttle status-bar + UI updates: every whole percent, or every 256 KiB when
                // Content-Length is unknown (verbatim from the old downloadAndInstall).
                val shouldEmit = when {
                    pct != null -> pct != lastReportedPct
                    else -> received - lastReportedBytes >= 256 * 1024 || lastReportedBytes < 0
                }
                if (shouldEmit) {
                    if (pct != null) lastReportedPct = pct
                    lastReportedBytes = received
                    AppUpdateNotifier.showProgress(appContext, received, total)
                    emit(onProgress, received, total)
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: "Download failed"
            AppUpdateNotifier.showError(appContext, msg)
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = msg)
            return@withContext null
        }

        // Final 100% tick when the length was known (or a last size tick when it was not).
        AppUpdateNotifier.showProgress(appContext, bytes.size.toLong(), bytes.size.toLong())
        emit(onProgress, bytes.size.toLong(), bytes.size.toLong())

        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "supermux-update.apk")
        try {
            apk.writeBytes(bytes)
        } catch (e: Throwable) {
            val msg = e.message ?: "Could not write APK"
            AppUpdateNotifier.showError(appContext, msg)
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = msg)
            return@withContext null
        }
        DownloadedInstaller(location = apk.absolutePath, kind = "apk")
    }

    override suspend fun install(installer: DownloadedInstaller): String? = withContext(Dispatchers.Main) {
        state.value = state.value.copy(phase = UpdatePhase.Installing, error = null)
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                File(installer.location),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            AppUpdateNotifier.showInstalling(appContext)
            null
        } catch (e: Throwable) {
            val msg = e.message ?: "Could not open installer"
            AppUpdateNotifier.showError(appContext, msg)
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = msg)
            msg
        }
    }

    override fun openReleaseNotes() {
        state.value.release?.notesUrl?.let { AppUpdate.openNotes(appContext, it) }
    }

    override fun openInstallPermissionSettings() {
        AppUpdate.openInstallPermissionSettings(appContext)
    }

    override fun dismiss() {
        state.value.release?.latestVersion?.let { AppUpdate.dismiss(appContext, it) }
        state.value = state.value.copy(dismissed = true)
    }

    private suspend fun emit(
        onProgress: (Long, Long?) -> Unit,
        received: Long,
        total: Long?,
    ) = withContext(Dispatchers.Main.immediate) { onProgress(received, total) }

    companion object {
        /** The exact refusal text the screen and the status-bar alert have always shown. */
        const val NEEDS_PERMISSION_TEXT = "Allow installing apps from this source, then try again."
    }
}
