package dev.supermux.desktop.host

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
fun sessionCountText(sessions: Int): String = if (sessions == 1) "1 session" else "$sessions sessions"

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
        val QUITTING = TrayModel(Dot.YELLOW, "Quitting…", false, null, false, false, false)

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
                        TrayModel(Dot.YELLOW, "Can't reach $remoteName · retrying", false, null, false, false, false)
                    else -> TrayModel(
                        Dot.GREY, remoteName?.let { "Connected to $it" } ?: "Not hosting", false, null, false, false, false,
                    )
                }
                HostingStatus.Starting -> TrayModel(Dot.YELLOW, "Starting supermux…", false, "Restart", false, true, true)
                is HostingStatus.Restarting -> TrayModel(
                    Dot.YELLOW, "supermux stopped unexpectedly · restarting (attempt ${s.attempt})", true, "Restart", false, true, true,
                )
                is HostingStatus.CantStart -> TrayModel(Dot.RED, "supermux can't start", true, "Try again", true, true, true)
                is HostingStatus.AskTakeover, is HostingStatus.AskDowngrade ->
                    TrayModel(Dot.YELLOW, "supermux is waiting for you", false, null, false, false, false)
                is HostingStatus.Running -> when {
                    s.readOnly -> TrayModel(Dot.GREEN, "supermux is running · $n · managed outside the app", false, "Restart", false, true, false)
                    // Moved off the default port (spec state 5). Not a problem worth a yellow dot, and
                    // the default may well be free again by now: just say which port.
                    s.port != HostingPrefs.DEFAULT_PORT ->
                        TrayModel(Dot.GREEN, "supermux is running · $n · port ${s.port}", false, "Restart", true, true, true)
                    else -> TrayModel(Dot.GREEN, "supermux is running · $n", false, "Restart", true, true, true)
                }
            }
        }
    }
}

object QuitText {
    const val BACKGROUND = "supermux will keep running in the background."

    /** The confirm text: what stops. */
    fun stops(sessions: Int): String = when {
        sessions <= 0 -> "This stops supermux."
        sessions == 1 -> "This stops supermux and your 1 running session."
        else -> "This stops supermux and your $sessions running sessions."
    }

    /** null => quit without a prompt. */
    fun of(s: HostingStatus, prefs: HostingPrefs, sessions: Int): String? = when {
        s is HostingStatus.Running && s.readOnly -> null
        s == HostingStatus.NotHosting -> null
        prefs.background -> BACKGROUND
        else -> stops(sessions)
    }
}
