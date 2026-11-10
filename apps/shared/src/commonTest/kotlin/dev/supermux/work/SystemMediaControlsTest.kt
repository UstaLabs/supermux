package dev.supermux.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system's Now Playing (#118 cars 3b/3c) against a fake system: what it is shown of the player's state, and what
 * its commands (the lock screen, a headset, the media keys) do to the player.
 */
class SystemMediaControlsTest {
    private class FakeOutput : AudioOutput {
        val calls = mutableListOf<String>()
        override fun open(uri: String) { calls += "open $uri" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun setRate(rate: Float) { calls += "rate $rate" }
        override fun release() { calls += "release" }
    }

    private class FakeSystem : SystemNowPlaying {
        val shown = mutableListOf<NowPlayingInfo>()
        var clears = 0
        var commands: ((MediaCommand) -> Boolean)? = null

        override fun show(info: NowPlayingInfo, commands: (MediaCommand) -> Boolean) {
            shown += info
            this.commands = commands
        }

        override fun clear() {
            clears++
            commands = null
        }
    }

    private val note = AudioItem("voice-note.m4a", "/cache/voice-note.m4a", chat = "demo-day-recap", durationMs = 130_000)
    private val events = mutableListOf<AudioOutput.Events>()
    private val outputs = mutableListOf<FakeOutput>()
    private val player = AudioPlayer(AudioOutputs { e -> events += e; FakeOutput().also { outputs += it } })
    private var clock = 0L
    private val system = FakeSystem()
    private val controls = SystemMediaControls(player, system) { clock }

    private fun sync() = controls.update(player.state.value)

    @Test fun anItemPlayingIsShownWithItsChatAsTheArtist() {
        player.play(note)
        val info = assertNotNull(nowPlayingInfo(player.state.value))
        assertEquals("voice-note.m4a", info.title)
        assertEquals("demo-day-recap", info.artist)
        assertEquals(130.0, info.durationSec)
        assertEquals(0.0, info.elapsedSec)
        assertEquals(1.0, info.rate)
        assertTrue(info.playing)
        assertTrue(info.seekable)
    }

    @Test fun pausedIsRateZeroAndTheSpeedIsTheRateWhilePlaying() {
        player.play(note)
        player.setSpeed(1.5f)
        events.last().onProgress(42_500, 131_000)
        val playing = assertNotNull(nowPlayingInfo(player.state.value))
        assertEquals(1.5, playing.rate)
        assertEquals(42.5, playing.elapsedSec)
        assertEquals(131.0, playing.durationSec, "the player's length once it knows")
        player.toggle()
        val paused = assertNotNull(nowPlayingInfo(player.state.value))
        assertEquals(0.0, paused.rate)
        assertFalse(paused.playing)
    }

    @Test fun anUnknownLengthIsNoDuration() {
        player.play(note.copy(durationMs = null))
        assertNull(assertNotNull(nowPlayingInfo(player.state.value)).durationSec)
    }

    @Test fun theOsVoiceIsShownButCannotSeek() {
        player.playOwn(AudioItem("Read aloud", "", chat = "demo-day-recap")) {}
        val info = assertNotNull(nowPlayingInfo(player.state.value))
        assertEquals("Read aloud", info.title)
        assertFalse(info.seekable)
        assertNull(info.durationSec)
        assertEquals(1.0, info.rate)
    }

    @Test fun idleOrFailedShowsNothing() {
        assertNull(nowPlayingInfo(null))
        player.play(note)
        events.last().onError("unplayable")
        assertNull(nowPlayingInfo(player.state.value), "a failed item says so in the dock, not on the lock screen")
    }

