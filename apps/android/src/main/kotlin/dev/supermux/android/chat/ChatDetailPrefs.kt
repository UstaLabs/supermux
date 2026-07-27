package dev.supermux.android.chat

import android.content.Context
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.sanitizeSetLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide chat detail preference (web `cmux:chat-detail` parity).
 * SharedPreferences + [StateFlow] so ChatScreen ⋮ and ChatPanel recompose together.
 */
object ChatDetailPrefs {
    private const val PREFS = "cmux-chat-detail"
    private const val KEY = "level"

    private val _level = MutableStateFlow(ChatDetailLevel.MEDIUM)
    val level: StateFlow<ChatDetailLevel> = _level.asStateFlow()
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            _level.value = ChatDetailLevel.parse(raw)
            loaded = true
        }
    }

    fun set(context: Context, level: ChatDetailLevel) {
        val next = sanitizeSetLevel(level) ?: return
        _level.value = next
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.wire)
            .apply()
    }
}
