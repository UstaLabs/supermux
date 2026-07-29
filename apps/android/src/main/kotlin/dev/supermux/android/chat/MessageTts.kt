package dev.supermux.android.chat

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Process-wide read-aloud: platform [TextToSpeech] or ChatGPT (codex) via broker /speak stream.
 * [resolveEngine] / [speakRemoteStream] are wired from AppViewModel when a host is connected.
 */
object MessageTts {
    private val engine = AtomicReference<TextToSpeech?>(null)
    private val ready = AtomicInteger(0) // 0=idle, 1=initing, 2=ready, -1=failed
    private val gen = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null

    /** Returns "platform" | "codex" (default platform). */
    var resolveEngine: (suspend () -> String)? = null

    /**
     * POST /speak NDJSON stream. Invokes [onChunk] for each audio piece as it arrives.
     * Required for codex engine.
     */
    var speakRemoteStream: (suspend (text: String, onChunk: (ByteArray) -> Unit) -> Unit)? = null

    var speakingKey by mutableStateOf<String?>(null)
        private set

    fun isSpeaking(textKey: String): Boolean = speakingKey == textKey

    fun toggle(context: Context, rawText: String) {
        val plain = plainTextForSpeech(rawText)
        if (plain.isBlank()) return
        if (speakingKey == plain) {
            stop()
            return
        }
        scope.launch {
            val eng = runCatching { resolveEngine?.invoke() }.getOrNull()?.ifBlank { null } ?: "platform"
            if (eng == "codex") {
                speakCodex(context.applicationContext, plain, rawText)
            } else {
                speakPlatform(context.applicationContext, plain)
            }
        }
    }

    fun stop() {
        gen.incrementAndGet()
        engine.get()?.stop()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { /* ignore */ }
        mediaPlayer = null
        setSpeakingKeyMainThread(null)
    }

    fun shutdown() {
        stop()
        engine.getAndSet(null)?.shutdown()
        ready.set(0)
    }

    /** Named to avoid JVM clash with the Compose `speakingKey` property setter. */
    private fun setSpeakingKeyMainThread(key: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            speakingKey = key
        } else {
            mainHandler.post { speakingKey = key }
        }
    }

    private suspend fun speakCodex(appCtx: Context, plain: String, rawText: String) {
        val remote = speakRemoteStream
        if (remote == null) {
            speakPlatform(appCtx, plain)
            return
        }
        val g = gen.incrementAndGet()
        setSpeakingKeyMainThread(plain)
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = scope.launch(Dispatchers.IO) {
            try {
                remote(rawText) { bytes ->
                    if (gen.get() == g) {
                        // trySend is non-suspending; channel is unlimited so it shouldn't fail.
                        queue.trySend(bytes)
                    }
                }
            } catch (_: Exception) {
                // consumer will see close and clear speaking
            } finally {
                queue.close()
            }
        }
        try {
            for (bytes in queue) {
                if (gen.get() != g) break
                withContext(Dispatchers.Main) {
                    if (gen.get() == g) playMp3AndWait(appCtx, bytes, g)
                }
            }
            if (gen.get() == g) setSpeakingKeyMainThread(null)
        } finally {
            producer.cancel()
            queue.close()
        }
    }

    private suspend fun playMp3AndWait(appCtx: Context, bytes: ByteArray, g: Int) {
        suspendCancellableCoroutine { cont ->
            try {
                mediaPlayer?.release()
                val file = File(appCtx.cacheDir, "read-aloud-$g-${System.nanoTime()}.mp3")
                file.writeBytes(bytes)
                val mp = MediaPlayer()
                mediaPlayer = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    try { file.delete() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, _, _ ->
                    try { file.delete() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                cont.invokeOnCancellation {
                    try {
                        mp.stop()
                        mp.release()
                    } catch (_: Exception) {}
                    try { file.delete() } catch (_: Exception) {}
                }
                mp.prepare()
                mp.start()
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun speakPlatform(appCtx: Context, plain: String) {
        ensureEngine(appCtx) { tts ->
            if (tts == null) return@ensureEngine
            val g = gen.incrementAndGet()
            setSpeakingKeyMainThread(plain)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (gen.get() == g) setSpeakingKeyMainThread(null)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (gen.get() == g) setSpeakingKeyMainThread(null)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (gen.get() == g) setSpeakingKeyMainThread(null)
                }
            })
            @Suppress("DEPRECATION")
            tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "msg-$g")
        }
    }

    private fun ensureEngine(appCtx: Context, then: (TextToSpeech?) -> Unit) {
        val existing = engine.get()
        if (existing != null && ready.get() == 2) {
            then(existing)
            return
        }
        if (ready.get() == -1) {
            then(null)
            return
        }
        ready.set(1)
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(appCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                engine.set(tts)
                ready.set(2)
                then(tts)
            } else {
                ready.set(-1)
                then(null)
            }
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
