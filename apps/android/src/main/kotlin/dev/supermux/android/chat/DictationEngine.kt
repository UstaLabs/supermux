package dev.supermux.android.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Outcome of trying to start on-device recognition. UNAVAILABLE → caller records audio instead. */
enum class DictationStart { STARTED, DENIED, UNAVAILABLE }

/**
 * Thin wrapper over [android.speech.SpeechRecognizer]'s on-device recognizer, exposing a small
 * state surface for Compose. This is the *optional* live-transcript path, gated behind
 * [dev.supermux.android.DevConfig.ENABLE_ONDEVICE_STT] (off by default). When on, it only ever
 * yields a fallback raw draft — production dictation uses the broker STT audio POST instead
 * (see [DictationController]).
 *
 * ## Keeping the mic open across pauses (the load-bearing behavior)
 * [SpeechRecognizer] is single-utterance: it treats the first stretch of silence as *end of
 * speech*, fires `onResults` (or `onError(ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT)` if it heard
 * nothing) and stops. Taken literally that freezes the live transcript the instant the speaker
 * pauses to think, and everything said after the pause is lost — the exact bug users hit ("it
 * stops when I pause").
 *
 * So this engine runs a **restart loop**: each recognizer session covers one utterance; when a
 * session ends on a pause we append its final text to an accumulated draft and immediately start a
 * new session on the same recognizer, so the mic stays open. Recognition only ends when the user
 * taps stop ([stop]/[cancel]) or a non-silence error occurs. [transcript]/[onPartial] always carry
 * the *full running transcript* (committed segments + the current session's live partial).
 *
 * [SpeechRecognizer] callbacks fire on the main thread; the restart is posted to the main looper so
 * it never runs re-entrantly from inside a callback (which some devices reject with
 * ERROR_RECOGNIZER_BUSY).
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

    /** Finalized text from segments already ended by a pause; the live partial is layered on top. */
    private val committed = StringBuilder()

    /** Glossary biasing captured at [start], reused verbatim on every restart. */
    private var contextualStrings: List<String> = emptyList()

    /** Restarts are posted here so they never fire re-entrantly from a recognizer callback. */
    private val mainHandler = Handler(Looper.getMainLooper())

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

        this.contextualStrings = contextualStrings
        committed.setLength(0)

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (listening && isRecoverableSttError(error)) {
                    // Silence / transient hiccup — keep the mic open across the pause.
                    scheduleRestart()
                } else {
                    // Real failure: finalize quietly; stop()/the composer keep what we have.
                    listening = false
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { update(joinSttSegments(committed.toString(), it)) }
            }

            override fun onResults(results: Bundle?) {
                // A pause ended this segment. Commit its final text and keep listening — do NOT stop.
                firstResult(results)?.let { seg ->
                    val joined = joinSttSegments(committed.toString(), seg)
                    committed.setLength(0)
                    committed.append(joined)
                }
                update(committed.toString())
                scheduleRestart()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        return try {
            rec.startListening(buildIntent())
            recognizer = rec
            listening = true
            transcript = ""
            DictationStart.STARTED
        } catch (_: Throwable) {
            runCatching { rec.destroy() }
            DictationStart.UNAVAILABLE
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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
            // Silence-tolerance hints: stretch how long a within-utterance pause can run before the
            // engine calls the segment complete. Only *hints* (the on-device engine often
            // ignores/caps them), so they merely reduce how often the restart loop kicks in — the
            // loop is what actually keeps the mic open across pauses.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            // Device locale; nothing hardcoded.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }

    /** Post a fresh recognizer session to the next main-loop tick (never re-entrant). */
    private fun scheduleRestart() {
        if (!listening) return
        mainHandler.post {
            if (!listening) return@post
            val rec = recognizer ?: return@post
            try {
                rec.startListening(buildIntent())
            } catch (_: Throwable) {
                // Couldn't restart — finalize with whatever we've committed.
                listening = false
            }
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
        listening = false
        mainHandler.removeCallbacksAndMessages(null)  // drop any pending restart
        runCatching { recognizer?.stopListening() }
        return transcript.trim()
    }

    fun cancel() {
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        teardown()
    }

    private fun teardown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }
}

/**
 * Stitches a newly-finalized [segment] onto the already-[committed] transcript with a single
 * separating space, tolerating empty/whitespace-only inputs on either side (no leading/trailing/
 * double spaces). Used both to fold a completed segment into the running draft and to render the
 * live "committed + current partial" view.
 */
fun joinSttSegments(committed: String, segment: String): String {
    val left = committed.trim()
    val right = segment.trim()
    return when {
        left.isEmpty() -> right
        right.isEmpty() -> left
        else -> "$left $right"
    }
}

/**
 * Whether a [SpeechRecognizer] error code means "keep waiting" (a pause with no speech, or a
 * transient busy state) rather than a real failure. Recoverable errors restart the recognizer so
 * the mic stays open across pauses; everything else (audio, permissions, network, server, client
 * teardown) ends the session.
 */
fun isRecoverableSttError(error: Int): Boolean = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
    else -> false
}
