package dev.supermux.desktop.chat

import dev.supermux.ui.chat.ReadAloud
import dev.supermux.ui.platform.TtsEngine

/**
 * The shared timeline's read-aloud control, over desktop's process-wide [MessageTts].
 *
 * `MessageTts` is still per-app until cluster D3 folds it into `:ui`; this adapter is the one place
 * that knows it, and disappears with it.
 */
class DesktopReadAloud(private val tts: TtsEngine) : ReadAloud {
    override fun isSpeaking(text: String): Boolean = MessageTts.isSpeaking(plainTextForSpeech(text))

    override fun toggle(text: String) {
        if (plainTextForSpeech(text).isBlank()) return
        MessageTts.toggle(tts, text)
    }

    override val available: Boolean get() = true
}
