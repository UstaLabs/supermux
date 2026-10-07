package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether a chat composer's text field holds the keyboard focus right now (M5 B2). The desktop's
 * Edit ▸ Paste image accelerator (Ctrl/Cmd+V) is bound only while it does: with the native editor
 * (a Compose field, no longer a JCEF child that swallowed the key natively) a window-wide
 * accelerator would turn every paste into the code editor into an image paste into the chat.
 */
object ChatInputFocus {
    private var owner: Any? = null

    /** Snapshot state: a menu bar reading it follows the focus. */
    var focused: Boolean by mutableStateOf(false)
        private set

    /** Composer [who] gained ([hasFocus]) or lost the focus; another composer's later loss does not clear this one's gain. */
    fun report(who: Any, hasFocus: Boolean) {
        if (hasFocus) { owner = who; focused = true }
        else if (owner === who) { owner = null; focused = false }
    }
}
