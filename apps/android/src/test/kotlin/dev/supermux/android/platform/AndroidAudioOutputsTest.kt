package dev.supermux.android.platform

import dev.supermux.work.AudioItem
import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import dev.supermux.work.DownloadOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #118 car 3a: Android's audio outputs over the media session's player, driven without a device. The session's player
 * (a `MediaController` to [AudioPlaybackService]'s ExoPlayer on a phone) is behind [SessionPlayer], so the command and
 * event mapping, the wait for the connection and the per-item guard run against a fake here. What only a device shows
 * — the service, its notification, the lock screen, audio focus, headphones out — is checked on one.
 */
class AndroidAudioOutputsTest {
    private class FakeSession : SessionPlayer {
        val calls = mutableListOf<String>()
        var listener: SessionPlayer.Listener? = null
        override var mediaId: String? = null
        override var positionMs = 0L
        override var durationMs = 0L

        override fun listen(listener: SessionPlayer.Listener) {
            this.listener = listener
        }

        override fun load(media: AudioMedia) {
            calls += "load ${media.uri} '${media.title}' by ${media.artist}"
            mediaId = media.id
        }

        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun setSpeed(speed: Float) { calls += "speed $speed" }
        override fun stop() {
            calls += "stop"
            mediaId = null
        }
    }

    /** Runs a command at once; a poll waits for [tick]. */
    private class FakeLoop : AndroidAudioLoop {
        val polls = mutableListOf<() -> Unit>()

        override fun run(block: () -> Unit) = block()

        override fun every(ms: Long, block: () -> Unit): () -> Unit {
            polls += block
            return { polls.remove(block) }
        }

        fun tick() = polls.toList().forEach { it() }
    }

    private class Heard : AudioOutput.Events {
        val said = mutableListOf<String>()
        override fun onProgress(positionMs: Long, durationMs: Long) { said += "progress $positionMs/$durationMs" }
        override fun onPlayingChanged(playing: Boolean) { said += "playing $playing" }
        override fun onEnded() { said += "ended" }
        override fun onError(message: String) { said += "error $message" }
    }

    private val note = AudioItem(
        "voice-note.m4a", "/cache/voice-note.m4a", DownloadOrigin("s1", "m7"), chat = "demo-day-recap", durationMs = 130_000,
    )
    private val other = AudioItem("other.mp3", "/cache/other.mp3")

    private val session = FakeSession()
    private val loop = FakeLoop()
    private var connects = 0
    private var ready: ((SessionPlayer) -> Unit)? = null
    private var failed: ((String) -> Unit)? = null

    /** Outputs whose connection comes when the test says ([connectNow] at once). */
    private fun outputs(connectNow: Boolean = true) = AndroidAudioOutputs(
        connect = { onReady, onFailed ->
            connects++
            ready = onReady
            failed = onFailed
            if (connectNow) onReady(session)
        },
        loop = loop,
    )

    @Test fun theDocksCommandsReachTheSessionsPlayerInOrder() {
        val out = outputs().create(Heard())
        out.open(note)
        out.setRate(1.5f)
        out.play()
        out.pause()
        out.seekTo(5_000)
        assertEquals(
            listOf("load /cache/voice-note.m4a 'voice-note.m4a' by demo-day-recap", "speed 1.5", "play", "pause", "seek 5000"),
            session.calls,
        )
    }

    @Test fun commandsWaitForTheSessionAndConnectOnce() {
        val outputs = outputs(connectNow = false)
        val out = outputs.create(Heard())
        out.open(note)
        out.play()
        outputs.create(Heard())
        assertTrue(session.calls.isEmpty(), "nothing reaches a player not connected yet")
        assertEquals(1, connects, "one connection for every item")
        ready!!(session)
        assertEquals(listOf("load /cache/voice-note.m4a 'voice-note.m4a' by demo-day-recap", "play"), session.calls)
    }

    @Test fun thePositionAndLengthArePolledFromThePlayer() {
        val heard = Heard()
        outputs().create(heard).open(note)
        session.positionMs = 42_000
        session.durationMs = 131_500
        loop.tick()
        assertEquals(listOf("progress 42000/131500"), heard.said)
    }

    @Test fun aPauseFromTheNotificationAnEndAndAnErrorReachTheItem() {
        val heard = Heard()
        outputs().create(heard).open(note)
        val id = session.mediaId
        val listener = session.listener!!
        listener.onPlayingChanged(id, false)
        listener.onPlayingChanged(id, true)
        listener.onEnded(id)
        assertEquals(listOf("playing false", "playing true", "ended"), heard.said)
    }

    @Test fun anErrorIsTheItemsAndItsPollEnds() {
        val heard = Heard()
        outputs().create(heard).open(note)
        session.listener!!.onError(session.mediaId, "Source error")
        loop.tick()
        assertEquals(listOf("error Source error"), heard.said)
        assertTrue(loop.polls.isEmpty())
    }

    @Test fun aReplacedItemHearsNothingMoreAndItsLateEventsAreIgnored() {
        val outputs = outputs()
        val first = Heard()
        val a = outputs.create(first)
        a.open(note)
        val oldId = session.mediaId
        // The player's order: the old item is released, then the new one opened.
        val second = Heard()
        val b = outputs.create(second)
        a.release()
        b.open(other)
        val listener = session.listener!!
        listener.onEnded(oldId)
        listener.onError(oldId, "late")
        listener.onPlayingChanged(session.mediaId, false)
        assertTrue(first.said.isEmpty(), "the replaced item says nothing more")
        assertEquals(listOf("playing false"), second.said)
        assertEquals("stop", session.calls[1], "the released item stopped the player before the next loaded")
    }

    @Test fun releasingAStaleItemLeavesTheCurrentOnePlaying() {
        val outputs = outputs()
        val a = outputs.create(Heard())
        a.open(note)
        val b = outputs.create(Heard())
        b.open(other)
        b.play()
        a.release()
        assertTrue("stop" !in session.calls, "a stale item does not stop what plays now")
    }

    @Test fun releaseStopsThePlayerAndThePoll() {
        val heard = Heard()
        val out = outputs().create(heard)
        out.open(note)
        out.release()
        out.play()
        loop.tick()
        assertEquals("stop", session.calls.last(), "nothing after the release reaches the player")
        assertTrue(loop.polls.isEmpty())
        assertTrue(heard.said.isEmpty())
    }

    @Test fun aSessionThatCannotConnectIsTheItemsError() {
        val heard = Heard()
        outputs(connectNow = false).create(heard).open(note)
        failed!!("no service")
        assertEquals(listOf("error no service"), heard.said)
    }

    @Test fun theSessionGoingAwayEndsTheItemAndTheNextConnectsAgain() {
        val outputs = outputs()
        val heard = Heard()
        outputs.create(heard).open(note)
        // supermux swiped away from Recents: the service stops, the controller is disconnected.
        session.listener!!.onDisconnected()
        assertEquals(listOf("ended"), heard.said)
        outputs.create(Heard()).open(other)
        assertEquals(2, connects)
    }

    @Test fun itStreamsUrlsSoReadAloudsVoiceGoesThroughIt() {
        assertTrue(outputs().canStreamUrl)
    }

    @Test fun theNotificationShowsTheTitleAndItsChat() {
        val media = audioMedia(note, "a1")
        assertEquals(AudioMedia(id = "a1", uri = "/cache/voice-note.m4a", title = "voice-note.m4a", artist = "demo-day-recap"), media)
        assertNull(audioMedia(other, "a2").artist, "no chat, no artist")
    }

    @Test fun theProcessHasOneSetOfOutputs() {
        val made = mutableListOf<AudioOutputs>()
        val make = { AudioOutputs { _ -> error("unused") }.also { made += it } }
        val first = AndroidAudio.shared(make)
        assertSame(first, AndroidAudio.shared(make), "every window's platform shares the session")
        assertEquals(1, made.size)
    }
}
