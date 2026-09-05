package dev.supermux.android.platform

import dev.supermux.ui.platform.CapturedAudio
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-free half of the cluster-D Android seams: the TTS sequencing, the mic's captured-audio
 * shape and the clipboard's image filter. Everything here is driven through the seam interfaces
 * ([TtsBackend], [AudioChunkPlayer], [AudioFileRecorder]) precisely so it needs no `TextToSpeech`,
 * `MediaRecorder` or `ContentResolver` — none of which exist in a JVM unit test.
 */
class AndroidChatSeamsTest {

    // ── AndroidTtsEngine ─────────────────────────────────────────────────────

    /** A backend that hands back the completion callback so a test decides when speech ends. */
    private class FakeTtsBackend : TtsBackend {
        val spoken = mutableListOf<String>()
        var stops = 0
        var shutdowns = 0
        var pendingDone: (() -> Unit)? = null

        override fun speak(text: String, utteranceId: String, onDone: () -> Unit) {
            spoken.add("$utteranceId:$text")
            pendingDone = onDone
        }

        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }

        /** Fire the utterance-finished callback the real engine would deliver. */
        fun finish() { pendingDone?.invoke(); pendingDone = null }
    }

    private class FakePlayer : AudioChunkPlayer {
        val played = mutableListOf<Int>()
        var stops = 0
        override suspend fun play(bytes: ByteArray) { played.add(bytes.size) }
        override fun stop() { stops++ }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `speak suspends until the engine reports the utterance done`() = runTest {
        val backend = FakeTtsBackend()
        val engine = AndroidTtsEngine(backend, FakePlayer())
        val finished = CompletableDeferred<Unit>()
        val job = launch {
            engine.speak("hello")
            finished.complete(Unit)
        }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("msg-1:hello"), backend.spoken)
        assertFalse(finished.isCompleted, "speak must not return before the engine is done")

        backend.finish()
        testScheduler.advanceUntilIdle()
        assertTrue(finished.isCompleted)
        job.join()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stop silences the engine and resumes the waiting speak`() = runTest {
        val backend = FakeTtsBackend()
        val player = FakePlayer()
        val engine = AndroidTtsEngine(backend, player)
        val job = launch { engine.speak("a long message") }
        testScheduler.advanceUntilIdle()

        engine.stop()
        testScheduler.advanceUntilIdle()

        assertEquals(1, backend.stops)
        assertEquals(1, player.stops)
        assertTrue(job.isCompleted, "a stopped utterance must not leave its caller suspended")
    }

    @Test
    fun `blank text is never handed to the engine`() = runTest {
        val backend = FakeTtsBackend()
        AndroidTtsEngine(backend, FakePlayer()).speak("   ")
        assertTrue(backend.spoken.isEmpty())
    }

    @Test
    fun `audio chunks go to the player and shutdown stops then releases`() = runTest {
        val backend = FakeTtsBackend()
        val player = FakePlayer()
        val engine = AndroidTtsEngine(backend, player)

        engine.playAudioChunk(byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf(4), player.played)

        engine.shutdown()
        assertEquals(1, backend.stops)
        assertEquals(1, player.stops)
        assertEquals(1, backend.shutdowns)
    }

    // ── AndroidMicCapture ────────────────────────────────────────────────────

    private class FakeRecorder(private val produce: () -> File?) : AudioFileRecorder {
        var started = false
        var cancelled = false
        override fun start() { started = true }
        override fun stop(): File? = produce()
        override fun cancel() { cancelled = true }
    }

    private fun micOver(produce: () -> File?) = AndroidMicCapture(
        recorder = FakeRecorder(produce),
        available = true,
        liveTranscript = null,
        onRequestPermission = { true },
    )

    @Test
    fun `stop returns m4a audio-mp4 bytes and deletes the recording`() {
        val file = File.createTempFile("voice-", ".m4a")
        file.writeBytes(byteArrayOf(7, 7, 7))
        val captured = micOver { file }.stop()

        assertEquals(
            CapturedAudio(byteArrayOf(7, 7, 7), file.name, "audio/mp4"),
            captured,
        )
        assertTrue(captured!!.filename.endsWith(".m4a"))
        assertEquals("audio/mp4", captured.mime)
        assertFalse(file.exists(), "the cache recording must not outlive the capture")
    }

    @Test
    fun `stop returns null when nothing was recorded or the file is empty`() {
        assertNull(micOver { null }.stop())

        val empty = File.createTempFile("voice-", ".m4a")
        try {
            assertNull(micOver { empty }.stop(), "an empty capture is not audio")
        } finally {
            empty.delete()
        }
    }

    @Test
    fun `a recorder that throws on start surfaces as mic unavailable, not a crash`() {
        val mic = AndroidMicCapture(
            recorder = object : AudioFileRecorder {
                override fun start(): Unit = throw IllegalStateException("no mic")
                override fun stop(): File? = null
                override fun cancel() = Unit
            },
            available = true,
            liveTranscript = null,
            onRequestPermission = { true },
        )
        assertFalse(mic.start())
    }

    // ── clipboard image filter ───────────────────────────────────────────────

    @Test
    fun `only clip entries whose resolved type is an image are staged`() {
        val types = mapOf(
            "shot.png" to "image/png",
            "clip.mp4" to "video/mp4",
            "notes.txt" to "text/plain",
            "photo.jpg" to "image/jpeg",
            "mystery" to null,
        )
        assertEquals(
            listOf("shot.png", "photo.jpg"),
            imageEntries(types.keys.toList()) { types[it] },
        )
    }

    @Test
    fun `an empty clip stages nothing`() {
        assertTrue(imageEntries(emptyList<String>()) { "image/png" }.isEmpty())
    }

    // ── file names ───────────────────────────────────────────────────────────

    @Test
    fun `attachment names are stripped to a bare file name`() {
        assertEquals("b.txt", safeFileName("a/b.txt"))
        assertEquals("file", safeFileName(""))
        assertEquals("file", safeFileName("dir/"))
        assertEquals("c.png", safeFileName("x\\c.png"))
    }
}
