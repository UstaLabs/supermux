// Cluster G1: desktop's actual behind `Platform.updates`. The release-feed poll, the installer
// download and the "hand it to the OS" call all still live in [AppUpdate] (java.awt.Desktop,
// java.nio, java.util.prefs); this is the state machine over them that a SHARED update screen
// (cluster G5) drives.
package dev.supermux.desktop.update

import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.DownloadedInstaller
import dev.supermux.ui.platform.UpdatePhase
import dev.supermux.ui.platform.UpdateStatus
import dev.supermux.ui.platform.settled
import dev.supermux.update.ClientUpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * The desktop client updating itself against supermux.dev/versions.json (GitHub fallback):
 * downloads the platform installer (.deb / .msi / .dmg) and opens it with the OS.
 *
 * The phases map 1:1 onto what `AppUpdateUi` kept in local `remember`s before the seam existed —
 * `loading` is [UpdatePhase.Checking], `installing` covers Downloading+Installing, `actionError` is
 * [UpdateStatus.error], and a check that could not produce a status at all leaves
 * [UpdateStatus.release] null, which is desktop's "Couldn't check for updates." case.
 */
class DesktopAppUpdater(
    private val http: HttpClient = HttpClient(CIO),
    override val currentVersion: String = DESKTOP_APP_VERSION,
) : AppUpdater {
    private val state = MutableStateFlow(UpdateStatus())
    override val status: StateFlow<UpdateStatus> = state.asStateFlow()

    /** Desktop packages carry no version CODE (that is Android's monotonic build counter). */
    override val currentVersionCode: Int? = null

    override suspend fun check(): UpdateStatus {
        // A live download/install owns the phase: the page and the banner BOTH check on open, and
        // opening one mid-download must not flip Downloading → Checking (losing the progress and
        // re-enabling the CTA). Poll again when it settles.
        if (state.value.busy) return state.value
        state.value = state.value.copy(phase = UpdatePhase.Checking, error = null)
        val result = runCatching { AppUpdate.check(http, currentVersion) }
        val next = result.fold(
            onSuccess = { s ->
                UpdateStatus(
                    phase = if (s.updateAvailable) UpdatePhase.Available else UpdatePhase.UpToDate,
                    release = s,
                    dismissed = s.latestVersion?.let { AppUpdate.isDismissed(it) } == true,
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
        state.value = state.value.copy(
            phase = UpdatePhase.Downloading,
            error = null,
            bytesReceived = 0L,
            contentLength = null,
        )
        // A caller that navigates away cancels this coroutine mid-transfer; without the `finally`
        // the phase would stay Downloading forever and every CTA stays disabled.
        try {
            val bytes = try {
                ClientUpdateChecker(http).download(url) { received, total ->
                    state.value = state.value.copy(bytesReceived = received, contentLength = total)
                    onProgress(received, total)
                }
            } catch (e: CancellationException) {
                // Not a failure: the CALLER went away (navigating off the page). Let it propagate
                // so the `finally` settles the phase instead of showing a spurious "Download
                // failed" under a disabled CTA.
                throw e
            } catch (e: Throwable) {
                state.value = state.value.copy(phase = UpdatePhase.Failed, error = e.message ?: "Download failed")
                return@withContext null
            }
            val ext = AppUpdate.installerExtension()
            val file = try {
                val dir = Files.createTempDirectory("supermux-update").toFile()
                File(dir, "supermux-update.$ext").also { it.writeBytes(bytes) }
            } catch (e: Throwable) {
                state.value = state.value.copy(
                    phase = UpdatePhase.Failed,
                    error = e.message ?: "Could not write installer",
                )
                return@withContext null
            }
            state.value = state.value.copy(
                bytesReceived = bytes.size.toLong(),
                contentLength = bytes.size.toLong(),
            )
            DownloadedInstaller(location = file.absolutePath, kind = ext)
        } finally {
            if (state.value.phase == UpdatePhase.Downloading) state.value = state.value.settled()
        }
    }

    override suspend fun install(installer: DownloadedInstaller): String? = withContext(Dispatchers.IO) {
        state.value = state.value.copy(phase = UpdatePhase.Installing, error = null)
        try {
            AppUpdate.openInstaller(File(installer.location))
            // The OS installer is up; this process keeps running until it is replaced, so the page
            // must settle back to a usable state (the old `installing = false`) rather than stay
            // Installing with every CTA disabled.
            state.value = state.value.settled()
            null
        } catch (e: Throwable) {
            val msg = e.message ?: "Could not open installer"
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = msg)
            msg
        }
    }

    override fun openReleaseNotes() {
        state.value.release?.notesUrl?.let { AppUpdate.openUrl(it) }
    }

    /** Desktop installs a `.deb`/`.msi`/`.dmg` through the OS; there is no permission to grant. */
    override fun openInstallPermissionSettings() {}

    override fun dismiss() {
        state.value.release?.latestVersion?.let { AppUpdate.dismiss(it) }
        state.value = state.value.copy(dismissed = true)
    }

    companion object {
        /**
         * The one updater for the process. `DesktopPlatform` is built per theme mount, and a new
         * updater per mount would drop an in-flight download's progress (and leak an `HttpClient`).
         */
        val shared: DesktopAppUpdater by lazy { DesktopAppUpdater() }
    }

}
