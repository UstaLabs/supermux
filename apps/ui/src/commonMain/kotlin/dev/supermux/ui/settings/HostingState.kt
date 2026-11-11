// This computer's hosting as the machine page shows it (#97 Step 2: While you're away).
//
// Platform-neutral: the page takes a [HostingUiState] and [HostingActions], so `apps/ui` never names a desktop class.
// Desktop builds the state from its `HostSupervisor`; every other host has no local broker (`Caps.localBroker` is
// false) and shows one line instead.
package dev.supermux.ui.settings

import dev.supermux.net.GitRequirement

/**
 * What the machine page's While you're away shows of this computer's hosting. Built by the host platform (desktop: from its supervisor).
 *
 * @property statusDot "🟢" / "🟡" / "🔴" / "⚪".
 * @property statusText e.g. "Running · 3 sessions · v1.5.0".
 * @property readOnly the broker was set up outside the app and the user chose to leave it alone.
 * @property localUrl the address other devices on this network reach it at; null hides the row.
 * @property relayUrl the relay address; null hides the Remote row.
 * @property logTail the last log lines; non-empty only when it can't start.
 * @property failed it can't start: the Restart button becomes "Try again".
 * @property restartEnabled false while it is starting/restarting, or when it is not ours to restart.
 * @property canPair a pairing code can be minted (the broker is running).
 * @property backgroundError why "Keep running in the background" could not be applied, if it couldn't.
 * @property gitRequirement the local broker's `requirements.git`; the "git not installed" notice shows
 *   while it is not ok (git features off; agents still run). Null: the broker has not said (not running, or older).
 * @property power "Keep this computer awake", "Even with the lid closed" and the reboot copy. Only
 *   the host computer builds it (desktop); null hides the section.
 * @property checkoutMoved the running broker's "restart it to run the new code" when it runs from a checkout that moved
 *   under it (a warning: sessions still start); null hides it.
 */
data class HostingUiState(
    val hosting: Boolean,
    val statusDot: String,
    val statusText: String,
    val readOnly: Boolean,
    val localUrl: String?,
    val relayUrl: String?,
    val relay: Boolean,
    val background: Boolean,
    val sessions: Int,
    val logTail: List<String>,
    val failed: Boolean = false,
    val restartEnabled: Boolean = true,
    val canPair: Boolean = true,
    val backgroundError: String? = null,
    val gitRequirement: GitRequirement? = null,
    val power: HostingPowerUi? = null,
    val checkoutMoved: String? = null,
    /** Where hosting is, for the machine page's status line and its While you're away section. */
    val phase: HostingPhase = if (hosting) HostingPhase.Running else HostingPhase.Off,
    /** Why it can't start ([HostingPhase.Failed]), in words. */
    val failReason: String? = null,
) {
    /** The local broker has no git: its git features are off (agents still run). */
    val gitMissing: Boolean get() = gitRequirement?.ok == false
}

/** This computer's hosting, as the machine page tells it (#97 Step 2). */
enum class HostingPhase { Off, Starting, Running, Restarting, Failed }

/**
 * The power rows of While you're away (spec "Keep the computer awake while hosting"):
 * ```
 * ✓ Keep this computer awake
 *     ✓ Also on battery             (laptops only)
 * ☐ Even with the lid closed       (MacBooks only)
 * ```
 * @property keepAwake null: the broker hasn't said (not running, or older) — the row is hidden.
 * @property lidClosed null: not a Mac laptop — the row is hidden.
 * @property autoLoginHint macOS with FileVault off: add the Automatic Login hint to the reboot copy.
 */
data class HostingPowerUi(
    val keepAwake: KeepAwakeUi? = null,
    val lidClosed: LidClosedUi? = null,
    val autoLoginHint: Boolean = false,
)

/**
 * @property switchEnabled false where this computer can't be kept awake (the switch is disabled and
 *   [note] says why).
 * @property showOnBattery the computer has a battery: show "Also on battery" under the switch.
 * @property warning why it isn't held although it is on (and the fix): the warning colour.
 * @property note a neutral line: "Paused while on battery", "Kept awake by the supermux app…", or
 *   the reason it is unsupported.
 */
