// M5-3: the pure "should this agent reply raise a desktop toast" decision. Mirrors the broker's
// src/core/push/hook.ts:firePushForReply guard (op==="reply" && !muted && !anyPresent), adapted to
// desktop's LOCAL viewing signal — WorkspaceRoot's (ui.selectedId, LocalWindowInfo focus) IS
// presence here; there's no cross-device tracker to query since this client IS the live
// connection. See this milestone's plan Goal for why closed-app push parity is explicitly out of
// scope — this file only ever runs while the desktop process is alive.
package dev.supermux.desktop.notify

import dev.supermux.proto.LogEntry

object NotifyDecision {
    /**
     * [entry] is a raw `message_append` payload for [session]. Only an AGENT REPLY
     * (`direction == "outbound" && op == "reply"`) can ever notify — the user's own echoed
     * `"inbound"` message and non-reply outbound entries (`react`/`edit_message`) always return
     * false, regardless of viewed/muted state. A reply notifies unless [muted] is true, or the
     * session is BOTH the [selectedId] AND the window is [windowFocused] (i.e. actively viewed).
     */
    fun shouldNotify(
        entry: LogEntry,
        session: String,
        selectedId: String?,
        windowFocused: Boolean,
        muted: Boolean,
    ): Boolean {
        if (entry.direction != "outbound" || entry.op != "reply") return false
        if (muted) return false
        if (session == selectedId && windowFocused) return false
        return true
    }

    /**
     * The toast body — mirrors the broker's `push/hook.ts:extractPreview` byte-for-byte rule (same
     * 117-char-plus-ellipsis truncation, same attachment-kind fallback) so a desktop toast reads
     * identically to a web/mobile push notification for the SAME reply.
     */
    fun previewText(entry: LogEntry): String {
        val text = entry.text
        if (!text.isNullOrEmpty()) {
            return if (text.length > 120) text.take(117) + "…" else text
        }
        val first = entry.attachments?.firstOrNull()
        return when (first?.kind) {
            "photo" -> "📷 Photo"
            "voice" -> "🎙 Voice message"
            "audio" -> "🎵 Audio"
            "video", "video_note" -> "🎥 Video"
            "document" -> "📎 ${first.name ?: "File"}"
            else -> "New message"
        }
    }
}

/**
 * Coalesces a burst of rapid replies to the SAME session into a single toast: once a notification
 * fires for a session, further replies to that session within [windowMs] are suppressed — a
 * multi-part agent reply landing as several `message_append` frames in quick succession shouldn't
 * spam several separate OS balloons. [nowMs] is caller-supplied so tests don't depend on wall time.
 */
class NotificationDedup(private val windowMs: Long = 4_000) {
    private val lastFiredAt = mutableMapOf<String, Long>()

    fun shouldFire(session: String, nowMs: Long): Boolean {
        val last = lastFiredAt[session]
        if (last != null && nowMs - last < windowMs) return false
        lastFiredAt[session] = nowMs
        return true
    }

    /** Reset a session's cooldown — called when the user opens/focuses that session (see
     *  [NotificationController.onSessionFocused]), so the next reply after they leave again
     *  notifies immediately rather than waiting out a stale window from before they looked. */
    fun clear(session: String) {
        lastFiredAt -= session
    }
}

/** The raw payload [dev.supermux.desktop.state.DesktopAppState.agentReplies] emits — an agent-
 *  reply `message_append` (`direction=="outbound" && op=="reply"`) for [session]. Defined here
 *  (not in the `state` package) because it's part of this module's public decision vocabulary. */
data class AgentReplyEvent(val session: String, val entry: LogEntry)
