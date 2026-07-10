// Desktop mic dictation (M5-1) — record -> POST to the broker's whisper /transcribe -> append the
// cleaned text to the composer draft. Ports ONLY the record->POST->cleaned-text path every
// supermux client falls back to; Android's on-device live-partial STT
// (apps/android/.../chat/Dictation.kt's DevConfig.ENABLE_ONDEVICE_STT branch) is deliberately NOT
// ported — it's gated off by default even there, and desktop has no on-device ASR story.
//
// Three layers in this file (Tasks 2+3):
//   - WavEncoder (pure): raw PCM bytes -> a canonical 44-byte-header WAV. No I/O — fully unit
//     tested (DictationTest.kt).
//   - MicCapture/MicRecorder: a seam interface + the real javax.sound.sampled.TargetDataLine
//     adapter. MicRecorder is the ONE genuinely untestable-without-hardware piece (no mic under
//     Xvfb/CI) — MicCapture exists so DesktopDictationController never depends on it directly.
//   - DesktopDictationController/rememberDesktopDictation/MicButton (Task 3): the state machine +
//     UI, seam-testable via a fake MicCapture — no real audio hardware needed.
//
// Unlike Android's Dictation.kt, there is no RECORD_AUDIO permission model on desktop (the OS
// grants mic access at the process level) and no RecordingBar live-transcript takeover (there is
// no on-device partial transcript to show) — a failed line-open just surfaces as micUnavailable.
package dev.supermux.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Desktop mic dictation format: 16kHz mono 16-bit signed little-endian PCM — the standard input
 *  rate for whisper-family ASR (matches the broker's transcription pipeline's expectation; desktop
 *  emits a WAV the broker/ffmpeg accepts directly, unlike Android's MediaRecorder AAC capture which
 *  the broker transcodes server-side). */
internal val DICTATION_FORMAT: AudioFormat = AudioFormat(16000f, 16, 1, true, false)

/** Pure WAV (RIFF/WAVE, PCM) framing: wraps raw little-endian PCM sample bytes in a canonical
 *  44-byte WAV header for [format]. No I/O — testable with any byte array, real or fake. */
internal object WavEncoder {
    fun encode(pcm: ByteArray, format: AudioFormat): ByteArray {
        val channels = format.channels
        val sampleRate = format.sampleRate.toInt()
        val bitsPerSample = format.sampleSizeInBits
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)

        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }

        str("RIFF"); le32(36 + dataSize); str("WAVE")
        str("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate)
        le32(byteRate); le16(blockAlign); le16(bitsPerSample)
        str("data"); le32(dataSize)
        out.write(pcm)
        return out.toByteArray()
    }
}

/** Seam over the mic capture backend so [DesktopDictationController] is unit-testable without real
 *  audio hardware — [MicRecorder] is the production implementation, tests inject a fake. */
internal interface MicCapture {
    /** Opens the mic and starts capturing. Returns true if capture is now active, false if the mic
     *  could not be opened (no default line, in use by another app, unsupported format, etc). */
    fun start(): Boolean

    /** Stops capture and returns the recorded audio as WAV bytes, or null if nothing was captured
     *  (start() never succeeded, or stop() called with no prior successful start()). */
    fun stop(): ByteArray?

    /** Discards any in-flight capture without producing bytes. */
    fun cancel()
}

/** Thin adapter over `javax.sound.sampled.TargetDataLine` at [DICTATION_FORMAT]. Reads on a
 *  background thread into an internal buffer between [start]/[stop] so the caller never blocks on
 *  line I/O. NOT unit-tested directly — see this file's header + this milestone's Ground rules. */
internal class MicRecorder : MicCapture {
    private var line: TargetDataLine? = null
    private var reader: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private val capturing = AtomicBoolean(false)

