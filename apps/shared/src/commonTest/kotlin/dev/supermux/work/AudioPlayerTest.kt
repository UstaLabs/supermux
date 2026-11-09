package dev.supermux.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The app's audio player (#118) against a fake output: one item at a time, its state, and what it tells the output. */
class AudioPlayerTest {
    /** Every output the player made, each with what it was told in order and its line back. */
    private class FakeOutputs : AudioOutputs {
        val made = mutableListOf<FakeOutput>()
        var closed = false

        override fun create(events: AudioOutput.Events): AudioOutput = FakeOutput(events).also { made += it }

        override fun close() {
            closed = true
        }
    }

    private class FakeOutput(val events: AudioOutput.Events) : AudioOutput {
        val calls = mutableListOf<String>()
        val released get() = "release" in calls

        override fun open(uri: String) { calls += "open $uri" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun setRate(rate: Float) { calls += "rate $rate" }
        override fun release() { calls += "release" }
    }

    private val origin = DownloadOrigin("s1", "m7")
    private val note = AudioItem("voice-note.m4a", "/cache/voice-note.m4a", origin, chat = "demo-day-recap", key = "att:h/1", durationMs = 130_000)
    private val other = AudioItem("other.mp3", "/cache/other.mp3")

    private fun player(): Pair<AudioPlayer, FakeOutputs> {
        val outputs = FakeOutputs()
        return AudioPlayer(outputs) to outputs
    }

    @Test fun idleUntilSomethingPlays() {
        val (p, outputs) = player()
        assertNull(p.state.value)
        assertTrue(outputs.made.isEmpty(), "no output is made before anything plays")
    }

    @Test fun playOpensTheSourceAndPlaysFromTheStart() {
        val (p, outputs) = player()
        p.play(note)
        val now = assertNotNull(p.state.value)
        assertSame(note, now.item)
        assertTrue(now.playing)
        assertEquals(0, now.positionMs)
        assertEquals(130_000, now.durationMs, "the sender's length until the player knows")
        assertEquals(1f, now.speed)
        assertEquals(listOf("open /cache/voice-note.m4a", "play"), outputs.made.single().calls)
    }

    @Test fun progressMovesThePositionAndLearnsTheLength() {
        val (p, outputs) = player()
        p.play(note)
        outputs.made.single().events.onProgress(42_000, 131_500)
        val now = assertNotNull(p.state.value)
        assertEquals(42_000, now.positionMs)
        assertEquals(131_500, now.durationMs)
        // A length not known yet (0) keeps what was known.
        outputs.made.single().events.onProgress(43_000, 0)
        assertEquals(131_500, p.state.value?.durationMs)
    }

    @Test fun togglePausesAndPlays() {
        val (p, outputs) = player()
        p.play(note)
        p.toggle()
        assertFalse(assertNotNull(p.state.value).playing)
        p.toggle()
        assertTrue(assertNotNull(p.state.value).playing)
        assertEquals(listOf("open /cache/voice-note.m4a", "play", "pause", "play"), outputs.made.single().calls)
    }

    @Test fun anOutputThatStopsOnItsOwnIsHeard() {
        val (p, outputs) = player()
        p.play(note)
        outputs.made.single().events.onPlayingChanged(false)
        assertFalse(assertNotNull(p.state.value).playing)
    }

    @Test fun seekMovesThereWithinTheItem() {
        val (p, outputs) = player()
        p.play(note)
        p.seek(60_000)
        assertEquals(60_000, p.state.value?.positionMs)
        p.seek(-5)
        assertEquals(0, p.state.value?.positionMs)
        p.seek(999_000)
        assertEquals(130_000, p.state.value?.positionMs)
        assertEquals(listOf("seek 60000", "seek 0", "seek 130000"), outputs.made.single().calls.filter { it.startsWith("seek") })
    }

    @Test fun speedGoesOneOneAndAHalfTwoAndRoundAgain() {
        val (p, outputs) = player()
        p.play(note)
        p.nextSpeed()
        assertEquals(1.5f, p.state.value?.speed)
        p.nextSpeed()
        assertEquals(2f, p.state.value?.speed)
        p.nextSpeed()
        assertEquals(1f, p.state.value?.speed)
        assertEquals(listOf("rate 1.5", "rate 2.0", "rate 1.0"), outputs.made.single().calls.filter { it.startsWith("rate") })
    }

    @Test fun theNextItemKeepsTheSpeed() {
        val (p, outputs) = player()
        p.play(note)
        p.setSpeed(1.5f)
        p.play(other)
        assertEquals(1.5f, p.state.value?.speed)
        assertEquals(listOf("open /cache/other.mp3", "rate 1.5", "play"), outputs.made[1].calls)
    }

    @Test fun stopReleasesTheOutputAndGoesIdle() {
        val (p, outputs) = player()
        p.play(note)
        p.stop()
        assertNull(p.state.value)
        assertTrue(outputs.made.single().released)
        // Nothing to toggle, seek or speed up once idle.
        p.toggle()
        p.seek(1_000)
        p.nextSpeed()
        assertNull(p.state.value)
        assertEquals("release", outputs.made.single().calls.last())
    }

    @Test fun aNewPlayReplacesTheOld() {
        val (p, outputs) = player()
        p.play(note)
        p.play(other)
        assertSame(other, p.state.value?.item)
        assertEquals(2, outputs.made.size, "one output per item")
        assertTrue(outputs.made[0].released, "the old item stops")
        assertFalse(outputs.made[1].released)
        // The old output's late words are not the new item's.
        outputs.made[0].events.onProgress(99_000, 130_000)
        outputs.made[0].events.onEnded()
        assertEquals(0, p.state.value?.positionMs)
        assertSame(other, p.state.value?.item)
    }

    @Test fun theEndGoesIdle() {
        val (p, outputs) = player()
        p.play(note)
        outputs.made.single().events.onEnded()
        assertNull(p.state.value)
        assertTrue(outputs.made.single().released)
    }

    @Test fun anErrorGoesIdleAndSaysWhy() {
        val (p, outputs) = player()
        p.play(note)
        outputs.made.single().events.onError("Can't decode")
        assertNull(p.state.value)
        assertEquals("Can't decode", p.error)
        p.play(other)
        assertNull(p.error, "a new item starts clean")
    }

    @Test fun withoutOutputsNothingPlaysUntilTheShellInstallsThem() {
        val p = AudioPlayer()
        p.play(note)
        assertNull(p.state.value)
        val outputs = FakeOutputs()
        p.useOutputs(outputs)
        p.play(note)
        assertNotNull(p.state.value)
    }

    @Test fun closeStopsAndLetsTheOutputsGo() {
        val (p, outputs) = player()
        p.play(note)
        p.close()
        assertNull(p.state.value)
        assertTrue(outputs.made.single().released)
        assertTrue(outputs.closed)
    }
}
