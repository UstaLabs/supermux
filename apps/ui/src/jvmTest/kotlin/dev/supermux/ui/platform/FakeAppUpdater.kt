package dev.supermux.ui.platform

import dev.supermux.update.ClientUpdateStatus
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
) : AppUpdater {
    private val state = MutableStateFlow(UpdateStatus())
    override val status: StateFlow<UpdateStatus> = state.asStateFlow()

    val dismissedVersions = mutableListOf<String>()
    var notesOpened = 0
    var permissionSettingsOpened = 0

    override suspend fun check(): UpdateStatus {
        state.value = state.value.copy(phase = UpdatePhase.Checking, error = null)
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
        for ((received, total) in ticks) {
            state.value = state.value.copy(bytesReceived = received, contentLength = total)
            onProgress(received, total)
        }
        downloadError?.let {
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = it)
            return null
        }
        return DownloadedInstaller(location = "/tmp/$url", kind = "apk")
    }

    override suspend fun install(installer: DownloadedInstaller): String? {
        state.value = state.value.copy(phase = UpdatePhase.Installing, error = null)
        installError?.let {
            state.value = state.value.copy(phase = UpdatePhase.Failed, error = it)
            return it
        }
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
