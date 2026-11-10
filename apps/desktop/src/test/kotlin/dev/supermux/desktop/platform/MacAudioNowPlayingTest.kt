package dev.supermux.desktop.platform

import dev.supermux.work.MediaCommand
import dev.supermux.work.NowPlayingInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's Now Playing and media keys (#118 car 3c). Linux CI has no MediaPlayer framework or user32, so what
 * they are told, and what their commands become, runs here against fakes.
 */
class MacAudioNowPlayingTest {
    private class FakeCenter : MpCenter {
        val calls = mutableListOf<String>()
        var handle: ((MacRemote, Double) -> Boolean)? = null

        override fun setInfo(info: NowPlayingInfo?) {
            calls += if (info == null) "clear" else "info ${info.title} ${macPlaybackState(info)}"
        }

        override fun enable(remotes: List<MacRemote>, handle: (MacRemote, Double) -> Boolean) {
            calls += "enable ${remotes.joinToString(",") { it.name }}"
            this.handle = handle
        }

        override fun disable() {
            calls += "disable"
            handle = null
        }
    }

    private class FakeKeys : WinKeys {
        val calls = mutableListOf<String>()
        var onKey: ((WinMediaKey) -> Unit)? = null

        override fun register(keys: List<WinMediaKey>, onKey: (WinMediaKey) -> Unit) {
            calls += "register ${keys.joinToString(",") { it.name }}"
            this.onKey = onKey
        }

        override fun unregister() {
            calls += "unregister"
        }
    }

    private val note = NowPlayingInfo("voice-note.m4a", "demo-day-recap", 130.0, 0.0, 1.0, playing = true, seekable = true)
    private val voice = NowPlayingInfo("Read aloud", null, null, 0.0, 1.0, playing = true, seekable = false)

    @Test fun eachRemoteIsItsCommand() {
        assertEquals(MediaCommand.Play, MacRemote.PLAY.command(0.0))
        assertEquals(MediaCommand.Pause, MacRemote.PAUSE.command(0.0))
        assertEquals(MediaCommand.TogglePlayPause, MacRemote.TOGGLE.command(0.0))
        assertEquals(MediaCommand.SeekTo(42_500), MacRemote.POSITION.command(42.5))
        assertEquals(MediaCommand.SkipBy(15_000), MacRemote.FORWARD.command(15.0))
        assertEquals(MediaCommand.SkipBy(-15_000), MacRemote.BACKWARD.command(15.0))
        assertEquals(MediaCommand.SkipBy(15_000), MacRemote.FORWARD.command(0.0), "no interval: 15 s")
    }

    @Test fun thePlaybackStateFollowsTheInfo() {
        assertEquals(MP_STATE_PLAYING, macPlaybackState(note))
        assertEquals(MP_STATE_PAUSED, macPlaybackState(note.copy(playing = false, rate = 0.0)))
        assertEquals(MP_STATE_STOPPED, macPlaybackState(null))
    }

    @Test fun theMacTakesTheCommandsWhileSomethingShowsAndLetsThemGo() {
        val center = FakeCenter()
        val np = MacAudioNowPlaying { center }
        val got = mutableListOf<MediaCommand>()
        np.show(note) { got += it; true }
        np.show(note.copy(elapsedSec = 30.0)) { got += it; true }
        assertEquals(listOf("enable PLAY,PAUSE,TOGGLE,POSITION,FORWARD,BACKWARD", "info voice-note.m4a 1", "info voice-note.m4a 1"), center.calls)
        assertTrue(center.handle!!(MacRemote.FORWARD, 15.0))
        assertEquals(listOf<MediaCommand>(MediaCommand.SkipBy(15_000)), got)
        np.clear()
        assertEquals(listOf("clear", "disable"), center.calls.takeLast(2))
        assertNull(center.handle)
    }

    @Test fun theOsVoiceTakesNoScrubOrSkip() {
        val center = FakeCenter()
        val np = MacAudioNowPlaying { center }
        np.show(voice) { true }
        assertEquals("enable PLAY,PAUSE,TOGGLE", center.calls.first())
        np.show(note) { true }
        assertEquals(listOf("disable", "enable PLAY,PAUSE,TOGGLE,POSITION,FORWARD,BACKWARD"), center.calls.subList(2, 4))
    }

    @Test fun windowsMediaKeysAreTheirCommands() {
        assertEquals(MediaCommand.TogglePlayPause, WinMediaKey.PLAY_PAUSE.command)
        assertEquals(MediaCommand.SkipBy(15_000), WinMediaKey.NEXT.command)
        assertEquals(MediaCommand.SkipBy(-15_000), WinMediaKey.PREVIOUS.command)
        assertEquals(0xB3, WinMediaKey.PLAY_PAUSE.virtualKey)
    }

    @Test fun windowsTakesTheKeysWhileSomethingShowsAndLetsThemGo() {
        val keys = FakeKeys()
        val np = WinAudioMediaKeys { keys }
        val got = mutableListOf<MediaCommand>()
        np.show(note) { got += it; true }
        np.show(note.copy(elapsedSec = 30.0)) { got += it; true }
        assertEquals(listOf("register PLAY_PAUSE,NEXT,PREVIOUS"), keys.calls)
        keys.onKey!!(WinMediaKey.PLAY_PAUSE)
        assertEquals(listOf<MediaCommand>(MediaCommand.TogglePlayPause), got)
        np.clear()
        assertEquals("unregister", keys.calls.last())
        keys.onKey!!(WinMediaKey.PLAY_PAUSE)
        assertEquals(1, got.size, "a key after the clear does nothing")
    }

    @Test fun linuxHasNone() {
        assertNull(desktopNowPlaying(DesktopOs.LINUX))
        assertIs<MacAudioNowPlaying>(desktopNowPlaying(DesktopOs.MACOS))
        assertIs<WinAudioMediaKeys>(desktopNowPlaying(DesktopOs.WINDOWS))
    }
}
