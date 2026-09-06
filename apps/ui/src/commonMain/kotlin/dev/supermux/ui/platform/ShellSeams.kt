// Cluster G1: the shell-shaped half of [Platform] — self-update, OS notifications, extra OS
// windows and push registration. Same rule as the rest of the seam set: a shared screen names no
// `Intent`, no `TrayState`, no `FileProvider`, no Firebase; it reads `LocalPlatform` and calls one
// of these, and every member is total (a host that cannot do a thing answers "nothing happened").
package dev.supermux.ui.platform

import androidx.compose.runtime.Immutable
import dev.supermux.update.ClientUpdateStatus
import kotlinx.coroutines.flow.StateFlow

// ── Self-update ───────────────────────────────────────────────────────────────

/** Where the app's own self-update currently is. One machine over BOTH hosts' updaters. */
enum class UpdatePhase {
    /** Nothing has been checked yet (or the last check was cleared). */
    Idle,

    /** A check is in flight. The screen shows its spinner only while [UpdateStatus.release] is null. */
    Checking,

    /** Checked, and this build is current. */
    UpToDate,

    /** Checked, and a newer release exists ([UpdateStatus.release] carries version/notes/download). */
    Available,

    /** The installer is coming down; [UpdateStatus.bytesReceived]/[UpdateStatus.contentLength] tick. */
    Downloading,

    /** The bytes are on disk and the OS installer has been handed the file. */
    Installing,

    /** The last check, download or install failed; [UpdateStatus.error] is the text to show. */
    Failed,
}

/**
 * The updater's whole observable state — the union of what desktop's `AppUpdateUi` and Android's
 * `AppUpdatePage`/`AppUpdateBanner` each kept in their own `remember`s (loading, installing,
 * actionError, downloadLabel, dismissed) plus the check result they both hold.
 *
 * [release] is the last SUCCESSFUL-ish check (`ClientUpdateStatus` already carries its own
 * `lastError` for "reached the endpoint but it complained"); it is null only before the first check
 * and after a check that could not produce a status at all — which is desktop's
 * "Couldn't check for updates." case, kept distinct from `release.lastError`.
 */
@Immutable
data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: ClientUpdateStatus? = null,
    val bytesReceived: Long = 0L,
    val contentLength: Long? = null,
    /** Failure text for [UpdatePhase.Failed] — the platform's own message, never invented here. */
    val error: String? = null,
    /**
     * Android only: the install was refused because "install unknown apps" is off for this source.
     * The screen says so and calls [AppUpdater.openInstallPermissionSettings].
     */
    val needsInstallPermission: Boolean = false,
    /** The user dismissed the banner for [ClientUpdateStatus.latestVersion] (persisted per host). */
    val dismissed: Boolean = false,
) {
    /** True while a download/install round trip is in flight — both CTAs disable on it. */
    val busy: Boolean get() = phase == UpdatePhase.Downloading || phase == UpdatePhase.Installing

    /** Whole percent when the content length is known; null while indeterminate. */
    val percent: Int?
        get() {
            val total = contentLength?.takeIf { it > 0 } ?: return null
            return ((bytesReceived * 100) / total).toInt().coerceIn(0, 100)
        }
}

/**
 * The phase an updater returns to once a download or install stops owning it — the last check's
 * answer.
 *
 * Every implementation calls this rather than inventing its own reset: an install that leaves the
 * phase at [UpdatePhase.Installing] (this process keeps running until the new build replaces it)
 * or a cancelled download that leaves it at [UpdatePhase.Downloading] disables every CTA forever,
 * which is exactly what the pre-seam screens avoided with their `installing = false`.
 */
fun UpdateStatus.settled(): UpdateStatus =
    copy(phase = if (release?.updateAvailable == true) UpdatePhase.Available else UpdatePhase.UpToDate)

/** An installer already on disk. Opaque to shared code — only ever handed back to [AppUpdater.install]. */
@Immutable
data class DownloadedInstaller(
    /** An absolute path on desktop, the cached APK's path on Android. Never shown to the user. */
    val location: String,
    /** Installer extension (`deb`/`msi`/`dmg`/`apk`) — desktop's caption names it. */
    val kind: String,
)

/**
 * The app updating ITSELF (never the broker): check → download → hand to the OS installer.
 *
 * Gated by [Caps.appUpdate]. A store build that must not self-update installs
 * [NoAppUpdater], whose check reports [UpdatePhase.UpToDate] and whose download refuses.
 */
