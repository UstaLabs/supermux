package dev.supermux.android.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide native [TextToSpeech] for agent-message "Read aloud".
 * One engine so only one message speaks at a time.
 * [speakingKey] is Compose-observable (plain text currently being spoken, or null).
 */
object MessageTts {
    private val engine = AtomicReference<TextToSpeech?>(null)
    private val ready = AtomicInteger(0) // 0=idle, 1=initing, 2=ready, -1=failed
    private val gen = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Plain text key of the utterance in flight, or null when idle. */
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
        speak(context, plain)
    }

    fun stop() {
        gen.incrementAndGet()
        engine.get()?.stop()
        setSpeakingKeyMainThread(null)
    }

    fun shutdown() {
        stop()
        engine.getAndSet(null)?.shutdown()
        ready.set(0)
    }

    private fun setSpeakingKeyMainThread(key: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            speakingKey = key
        } else {
            mainHandler.post { speakingKey = key }
        }
    }

    private fun speak(context: Context, plain: String) {
        ensureEngine(context.applicationContext) { tts ->
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
    // Clean up artifacts from stripped fences ("Hello. . world")
    s = s.replace(Regex("(?:\\.\\s*){2,}"), ". ").replace(Regex("\\s+"), " ").trim()
    return s
}