data class KeepAwakeUi(
    val enabled: Boolean,
    val onBattery: Boolean,
    val switchEnabled: Boolean = true,
    val showOnBattery: Boolean = false,
    val warning: String? = null,
    val note: String? = null,
    val error: String? = null,
)

/**
 * @property on ON only while something actually holds the lid (never on with no helper).
 * @property installed the lid helper is installed for this user: the "Uninstall" link shows.
 * @property busy the install/uninstall password prompt is open.
 * @property enabled false where it can't be used (e.g. this account's home folder); [note] says why.
 * @property note the neutral line under the switch (e.g. "macOS will ask for your password once.").
 * @property error why the last install/uninstall didn't happen.
 */
data class LidClosedUi(
    val on: Boolean,
    val installed: Boolean,
    val busy: Boolean = false,
    val error: String? = null,
    val enabled: Boolean = true,
    val note: String? = null,
)

/** What the machine page can ask the host platform to do. Every call returns at once; work runs elsewhere. */
interface HostingActions {
    fun setHosting(on: Boolean)
    fun setBackground(on: Boolean)
    fun setRelay(on: Boolean)
    fun restart()
    fun retry()
    fun showLog()
    fun pairDevice()
    fun manageIt()
    /**
     * Start installing git on this computer through its broker (`POST /system/install-git`):
     * Apple's installer on macOS, winget on Windows. True when the installer started.
     */
    suspend fun installGit(): Boolean

    /** "Keep this computer awake" (the local broker's setting). */
    fun setKeepAwake(on: Boolean) = Unit

    /** "Also on battery". */
    fun setKeepAwakeOnBattery(on: Boolean) = Unit

    /** "Even with the lid closed": ticking installs the lid helper first when it isn't. */
    fun setLidClosed(on: Boolean) = Unit

    /** "Uninstall lid helper" (an admin prompt). */
    fun uninstallLidHelper() = Unit
}

/** The copy the page shows, as constants so the tests assert the exact strings. */
object HostingCopy {
    const val TITLE = "Host on this computer"
    const val SUBTITLE = "Your agents run here. Your phone and other devices connect to it."
    const val OFF = "This app connects to brokers elsewhere."
    const val RELAY = "Remote access through relay"
    const val BACKGROUND = "Keep running in the background"
    const val BACKGROUND_HELP = "Your agents stay reachable after you quit, sign out or restart."
    const val BACKGROUND_READ_ONLY = "Managed by its own service"
    const val MANAGE = "Let the app manage it"

    const val KEEP_AWAKE = "Keep this computer awake"
    const val KEEP_AWAKE_HELP = "Stops this computer from sleeping while supermux runs. The screen can still turn off."
    const val ON_BATTERY = "Also on battery"
    const val PAUSED_ON_BATTERY = "Paused while on battery"
    const val APP_HELD = "Kept awake by the supermux app while it's open."
    const val WAITING_FOR_LOGIN = "Waiting for you to log in… supermux keeps this computer awake once your desktop session starts."
    const val LID_CLOSED = "Even with the lid closed"
    const val LID_CLOSED_HELP =
        "Your Mac stays awake with the lid closed. It can get hot: don't put it in a bag while this is on."
    const val LID_INSTALL = "macOS will ask for your password once."
    const val LID_NOT_INSTALLED = "The lid helper isn't installed. Tick to install it; macOS will ask for your password once."
    const val LID_OTHER_USER = "The lid helper is installed for another user. Tick to reinstall it for you."
    const val LID_HOME_UNSUPPORTED = "Not available for this account's home folder."
    const val LID_PAUSED_ON_BATTERY = "Paused while on battery"
    const val LID_WAITING = "Waiting for your password…"
    const val LID_UNINSTALL = "Uninstall lid helper"
    const val REBOOT = "After a restart, supermux starts when you log in."
    const val AUTO_LOGIN =
        "To start without anyone at the keyboard, turn on Automatic Login in System Settings ▸ Users & Groups."

    /** The confirm shown before turning hosting off. */
    fun stopConfirm(sessions: Int): String {
        val stops = when {
            sessions <= 0 -> "This stops supermux and removes it from startup."
            sessions == 1 -> "This stops supermux and your 1 running session, and removes it from startup."
            else -> "This stops supermux and your $sessions running sessions, and removes it from startup."
        }
        return "Stop hosting? $stops Your data stays in ~/.mux."
    }
}
