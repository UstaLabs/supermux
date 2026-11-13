package dev.supermux.desktop.platform

import dev.supermux.work.AudioOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * macOS's audio (#118) on AVAudioPlayer: the library's macOS backend cannot play an audio-only file. Linux CI cannot
 * make a sound, so the command and state logic runs here against a fake AVAudioPlayer and a hand-cranked loop.
 */
class MacAudioOutputsTest {
    /** The ObjC side: what each sound was told, and what it reports. */
    private class FakeAv : AvAudio {
        val loaded = mutableListOf<String>()
        val sounds = mutableListOf<FakeSound>()
        var fails: String? = null

        override fun load(path: String): AvAudio.Sound {
            loaded += path
            fails?.let { throw AvAudioException(it) }
            return FakeSound().also { sounds += it }
        }
    }

    private class FakeSound : AvAudio.Sound {
        val calls = mutableListOf<String>()
        override var playing = false
        override var currentTime = 0.0
        override var duration = 16.36
        var playWorks = true

        override fun play(): Boolean {
            calls += "play"
            playing = playWorks
            return playWorks
        }

        override fun pause() {
            calls += "pause"
            playing = false
        }

        override fun seek(seconds: Double) {
            calls += "seek $seconds"
            currentTime = seconds
        }

        override fun rate(rate: Float) {
            calls += "rate $rate"
        }

        override fun release() {
            calls += "release"
        }
    }

    /** Runs each command at once; the polls wait for [tick]. */
    private class HandLoop : AudioLoop {
        val polls = mutableListOf<() -> Unit>()
        val periods = mutableListOf<Long>()

        override fun run(block: () -> Unit) = block()

        override fun every(ms: Long, block: () -> Unit): () -> Unit {
            polls += block
            periods += ms
            return { polls -= block }
        }

        fun tick() = polls.toList().forEach { it() }
    }

    private class Heard : AudioOutput.Events {
        val said = mutableListOf<String>()
        override fun onProgress(positionMs: Long, durationMs: Long) {
            said += "progress $positionMs/$durationMs"
        }
        override fun onPlayingChanged(playing: Boolean) {
            said += "playing $playing"
        }
        override fun onEnded() {
            said += "ended"
        }
        override fun onError(message: String) {
            said += "error $message"
        }
    }

    private fun outputs(av: FakeAv = FakeAv(), loop: HandLoop = HandLoop()) = Triple(MacAudioOutputs({ av }, loop), av, loop)

    @Test fun aFileUriOrAPathOpensTheFile() {
        val (outputs, av) = outputs()
        outputs.create(Heard()).open("file:///Users/a/Library/Caches/note%20one.m4a")
        outputs.create(Heard()).open("/Users/a/note.m4a")
        assertEquals(listOf("/Users/a/Library/Caches/note one.m4a", "/Users/a/note.m4a"), av.loaded)
    }

    @Test fun commandsReachThePlayer() {
        val (outputs, av) = outputs()
        val out = outputs.create(Heard())
        out.open("/n.m4a")
        out.setRate(1.5f)
        out.play()
        out.seekTo(12_000)
        out.pause()
        assertEquals(listOf("rate 1.5", "play", "seek 12.0", "pause"), av.sounds.single().calls)
    }

    @Test fun itsPositionAndLengthArePolledEveryQuarterSecond() {
        val (outputs, av, loop) = outputs()
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/n.m4a")
        out.play()
        assertEquals(listOf(250L), loop.periods)
        av.sounds.single().currentTime = 1.5
        loop.tick()
        assertEquals(listOf("progress 1500/16360"), heard.said)
        // Our own pause is not news to the player.
        out.pause()
        heard.said.clear()
        loop.tick()
        assertEquals(listOf("progress 1500/16360"), heard.said)
    }

    @Test fun aPauseOfItsOwnIsSaidOnce() {
        val (outputs, av, loop) = outputs()
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/n.m4a")
        out.play()
        av.sounds.single().apply { currentTime = 6.0; playing = false }
        loop.tick()
        loop.tick()
        assertEquals(1, heard.said.count { it == "playing false" }, "${heard.said}")
        assertFalse("ended" in heard.said, "mid-item is not its end")
    }

    @Test fun theEndIsHeardOnce() {
        val (outputs, av, loop) = outputs()
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/n.m4a")
        out.play()
        loop.tick()
        // AVAudioPlayer at its end: no longer playing, back at 0, without a pause from us.
        av.sounds.single().apply { playing = false; currentTime = 0.0 }
        loop.tick()
        loop.tick()
        assertEquals(1, heard.said.count { it == "ended" }, "${heard.said}")
        assertTrue("playing false" !in heard.said, "an end is not a pause: ${heard.said}")
    }

    @Test fun aFileItCannotOpenIsAnError() {
        val av = FakeAv().apply { fails = "The operation couldn’t be completed. (OSStatus error 1954115647.)" }
        val (outputs, _, loop) = outputs(av)
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/broken.m4a")
        out.play()
        out.seekTo(1_000)
        assertEquals(listOf("error The operation couldn’t be completed. (OSStatus error 1954115647.)"), heard.said)
        assertTrue(loop.polls.isEmpty(), "nothing to poll")
    }

    @Test fun aPlayThatFailsIsAnError() {
        val (outputs, av) = outputs()
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/n.m4a")
        av.sounds.single().playWorks = false
        out.play()
        assertEquals("error", heard.said.single().substringBefore(' '))
    }

    @Test fun releaseStopsPollingAndLetsTheObjectGo() {
        val (outputs, av, loop) = outputs()
        val heard = Heard()
        val out = outputs.create(heard)
        out.open("/n.m4a")
        out.play()
        out.release()
        assertEquals(listOf("play", "pause", "release"), av.sounds.single().calls)
        assertTrue(loop.polls.isEmpty())
        out.play()
        loop.tick()
        assertEquals(listOf("play", "pause", "release"), av.sounds.single().calls, "a released output does nothing")
        assertTrue(heard.said.isEmpty())
    }

    @Test fun itPlaysLocalFilesOnly() {
        assertFalse(MacAudioOutputs({ FakeAv() }, HandLoop()).canStreamUrl, "AVAudioPlayer cannot stream a URL")
        val (outputs, av) = outputs()
        val heard = Heard()
        outputs.create(heard).open("http://h/speak/audio/x.mp3")
        assertTrue(av.loaded.isEmpty())
        assertEquals("error", heard.said.single().substringBefore(' '))
    }

    @Test fun macOsGetsAvAudioPlayerElsewhereTheLibrary() {
        assertIs<MacAudioOutputs>(desktopAudioOutputs(DesktopOs.MACOS))
        assertNull(desktopAudioOutputs(DesktopOs.LINUX))
        assertNull(desktopAudioOutputs(DesktopOs.WINDOWS))
    }
}
