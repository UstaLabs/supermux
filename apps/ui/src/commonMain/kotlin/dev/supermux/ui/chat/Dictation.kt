// Voice dictation for every composer on every host (cluster D3): mic → audio → the broker's
// /transcribe STT engine → the cleaned text appended to the draft.
//
// One state machine, two paths, decided by [DictationMode] (a per-device setting, see
// [resolveDictationMode]) and by what the platform can do:
//   - DEVICE, on a host with `Platform.mic.liveTranscript` (iOS, Android): recognise on the device,
//     show the growing partial in the RecordingBar, and POST only the finished draft for a cleanup
//     pass (`transcribeDraft`).
//   - BROKER (every host; the only mode on web and desktop): record, then POST the encoded audio
//     (`transcribeAudio`). While recording, each pause sends everything said so far as a live
//     partial pass (`transcribePartial`), and the answer fills the RecordingBar's transcript, so
//     the user can see the work happening long before Stop.
//
// The controller's lifecycle is desktop's: it is `remember(resetKey)`-scoped and cancels an
// IN-FLIGHT transcription on dispose, so a ~20-30s POST launched under session A can never resolve
// into session B's draft. The states and the UI are Android's: a RecordingBar takeover of the
// composer row, a "Transcribing…" strip, and the microphone-permission dialog.
package dev.supermux.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Where a device recognises dictation. Persisted per device as [wire] (`SettingsKeys.DICTATION_MODE`). */
enum class DictationMode(val wire: String) {
    /** Upload audio to the broker's STT engine, with live partial passes at each pause. */
    Broker("broker"),
    /** The OS recogniser (Apple Speech, Android SpeechRecognizer); the broker only cleans up. */
    Device("device");

