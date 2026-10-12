package dev.supermux.ui.chat

import dev.supermux.ui.platform.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [TtsEngine] that records the ORDER of what it was asked to do. */
private class RecordingTts : TtsEngine {
    val events = mutableListOf<String>()
    override suspend fun speak(text: String) { events.add("speak:$text") }
    override suspend fun playAudioChunk(bytes: ByteArray) { events.add("chunk:${bytes.size}") }
    override fun stop() { events.add("stop") }
    override fun shutdown() { events.add("shutdown") }
    var urlCapable = false
    override val canPlayUrl: Boolean get() = urlCapable
    override suspend fun playUrl(url: String) { events.add("url:$url") }
    override fun prepareForTap() { events.add("prepare") }
}

/**
 * [MessageTts]'s sequencing. It is process-wide by design (read-aloud has to survive an Android
 * activity recreation) and speaks on its own `Dispatchers.Main` scope, so these install a test main
 * dispatcher and drain it rather than racing the coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageTtsStateTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest fun silence() {
        MessageTts.resolveEngine = null
        MessageTts.speakRemoteStream = null
        MessageTts.speakRemoteUrl = null
        MessageTts.stop(RecordingTts())
        Dispatchers.resetMain()
    }

    /**
     * REGRESSION: the engine is SILENCED before the next utterance starts. Android's
     * `TextToSpeech` QUEUE_FLUSH hides this — desktop's engine is a child `say`/`ffplay` process
     * that keeps running until it is killed, so without the leading stop two messages overlap.
     */
    @Test fun speaking_stops_the_previous_utterance_first() = runTest(dispatcher) {
        val tts = RecordingTts()
        MessageTts.toggle(tts, "a message")
        testScheduler.advanceUntilIdle()

        assertTrue(tts.events.isNotEmpty(), "toggle should have driven the engine")
        // `prepare` is the synchronous gesture hook and makes no noise; the order that matters
        // is everything after it.
        assertEquals("stop", tts.events.first { it != "prepare" }, "the previous utterance must be stopped first")
        assertTrue(
            tts.events.contains("speak:a message"),
            "the message should then be spoken, got ${tts.events}",
        )
        assertTrue(
            tts.events.indexOf("stop") < tts.events.indexOf("speak:a message"),
            "stop must come BEFORE speak, got ${tts.events}",
        )
    }

    @Test fun blank_after_flattening_never_speaks() = runTest(dispatcher) {
        val tts = RecordingTts()
        MessageTts.toggle(tts, "```\ncode only\n```")
        testScheduler.advanceUntilIdle()
        assertTrue(tts.events.none { it.startsWith("speak") }, "got ${tts.events}")
    }

    /** The codex voice plays ONE progressive URL when the engine can, never the chunk stream. */
    @Test fun codex_plays_the_progressive_url_when_the_engine_can() = runTest(dispatcher) {
        val tts = RecordingTts().apply { urlCapable = true }
        var streamed = false
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, _ -> streamed = true }
        MessageTts.speakRemoteUrl = { "http://h/speak/audio/x.mp3" }
        MessageTts.toggle(tts, "read me")
        testScheduler.advanceUntilIdle()

        assertEquals("prepare", tts.events.first(), "the gesture hook must run before anything suspends")
        assertTrue(tts.events.contains("url:http://h/speak/audio/x.mp3"), "got ${tts.events}")
        assertTrue(!streamed, "the chunk stream must not also run")
        assertEquals(null, MessageTts.speakingKey, "the marker clears when the URL finishes")
    }

    /** A broker without `/speak/start` (null) falls back to the chunk stream. */
    @Test fun codex_falls_back_to_chunks_on_an_older_broker() = runTest(dispatcher) {
        val tts = RecordingTts().apply { urlCapable = true }
        var streamed = false
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, onChunk -> streamed = true; onChunk(byteArrayOf(1, 2)) }
        MessageTts.speakRemoteUrl = { null }
        MessageTts.toggle(tts, "read me")
        // The producer runs on Dispatchers.Default, outside the test scheduler.
        repeat(100) {
            testScheduler.advanceUntilIdle()
            if (tts.events.contains("chunk:2")) return@repeat
            Thread.sleep(10)
        }
        assertTrue(streamed, "fell back to the stream")
        assertTrue(tts.events.none { it.startsWith("url:") }, "got ${tts.events}")
        assertTrue(tts.events.contains("chunk:2"), "got ${tts.events}")
    }
}
