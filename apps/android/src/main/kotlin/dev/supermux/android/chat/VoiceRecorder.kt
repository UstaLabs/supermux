package dev.supermux.android.chat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.supermux.android.platform.PcmRecorder
import dev.supermux.ui.platform.pcm16Rms
import dev.supermux.ui.platform.wavFromPcm16
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The production [PcmRecorder]: `AudioRecord` at 16 kHz mono 16-bit, read on a background thread
 * into memory and framed as WAV. It used to be `MediaRecorder` → AAC `.m4a`, but an MPEG-4 file is
 * unreadable until the recorder writes its index at stop, and the live dictation passes need the
 * audio so far mid-recording. 16 kHz mono PCM is 32 KB/s — two minutes is ~3.8 MB.
 */
class VoiceRecorder : PcmRecorder {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private val capturing = AtomicBoolean(false)
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> get() = _level

    // RECORD_AUDIO is granted before start() is reached: the dictation controller asks first.
    @SuppressLint("MissingPermission")
    override fun start() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, READ_BYTES * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("AudioRecord failed to initialise")
        }
        r.startRecording()
        record = r
        synchronized(buffer) { buffer.reset() }
        _level.value = 0f
        capturing.set(true)
        reader = thread(name = "dictation-mic-reader") {
            // 50 ms of audio per read, so the level the dictation controller samples is fresh.
            val chunk = ByteArray(READ_BYTES)
            while (capturing.get()) {
                val n = r.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                    _level.value = pcm16Rms(chunk, 0, n)
                }
            }
        }
    }

    override fun stop(): ByteArray? {
        val r = record ?: return null
        halt(r)
        val pcm = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
        return if (pcm.isEmpty()) null else wavFromPcm16(pcm, SAMPLE_RATE)
    }

    override fun snapshot(): ByteArray? {
        if (!capturing.get()) return null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return if (pcm.isEmpty()) null else wavFromPcm16(pcm, SAMPLE_RATE)
    }

    override fun cancel() {
        record?.let { halt(it) }
        synchronized(buffer) { buffer.reset() }
    }

    private fun halt(r: AudioRecord) {
        capturing.set(false)
        reader?.join(2000)
        reader = null
        runCatching { r.stop() }
        r.release()
        record = null
        _level.value = 0f
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        /** 50 ms at 16 kHz mono 16-bit. */
        const val READ_BYTES = 1600
    }
}
