// Voice dictation for every composer on every host (cluster D3): mic → audio → the broker's
// /transcribe STT engine → the cleaned text appended to the draft.
//
// One state machine, two paths, decided by the PLATFORM rather than by the app module:
//   - `Platform.mic.liveTranscript != null` (Android with on-device STT enabled): recognise on the
//     device, show the growing partial in the RecordingBar, and POST only the finished draft for a
//     cleanup pass (`transcribeDraft`).
//   - otherwise (desktop, and Android with on-device STT off — the default): record, then POST the
//     encoded audio (`transcribeAudio`).
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    /** Audio (record-then-POST) capture active. */
    var recording by mutableStateOf(false); private set

    /** On-device streaming recognition active (only where `Platform.mic.liveTranscript` exists). */
    var listening by mutableStateOf(false); private set

    /** The POST is in flight — the "Transcribing…" strip is up and the mic is disabled. */
    var transcribing by mutableStateOf(false); private set

    /** The growing on-device partial; null when there is no live path (desktop, STT off). */
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

    /** Project/agent names biasing on-device recognition. */
    val glossary = mutableStateListOf<String>()

    /** Whether a capture is in progress (the RecordingBar takes over the composer). */
    val active: Boolean get() = recording || listening

    // Rebound every recomposition by rememberDictation so they never go stale (chat re-wires the
    // session-bound closures + draft sink whenever the active session switches).
    var transcribeDraft: suspend (String) -> String? = { null }
    var transcribeAudio: suspend (ByteArray, String) -> String? = { _, _ -> null }
    var onAppend: (String) -> Unit = {}

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
    private suspend fun transcribeAndAppend(rawFallback: String?, call: suspend () -> String?) {
        val cleaned = call()?.trim()
        when {
            !cleaned.isNullOrEmpty() -> appendToDraft(cleaned)
            // On-device already produced usable text — keep it rather than losing the turn.
            !rawFallback.isNullOrBlank() -> appendToDraft(rawFallback)
            else -> fail("Transcription failed")
        }
    }

    private fun runTranscription(rawFallback: String?, call: suspend () -> String?) {
        transcribeJob = scope.launch {
            transcribing = true
            try {
                transcribeAndAppend(rawFallback, call)
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
        micUnavailable = false
        val live = mic.liveTranscript
        if (live != null && live.start(glossary.toList())) {
            liveSessionOpen = true
            listening = true
            liveTranscript = ""
            partialJob?.cancel()
            partialJob = scope.launch { live.partial.collect { liveTranscript = it } }
            return
        }
        recording = mic.start()
        if (!recording) micUnavailable = true
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
            val audio: CapturedAudio? = mic.stop()
            if (audio == null) {
                fail("Didn't catch that")
                return
            }
            runTranscription(rawFallback = null) { transcribeAudio(audio.bytes, audio.filename) }
        }
    }

    fun cancelMic() {
        val wasRecording = recording
        val wasLiveOpen = liveSessionOpen
        recording = false
        listening = false
        liveSessionOpen = false
        liveTranscript = null
        partialJob?.cancel()
        partialJob = null
        // Cancel a pending transcription too (not just a live recording): on a session switch the
        // composer's DisposableEffect(resetKey) disposes THIS controller, and a still-in-flight POST
        // must not resolve into the next session's draft.
        transcribeJob?.cancel()
        transcribeJob = null
        // [liveSessionOpen] and not `listening`: a cancel that arrives while `stopMic` is still
        // draining the recogniser must stop it too, or the next dictation starts a second one.
        // `cancel()` is idempotent on every host and also clears the partial.
        if (wasLiveOpen) mic.liveTranscript?.cancel()
        if (wasRecording) mic.cancel()
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
    transcribeAudio: suspend (ByteArray, String) -> String?,
    onAppend: (String) -> Unit,
    mic: MicCapture = LocalPlatform.current.mic,
): DictationController {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val controller = remember(resetKey, mic) { DictationController(mic, scope, haptic) }

    // Keep the session-bound closures + draft sink current across recompositions.
    controller.transcribeDraft = transcribeDraft
    controller.transcribeAudio = transcribeAudio
    controller.onAppend = onAppend

    LaunchedEffect(controller) {
        controller.glossary.clear()
        controller.glossary.addAll(runCatching { loadGlossary() }.getOrDefault(emptyList()))
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
    LaunchedEffect(controller, controller.banner) {
        if (controller.banner != null) {
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
    IconButton(onClick = onClick, enabled = !transcribing && !micUnavailable, modifier = modifier) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (recording) cs.error else cs.surfaceContainer),
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
                    Icons.Filled.Mic, contentDescription = "Record voice",
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp),
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
 * Recording takeover of the composer row (parity with iOS RecordingBar): a small de-emphasized
 * trash CANCEL far left, a blinking red dot + mono timer, and a big primary STOP where Send
 * normally sits. When on-device STT has partial text, a scrollable live transcript sits above.
 *
 * Touch-target rule: STOP is a 48dp visual inside a ≥48dp IconButton (the obvious large target);
 * CANCEL is a 32dp visual inside the 48dp IconButton min-size, so an accidental cancel is hard.
 */
@Composable
fun RecordingBar(
    seconds: Int,
    liveTranscript: String,   // "" when audio-only (no on-device)
    onStop: () -> Unit,       // big STOP (transcribe)
    onCancel: () -> Unit,     // small trash (discard)
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("composer_recording_bar"),
    ) {
        // Live transcript area (only when on-device STT has partial text). maxHeight ~120dp, scroll.
        if (liveTranscript.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("voice_live_transcript"),
            ) {
                Text(liveTranscript, color = cs.onSurface, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // 1) Small de-emphasized CANCEL (trash), 48dp tap target / 32dp visual, far left.
            IconButton(onClick = onCancel, modifier = Modifier.testTag("voice_cancel")) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Discard recording",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 2) Blinking red dot + mono timer
            val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "dot",
            )
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(cs.error.copy(alpha = blink)),
            )
            Text(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                color = cs.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            // 3) BIG STOP — primary, where Send normally sits. 48dp filled circle, ≥48dp target.
            IconButton(onClick = onStop, modifier = Modifier.testTag("voice_stop")) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop and transcribe",
                        tint = cs.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