    companion object {
        fun parse(wire: String?): DictationMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The mode a dictation actually runs in. A host without an on-device recogniser can only use the
 * broker. Otherwise the user's explicit choice wins; with none, the broker is the default when it
 * has a ChatGPT (Codex) login (gpt-transcribe beats the OS recognisers), and the device when it
 * does not. An unknown login state ([codexAvailable] null — the config fetch failed or has not
 * answered) counts as the broker, which still has its fallback engines.
 */
fun resolveDictationMode(
    explicit: DictationMode?,
    codexAvailable: Boolean?,
    hasDeviceRecognizer: Boolean,
): DictationMode = when {
    !hasDeviceRecognizer -> DictationMode.Broker
    explicit != null -> explicit
    codexAvailable == false -> DictationMode.Device
    else -> DictationMode.Broker
}

/** What the broker reports about its STT (`/settings/config`'s probed `voice*` fields). */
data class VoiceCaps(val codexAvailable: Boolean, val livePartials: Boolean)

/**
 * A live pass's answer. [skipped] = the broker declined (its engine is too slow for live passes —
 * stop sending them for this recording).
 *
 * [fromMs] says what [text] covers: > 0 = only the audio after that cut (the pass asked for one
 * and the engine honoured it), 0 = the whole recording. [durationMs] is the whole recording's
 * length as the broker measured it; null when it did not.
 */
data class LivePass(
    val text: String,
    val skipped: Boolean = false,
    /** [text] is the complete dictated text — it replaces everything dictated so far. */
    val full: Boolean = false,
    /** The words this pass added at the end — checked for a spoken edit. */
    val added: String? = null,
    val fromMs: Long = 0,
    val durationMs: Long? = null,
)

/** One live pass request: the audio so far, and where to cut it — everything before [fromMs] is
 *  already in [previousText] (the dictation so far, hand edits included), which the broker
 *  merges the new audio into. */
data class LivePassRequest(
    val bytes: ByteArray,
    val filename: String,
    val mime: String,
    val fromMs: Long = 0,
    val previousText: String = "",
    /** The recording this pass belongs to: the broker keeps one STT connection open for all of
     *  its passes instead of setting one up per pass (~1 s each). */
    val dictationId: String? = null,
    /** Stop's pass, the recording's last: the broker closes that connection after it. */
    val final: Boolean = false,
)

/** Timing of the live partial passes; a parameter only so tests can name the numbers. */
data class LivePassTiming(
    /** How often the mic level is sampled. */
    val sampleMs: Long = 50,
    /** Quiet this long after speech = a pause → send a pass. */
    val pauseMs: Long = 700,
    /** Talking without a pause this long → send a pass anyway. */
    val maxGapMs: Long = 6_000,
    /** No live passes past this much audio: each pass re-sends it all, so they would only get
     *  slower and costlier. The final pass at Stop still runs. */
    val capMs: Long = 120_000,
    /** Speech = louder than this, or 3× the noise floor, whichever is higher. */
    val minThreshold: Float = 0.02f,
    /** A pass needs at least this much speech since the last one. A clip of noise, a breath or a
     *  click is where speech models invent sentences (measured: gpt-4o-transcribe turned 2.5 s of
     *  silence into "Bu sabah çok güzel bir breakfast yaptım."). */
    val minSpeechMs: Long = 250,
)

/** A fresh id for one recording — unique enough to key the broker's per-dictation connection. */
@OptIn(ExperimentalUuidApi::class)
private fun newDictationId(): String = Uuid.random().toString()

/** How far before the last pass's end the next pass's audio starts. */
private const val PASS_OVERLAP_MS = 400L

/**
 * The dictation state machine. Drive it with [onMicClick] / [stopMic] / [cancelMic] and render
 * [recording]/[listening]/[transcribing]/[liveTranscript]/[micDenied]/[banner].
 *
 * Created by [rememberDictation]; never constructed directly outside tests (it needs a scope, and
 * the Compose-scoped effects live in the remember function).
 */
class DictationController(
    private val mic: MicCapture,
    private val scope: CoroutineScope,
    private val haptic: Haptics = NoHaptics,
    private val timing: LivePassTiming = LivePassTiming(),
) {
    /** Audio (record-then-POST) capture active. */
    var recording by mutableStateOf(false); private set

    /** On-device streaming recognition active (only where `Platform.mic.liveTranscript` exists). */
    var listening by mutableStateOf(false); private set

    /** The POST is in flight — the "Transcribing…" strip is up and the mic is disabled. */
    var transcribing by mutableStateOf(false); private set

    /** The growing live transcript — the on-device partial, or the latest broker partial pass;
     *  null when there is no live path for this recording. */
    var liveTranscript by mutableStateOf<String?>(null); private set

    /** Elapsed capture seconds, driven by [rememberDictation]'s timer. */
    var recordingSeconds by mutableIntStateOf(0)

    /** The user refused (or permanently denied) microphone permission — show [MicDeniedDialog]. */
    var micDenied by mutableStateOf(false)

    /** Transient one-line status ("Didn't catch that" / "Transcription failed"), auto-cleared. */
    var banner by mutableStateOf<String?>(null)

    /**
     * The mic could not be opened at all (no line, in use by another app). Desktop's fourth
     * MicButton state; Android surfaces a refused permission through [micDenied] instead.
     */
    var micUnavailable by mutableStateOf(false); private set

    /** Desktop's inline composer error line. Same text as [banner]; kept as a separate hook because
     *  the pointer composer renders it under the card rather than as a takeover strip. */
    var errorMessage by mutableStateOf<String?>(null)

    /**
     * The recording whose transcription just FAILED, kept so the user can [retryTranscription] or
     * send it as an audio attachment ([takeFailedAudio]) instead of losing what they said. Null
     * when there is nothing to recover. While set, the banner does not auto-clear.
     */
    var failedAudio by mutableStateOf<CapturedAudio?>(null); private set

    /** Project/agent names biasing on-device recognition. */
    val glossary = mutableStateListOf<String>()

    /** Whether a capture is in progress (the RecordingBar takes over the composer). */
    val active: Boolean get() = recording || listening

    // Rebound every recomposition by rememberDictation so they never go stale (chat re-wires the
    // session-bound closures + draft sink whenever the active session switches).
    var transcribeDraft: suspend (String) -> String? = { null }
    var transcribeAudio: suspend (ByteArray, String, String) -> String? = { _, _, _ -> null }
    /** A live partial pass over the audio so far; null = this composer sends none. Null result =
     *  the pass failed, which is ignored (the previous live text stays). */
    var transcribePartial: (suspend (LivePassRequest) -> LivePass?)? = null
    /** Mic start: ask the broker to open the connection this recording's passes will reuse, so
     *  even the first one skips the setup. Null = this composer has none. */
    var transcribeWarm: (suspend (String) -> Unit)? = null
    var onAppend: (String) -> Unit = {}

    /** This device's explicit [DictationMode]; null = never chosen. */
    var preferredMode: DictationMode? = null

    /** The broker has a usable ChatGPT login; null = not known (yet). */
    var codexAvailable: Boolean? = null

    /**
     * Spoken-edit check: (the text before this pass's words, those words) → the edited text, or
     * null when they were dictation. Null seam = no spoken edits.
     */
    var checkEdit: (suspend (String, String) -> String?)? = null

    /** Edit checks still out, awaited by Stop so its final text includes them. */
    private val editJobs = mutableListOf<Job>()

    /** The broker's engine can serve live passes; null = not known, try (it answers `skipped`). */
    var livePartialsAvailable: Boolean? = null

    /** Quiet time at the end of the latest level sample, ms (the pause detector's state). */
    private var quietMs = 0L

    /** The latest complete dictated text from a pass, and how much of the recording it covers.
     *  The next pass sends that text plus only the audio after [mergedEndMs] (less a short
     *  overlap), so it costs the same at minute two as at second five. */
    private var mergedText = ""
    private var mergedEndMs = 0L

    /** What the dictated stretch of the field holds NOW — the last pass's text plus whatever the
     *  user typed into it since. Sent as the current text, so hand edits survive. Null = use the
     *  last pass's text. Set by the composer. */
    var currentText: () -> String? = { null }

    /** The current recording's id, sent with each of its passes; null when it has no live passes. */
    private var dictationId: String? = null

    /** The live partial pass loop for the current recording, cancelled at Stop/cancel. */
    private var livePassJob: Job? = null

    /** The pass currently out (snapshot → broker), a child of [livePassJob]. */
    private var inFlightPass: Job? = null

    /** Level samples above the speech threshold so far in this recording — a clock that only
     *  moves while the user talks, so "nothing said since that snapshot" is one comparison. */
    private var speechSamples = 0L

    /** [speechSamples] when the in-flight pass took its snapshot; -1 = it has none yet. */
    private var inFlightSpeech = -1L

    /** The latest successful live pass, and [speechSamples] when its audio was taken. */
    private var coveredText: String? = null
    private var coveredSpeech = -1L

    /**
     * Each new live text, delivered as it happens — the composer writes it into the field. A
     * callback rather than an observed [liveTranscript]: two updates in one frame (an edit landing
     * just before Stop clears it) would show the observer only the second.
     */
    var onLive: (String) -> Unit = {}

    private fun showLive(text: String) {
        liveTranscript = text
        onLive(text)
    }

    /** Called when the user DISCARDS a recording ([discardMic]) — the composer removes the live
     *  text it had written into the field. Not called for a session switch or dispose. */
    var onDiscard: () -> Unit = {}

    /** The in-flight transcribe coroutine, or null when idle. Tracked so [cancelMic] can cancel a
     *  PENDING transcription on a session switch — otherwise A's POST could land A's text in B. */
    private var transcribeJob: Job? = null

    /** The on-device partial collector, cancelled with the recognition session it belongs to. */
    private var partialJob: Job? = null

    /**
     * Whether the on-device recogniser has been STARTED and not yet stopped or cancelled.
     *
     * Not the same as [listening], and the difference is the bug it exists to prevent. [stopMic]
     * leaves the listening state immediately and only THEN awaits `LiveTranscript.stop()`, which
     * on iOS drains the analyzer. During that window a [cancelMic] — a session switch, the
     * composer being disposed — used to cancel the transcribe job while leaving the recogniser
     * running, and the next dictation would start a second one on top of it. iOS caps how many can
     * exist at once ("maximum number of recognizers reached"), so the mic would simply stop
     * working until the app was relaunched.
     */
    private var liveSessionOpen = false

    /**
     * Whether the RECORDER has been started and not yet stopped or cancelled — the [recording]
     * analogue of [liveSessionOpen], and it exists for the same bug.
     *
     * [stopMic] leaves the recording state on the frame the user tapped Stop and only THEN awaits
     * [MicCapture.flush] inside the transcribe job. A [cancelMic] landing in that window cancels
     * the job, so neither `stop()` nor `cancel()` would ever run: the mic would stay open (a
     * browser tab's recording indicator lit for good, a desktop line held) and the next dictation
     * would ADOPT the still-running recorder and splice the abandoned audio onto the front of it.
     */
    private var audioSessionOpen = false

    private fun fail(message: String) {
        banner = message
        errorMessage = message
    }

    private fun appendToDraft(s: String) {
        val t = s.trim()
        if (t.isNotEmpty()) onAppend(t)
    }

    /** The cleanup POST and what to do with its answer. Split out of [runTranscription] so the
     *  on-device path can await [LiveTranscript.stop] and then this in ONE coroutine — two would
     *  mean two jobs, and [cancelMic] can only cancel the one it is holding. */
    private suspend fun transcribeAndAppend(
        rawFallback: String?,
        audio: CapturedAudio? = null,
        call: suspend () -> String?,
    ) {
        // A throwing seam is a failed transcription too — never lose the recording to it.
        val cleaned = try {
            call()?.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        when {
            !cleaned.isNullOrEmpty() -> appendToDraft(cleaned)
            // On-device already produced usable text — keep it rather than losing the turn.
            !rawFallback.isNullOrBlank() -> appendToDraft(rawFallback)
            else -> {
                failedAudio = audio
                fail("Transcription failed")
            }
        }
    }

    /** Re-POST the [failedAudio] through the same transcribe seam. No-op when there is none. */
    fun retryTranscription() {
        val audio = failedAudio ?: return
        if (active || transcribing) return
        haptic.perform(HapticKind.Tick)
        failedAudio = null
        banner = null
        errorMessage = null
        transcribing = true
        transcribeJob = scope.launch {
            try {
                transcribeAndAppend(rawFallback = null, audio = audio) {
                    transcribeAudio(audio.bytes, audio.filename, audio.mime)
                }
            } finally {
                transcribing = false
            }
        }
    }

    /** Hand over the [failedAudio] (to stage as an attachment) and clear the failure. */
    fun takeFailedAudio(): CapturedAudio? {
        val audio = failedAudio ?: return null
        failedAudio = null
        banner = null
        errorMessage = null
        return audio
    }

    /** Drop the [failedAudio] and its error line. */
    fun dismissFailure() {
        failedAudio = null
        banner = null
        errorMessage = null
    }

    private fun runTranscription(rawFallback: String?, call: suspend () -> String?) {
        transcribeJob = scope.launch {
            transcribing = true
            try {
                transcribeAndAppend(rawFallback, call = call)
            } finally {
                transcribing = false
            }
        }
    }

    /** Begin capture. Assumes permission is already granted — [onMicClick] is the entry point. */
    fun startMic() {
        if (active || transcribing) return
        haptic.perform(HapticKind.Tick)
        banner = null
        errorMessage = null
        failedAudio = null
        micUnavailable = false
        val live = mic.liveTranscript
        val mode = resolveDictationMode(preferredMode, codexAvailable, hasDeviceRecognizer = live != null)
        if (mode == DictationMode.Device && live != null && live.start(glossary.toList())) {
            liveSessionOpen = true
            listening = true
            liveTranscript = ""
            partialJob?.cancel()
            partialJob = scope.launch { live.partial.collect { showLive(it) } }
            return
        }
        recording = mic.start()
        audioSessionOpen = recording
        if (!recording) {
            micUnavailable = true
            return
        }
        val level = mic.level
        val partial = transcribePartial
        speechSamples = 0
        quietMs = 0
        mergedText = ""
        mergedEndMs = 0
        inFlightSpeech = -1
        coveredText = null
        coveredSpeech = -1
        dictationId = null
        if (level != null && partial != null && livePartialsAvailable != false) {
            val id = newDictationId()
            dictationId = id
            transcribeWarm?.let { warm ->
                scope.launch { try { warm(id) } catch (e: CancellationException) { throw e } catch (_: Throwable) {} }
            }
            liveTranscript = ""
            livePassJob = scope.launch { runLivePasses(level, partial) }
        }
    }

    /**
     * Watch the mic level for pauses and send the audio so far at each one. ONE pass in flight at
     * a time: a pause that comes while one is out just marks [pending], and exactly one more pass
     * (over the then-newest audio) follows it, so answers can never arrive out of order.
     */
    private suspend fun runLivePasses(
        level: StateFlow<Float>,
        partial: suspend (LivePassRequest) -> LivePass?,
    ) = coroutineScope {
        var elapsed = 0L
        var sinceLastPass = 0L
        // Speech heard since the last pass was triggered, ms.
        var spokeMs = 0L
        // Starts at silence, not at the first sample: someone who starts talking the moment they
        // tap would otherwise set the floor to their own voice and never register as speaking.
        var noiseFloor = 0f
        var pending = false
        var declined = false

        suspend fun pass() {
            do {
                pending = false
                inFlightSpeech = -1
                val audio = try { mic.snapshot() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                    ?: return
                // Everything heard up to here is inside this snapshot.
                val covers = speechSamples
                inFlightSpeech = covers
                val sent = textNow()
                val r = runPass(partial, audio, sent)
                if (r?.skipped == true) {
                    declined = true
                    return
                }
                inFlightSpeech = -1
                if (r == null) continue
                // The user typed into the dictated text while this was out: applying the answer
                // would drop their typing. Ask again with what is there now.
                if (textNow() != sent) {
                    pending = true
                    continue
                }
                val text = resolve(r, sent) ?: continue
                mergedText = text
                mergedEndMs = r.durationMs ?: 0
                coveredText = text
                coveredSpeech = covers
                // Also after Stop: Stop waits for this pass when it covers everything said and then
                // writes nothing itself, so this is the only place a short dictation's words land.
                showLive(text)
                // In the background: were the new words "remove the last sentence" and the like?
                r.added?.takeIf { it.isNotBlank() && r.full }?.let { launchEditCheck(sent, it, text) }
            } while (pending && recording)
        }

        while (recording && !declined && elapsed < timing.capMs) {
            delay(timing.sampleMs)
            elapsed += timing.sampleMs
            sinceLastPass += timing.sampleMs
            val l = level.value
            // Noise floor: drops to any quieter sample at once, creeps up slowly, so sustained
            // speech does not raise it but a noisier room eventually does.
            noiseFloor = if (l < noiseFloor) l else noiseFloor + (l - noiseFloor) * 0.002f
            val threshold = maxOf(timing.minThreshold, noiseFloor * 3f)
            if (l > threshold) {
                spokeMs += timing.sampleMs
                speechSamples++
                quietMs = 0
            } else {
                quietMs += timing.sampleMs
            }
            if (spokeMs >= timing.minSpeechMs && (quietMs >= timing.pauseMs || sinceLastPass >= timing.maxGapMs)) {
                spokeMs = 0
                sinceLastPass = 0
                if (inFlightPass?.isActive == true) pending = true
                else inFlightPass = launch { pass() }
            }
        }
        // Past the cap (or declined): let a pass already out land, but start no more.
        inFlightPass?.join()
    }

    private fun textNow(): String = currentText() ?: mergedText

    /**
     * Ask whether [added] (spoken after [base]) was an edit; if so, put the edited text in place of
     * [passText] — keeping whatever came after it meanwhile (a later pass, the user's typing). If
     * the field no longer starts with [passText] the user changed it, and the edit is dropped.
     */
    private fun launchEditCheck(base: String, added: String, passText: String) {
        val check = checkEdit ?: return
        // Nothing written before these words: there is nothing for them to edit.
        if (base.isBlank()) return
        lateinit var job: Job
        job = scope.launch {
            try {
                val edited = try { check(base, added) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                    ?: return@launch
                val now = textNow()
                if (!now.startsWith(passText)) return@launch
                val newText = (edited + now.substring(passText.length)).trim()
                mergedText = newText
                if (coveredText == passText) coveredText = newText
                showLive(newText)
            } finally {
                editJobs.remove(job)
            }
        }
        editJobs.add(job)
    }

    /**
     * One pass: [text] (the dictation so far) + the audio since the last pass. The audio starts a
     * little BEFORE where the last one ended, so a word cut in half there is heard whole; the
     * broker is told the clip may repeat the text's last words. Null = it failed.
     */
    private suspend fun runPass(
        partial: suspend (LivePassRequest) -> LivePass?,
        audio: CapturedAudio,
        text: String,
        final: Boolean = false,
    ): LivePass? {
        val from = if (mergedEndMs > 0 && text.isNotBlank()) (mergedEndMs - PASS_OVERLAP_MS).coerceAtLeast(0) else 0
        return try {
            partial(LivePassRequest(audio.bytes, audio.filename, audio.mime, from, text, dictationId, final))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The complete dictated text a pass answered with. Current brokers always send it whole
     * ([LivePass.full]) — it may even be shorter, after "remove the last sentence". An older
     * broker sends either the whole recording's transcript or, after a cut, only the new words.
     * Null = nothing usable (an older broker's empty answer over silence).
     */
    private fun resolve(r: LivePass, sent: String): String? {
        val text = r.text.trim()
        if (r.full) return text
        if (text.isEmpty()) return null
        return if (r.fromMs > 0) listOf(sent.trim(), text).filter { it.isNotEmpty() }.joinToString(" ") else text
    }

    /** Stop's transcript the cheap way — the dictation so far + only the audio since the last
     *  pass, merged. Null when there is no live seam or the pass failed or was declined. */
    private suspend fun finalPass(audio: CapturedAudio): String? {
        val partial = transcribePartial ?: return null
        if (livePartialsAvailable == false) return null
        val sent = textNow()
        val r = runPass(partial, audio, sent, final = true) ?: return null
        if (r.skipped) return null
        val text = resolve(r, sent) ?: return null
        // The last words may be an edit too ("…and remove the last sentence", then Stop).
        val added = r.added?.takeIf { it.isNotBlank() && r.full && sent.isNotBlank() } ?: return text
        val edited = try { checkEdit?.invoke(sent, added) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
        return edited ?: text
    }

    private fun stopLivePasses() {
        livePassJob?.cancel()
        livePassJob = null
        inFlightPass = null
    }

    /**
     * The live text that already covers EVERYTHING said, so Stop need not transcribe it again:
     * the last pass, when no speech was heard after its snapshot, or the pass still in flight,
     * awaited, when no speech was heard after ITS snapshot. Null = there is newer speech (or no
     * usable pass), and Stop sends the whole recording as before.
     *
     * Only for engines that never clean up (every live-pass engine): a live pass is raw STT, and
     * the final pass of such an engine is the very same call over the very same audio.
     */
    private suspend fun upToDateLiveText(): String? {
        val pass = inFlightPass
        // Less than a pass's worth of speech since (a breath, the tap on Stop) counts as nothing.
        val minSamples = timing.minSpeechMs / timing.sampleMs
        if (pass?.isActive == true && inFlightSpeech >= 0 && speechSamples - inFlightSpeech < minSamples) pass.join()
        return coveredText?.takeIf { coveredSpeech >= 0 && speechSamples - coveredSpeech < minSamples }
    }

    fun stopMic() {
        haptic.perform(HapticKind.Tick)
        if (listening) {
            listening = false
            partialJob?.cancel()
            partialJob = null
            val live = mic.liveTranscript
            liveTranscript = null
            // Set here rather than inside the coroutine: the strip has to be up on the frame the
            // user tapped Stop, and a state write on the next dispatch would leave the composer
            // showing nothing at all for a frame.
            transcribing = true
            // The UI has already left the listening state, so the wait below is covered by the
            // "Transcribing…" strip rather than a RecordingBar that will not go away.
            // `LiveTranscript.stop()` suspends because iOS must drain its on-device analyzer to
            // finalise the last words; Android's returns without suspending.
            transcribeJob = scope.launch {
                try {
                    val draft = try { live?.stop().orEmpty() } finally { liveSessionOpen = false }
                    if (draft.isBlank()) {
                        fail("Didn't catch that")
                        return@launch
                    }
                    transcribeAndAppend(rawFallback = draft) { transcribeDraft(draft) }
                } finally {
                    transcribing = false
                }
            }
        } else if (recording) {
            recording = false
            val liveText = liveTranscript
            // Same shape as the on-device branch above, and for the same reason: `transcribing` is
            // set on the frame the user tapped Stop, and the await happens INSIDE the one job
            // [cancelMic] holds. The await is [MicCapture.flush] — the browser's `MediaRecorder`
            // only ever delivers bytes through an event, so the tail of the recording is still in
            // the encoder until it has fired. Every other host's flush returns without suspending.
            transcribing = true
            transcribeJob = scope.launch {
                try {
                    val audio: CapturedAudio? = try {
                        mic.flush()
                        mic.stop()
                    } finally {
                        audioSessionOpen = false
                    }
                    // Nothing said since the last live pass → its text is already in the field (with
                    // anything the user typed since); there is nothing left to transcribe or write.
                    val upToDate = try { upToDateLiveText() } finally { stopLivePasses() }
                    // A spoken edit still being checked lands first.
                    editJobs.toList().joinAll()
                    liveTranscript = null
                    if (upToDate != null) return@launch
                    if (audio == null) {
                        fail("Didn't catch that")
                        return@launch
                    }
                    // The words since the last pass, merged into the text so far.
                    val merged = finalPass(audio)
                    if (merged != null) {
                        appendToDraft(merged)
                        return@launch
                    }
                    // A failed final pass keeps the live text already in the field rather than
                    // turning it into an error.
                    transcribeAndAppend(rawFallback = coveredText ?: liveText, audio = audio) {
                        transcribeAudio(audio.bytes, audio.filename, audio.mime)
                    }
                } finally {
                    stopLivePasses()
                    transcribing = false
                }
            }
        }
    }

    fun cancelMic() {
        val wasRecording = recording
        val wasLiveOpen = liveSessionOpen
        val wasAudioOpen = audioSessionOpen
        recording = false
        listening = false
        liveSessionOpen = false
        audioSessionOpen = false
        liveTranscript = null
        partialJob?.cancel()
        partialJob = null
        stopLivePasses()
        editJobs.toList().forEach { it.cancel() }
        editJobs.clear()
        // Cancel a pending transcription too (not just a live recording): on a session switch the
        // composer's DisposableEffect(resetKey) disposes THIS controller, and a still-in-flight POST
        // must not resolve into the next session's draft.
        transcribeJob?.cancel()
        transcribeJob = null
        // [liveSessionOpen] and not `listening`: a cancel that arrives while `stopMic` is still
        // draining the recogniser must stop it too, or the next dictation starts a second one.
        // `cancel()` is idempotent on every host and also clears the partial.
        if (wasLiveOpen) mic.liveTranscript?.cancel()
        // [audioSessionOpen] and not just `recording`, for the same reason: a cancel that lands
        // while `stopMic`'s job is still awaiting the flush must close the recorder, which that
        // job will no longer reach.
        if (wasRecording || wasAudioOpen) mic.cancel()
    }

    /** The user's own Cancel: stop without transcribing AND take back the live text. */
    fun discardMic() {
        cancelMic()
        onDiscard()
    }

    /**
     * The mic button's click. Asks for permission first where the platform has one (Android's
     * RECORD_AUDIO; desktop grants it to the process and answers true immediately) and shows
     * [MicDeniedDialog] on a refusal, so a denied mic never looks like a broken button.
     */
    fun onMicClick() {
        if (active) {
            stopMic()
            return
        }
        scope.launch {
            if (mic.requestPermission()) startMic() else micDenied = true
        }
    }
}

/**
 * Remembers a [DictationController] over `Platform.mic` and installs its Compose-scoped effects
 * (glossary load, the elapsed-seconds timer, banner auto-clear, cancel-on-leave).
 *
 * The controller is `remember(resetKey)`-SCOPED, not a bare `remember {}`: both composers stay
 * composed across session switches, so one shared controller with repointed closures would leak
 * session A's in-flight dictation into B. Rekeying gives each session a fresh controller and the
 * `DisposableEffect(resetKey)` cancels the outgoing one's pending POST. Pass the session id in
 * chat, a constant in the launcher.
 */
@Composable
fun rememberDictation(
    resetKey: Any,
    loadGlossary: suspend () -> List<String> = { emptyList() },
    transcribeDraft: suspend (String) -> String? = { null },
    transcribeAudio: suspend (ByteArray, String, String) -> String?,
    onAppend: (String) -> Unit,
    mic: MicCapture = LocalPlatform.current.mic,
    transcribePartial: (suspend (LivePassRequest) -> LivePass?)? = null,
    loadVoiceCaps: suspend () -> VoiceCaps? = { null },
    checkEdit: (suspend (String, String) -> String?)? = null,
    transcribeWarm: (suspend (String) -> Unit)? = null,
): DictationController {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val controller = remember(resetKey, mic) { DictationController(mic, scope, haptic) }

    // Keep the session-bound closures + draft sink current across recompositions.
    controller.transcribeDraft = transcribeDraft
    controller.transcribeAudio = transcribeAudio
    controller.transcribePartial = transcribePartial
    controller.checkEdit = checkEdit
    controller.transcribeWarm = transcribeWarm
    controller.onAppend = onAppend

    LaunchedEffect(controller) {
        controller.glossary.clear()
        controller.glossary.addAll(runCatching { loadGlossary() }.getOrDefault(emptyList()))
    }
    // The mode inputs: this device's own choice (a Settings change applies to the next dictation)
    // and what the broker can do. Read here, not in `startMic`, so starting never waits on a fetch.
    val prefs = LocalUiPrefsOrNull.current
    LaunchedEffect(controller, prefs) {
        prefs?.dictationMode?.collect { controller.preferredMode = it }
    }
    LaunchedEffect(controller) {
        val caps = runCatching { loadVoiceCaps() }.getOrNull() ?: return@LaunchedEffect
        controller.codexAvailable = caps.codexAvailable
        controller.livePartialsAvailable = caps.livePartials
    }
    LaunchedEffect(controller, controller.active) {
        if (controller.active) {
            controller.recordingSeconds = 0
            // Bounded at an hour rather than `while (true)`: an hour-long "dictation" is a stuck
            // mic, not a message, and an effect that never returns is a HANG under a Compose UI
            // test's virtual clock (waitForIdle would never see the composition go idle).
            while (controller.recordingSeconds < 3600) {
                delay(1000)
                controller.recordingSeconds++
            }
        }
    }
    LaunchedEffect(controller, controller.banner, controller.failedAudio) {
        // A recoverable failure stays up until the user retries, attaches or dismisses it.
        if (controller.banner != null && controller.failedAudio == null) {
            delay(4000)
            controller.banner = null
            controller.errorMessage = null
        }
    }
    // Cancel any in-flight recording when the host leaves composition / switches away, so a
    // backgrounded recording never leaks the mic or posts stale audio.
    DisposableEffect(controller) {
        onDispose { controller.cancelMic() }
    }
    return controller
}

/**
 * Round mic control, 32dp visual inside a ≥48dp IconButton tap target: grey Mic (idle) → red Stop
 * (recording) → a small spinner (transcribing, disabled) → MicOff (line unavailable, disabled).
 */
@Composable
fun MicButton(
    recording: Boolean,
    transcribing: Boolean,
    micUnavailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pointer = dev.supermux.ui.adaptive.LocalPointerAvailable.current
    // A bare glyph in a circle like every other toolbar control; only a live recording fills it.
    IconButton(
        onClick = onClick,
        enabled = !transcribing && !micUnavailable,
        modifier = modifier.then(if (pointer) Modifier.size(32.dp) else Modifier),
    ) {
        Box(
            Modifier
                .size(if (pointer) 32.dp else 36.dp)
                .clip(CircleShape)
                .background(if (recording) cs.error else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            when {
                transcribing -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = cs.primary,
                )
                micUnavailable -> Icon(
                    Icons.Filled.MicOff, contentDescription = "Microphone unavailable",
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
                recording -> Icon(
                    Icons.Filled.Stop, contentDescription = "Stop and transcribe",
                    tint = cs.onError, modifier = Modifier.size(16.dp),
                )
                else -> Icon(
                    Icons.Outlined.Mic, contentDescription = "Record voice",
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(if (pointer) 18.dp else 22.dp),
                )
            }
        }
    }
}

/** "Transcribing…" indicator (parity with iOS transcribingBar). */
@Composable
fun TranscribingIndicator(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("composer_transcribing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = cs.primary,
            strokeWidth = 1.5.dp,
        )
        Text("Transcribing…", color = cs.onSurfaceVariant, fontSize = 12.sp)
    }
}

/** Mic-permission-denied dialog (parity with iOS ChatPane). */
@Composable
fun MicDeniedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone access needed") },
        text = { Text("Enable microphone access in Settings to dictate messages.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        modifier = Modifier.testTag("mic_denied_dialog"),
    )
}

/**
 * The recording pill shown above the composer while dictating: a small rounded capsule, centred,
 * with a discard button, a blinking red dot + timer, and Stop — the only Stop on screen (the
 * toolbar's mic hides meanwhile). The field below stays: the live transcript is written into it.
 *
 * Both buttons keep ≥48dp tap targets around their smaller visuals; Discard is the de-emphasized
 * one, at the far end from Stop, so an accidental discard is hard.
 */
@Composable
fun RecordingBar(
    seconds: Int,
    onStop: () -> Unit,       // STOP (transcribe)
    onCancel: () -> Unit,     // trash (discard)
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(cs.surfaceContainerHighest)
                .padding(horizontal = 2.dp)
                .testTag("composer_recording_bar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.testTag("voice_cancel")) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Discard recording",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "dot",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(cs.error.copy(alpha = blink)),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                color = cs.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            IconButton(onClick = onStop, modifier = Modifier.testTag("voice_stop")) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(cs.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop and transcribe",
                        tint = cs.onError,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A failed transcription with its recording still in hand: the error text plus Retry, "Send as
 * audio" (stage the recording as an attachment) and dismiss. Replaces the transient banner while
 * [DictationController.failedAudio] is set, so what the user said is never silently lost.
 */
@Composable
fun DictationFailureBar(
    message: String,
    onRetry: () -> Unit,
    onAttach: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp)
            .testTag("composer_dictation_failed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = cs.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.testTag("dictation_retry")) {
            Text("Retry", fontSize = 12.sp)
        }
        if (onAttach != null) {
            TextButton(onClick = onAttach, modifier = Modifier.testTag("dictation_attach")) {
                Text("Send as audio", fontSize = 12.sp)
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("dictation_dismiss")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
