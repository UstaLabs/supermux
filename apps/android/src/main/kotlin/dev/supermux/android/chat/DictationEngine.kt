package dev.supermux.android.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Outcome of trying to start on-device recognition. UNAVAILABLE → caller records audio instead. */
enum class DictationStart { STARTED, DENIED, UNAVAILABLE }

/**
 * Thin wrapper over [android.speech.SpeechRecognizer]'s on-device recognizer, exposing a small
 * state surface for Compose. This is the *nice-to-have* live-transcript path, gated behind
 * [dev.supermux.android.DevConfig.ENABLE_ONDEVICE_STT] (off by default). It only ever yields a
 * fallback raw draft — the real transcription is always the whisper audio POST (see ChatScreen).
 *
 * [SpeechRecognizer] callbacks fire on the main thread, so [transcript]/[onPartial] updates are
 * marshaled straight to state with no extra dispatch.
 */
class DictationEngine(private val context: Context) {
    /** Live transcript updates (delivered on the main thread). */
    var onPartial: ((String) -> Unit)? = null

    @Volatile
    var transcript: String = ""
        private set

    @Volatile
    var listening: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null

    /** Start continuous on-device recognition biased by [contextualStrings] (glossary).
     *  Returns UNAVAILABLE when on-device recognition isn't usable so the caller falls back. */
    fun start(contextualStrings: List<String> = emptyList()): DictationStart {
        // Gate: API 33+ has reliable on-device + EXTRA_ENABLE_BIASING_DEVICE_CONTEXT/strings.
        if (Build.VERSION.SDK_INT < 33) return DictationStart.UNAVAILABLE
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return DictationStart.UNAVAILABLE

        val rec = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Throwable) {
            return DictationStart.UNAVAILABLE
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Glossary biasing (API 33+, best-effort; ignored where unsupported). The constant
            // EXTRA_BIASING_STRINGS is API 33+; the documented literal avoids a hard compile dep.
            if (contextualStrings.isNotEmpty()) {
                putStringArrayListExtra(
                    "android.speech.extra.BIASING_STRINGS",
                    ArrayList(contextualStrings),
                )
            }
            // Device locale; nothing hardcoded.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Finalize quietly; stop()/the composer keep whatever transcript we have.
                listening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { update(it) }
            }

            override fun onResults(results: Bundle?) {
                firstResult(results)?.let { update(it) }
                listening = false
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        return try {
            rec.startListening(intent)
            recognizer = rec
            listening = true
            transcript = ""
            DictationStart.STARTED
        } catch (_: Throwable) {
            runCatching { rec.destroy() }
            DictationStart.UNAVAILABLE
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun update(text: String) {
        transcript = text
        onPartial?.invoke(text)
    }

    /** Stop and return the accumulated transcript (best-effort). */
    fun stop(): String {
        runCatching { recognizer?.stopListening() }
        listening = false
        return transcript.trim()
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        teardown()
    }

    private fun teardown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }
}
