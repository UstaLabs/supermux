package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.LogEntry
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.work.AudioDockTest
import dev.supermux.ui.work.LocalAudioPlayer
import dev.supermux.work.AudioItem
import dev.supermux.work.AudioPlayer
import dev.supermux.work.DownloadOrigin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reply's 🔊 (#118 car 2): reading it is the app's player's item — its first words, its chat and message — and the
 * 🔊 shows "speaking" for as long as the player is reading it, whoever stops it.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineReadAloudTest {
    private val platform = FakePlatform().apply { tts.hold = true }

    @AfterTest fun silence() = MessageTts.stop(platform.tts)

    private fun ComposeUiTest.reply(player: AudioPlayer) = setPlatformContent(platform) {
        CompositionLocalProvider(
            LocalAudioPlayer provides player,
            LocalChatSessionId provides "s1",
            LocalChatTitle provides "demo-day-recap",
        ) {
            TimelineItemRow(TimelineItem.Msg(LogEntry(id = "m7", ts = "2026-01-01T00:00:01Z", direction = "outbound", text = "Here is the plan")))
        }
    }

    private fun ComposeUiTest.speaker(description: String) =
        onNode(hasTestTag("message_read_aloud") and hasContentDescription(description))

    private fun ComposeUiTest.waitFor(block: () -> Unit) = waitUntil(timeoutMillis = 5_000) {
        try {
            block()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Test fun readingAReplyIsThePlayersItem() = runComposeUiTest {
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        reply(player)
        speaker("Read aloud").performClick()
        waitFor { speaker("Stop reading").assertExists() }
        val item = player.state.value?.item
        assertEquals("Read aloud · Here is the plan", item?.title)
        assertEquals(DownloadOrigin("s1", "m7"), item?.origin)
        assertEquals("demo-day-recap", item?.chat)
        assertTrue(player.state.value?.stopOnly == true)
    }

    @Test fun theSpeakerFollowsThePlayersCross() = runComposeUiTest {
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        reply(player)
        speaker("Read aloud").performClick()
        waitFor { speaker("Stop reading").assertExists() }
        val stops = platform.tts.stops
        runOnIdle { player.stop() }
        waitFor { speaker("Read aloud").assertExists() }
        assertTrue(platform.tts.stops > stops, "✕ silenced the voice")
    }

    @Test fun anAttachmentPlayingEndsTheReading() = runComposeUiTest {
        val player = AudioPlayer(AudioDockTest.FakeOutputs())
        reply(player)
        speaker("Read aloud").performClick()
        waitFor { speaker("Stop reading").assertExists() }
        runOnIdle { player.play(AudioItem("voice-note.m4a", "/cache/voice-note.m4a", key = "att:h/1")) }
        waitFor { speaker("Read aloud").assertExists() }
        assertEquals("voice-note.m4a", player.state.value?.item?.title)
    }
}
