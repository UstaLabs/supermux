package dev.supermux.ui.chat

import dev.supermux.ui.platform.TtsEngine
import dev.supermux.ui.work.AudioDockTest
import dev.supermux.work.AudioItem
import dev.supermux.work.AudioPlayer
import dev.supermux.work.DownloadOrigin
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [TtsEngine] that records the ORDER of what it was asked to do. */
private class RecordingTts : TtsEngine {
    val events = mutableListOf<String>()

    /** Speech (and each chunk) lasts until [stop], as a real voice does, instead of ending at once. */
    var hold = false
    private var gate = CompletableDeferred<Unit>()

    override suspend fun speak(text: String) {
        events.add("speak:$text")
        if (hold) gate.await()
    }
    override suspend fun playAudioChunk(bytes: ByteArray) {
        events.add("chunk:${bytes.size}")
        if (hold) gate.await()
    }
    override fun stop() {
        events.add("stop")
        gate.complete(Unit)
        gate = CompletableDeferred()
    }
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

    // ── read-aloud through the app's player (#118 car 2) ───────────────────────────────────────

    private val origin = DownloadOrigin("s1", "m7")
    private val note = AudioItem("voice-note.m4a", "/cache/voice-note.m4a", origin, chat = "demo-day-recap", key = "att:h/1")

    @Test fun theOsVoiceIsThePlayersItemWithItsChat() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "read me please", player, origin, "demo-day-recap")
        testScheduler.advanceUntilIdle()

        val now = assertNotNull(player.state.value, "reading is what plays")
        assertEquals("Read aloud · read me please", now.item.title)
        assertEquals(origin, now.item.origin)
        assertEquals("demo-day-recap", now.item.chat)
        assertTrue(now.stopOnly, "the OS voice has no pause, seek or speed")
        assertTrue(MessageTts.isSpeaking("read me please"))
        assertTrue(tts.events.contains("speak:read me please"), "got ${tts.events}")
    }

    @Test fun aLongMessageIsNamedByItsFirstWords() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "Here is the plan for the demo on Friday and the week after it", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        val title = assertNotNull(player.state.value).item.title
        assertTrue(title.startsWith("Read aloud · Here is the plan"), title)
        assertTrue(title.endsWith("…"), title)
        assertTrue(title.length <= 50, title)
    }

    @Test fun thePlayersCrossStopsTheVoice() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        tts.events.clear()

        player.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("stop"), tts.events, "✕ silences the engine")
        assertFalse(MessageTts.isSpeaking("read me please"), "the row's 🔊 follows the player")
        assertNull(player.state.value)
    }

    @Test fun playingAnAttachmentStopsReadAloud() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        tts.events.clear()

        player.play(note)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("stop"), tts.events)
        assertFalse(MessageTts.isSpeaking("read me please"))
        assertEquals(note, player.state.value?.item)
        assertEquals(listOf("open /cache/voice-note.m4a", "play"), outputs.made.single().calls, "the note plays on")
    }

    @Test fun readAloudStopsAPlayingAttachment() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs).apply { play(note) }
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        assertEquals("release", outputs.made.single().calls.last(), "one thing plays at a time")
        assertEquals("Read aloud · read me please", player.state.value?.item?.title)
    }

    @Test fun theEndOfTheVoiceEmptiesThePlayer() = runTest(dispatcher) {
        val tts = RecordingTts()
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        assertNull(player.state.value)
        assertFalse(MessageTts.isSpeaking("read me please"))
    }

    @Test fun theRowsSpeakerStopsThePlayersReading() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        assertNull(player.state.value)
        assertFalse(MessageTts.isSpeaking("read me please"))
    }

    /** The codex voice's progressive URL plays in the app's player: ❚❚, seek and speed as an attachment. */
    @Test fun theCodexUrlPlaysThroughThePlayersOutput() = runTest(dispatcher) {
        val tts = RecordingTts()
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, _ -> }
        MessageTts.speakRemoteUrl = { "http://h/speak/audio/x.mp3" }
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()

        assertTrue(tts.events.none { it.startsWith("url:") }, "not the engine's own player: ${tts.events}")
        val now = assertNotNull(player.state.value)
        assertEquals("Read aloud · read me please", now.item.title)
        assertFalse(now.stopOnly)
        val out = outputs.made.single()
        assertEquals(listOf("open http://h/speak/audio/x.mp3", "play"), out.calls)
        assertTrue(MessageTts.isSpeaking("read me please"))

        player.toggle()
        player.seek(5_000)
        player.setSpeed(1.5f)
        assertEquals(listOf("pause", "seek 5000", "rate 1.5"), out.calls.drop(2))
        assertTrue(MessageTts.isSpeaking("read me please"), "paused is still this message's reading")

        out.events.onEnded()
        testScheduler.advanceUntilIdle()
        assertNull(player.state.value)
        assertFalse(MessageTts.isSpeaking("read me please"), "the row's 🔊 follows the player")
    }

    @Test fun anAttachmentReplacesTheCodexUrl() = runTest(dispatcher) {
        val tts = RecordingTts()
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, _ -> }
        MessageTts.speakRemoteUrl = { "http://h/speak/audio/x.mp3" }
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        player.play(note)
        testScheduler.advanceUntilIdle()
        assertEquals("release", outputs.made[0].calls.last())
        assertFalse(MessageTts.isSpeaking("read me please"))
    }

    /** iOS and the web stop read-aloud when the app goes to the background: the player's reading stops too. */
    @Test fun stoppingReadAloudStopsThePlayersReading() = runTest(dispatcher) {
        val tts = RecordingTts()
        val outputs = AudioDockTest.FakeOutputs()
        val player = AudioPlayer(outputs)
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, _ -> }
        MessageTts.speakRemoteUrl = { "http://h/speak/audio/x.mp3" }
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        MessageTts.stop(tts)
        assertNull(player.state.value)
        assertEquals("release", outputs.made.single().calls.last())
    }

    @Test fun stoppingReadAloudLeavesAnAttachmentPlaying() = runTest(dispatcher) {
        val tts = RecordingTts()
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        testScheduler.advanceUntilIdle()
        player.play(note)
        MessageTts.stop(tts)
        assertEquals(note, player.state.value?.item)
    }

    /** The chunk stream (an older broker) is the player's item too, with ✕ only. */
    @Test fun theCodexChunksAreAStopOnlyItem() = runTest(dispatcher) {
        val tts = RecordingTts().apply { hold = true }
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        MessageTts.resolveEngine = { "codex" }
        MessageTts.speakRemoteStream = { _, onChunk -> onChunk(byteArrayOf(1, 2)) }
        MessageTts.speakRemoteUrl = { null }
        MessageTts.toggle(tts, "read me please", player, origin, "chat")
        repeat(100) {
            testScheduler.advanceUntilIdle()
            if (tts.events.contains("chunk:2")) return@repeat
            Thread.sleep(10)
        }
        val now = assertNotNull(player.state.value)
        assertTrue(now.stopOnly)
        tts.events.clear()
        player.stop()
        assertTrue(tts.events.contains("stop"), "got ${tts.events}")
    }
}
