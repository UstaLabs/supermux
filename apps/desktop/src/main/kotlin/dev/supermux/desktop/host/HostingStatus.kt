package dev.supermux.desktop.host

import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

/** What the supervisor is doing right now (spec §States). */
sealed interface HostingStatus {
    data object NotHosting : HostingStatus
    data object Starting : HostingStatus
    data class Running(val port: Int, val readOnly: Boolean) : HostingStatus
    data class Restarting(val attempt: Int) : HostingStatus
    data class CantStart(val reason: String) : HostingStatus
    data class AskTakeover(val hostId: String?) : HostingStatus
    data class AskDowngrade(val hostId: String) : HostingStatus
}

enum class Dot { GREEN, YELLOW, RED, GREY }

/**
 * "1 session" / "N sessions": the count of sessions on this computer's broker, worded once for the
 * tray header and Settings ▸ Hosting. Both are fed the same number, `FleetFacts.localSessions`
 * (Main.kt collects [hostingFacts] once and hands it to the tray and to `LocalHostingSessions`).
 */
fun sessionCountText(sessions: Int): String = localizedPlural(Plurals.host_app_desktop_sessions, sessions, sessions)

data class TrayModel(
    val dot: Dot,
    val header: String,
    val showLog: Boolean,
    /** null => no Restart item (not hosting). */
    val restartLabel: String?,
    val restartEnabled: Boolean,
    val showKeepRunning: Boolean,
    val keepRunningEnabled: Boolean,
) {
    companion object {
        /** While the app's quit runs (the child's stop grace): just the header, Open and Quit. */
        val QUITTING get() = TrayModel(Dot.YELLOW, localized(Str.host_app_desktop_tray_quitting), false, null, false, false, false)

        /** [remoteReachable]: whether [remoteName]'s host is connected (spec state 9 when not). */
        fun of(
            s: HostingStatus,
            prefs: HostingPrefs,
            sessions: Int,
            remoteName: String?,
            remoteReachable: Boolean = true,
        ): TrayModel {
            val n = sessionCountText(sessions)
            return when (s) {
                HostingStatus.NotHosting -> when {
                    remoteName != null && !remoteReachable ->
                        TrayModel(Dot.YELLOW, localized(Str.host_app_desktop_tray_cant_reach, remoteName), false, null, false, false, false)
                    else -> TrayModel(
                        Dot.GREY, remoteName?.let { localized(Str.host_app_desktop_tray_connected_to, it) } ?: localized(Str.shell_machine_not_hosting), false, null, false, false, false,
                    )
                }
                HostingStatus.Starting -> TrayModel(Dot.YELLOW, localized(Str.host_app_desktop_tray_starting), false, localized(Str.host_app_desktop_restart), false, true, true)
                is HostingStatus.Restarting -> TrayModel(
                    Dot.YELLOW, localized(Str.host_app_desktop_tray_restarting, s.attempt), true, localized(Str.host_app_desktop_restart), false, true, true,
                )
                is HostingStatus.CantStart -> TrayModel(Dot.RED, localized(Str.host_app_desktop_tray_cant_start), true, localized(Str.accounts_try_again), true, true, true)
                is HostingStatus.AskTakeover, is HostingStatus.AskDowngrade ->
                    TrayModel(Dot.YELLOW, localized(Str.host_app_desktop_tray_waiting), false, null, false, false, false)
                is HostingStatus.Running -> when {
                    s.readOnly -> TrayModel(Dot.GREEN, localized(Str.host_app_desktop_tray_running_managed, n), false, localized(Str.host_app_desktop_restart), false, true, false)
                    // Moved off the default port (spec state 5). Not a problem worth a yellow dot, and
                    // the default may well be free again by now: just say which port.
                    s.port != HostingPrefs.DEFAULT_PORT ->
                        TrayModel(Dot.GREEN, localized(Str.host_app_desktop_tray_running_port, n, s.port), false, localized(Str.host_app_desktop_restart), true, true, true)
                    else -> TrayModel(Dot.GREEN, localized(Str.host_app_desktop_tray_running, n), false, localized(Str.host_app_desktop_restart), true, true, true)
                }
            }
        }
    }
}

object QuitText {
    val BACKGROUND get() = localized(Str.host_app_desktop_quit_background)

    /** Background on, and "Keep this computer awake" is on and holding: say that it stays awake. */
    val BACKGROUND_AWAKE get() = localized(Str.host_app_desktop_quit_background_awake)

    /** The confirm text: what stops. */
    fun stops(sessions: Int): String = when {
        sessions <= 0 -> localized(Str.host_app_desktop_quit_stops)
        else -> localizedPlural(Plurals.host_app_desktop_quit_stops_sessions, sessions, sessions)
    }

    /** null => quit without a prompt. */
    fun of(s: HostingStatus, prefs: HostingPrefs, sessions: Int): String? = when {
        s is HostingStatus.Running && s.readOnly -> null
        s == HostingStatus.NotHosting -> null
        prefs.background -> BACKGROUND
        else -> stops(sessions)
    }
}
