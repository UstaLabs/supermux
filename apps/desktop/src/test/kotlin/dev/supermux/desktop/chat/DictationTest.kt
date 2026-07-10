package dev.supermux.desktop.chat

import javax.sound.sampled.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M5-1 Task 2: [WavEncoder] — pure RIFF/WAVE framing around raw little-endian PCM sample bytes, no
 * I/O. The mic device I/O itself ([MicRecorder], wrapping `javax.sound.sampled.TargetDataLine`) is
 * a thin, hardware-bound adapter with NO unit coverage — there is no mic under Xvfb/CI (see this
 * milestone's Ground rules); it is exercised only by Task 6's SM_DICTATE live-verify hook, which
 * feeds a real WAV through the transcribe path without ever opening a TargetDataLine.
 */
class DictationTest {

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun ascii(b: ByteArray, off: Int, len: Int): String = String(b, off, len, Charsets.US_ASCII)

    @Test fun encode_wraps_pcm_in_a_44_byte_canonical_wav_header() {
        val format = AudioFormat(16000f, 16, 1, true, false)
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) // 4 fake 16-bit mono samples

        val wav = WavEncoder.encode(pcm, format)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, le32(wav, 16))                    // PCM fmt chunk size
        assertEquals(1, le16(wav, 20))                     // PCM format tag
        assertEquals(1, le16(wav, 22))                     // channels
        assertEquals(16000, le32(wav, 24))                 // sample rate
        assertEquals(16000 * 1 * 16 / 8, le32(wav, 28))    // byte rate
        assertEquals(1 * 16 / 8, le16(wav, 32))            // block align
        assertEquals(16, le16(wav, 34))                    // bits per sample
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40))
        assertEquals(pcm.toList(), wav.copyOfRange(44, wav.size).toList())
    }

    @Test fun encode_handles_a_stereo_format() {
        val format = AudioFormat(44100f, 16, 2, true, false)
        val pcm = ByteArray(16) { it.toByte() }

        val wav = WavEncoder.encode(pcm, format)

        assertEquals(2, le16(wav, 22))
        assertEquals(44100, le32(wav, 24))
        assertEquals(44100 * 2 * 16 / 8, le32(wav, 28))
        assertEquals(2 * 16 / 8, le16(wav, 32))
    }

    @Test fun encode_of_empty_pcm_still_produces_a_valid_zero_length_data_chunk() {
        val format = AudioFormat(16000f, 16, 1, true, false)

        val wav = WavEncoder.encode(ByteArray(0), format)

        assertEquals(44, wav.size)
        assertEquals(0, le32(wav, 40))
    }
}