    override fun start(): Boolean {
        val info = DataLine.Info(TargetDataLine::class.java, DICTATION_FORMAT)
        if (!AudioSystem.isLineSupported(info)) return false
        val l = try {
            (AudioSystem.getLine(info) as TargetDataLine).apply { open(DICTATION_FORMAT) }
        } catch (e: LineUnavailableException) {
            println("[MicRecorder] line unavailable: ${e.message}")
            return false
        } catch (e: SecurityException) {
            println("[MicRecorder] mic access denied: ${e.message}")
            return false
        }
        l.start()
        line = l
        buffer.reset()
        capturing.set(true)
        reader = thread(name = "dictation-mic-reader") {
            val chunk = ByteArray(4096)
            while (capturing.get()) {
                val n = l.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
            }
        }
        return true
    }

    override fun stop(): ByteArray? {
        val l = line ?: return null
        capturing.set(false)
        reader?.join(2000)
        reader = null
        l.stop(); l.close()
        line = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return if (pcm.isEmpty()) null else WavEncoder.encode(pcm, DICTATION_FORMAT)
    }

    override fun cancel() {
        val wasCapturing = capturing.get()
        capturing.set(false)
        reader?.join(2000)
        reader = null
        if (wasCapturing) { line?.stop(); line?.close() }
        line = null
        synchronized(buffer) { buffer.reset() }
    }
}

/**
 * Desktop's mic-dictation state machine: record -> POST to the broker's whisper transcribe
 * endpoint -> append the cleaned text to the composer draft. There is no on-device-STT path (see
 * this file's header) and no OS mic-permission prompt (unlike Android's RECORD_AUDIO flow) — a
 * failed [recorder] open surfaces as [micUnavailable], not a permission dialog.
 *
 * [recorder] is a [MicCapture] seam so this is unit-testable with a fake — no real audio hardware
 * needed. [transcribeAudio]/[onAppend] are REBOUND every recomposition by [rememberDesktopDictation]
 * (mirrors Android's `rememberDictation`), so they always close over the current session/draft even
 * though the controller instance itself is remembered once per composer.
 */
internal class DesktopDictationController(
    private val recorder: MicCapture,
    private val scope: CoroutineScope,
) {
    var recording by mutableStateOf(false); private set
    var transcribing by mutableStateOf(false); private set
    var micUnavailable by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null)

    var transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null }
    var onAppend: (String) -> Unit = {}

    fun startMic() {
        if (recording || transcribing) return
        errorMessage = null
        micUnavailable = false
        recording = recorder.start()
        if (!recording) micUnavailable = true
    }

    fun stopMic() {
        if (!recording) return
        recording = false
        val wav = recorder.stop()
        if (wav == null) {
            errorMessage = "Didn't catch that"
            return
        }
        scope.launch {
            transcribing = true
            try {
                val cleaned = transcribeAudio(wav, "dictation-${System.currentTimeMillis()}.wav")?.trim()
                if (!cleaned.isNullOrEmpty()) onAppend(cleaned) else errorMessage = "Transcription failed"
            } finally {
                transcribing = false
            }
        }
    }

    fun cancelMic() {
        val wasRecording = recording
        recording = false
        if (wasRecording) recorder.cancel()
    }
}

/** Remembers a [DesktopDictationController], rebinds its session-scoped closures every
 *  recomposition, and cancels any in-flight recording when [resetKey] changes (session switch in
 *  chat; a constant in the launcher — mirrors Android's `rememberDictation`'s KDoc guidance). */
@Composable
internal fun rememberDesktopDictation(
    resetKey: Any,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String?,
    onAppend: (String) -> Unit,
    recorderFactory: () -> MicCapture = { MicRecorder() },
): DesktopDictationController {
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopDictationController(recorderFactory(), scope) }
    controller.transcribeAudio = transcribeAudio
    controller.onAppend = onAppend
    DisposableEffect(resetKey) { onDispose { controller.cancelMic() } }
    return controller
}

/** Round mic control, 32dp visual inside a >=48dp IconButton tap target (matches the Attach/Send
 *  icons it sits alongside): grey Mic (idle, tap to start) -> red Stop (recording, tap to stop) ->
 *  a small spinner (transcribing, disabled) -> a MicOff glyph (line unavailable, disabled). */
@Composable
internal fun MicButton(
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
