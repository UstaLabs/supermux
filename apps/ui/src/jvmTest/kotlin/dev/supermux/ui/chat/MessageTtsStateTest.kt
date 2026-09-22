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
        assertEquals("stop", tts.events.first(), "the previous utterance must be stopped first")
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
}
