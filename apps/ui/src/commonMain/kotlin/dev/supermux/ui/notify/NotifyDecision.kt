// M5-3: the pure "should this agent reply raise a desktop toast" decision. Mirrors the broker's
// src/core/push/hook.ts:firePushForReply guard (op==="reply" && !muted && !anyPresent), adapted to
// desktop's LOCAL viewing signal — SupermuxApp's (ui.selectedId, window focus) IS
// presence here; there's no cross-device tracker to query since this client IS the live
// connection. See this milestone's plan Goal for why closed-app push parity is explicitly out of
// scope — this file only ever runs while the desktop process is alive.
package dev.supermux.ui.notify

import androidx.compose.runtime.Composable
import dev.supermux.proto.LogEntry
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

/**
 * The words a toast body falls back to when a reply has no text (#123). English by default, the broker's own push
 * wording; the shell passes [localizedNotifyWords] so a toast reads in the app's language.
 */
data class NotifyWords(
    val photo: String = "📷 Photo", // i18n-ok: English default, broker parity; UI passes localizedNotifyWords()
    val voice: String = "🎙 Voice message", // i18n-ok: English default, broker parity
    val audio: String = "🎵 Audio", // i18n-ok: English default, broker parity
    val video: String = "🎥 Video", // i18n-ok: English default, broker parity
    val file: String = "File", // i18n-ok: English default, broker parity
    val newMessage: String = "New message", // i18n-ok: English default, broker parity
)

/** [NotifyWords] in the app's language. */
@Composable
fun localizedNotifyWords(): NotifyWords = NotifyWords(
    photo = localized(Str.notify_photo),
    voice = localized(Str.notify_voice_message),
    audio = localized(Str.notify_audio),
    video = localized(Str.notify_video),
    file = localized(Str.notify_file),
    newMessage = localized(Str.notify_new_message),
)

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
    fun previewText(entry: LogEntry, words: NotifyWords = NotifyWords()): String {
        val text = entry.text
        if (!text.isNullOrEmpty()) {
            return if (text.length > 120) text.take(117) + "…" else text
        }
        val first = entry.attachments?.firstOrNull()
        return when (first?.kind) {
            "photo" -> words.photo
            "voice" -> words.voice
            "audio" -> words.audio
            "video", "video_note" -> words.video
            "document" -> "📎 ${first.name ?: words.file}"
            else -> words.newMessage
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
