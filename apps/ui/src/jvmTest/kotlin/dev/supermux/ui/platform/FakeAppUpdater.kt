package dev.supermux.ui.platform

import dev.supermux.update.ClientUpdateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The reference [AppUpdater]: an in-memory updater that walks the SAME phases both real ones do.
 *
 * It is the executable half of the seam's contract — `AppUpdaterContractTest` drives it through
 * every path (up to date, available, refused install, failed download, dismissal) and asserts the
 * transitions `DesktopAppUpdater` and `AndroidAppUpdater` must also make, so a host implementation
 * has one place to be compared against.
 */
internal class FakeAppUpdater(
    override val currentVersion: String = "1.0.0",
    override val currentVersionCode: Int? = null,
    /** What [check] finds; null makes the check FAIL (no status at all). */
    var release: ClientUpdateStatus? = null,
    /** Progress ticks [download] emits before finishing, as `(received, total)`. */
    var ticks: List<Pair<Long, Long?>> = listOf(50L to 100L, 100L to 100L),
    /** Non-null makes [download] fail with this text. */
    var downloadError: String? = null,
    /** True makes [download] refuse up front the way an Android unknown-sources block does. */
    var refuseInstall: Boolean = false,
    /** Non-null makes [install] fail with this text. */
    var installError: String? = null,
    /**
     * Held open by a test that needs to observe a download MID-FLIGHT (the phase guard, a
     * cancellation): [download] awaits it after its first tick. Null = run straight through.
     */
    var midDownload: CompletableDeferred<Unit>? = null,
    /**
     * Held open by a test that needs to observe a CHECK mid-flight — the screen's "spinner while
     * the first check runs and there is nothing to show yet" state. Null = answer immediately.
     */
    var midCheck: CompletableDeferred<Unit>? = null,
) : AppUpdater {
    private val state = MutableStateFlow(UpdateStatus())
    override val status: StateFlow<UpdateStatus> = state.asStateFlow()

    val dismissedVersions = mutableListOf<String>()
    var notesOpened = 0
    var permissionSettingsOpened = 0

    /** How many checks actually ran (a check refused by the busy guard does not count). */
    var checks = 0
        private set

    /** Every installer handed to [install], in order. */
    val installed = mutableListOf<DownloadedInstaller>()

    override suspend fun check(): UpdateStatus {
        // Contract: a live download/install owns the phase (both host updaters do this).
        if (state.value.busy) return state.value
        state.value = state.value.copy(phase = UpdatePhase.Checking, error = null)
        checks++
        midCheck?.await()
        val found = release
        state.value = if (found == null) {
            UpdateStatus(phase = UpdatePhase.Failed, error = "Couldn't check for updates.")
        } else {
            UpdateStatus(
                phase = if (found.updateAvailable) UpdatePhase.Available else UpdatePhase.UpToDate,
                release = found,
                dismissed = found.latestVersion in dismissedVersions,
            )
        }
        return state.value
    }

    override suspend fun download(onProgress: (Long, Long?) -> Unit): DownloadedInstaller? {
        val url = state.value.release?.downloadUrl ?: return null
        if (refuseInstall) {
            state.value = state.value.copy(
                phase = UpdatePhase.Failed,
                error = "Allow installing apps from this source, then try again.",
                needsInstallPermission = true,
            )
            return null
        }
        state.value = state.value.copy(
            phase = UpdatePhase.Downloading,
            error = null,
            needsInstallPermission = false,
            bytesReceived = 0L,
            contentLength = null,
        )
        try {
            var first = true
            for ((received, total) in ticks) {
                state.value = state.value.copy(bytesReceived = received, contentLength = total)
                onProgress(received, total)
                if (first) {
                    first = false
                    midDownload?.await()
                }
            }
            downloadError?.let {
                state.value = state.value.copy(phase = UpdatePhase.Failed, error = it)
                return null
            }
            return DownloadedInstaller(location = "/tmp/$url", kind = "apk")
        } finally {
            // Contract: a cancelled transfer must not strand the phase at Downloading.
            if (state.value.phase == UpdatePhase.Downloading) state.value = state.value.settled()
        }
    }

    override suspend fun install(installer: DownloadedInstaller): String? {
        installed += installer
        state.value = state.value.copy(phase = UpdatePhase.Installing, error = null)
        installError?.let {
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = it)
            return it
        }
        // Contract: the OS installer is up but this process lives on, so the phase settles back to
        // a usable state instead of leaving every CTA disabled.
        state.value = state.value.settled()
        return null
    }


    override fun openReleaseNotes() {
        if (state.value.release?.notesUrl != null) notesOpened++
    }

    override fun openInstallPermissionSettings() {
        permissionSettingsOpened++
    }

    override fun dismiss() {
        state.value.release?.latestVersion?.let { dismissedVersions.add(it) }
        state.value = state.value.copy(dismissed = true)
    }
}
