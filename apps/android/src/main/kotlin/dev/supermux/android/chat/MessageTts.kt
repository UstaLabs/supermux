package dev.supermux.android.chat

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.ui.platform.TtsEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Process-wide read-aloud STATE: which message is speaking, and whether to use the platform voice
 * or ChatGPT (codex) through the broker's `/speak` stream. [resolveEngine] / [speakRemoteStream]
 * are wired from AppViewModel when a host is connected.
 *
 * The noise itself is [TtsEngine] (`Platform.tts`) — cluster D1 moved `TextToSpeech`, `MediaPlayer`
 * and the cache-file juggling behind that seam, so this object is pure state + sequencing and moves
 * to `:ui` in cluster D3 unchanged.
 */
object MessageTts {
    private val gen = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            if (eng == "codex") speakCodex(tts, plain, rawText) else speakPlatform(tts, plain)
        }
    }

    fun stop(tts: TtsEngine) {
        gen.incrementAndGet()
        tts.stop()
        setSpeakingKeyMainThread(null)
    }

    fun shutdown(tts: TtsEngine) {
        stop(tts)
        tts.shutdown()
    }

    /** Named to avoid a JVM clash with the Compose `speakingKey` property setter. */
    private fun setSpeakingKeyMainThread(key: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) speakingKey = key
        else mainHandler.post { speakingKey = key }
    }

    /** Stream the broker's audio chunks into [tts] in arrival order; a newer [gen] abandons them. */
    private suspend fun speakCodex(tts: TtsEngine, plain: String, rawText: String) {
        val remote = speakRemoteStream
        if (remote == null) {
            speakPlatform(tts, plain)
            return
        }
        val g = gen.incrementAndGet()
        setSpeakingKeyMainThread(plain)
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = scope.launch(Dispatchers.IO) {
            try {
                // trySend is non-suspending; the channel is unlimited so it cannot fail.
                remote(rawText) { bytes -> if (gen.get() == g) queue.trySend(bytes) }
            } catch (_: Exception) {
                // The consumer sees the close and clears the speaking marker.
            } finally {
                queue.close()
            }
        }
        try {
            for (bytes in queue) {
                if (gen.get() != g) break
                tts.playAudioChunk(bytes)
            }
        } finally {
            producer.cancel()
            queue.close()
            if (gen.get() == g) setSpeakingKeyMainThread(null)
        }
    }

    private suspend fun speakPlatform(tts: TtsEngine, plain: String) {
        val g = gen.incrementAndGet()
        setSpeakingKeyMainThread(plain)
        try {
            tts.speak(plain)
        } finally {
            if (gen.get() == g) setSpeakingKeyMainThread(null)
        }
    }
}

/** Flatten markdown-ish agent text for TTS (mirrors web `plainTextForSpeech`). */
fun plainTextForSpeech(md: String): String {
    if (md.isBlank()) return ""
    var s = md
    s = s.replace(Regex("```[\\s\\S]*?```"), " ")
    s = s.replace(Regex("`([^`]+)`"), "$1")
    s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
    s = s.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
    s = s.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
    s = s.replace(Regex("~~(.*?)~~"), "$1")
    s = s.replace(Regex("\\n{2,}"), ". ")
    s = s.replace('\n', ' ')
    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.replace(Regex("(?:\\.\\s*){2,}"), ". ").replace(Regex("\\s+"), " ").trim()
    return s
}
