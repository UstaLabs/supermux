package dev.supermux.web.seams

import dev.supermux.ui.platform.TtsEngine

@Suppress("UNUSED_PARAMETER")
private fun speakJs(text: String): Unit =
    js("{ if (window.speechSynthesis) { window.speechSynthesis.cancel(); window.speechSynthesis.speak(new SpeechSynthesisUtterance(text)); } }")

private fun cancelJs(): Unit = js("{ if (window.speechSynthesis) window.speechSynthesis.cancel(); }")

/**
 * Read-aloud through the browser's own synthesiser. Broker-streamed audio chunks
 * ([playAudioChunk]) need an AudioContext decoder and arrive in plan 3; until then they are
 * dropped silently rather than thrown — this object is read during composition.
 */
object WebTts : TtsEngine {
    override suspend fun speak(text: String) = speakJs(text)
    override fun stop() = cancelJs()
    override suspend fun playAudioChunk(bytes: ByteArray) = Unit
    override fun shutdown() = cancelJs()
}
