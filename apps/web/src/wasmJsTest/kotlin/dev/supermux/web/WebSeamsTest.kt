package dev.supermux.web

import dev.supermux.web.seams.WebClipboard
import dev.supermux.web.seams.WebMic
import dev.supermux.web.seams.WebTts
import dev.supermux.web.seams.dictationExtensionFor
import dev.supermux.web.seams.pastedExtensionFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan-3 chat seams, in a real browser (headless Chrome via Karma).
 *
 * What CAN be asserted here is the shape a unit test can reach without a human: the capability
 * probes, the pure mime→extension mapping, the "no permission granted" degradations, and — the one
 * genuinely end-to-end piece — that [WebTts.playAudioChunk] actually decodes real encoded audio
 * through `AudioContext` and resumes its caller when the node finishes. A recording needs a mic
 * gesture, so `WebMic.start()` is only covered on the refusal path; the browser run against the
 * hermetic broker (`--use-fake-device-for-media-stream`) covers the happy one.
 */
class WebSeamsTest {

    // ── capability probes ───────────────────────────────────────────────────────────────────

    @Test fun clipboard_reports_images_when_the_async_clipboard_api_exists() {
        // Karma serves over http://localhost, which IS a secure context, so Chrome exposes
        // `navigator.clipboard.read`. The probe is a capability check by design — see WebClipboard.
        assertTrue(WebClipboard.hasImage(), "navigator.clipboard.read missing in the test browser")
    }

    @Test fun clipboard_read_without_permission_degrades_to_an_empty_list() = runTest {
        // Headless Chrome denies clipboard-read without a gesture; the seam's contract is an empty
        // list on any failure, never a throw and never a hang.
        assertEquals(emptyList(), WebClipboard.readImages())
    }

    @Test fun mic_is_available_when_the_browser_has_getUserMedia_and_MediaRecorder() {
        assertTrue(WebMic.available, "getUserMedia/MediaRecorder missing in the test browser")
    }

    @Test fun mic_start_fails_without_a_granted_stream() {
        // No permission has been requested in this test, so there is no MediaStream to record.
        assertFalse(WebMic.start())
        assertEquals(null, WebMic.stop())
    }

    // ── pure mime → extension mapping ───────────────────────────────────────────────────────

    @Test fun dictation_extension_follows_the_recorded_container() {
        assertEquals("webm", dictationExtensionFor("audio/webm;codecs=opus"))
        assertEquals("webm", dictationExtensionFor("audio/webm"))
        assertEquals("mp4", dictationExtensionFor("audio/mp4;codecs=mp4a.40.2"))
        assertEquals("ogg", dictationExtensionFor("audio/ogg;codecs=opus"))
        assertEquals("wav", dictationExtensionFor("audio/wav"))
        assertEquals("webm", dictationExtensionFor(""))
    }

    @Test fun pasted_extension_follows_the_clipboard_flavour() {
        assertEquals("png", pastedExtensionFor("image/png"))
        assertEquals("jpg", pastedExtensionFor("image/jpeg"))
        assertEquals("gif", pastedExtensionFor("image/gif"))
        assertEquals("webp", pastedExtensionFor("image/webp"))
        assertEquals("svg", pastedExtensionFor("image/svg+xml"))
        assertEquals("png", pastedExtensionFor(""))
    }

    // ── read-aloud playback ─────────────────────────────────────────────────────────────────

    @Test fun play_audio_chunk_decodes_a_real_clip_and_resumes_when_it_finishes() = runTest {
        // A 20 ms silent 8 kHz mono WAV — small, valid, and decodable by every browser, which is
        // what this asserts: the call returns only after the source node's `onended`.
        WebTts.playAudioChunk(silentWav(millis = 20))
        WebTts.stop()
    }

    @Test fun play_audio_chunk_resolves_instead_of_throwing_on_bytes_that_will_not_decode() = runTest {
        // One bad chunk must skip a sentence, not kill the read-aloud queue.
        WebTts.playAudioChunk(ByteArray(64) { 0x7F })
    }

    /** Canonical 44-byte-header PCM WAV, 8 kHz mono 16-bit, [millis] of silence. */
    private fun silentWav(millis: Int): ByteArray {
        val rate = 8000
        val samples = rate * millis / 1000
        val dataSize = samples * 2
        val out = ArrayList<Byte>(44 + dataSize)
        fun ascii(s: String) = s.forEach { out.add(it.code.toByte()) }
        fun le32(v: Int) { for (i in 0 until 4) out.add(((v shr (8 * i)) and 0xFF).toByte()) }
        fun le16(v: Int) { for (i in 0 until 2) out.add(((v shr (8 * i)) and 0xFF).toByte()) }
        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        ascii("data"); le32(dataSize)
        repeat(dataSize) { out.add(0) }
        return out.toByteArray()
    }
}
