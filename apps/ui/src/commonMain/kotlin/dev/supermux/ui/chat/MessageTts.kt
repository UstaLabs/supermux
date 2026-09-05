package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.chat.plainTextForSpeech
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.platform.TtsEngine
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Process-wide read-aloud STATE: which message is speaking, and whether to use the platform voice
 * or ChatGPT (codex) through the broker's `/speak` stream. [resolveEngine] / [speakRemoteStream]
 * are wired from each host's connection layer once a host is connected.
 *
 * The noise itself is [TtsEngine] (`Platform.tts`) — D1 moved `TextToSpeech`/`MediaPlayer` and
 * desktop's `ProcessBuilder` player behind that seam; this is pure state + sequencing, and D3
 * merged the two apps' byte-identical copies into this one.
 *
 * Deliberately an `object`: read-aloud must survive an Android activity recreation (rotate while a
 * message is being read and the SAME state has to be able to stop it), so this is process-scoped,
 * not remembered per composition. It is only ever touched from a Compose frame or from a coroutine
 * on [scope], so `speakingKey` needs no extra thread marshalling.
 */
object MessageTts {
    private val gen = atomic(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Returns "platform" | "codex" (default platform). */
    var resolveEngine: (suspend () -> String)? = null

    /**
     * POST /speak NDJSON stream. Invokes `onChunk` for each audio piece as it arrives.
     * Required for the codex engine.
     */
    var speakRemoteStream: (suspend (text: String, onChunk: (ByteArray) -> Unit) -> Unit)? = null

    var speakingKey by mutableStateOf<String?>(null)
        private set

    fun isSpeaking(textKey: String): Boolean = speakingKey == textKey

    fun toggle(tts: TtsEngine, rawText: String) {
        val plain = plainTextForSpeech(rawText)
        if (plain.isBlank()) return
        if (speakingKey == plain) {
            stop(tts)
            return
        }
        scope.launch {
            val eng = runCatching { resolveEngine?.invoke() }.getOrNull()?.ifBlank { null } ?: "platform"
            if (eng == "codex" && speakRemoteStream != null) speakCodex(tts, plain, rawText)
            else speakPlatform(tts, plain)
        }
    }

    fun stop(tts: TtsEngine) {
        gen.incrementAndGet()
        tts.stop()
        speakingKey = null
    }

    /** Release the underlying engine — the app is going away, not just this message. */
    fun shutdown(tts: TtsEngine) {
        stop(tts)
        tts.shutdown()
    }

    /** Stream the broker's audio chunks into [tts] in arrival order; a newer [gen] abandons them. */
    private suspend fun speakCodex(tts: TtsEngine, plain: String, rawText: String) {
        val remote = speakRemoteStream ?: return speakPlatform(tts, plain)
        // Silence whatever is speaking BEFORE starting the next one. Android's TextToSpeech
        // QUEUE_FLUSH makes this look redundant; desktop's engine is a child `say`/`ffplay`
        // process that keeps running until it is killed, so without this two messages overlap.
        stop(tts)
        val g = gen.incrementAndGet()
        speakingKey = plain
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = scope.launch(Dispatchers.Default) {
            try {
                // trySend is non-suspending; the channel is unlimited so it cannot fail.
                remote(rawText) { bytes -> if (gen.value == g) queue.trySend(bytes) }
            } catch (_: Exception) {
                // The consumer sees the close and clears the speaking marker.
            } finally {
                queue.close()
            }
        }
        try {
            for (bytes in queue) {
                if (gen.value != g) break
                tts.playAudioChunk(bytes)
            }
        } finally {
            producer.cancel()
            queue.close()
            if (gen.value == g) speakingKey = null
        }
    }

    private suspend fun speakPlatform(tts: TtsEngine, plain: String) {
        // See speakCodex: stop the previous utterance first, or desktop overlaps two processes.
        stop(tts)
        val g = gen.incrementAndGet()
        speakingKey = plain
        try {
            tts.speak(plain)
        } finally {
            if (gen.value == g) speakingKey = null
        }
    }
}

/**
 * The timeline's read-aloud control, over the process-wide [MessageTts] and `Platform.tts`.
 *
 * D2's per-app read-aloud adapters are gone with `MessageTts`'s move: the meta row builds
 * this straight from `LocalPlatform`. The "Nothing to read" notice (Android's toast, desktop's
 * snackbar) is what both hosts already did for a message with no speakable text.
 */
class PlatformReadAloud(private val platform: Platform) : ReadAloud {
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
