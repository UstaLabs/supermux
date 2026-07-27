package dev.supermux.desktop.chat

import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.sanitizeSetLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

/**
 * Process-wide chat detail preference (web `cmux:chat-detail` parity).
 * java.util.prefs + [StateFlow] so OverflowMenu and ChatPanel stay in sync.
 */
object ChatDetailPrefs {
    private val prefs: Preferences = Preferences.userRoot().node("dev/supermux/desktop/chat_detail")
    private val _level = MutableStateFlow(ChatDetailLevel.parse(prefs.get("level", null)))
    val level: StateFlow<ChatDetailLevel> = _level.asStateFlow()

    fun set(level: ChatDetailLevel) {
        val next = sanitizeSetLevel(level) ?: return
        _level.value = next
        prefs.put("level", next.wire)
        runCatching { prefs.flush() }
    }
}
