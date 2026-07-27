package dev.supermux.android.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.supermux.android.DevConfig
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared voice-dictation drive logic for the chat composer AND the new-session launcher, so the
 * two can't drift apart (the launcher previously skipped the AI-cleanup pass entirely).
 *
 * Flow: mic → audio recording → broker STT engine (/transcribe multipart) → append cleaned text
 * via [onAppend]. Optionally (gated by [DevConfig.ENABLE_ONDEVICE_STT], currently off) try
 * on-device STT first and only POST the draft for cleanup. Chat passes its session id; the
 * launcher passes none (id-less /transcribe).
 *
 * Create with [rememberDictation]; never construct directly (it needs Compose-scoped effects).
 */
internal class DictationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haptic: (HapticKind) -> Unit,
) {
    val recorder = VoiceRecorder(context)
    val dictation = DictationEngine(context)

    var recording by mutableStateOf(false); private set   // audio (whisper) path active
    var listening by mutableStateOf(false); private set   // on-device STT active
    var transcribing by mutableStateOf(false); private set // POST in flight ("Transcribing…")
    var liveTranscript by mutableStateOf(""); private set  // on-device partials
    var recordingSeconds by mutableIntStateOf(0)           // driven by rememberDictation's timer
    var micDenied by mutableStateOf(false)
    var banner by mutableStateOf<String?>(null)            // transient ("Didn't catch that" / failed)
    val glossary = mutableStateListOf<String>()

    /** Whether a recording/dictation is in progress (the RecordingBar takes over the composer). */
    val active: Boolean get() = recording || listening

    // Rebound every recomposition by rememberDictation so they never go stale (chat re-wires the
    // session-bound closures + draft sink whenever the active session switches).
    var transcribeDraft: suspend (String) -> String? = { null }
    var transcribeAudio: suspend (ByteArray, String) -> String? = { _, _ -> null }
    var onAppend: (String) -> Unit = {}
    lateinit var permLauncher: ManagedActivityResultLauncher<String, Boolean>

    private fun appendToDraft(s: String) {
        val t = s.trim()
        if (t.isNotEmpty()) onAppend(t)
    }

    private suspend fun runTranscription(rawFallback: String?, call: suspend () -> String?) {
        transcribing = true
        try {
            val cleaned = call()?.trim()
            when {
                !cleaned.isNullOrEmpty() -> appendToDraft(cleaned)
                !rawFallback.isNullOrBlank() -> appendToDraft(rawFallback)  // keep on-device draft
                else -> banner = "Transcription failed"                     // nothing to keep
            }
        } finally {
            transcribing = false
        }
    }

    fun startMic() {
        haptic(HapticKind.Tick)
        val started =
            if (DevConfig.ENABLE_ONDEVICE_STT) dictation.start(glossary.toList())
            else DictationStart.UNAVAILABLE
        when (started) {
            DictationStart.STARTED -> {
                listening = true
                liveTranscript = ""
                dictation.onPartial = { liveTranscript = it }
            }
            DictationStart.DENIED -> micDenied = true
            DictationStart.UNAVAILABLE -> {  // whisper path
                recorder.start()
                recording = true
            }
        }
    }

    fun stopMic() {
        haptic(HapticKind.Tick)
        if (listening) {
            listening = false
            val draft = dictation.stop()
            if (draft.isBlank()) { banner = "Didn't catch that"; return }
            scope.launch { runTranscription(rawFallback = draft) { transcribeDraft(draft) } }
        } else if (recording) {
            recording = false
            val f = recorder.stop()
            if (f == null) { banner = "Didn't catch that"; return }
            scope.launch(Dispatchers.IO) {
                val bytes = f.readBytes()
                val name = f.name
                withContext(Dispatchers.Main) {
                    runTranscription(rawFallback = null) { transcribeAudio(bytes, name) }
                }
            }
        }
    }

    fun cancelMic() {
        dictation.cancel()
        recorder.cancel()
        listening = false
        recording = false
        liveTranscript = ""
    }

    // Mic needs RECORD_AUDIO for BOTH paths (MediaRecorder + SpeechRecognizer). A fresh grant routes
    // through startMic() so it makes the same on-device-vs-audio decision. A permanent denial returns
    // granted=false with no re-prompt → show the "enable in Settings" dialog.
    fun onMicClick() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) startMic()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/**
 * Remembers a [DictationController] and installs its Compose-scoped effects (glossary load, the
 * elapsed-seconds timer, the transient-banner auto-clear, the RECORD_AUDIO permission launcher, and
 * cancel-on-leave). [resetKey] re-loads the glossary and cancels any in-flight capture when it
 * changes — pass the session id in chat (cancel on session switch) or a constant in the launcher.
 */
@Composable
internal fun rememberDictation(
    resetKey: Any,
    loadGlossary: suspend () -> List<String>,
    transcribeDraft: suspend (String) -> String?,
    transcribeAudio: suspend (ByteArray, String) -> String?,
    onAppend: (String) -> Unit,
): DictationController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val controller = remember { DictationController(context, scope, haptic) }

    // Keep the session-bound closures + draft sink current across recompositions.
    controller.transcribeDraft = transcribeDraft
    controller.transcribeAudio = transcribeAudio
    controller.onAppend = onAppend
    controller.permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) controller.startMic() else controller.micDenied = true
    }

    LaunchedEffect(resetKey) {
        controller.glossary.clear()
        controller.glossary.addAll(loadGlossary())
    }
    LaunchedEffect(controller.active) {
        if (controller.active) {
            controller.recordingSeconds = 0
            while (true) {
                delay(1000)
                controller.recordingSeconds++
            }
        }
    }
    LaunchedEffect(controller.banner) {
        if (controller.banner != null) {
            delay(4000)
            controller.banner = null
        }
    }
    // Cancel any in-flight recording when the host leaves composition / switches away, so a
    // backgrounded recording never leaks the mic or posts stale audio (iOS parity).
    DisposableEffect(resetKey) {
        onDispose { controller.cancelMic() }
    }
    return controller
}

/** Round mic button (≥48dp tap target, 32dp visual) — starts dictation. */
@Composable
internal fun MicButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = "Record voice",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "Transcribing…" indicator (parity with iOS transcribingBar). */
@Composable
internal fun TranscribingIndicator(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
internal fun MicDeniedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone access needed") },
        text = { Text("Enable microphone access in Settings to dictate messages.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
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
internal fun RecordingBar(
    seconds: Int,
    liveTranscript: String,   // "" when audio-only (no on-device)
    onStop: () -> Unit,       // big STOP (transcribe)
    onCancel: () -> Unit,     // small trash (discard)
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
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
                    .padding(12.dp),
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
                        painterResource(R.drawable.ic_trash),
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
                "%d:%02d".format(seconds / 60, seconds % 60),
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
                        painterResource(R.drawable.ic_square),
                        contentDescription = "Stop and transcribe",
                        tint = cs.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