    @Test fun commandsDriveThePlayer() {
        player.play(note)
        events.last().onProgress(40_000, 130_000)
        assertTrue(player.perform(MediaCommand.Pause))
        assertFalse(assertNotNull(player.state.value).playing)
        assertTrue(player.perform(MediaCommand.Pause), "pausing a paused item is done already")
        assertFalse(assertNotNull(player.state.value).playing)
        assertTrue(player.perform(MediaCommand.Play))
        assertTrue(assertNotNull(player.state.value).playing)
        assertTrue(player.perform(MediaCommand.TogglePlayPause))
        assertFalse(assertNotNull(player.state.value).playing)
        assertTrue(player.perform(MediaCommand.SkipBy(MediaCommand.SKIP_MS)))
        assertEquals(55_000, assertNotNull(player.state.value).positionMs)
        assertTrue(player.perform(MediaCommand.SkipBy(-MediaCommand.SKIP_MS)))
        assertEquals(40_000, assertNotNull(player.state.value).positionMs)
        assertTrue(player.perform(MediaCommand.SeekTo(100_000)))
        assertEquals(100_000, assertNotNull(player.state.value).positionMs)
        assertEquals(listOf("open /cache/voice-note.m4a", "play", "pause", "play", "pause", "seek 55000", "seek 40000", "seek 100000"), outputs.single().calls)
    }

    @Test fun skippingBackPastTheStartGoesToTheStart() {
        player.play(note)
        events.last().onProgress(5_000, 130_000)
        assertTrue(player.perform(MediaCommand.SkipBy(-MediaCommand.SKIP_MS)))
        assertEquals(0, assertNotNull(player.state.value).positionMs)
    }

    @Test fun nothingPlayingTakesNoCommand() {
        assertFalse(player.perform(MediaCommand.TogglePlayPause))
        player.play(note)
        events.last().onError("unplayable")
        assertFalse(player.perform(MediaCommand.Play))
    }

    @Test fun pausingTheOsVoiceStopsItAndItCannotSeek() {
        var stopped = 0
        player.playOwn(AudioItem("Read aloud", "")) { stopped++ }
        assertFalse(player.perform(MediaCommand.SkipBy(MediaCommand.SKIP_MS)))
        assertTrue(player.perform(MediaCommand.TogglePlayPause))
        assertEquals(1, stopped)
        assertNull(player.state.value)
    }

    @Test fun theSystemIsShownWhatPlaysAndClearedWhenItStops() {
        sync()
        assertTrue(system.shown.isEmpty(), "nothing is shown while idle")
        assertEquals(0, system.clears)
        player.play(note)
        sync()
        assertEquals("voice-note.m4a", system.shown.single().title)
        player.stop()
        sync()
        assertEquals(1, system.clears)
        assertNull(system.commands)
        sync()
        assertEquals(1, system.clears, "cleared once")
    }

    @Test fun theSystemsCommandsReachThePlayer() {
        player.play(note)
        sync()
        val commands = assertNotNull(system.commands)
        assertTrue(commands(MediaCommand.TogglePlayPause))
        assertFalse(assertNotNull(player.state.value).playing)
    }

    @Test fun progressAsTheSystemWouldCountItIsNotPushedButASeekIs() {
        player.play(note)
        sync()
        clock = 1_000
        events.last().onProgress(1_000, 130_000)
        sync()
        clock = 2_000
        events.last().onProgress(2_100, 130_000)
        sync()
        assertEquals(1, system.shown.size, "the system runs the clock from the elapsed time and the rate")
        player.seek(60_000)
        sync()
        assertEquals(2, system.shown.size)
        assertEquals(60.0, system.shown.last().elapsedSec)
        player.toggle()
        sync()
        assertEquals(3, system.shown.size, "a pause is news")
        assertEquals(0.0, system.shown.last().rate)
        clock = 10_000
        sync()
        assertEquals(3, system.shown.size, "paused, the clock stands still")
    }

    @Test fun startFollowsThePlayer() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val job = controls.start(scope)
        player.play(note)
        assertEquals("voice-note.m4a", system.shown.lastOrNull()?.title)
        player.stop()
        assertEquals(1, system.clears)
        job.cancel()
    }
}
