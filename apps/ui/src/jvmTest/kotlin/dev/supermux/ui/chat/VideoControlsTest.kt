package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inline video control bar, driven by a fake [VideoTransport] — the real player would load a
 * native media backend in the Gradle worker. Covers the design rules that are easy to regress:
 * controls are visible while paused and hidden during playback, the centred play glyph and the
 * buffering spinner are mutually exclusive, and every button reaches the transport.
 */
@OptIn(ExperimentalTestApi::class)
class VideoControlsTest {

    private class FakeTransport(
        playing: Boolean = false,
        loading: Boolean = false,
    ) : VideoTransport {
        override var isPlaying by mutableStateOf(playing)
        override var isLoading by mutableStateOf(loading)
        override var sliderPos by mutableStateOf(0f)
        override val positionText = "00:03"
        override val durationText = "00:06"
        override var muted by mutableStateOf(false)
        var externalOpens = 0

        override fun togglePlay() { isPlaying = !isPlaying }
        override fun seekStart(value: Float) { sliderPos = value }
        override fun seekFinished() {}
        override fun toggleMute() { muted = !muted }
        override fun openExternally() { externalOpens++ }
    }

    private fun frame(t: VideoTransport) = @androidx.compose.runtime.Composable {
        VideoPlayerFrame(t) { m -> Box(m.fillMaxSize().background(Color.DarkGray).testTag("fake_surface")) { Text("") } }
    }

    @Test fun controls_and_center_play_are_shown_while_paused() = runComposeUiTest {
        setPlatformContent { frame(FakeTransport(playing = false))() }
        onNodeWithTag("attachment_video_controls", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("attachment_video_center_play", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("attachment_video_position", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("attachment_video_duration", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun controls_hide_during_playback() = runComposeUiTest {
        // Not hovered, not scrubbing, playing → the picture is unobstructed.
        setPlatformContent { frame(FakeTransport(playing = true))() }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_controls", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithTag("attachment_video_center_play", useUnmergedTree = true).fetchSemanticsNodes().size,
            "the centred play glyph must not sit over a playing clip",
        )
    }

    @Test fun buffering_replaces_the_center_play_glyph() = runComposeUiTest {
        setPlatformContent { frame(FakeTransport(playing = false, loading = true))() }
        onNodeWithTag("attachment_video_buffering", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithTag("attachment_video_center_play", useUnmergedTree = true).fetchSemanticsNodes().size,
            "a slow first frame must read as buffering, not as paused",
        )
    }

    @Test fun on_a_touch_host_a_tap_reveals_the_controls_instead_of_toggling_play() = runComposeUiTest {
        // No hover on a phone, so the pointer rule would hide the controls for the whole clip.
        val t = FakeTransport(playing = true)
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { frame(t)() }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_controls", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag("attachment_video_player").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_controls", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(t.isPlaying, "the reveal tap must not double as play/pause on touch")
    }

    @Test fun the_touch_reveal_hides_itself_again() = runComposeUiTest {
        val t = FakeTransport(playing = true)
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { frame(t)() }
        onNodeWithTag("attachment_video_player").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_controls", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // CONTROLS_REVEAL_MS later they are gone again — the picture is not permanently boxed in.
        mainClock.advanceTimeBy(CONTROLS_REVEAL_MS + 500L)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_video_controls", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun clicking_the_surface_toggles_playback() = runComposeUiTest {
        val t = FakeTransport(playing = false)
        setPlatformContent { frame(t)() }
        onNodeWithTag("attachment_video_player").performClick()
        assertTrue(t.isPlaying, "the whole surface is the play target")
    }

    @Test fun playpause_button_toggles_and_relabels() = runComposeUiTest {
        val t = FakeTransport(playing = false)
        setPlatformContent { frame(t)() }
        assertTrue(hasLabel("Play"), "a paused clip offers Play")
        onNodeWithTag("attachment_video_playpause", useUnmergedTree = true).performClick()
        assertTrue(t.isPlaying)
        // Paused again → the button (and its a11y label) must flip back, not stick.
        t.isPlaying = false
        waitForIdle()
        assertTrue(hasLabel("Play"))
        assertTrue(!hasLabel("Pause"))
    }

    @Test fun mute_button_toggles_and_relabels() = runComposeUiTest {
        val t = FakeTransport()
        setPlatformContent { frame(t)() }
        assertTrue(hasLabel("Mute"))
        onNodeWithTag("attachment_video_mute", useUnmergedTree = true).performClick()
        assertTrue(t.muted)
        waitForIdle()
        assertTrue(hasLabel("Unmute"), "the label must say what the next tap does")
    }

    @Test fun external_button_hands_the_clip_to_the_os_player() = runComposeUiTest {
        val t = FakeTransport()
        setPlatformContent { frame(t)() }
        onNodeWithTag("attachment_video_external", useUnmergedTree = true).performClick()
        assertEquals(1, t.externalOpens)
    }

    /** Icons carry the label, the tagged IconButton does not — search the whole unmerged tree. */
    private fun androidx.compose.ui.test.ComposeUiTest.hasLabel(label: String): Boolean =
        onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}
