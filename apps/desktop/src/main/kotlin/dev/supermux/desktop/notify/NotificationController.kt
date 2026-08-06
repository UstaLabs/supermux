// M5-3: bridges the pure decision layer (NotifyDecision/NotificationDedup) to a live
// NotificationManager. Owns the dedup state and the "last notified session" the tray icon's click
// handler reads (Main.kt, Task 2) — see this milestone's plan, scoping decision 3, for why a
// per-toast click target isn't available (Compose's Notification carries no callback/id; only the
// tray ICON itself does).
package dev.supermux.desktop.notify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.proto.LogEntry

/**
 * Plain (non-`@Composable`) class exactly like [dev.supermux.desktop.shell.ShellUiState] —
 * `mutableStateOf` works fine outside composition; it only needs a Composer to trigger
 * RECOMPOSITION on change, not to be read/written. [lastNotifiedSession] is `Stable`-shaped for the
 * same reason `ShellUiState.selectedId` is.
 */
class NotificationController(
    private val manager: NotificationManager,
    private val dedup: NotificationDedup = NotificationDedup(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The session id whose reply most recently fired a toast — read by the tray icon's `onAction`
     *  (Main.kt) to pick something reasonable to focus on a bare icon click. */
    var lastNotifiedSession: String? by mutableStateOf(null)
        private set

    /**
     * Evaluate one agent-reply event and fire a notification if [NotifyDecision.shouldNotify] and
     * [NotificationDedup] both allow it. Resolving [sessionName]/[selectedId]/[windowFocused]/
     * [muted] is the CALLER's job (`AppShell` already has `app.sessions`/`ui.selectedId`/
     * `focused` in scope) — this function stays a thin, fully-parameterized decision + dispatch so
     * it's testable with a fake [NotificationManager] and no Compose/coroutine context of its own.
     */
    fun onAgentReply(
        entry: LogEntry,
        session: String,
        sessionName: String,
        selectedId: String?,
        windowFocused: Boolean,
        muted: Boolean,
    ) {
        if (!NotifyDecision.shouldNotify(entry, session, selectedId, windowFocused, muted)) return
        if (!dedup.shouldFire(session, clock())) return
        val body = NotifyDecision.previewText(entry)
        // Unconditional (not gated by any env var) — this is the load-bearing, always-on proof
        // point for headless live-verification (Task 4): the OS toast can't be screenshotted
        // reliably under Xvfb, but the DECISION + DISPATCH always logs here.
        println("[notify] session=$session name=$sessionName text=$body")
        manager.notify(session, sessionName, body)
        lastNotifiedSession = session
    }

    /** Reset [session]'s dedup cooldown — called when the user opens/focuses it, so the next reply
     *  after they leave again notifies immediately rather than waiting out a stale window. */
    fun onSessionFocused(session: String) {
        dedup.clear(session)
    }
}
