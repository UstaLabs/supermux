package dev.supermux.android.chat

import dev.supermux.ui.chat.ReadAloud
import dev.supermux.ui.platform.Platform

/**
 * The shared timeline's read-aloud control, over Android's process-wide [MessageTts].
 *
 * `MessageTts` is still per-app until cluster D3 folds it into `:ui`; this adapter is the one place
 * that knows it, and disappears with it. The "Nothing to read" toast is Android's own behaviour,
 * preserved here rather than pushed into the shared row.
 */
class AndroidReadAloud(private val platform: Platform) : ReadAloud {
    override fun isSpeaking(text: String): Boolean = MessageTts.isSpeaking(plainTextForSpeech(text))

    override fun toggle(text: String) {
        if (plainTextForSpeech(text).isBlank()) {
            platform.notices.show("Nothing to read")
            return
        }
        MessageTts.toggle(platform.tts, text)
    }

    override val available: Boolean get() = true
}