interface AppUpdater {
    /** Live state. Every mutator below also publishes here, so a banner and a page agree. */
    val status: StateFlow<UpdateStatus>

    /** The running build's marketing version (`1.0.0`) — the page's headline. */
    val currentVersion: String

    /** The running build's version code, or null where the platform has none (desktop). */
    val currentVersionCode: Int?

    /** Poll the release feed and publish the result. Returns the same value [status] moves to. */
    suspend fun check(): UpdateStatus

    /**
     * Download the installer for the release the last [check] found, reporting `(received, total)`
     * as it goes (total null while the length is unknown). Null when there is nothing to download,
     * when the platform refused up front (Android's "install unknown apps" gate — [status] then
     * carries [UpdateStatus.needsInstallPermission]), or when the transfer failed.
     */
    suspend fun download(onProgress: (received: Long, total: Long?) -> Unit): DownloadedInstaller?

    /** Hand [installer] to the OS (Software Install / MSI wizard / DiskImageMounter / PackageInstaller).
     *  Returns an error string, or null when the installer UI was launched. */
    suspend fun install(installer: DownloadedInstaller): String?

    /** Open the release-notes URL from the last check. No-op when there is none. */
    fun openReleaseNotes()

    /** Android's "allow installs from this source" settings page. No-op elsewhere. */
    fun openInstallPermissionSettings()

    /** Remember that the banner was dismissed for the currently-known latest version. */
    fun dismiss()
}

// ── OS notifications ──────────────────────────────────────────────────────────

/**
 * Fires one OS notification for an agent reply. Desktop's `TrayNotificationManager` wraps Compose
 * Desktop's `TrayState.sendNotification`; Android installs [NoopNotificationManager] because its
 * replies already arrive as FCM pushes (posting a second local one would double every message).
 *
 * The pure decision layer that calls this (desktop's `NotifyDecision`/`NotificationController`)
 * stays on the host until the shell moves — this is only the "actually show it" seam, which is what
 * a headless test replaces with a capturing fake.
 */
interface NotificationManager {
    fun notify(sessionId: String, title: String, message: String)
}

/** Null-object [NotificationManager] — the default for a host that shows nothing itself. */
object NoopNotificationManager : NotificationManager {
    override fun notify(sessionId: String, title: String, message: String) {}
}

// ── Extra OS windows ──────────────────────────────────────────────────────────

/**
 * Detaching panes into real OS windows. Non-null only where [Caps.multiWindow] is true (desktop);
 * Android has no second window to tear a pane into, and reads null.
 *
 * The registry itself (`WindowHosts`, its claim/rebase/transfer algebra and its 32 tests) stays
 * desktop — this is only the three verbs a shared shell needs.
 */
interface WindowHostController {
    /** Tear the pane showing [viewId] out of the calling window into a new one. */
    fun tearOutTab(viewId: String)

    /** Tear the calling window's whole workspace canvas out into a new window. */
    fun tearOutCanvas()

    /** Release the extra window [hostId] — its claim goes back to whoever owned it. */
    fun release(hostId: String)
}

// ── Push registration ─────────────────────────────────────────────────────────

/**
 * Native push registration. Non-null only where [Caps.push] is true (Android/FCM); desktop reads
 * null and relies on its own tray notifications while the process is alive.
 *
 * Both members are idempotent and safe to call on every launch and after every pairing change —
 * which is exactly how the entry point uses them.
 */
interface PushRegistrar {
    /** Create/refresh the OS notification channel. Must run before the app is STARTED. */
    fun ensureChannel()

    /** Ask for the runtime notification permission if the OS wants one. No-op where it does not. */
    fun requestPermission()

    /** Register (or re-register) this device with every paired host's relay. No-op when unpaired. */
    fun registerIfPaired()

    /** Drop any notification still showing for [sessionId] (the user just opened that chat). */
    fun cancelForSession(sessionId: String)
}

/** The "this build does not update itself" updater: nothing to check, nothing to download. */
object NoAppUpdater : AppUpdater {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(UpdateStatus(phase = UpdatePhase.UpToDate))
    override val status: StateFlow<UpdateStatus> = state
    override val currentVersion: String = ""
    override val currentVersionCode: Int? = null
    override suspend fun check(): UpdateStatus = state.value
    override suspend fun download(onProgress: (Long, Long?) -> Unit): DownloadedInstaller? = null
    override suspend fun install(installer: DownloadedInstaller): String? = "Not supported"
    override fun openReleaseNotes() {}
    override fun openInstallPermissionSettings() {}
    override fun dismiss() {}
}
