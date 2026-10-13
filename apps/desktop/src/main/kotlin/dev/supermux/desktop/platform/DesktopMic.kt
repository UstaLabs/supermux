// Desktop mic dictation (M5-1) — record -> POST to the broker's /transcribe STT engine -> append the
// cleaned text to the composer draft, with live partial passes at each pause (the reader thread
// meters the level; `snapshot` WAV-frames the PCM so far). Desktop has no on-device ASR, so it is
// always in the broker dictation mode.
//
// Two layers in this file (D3 moved the state machine and the MicButton into `:ui`; what is left is
// the desktop actual behind `Platform.mic`):
//   - WavEncoder (pure): raw PCM bytes -> a canonical 44-byte-header WAV. No I/O — fully unit
//     tested (DictationTest.kt).
//   - MicCapture/MicRecorder: a seam interface + the real javax.sound.sampled.TargetDataLine
//     adapter. MicRecorder is the ONE genuinely untestable-without-hardware piece (no mic under
//     Xvfb/CI); MicCapture exists so DesktopMicCapture never depends on it directly.
//
// There is no RECORD_AUDIO permission model on desktop (the OS grants mic access at the process
// level) and no on-device partial transcript — a failed line-open surfaces as micUnavailable in the
// shared dictation UI.
package dev.supermux.desktop.platform

import dev.supermux.ui.platform.pcm16Rms
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** Desktop mic dictation format: 16kHz mono 16-bit signed little-endian PCM — the standard input
 *  rate for whisper-family ASR, and the WAV every client sends: the broker reads WAV in-process and
 *  decodes nothing else (no ffmpeg on the host). */
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

/** True when 16-bit PCM holds only exact zeros — what a TCC-denied mic delivers. Real room noise
 *  never stays at 0, so this cannot swallow a quiet speaker. */
internal fun isDigitalSilence(pcm: ByteArray): Boolean = pcm.all { it == 0.toByte() }

/** Seam over the raw mic capture backend so [DesktopMicCapture] is unit-testable without real audio
 *  hardware — [MicRecorder] is the production implementation, tests inject a fake. Distinct from the
 *  shared `dev.supermux.ui.platform.MicCapture`, which is what the dictation UI actually talks to:
 *  this one deals in raw WAV bytes, that one in [dev.supermux.ui.platform.CapturedAudio]. */
internal interface RawMicCapture {
    /** Opens the mic and starts capturing. Returns true if capture is now active, false if the mic
     *  could not be opened (no default line, in use by another app, unsupported format, etc). */
    fun start(): Boolean

    /** Stops capture and returns the recorded audio as WAV bytes, or null if nothing was captured
     *  (start() never succeeded, or stop() called with no prior successful start()). */
    fun stop(): ByteArray?

    /** Discards any in-flight capture without producing bytes. */
    fun cancel()

    /** The capture so far as WAV bytes, without stopping; null when nothing is captured yet. */
    fun snapshot(): ByteArray? = null

    /** Loudness of the latest frames, 0..1; null when this backend cannot meter. */
    val level: StateFlow<Float>? get() = null
}

/** Thin adapter over `javax.sound.sampled.TargetDataLine` at [DICTATION_FORMAT]. Reads on a
 *  background thread into an internal buffer between [start]/[stop] so the caller never blocks on
 *  line I/O. NOT unit-tested directly — see this file's header + this milestone's Ground rules. */
internal class MicRecorder : RawMicCapture {
    private var line: TargetDataLine? = null
    private var reader: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private val capturing = AtomicBoolean(false)
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> get() = _level

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
        _level.value = 0f
        reader = thread(name = "dictation-mic-reader") {
            // 50 ms of audio per read, so the level the dictation controller samples is fresh.
            val chunk = ByteArray(READ_BYTES)
            while (capturing.get()) {
                val n = l.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                    _level.value = pcm16Rms(chunk, 0, n)
                }
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
        if (pcm.isEmpty()) return null
        if (isDigitalSilence(pcm)) {
            // A denied mic on macOS is not an exception — the line opens and reads zeros. Sending that
            // makes the STT model hallucinate a whole sentence, so report "nothing captured" instead.
            println("[MicRecorder] captured only digital silence — microphone access is probably denied " +
                "(System Settings → Privacy & Security → Microphone)")
            return null
        }
        return WavEncoder.encode(pcm, DICTATION_FORMAT)
    }

    override fun snapshot(): ByteArray? {
        if (!capturing.get()) return null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.isEmpty() || isDigitalSilence(pcm)) return null
        return WavEncoder.encode(pcm, DICTATION_FORMAT)
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

    private companion object {
        /** 50 ms at 16 kHz mono 16-bit. */
        const val READ_BYTES = 1600
    }
}
