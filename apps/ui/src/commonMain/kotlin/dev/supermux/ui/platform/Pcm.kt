// Raw 16-bit PCM helpers shared by the hosts that capture PCM themselves (desktop's TargetDataLine,
// Android's AudioRecord): the loudness the dictation controller watches for pauses, and the WAV
// framing that makes a mid-recording snapshot a file the broker can decode on its own.
package dev.supermux.ui.platform

import kotlin.math.sqrt

/** RMS of 16-bit signed little-endian mono samples in [pcm] [from, from+len), as 0..1. */
fun pcm16Rms(pcm: ByteArray, from: Int = 0, len: Int = pcm.size - from): Float {
    val samples = len / 2
    if (samples <= 0) return 0f
    var sum = 0.0
    var i = from
    repeat(samples) {
        val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0
        sum += v * v
        i += 2
    }
    return sqrt(sum / samples).toFloat()
}

/** [pcm] (16-bit signed little-endian, [channels] interleaved) behind a canonical 44-byte WAV header. */
fun wavFromPcm16(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val out = ByteArray(44 + pcm.size)
    var p = 0
    fun str(s: String) { for (c in s) out[p++] = c.code.toByte() }
    fun le32(v: Int) { out[p++] = v.toByte(); out[p++] = (v shr 8).toByte(); out[p++] = (v shr 16).toByte(); out[p++] = (v shr 24).toByte() }
    fun le16(v: Int) { out[p++] = v.toByte(); out[p++] = (v shr 8).toByte() }
    val blockAlign = channels * 2
    str("RIFF"); le32(36 + pcm.size); str("WAVE")
    str("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate)
    le32(sampleRate * blockAlign); le16(blockAlign); le16(16)
    str("data"); le32(pcm.size)
    pcm.copyInto(out, p)
    return out
}
