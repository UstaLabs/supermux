package dev.supermux.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ONE fullscreen viewer: every picture and clip of the chat in a row, opened at the tapped
 * one. Moves by swipe, ←/→ and the on-screen arrows, says where it is ("2 / 3"), closes on Esc or
 * a swipe down, plays a clip in place only once its page is the current one, and keeps the
 * picture's save. The player is a stub here: a headless worker must never load a native backend.
 */
@OptIn(ExperimentalTestApi::class)
class ChatMediaViewerTest {

    private val imgA = ChatMediaItem.FromAttachment(
        "m1", Attachment(file_id = "a", kind = "photo", mime = "image/png", name = "a.png"), FilePreviewKind.Image,
    )
    private val clip = ChatMediaItem.FromAttachment(
        "m1", Attachment(file_id = "v", kind = "video", mime = "video/mp4", name = "v.mp4"), FilePreviewKind.Video,
    )
    private val imgB = ChatMediaItem.FromAttachment(
        "m2", Attachment(file_id = "b", kind = "photo", mime = "image/png", name = "b.png"), FilePreviewKind.Image,
    )
    private val items = listOf(imgA, clip, imgB)

    private class Media : AttachmentMedia {
        val clipReads = AtomicInteger(0)

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
            if (att.kind == "video") {
                clipReads.incrementAndGet()
                return ByteArray(16)
            }
            return TINY_PNG_BYTES
        }

        override suspend fun playableUri(att: Attachment): String? {
            if (att.kind != "video") return null
            clipReads.incrementAndGet()
            return "file:///cache/${att.file_id}.mp4"
        }
    }

    private class FakeTransport : VideoTransport {
        val toggles = AtomicInteger(0)
        val fullscreens = AtomicInteger(0)
        override val isPlaying = false
        override val isLoading = false
        override val sliderPos = 0f
        override val positionText = "0:00"
        override val durationText = "0:10"
        override val muted = false
        override fun togglePlay() { toggles.incrementAndGet() }
        override fun seekStart(value: Float) = Unit
        override fun seekFinished() = Unit
        override fun toggleMute() = Unit
        override fun openExternally() = Unit
        override fun toggleFullscreen() { fullscreens.incrementAndGet() }
    }

    @Composable
    private fun Viewer(
        start: ChatMediaItem,
        media: Media = Media(),
        transport: FakeTransport = FakeTransport(),
        onDismiss: () -> Unit = {},
    ) {
        ChatMediaViewer(
            items = items,
            startKey = start.key,
            resolve = { item -> MediaHandle((item as ChatMediaItem.FromAttachment).att, media, attachmentImageKey(item.att)) },
            onDismiss = onDismiss,
            renderPlayer = { uri, _, onTransport ->
                DisposableEffect(uri) {
                    onTransport(transport)
                    onDispose { onTransport(null) }
                }
                Text("player $uri", modifier = Modifier.testTag("stub_player"))
            },
        )
    }

    private fun ComposeUiTest.counter() = onNodeWithTag("chat_media_viewer_counter", useUnmergedTree = true)

    @Test fun it_opens_at_the_tapped_item() = runComposeUiTest {
        setPlatformContent { Viewer(start = imgB) }
        onNodeWithTag("chat_media_viewer").assertIsDisplayed()
        counter().assertTextEquals("3 / 3")
    }

    @Test fun arrow_keys_move_and_stop_at_the_ends() = runComposeUiTest {
        setPlatformContent { Viewer(start = imgA) }
        val root = onNodeWithTag("chat_media_viewer")
        root.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        counter().assertTextEquals("2 / 3")
        root.performKeyInput { pressKey(Key.DirectionRight) }
        root.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        counter().assertTextEquals("3 / 3")
        root.performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun escape_closes_it() = runComposeUiTest {
        var closed = false
        setPlatformContent { Viewer(start = imgA, onDismiss = { closed = true }) }
        onNodeWithTag("chat_media_viewer").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(closed)
    }

    @Test fun the_side_arrows_move_under_a_pointer() = runComposeUiTest {
        setPlatformContent(pointer = true) { Viewer(start = clip) }
        onNodeWithTag("chat_media_viewer_next").performClick()
        waitForIdle()
        counter().assertTextEquals("3 / 3")
        onNodeWithTag("chat_media_viewer_prev").performClick()
        onNodeWithTag("chat_media_viewer_prev").performClick()
        waitForIdle()
        counter().assertTextEquals("1 / 3")
    }

    @Test fun a_clip_downloads_only_once_its_page_is_the_current_one() = runComposeUiTest {
        val media = Media()
        setPlatformContent { Viewer(start = imgA, media = media) }
        waitForIdle()
        assertEquals(0, media.clipReads.get(), "the clip next door is not opened yet")
        assertEquals(0, onAllNodesWithTag("stub_player").fetchSemanticsNodes().size)
        onNodeWithTag("chat_media_viewer").performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(media.clipReads.get() >= 1, "opening the clip's page fetches it")
        // Moving on takes the player down with the page.
        onNodeWithTag("chat_media_viewer").performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("stub_player").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun a_clip_dragged_halfway_into_view_downloads_nothing() = runComposeUiTest {
        // Mid-swipe the clip's page is composed and on screen, but nobody opened it yet.
        val media = Media()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { Viewer(start = imgA, media = media) }
        onNodeWithTag("chat_media_viewer_pager").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.4f, 0f))
        }
        waitForIdle()
        onNodeWithTag("chat_media_viewer_poster", useUnmergedTree = true).assertExists("the clip's page is on screen")
        assertEquals(0, media.clipReads.get(), "a clip swiped past is never fetched")
    }

    @Test fun space_and_f_reach_the_current_clip_only() = runComposeUiTest {
        val transport = FakeTransport()
        setPlatformContent { Viewer(start = imgA, transport = transport) }
        val root = onNodeWithTag("chat_media_viewer")
        root.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(0, transport.toggles.get(), "a picture has nothing to play")
        root.performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("stub_player").fetchSemanticsNodes().isNotEmpty() }
        root.performKeyInput { pressKey(Key.Spacebar) }
        root.performKeyInput { pressKey(Key.F) }
        waitForIdle()
        assertEquals(1, transport.toggles.get())
        assertEquals(1, transport.fullscreens.get())
    }

    @Test fun the_strip_shows_under_a_pointer_and_jumps() = runComposeUiTest {
        setPlatformContent(pointer = true) { Viewer(start = imgA) }
        onNodeWithTag("chat_media_viewer_strip").assertIsDisplayed()
        onNodeWithTag("chat_media_viewer_thumb:2").performClick()
        waitForIdle()
        counter().assertTextEquals("3 / 3")
    }

    @Test fun touch_has_no_strip_and_swipes_between_items() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { Viewer(start = imgA) }
        assertEquals(0, onAllNodesWithTag("chat_media_viewer_strip").fetchSemanticsNodes().size)
        onNodeWithTag("chat_media_viewer_pager").performTouchInput { swipeLeft() }
        waitForIdle()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun a_swipe_down_closes_it_on_touch() = runComposeUiTest {
        var closed = false
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            Viewer(start = imgA, onDismiss = { closed = true })
        }
        onNodeWithTag("chat_media_viewer_pager").performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(closed)
    }

    @Test fun save_on_a_picture_uses_the_hosts_save_rule() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform, pointer = true) { Viewer(start = imgA) }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("chat_media_viewer_image").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("chat_media_viewer_save").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.openedSaved.isNotEmpty() }
        assertEquals(listOf("a.png|image/png|${TINY_PNG_BYTES.size}"), platform.files.saved)
    }

    // ── A Mac trackpad ────────────────────────────────────────────────────────────────

    /** A Mac: two-finger scrolls arrive as wheel events that run on into the system's momentum. */
    private fun macTrackpad() = FakePlatform(caps = NO_CAPS.copy(trackpadMomentum = true))

    /**
     * Two fingers on a Mac trackpad the way AWT hands them over: a wheel event every 8 ms in wheel
     * units of 10 dp, the fingers' own and then, with nothing in between to say they lifted, the
     * system's momentum. Sideways [units] > 0 move toward the next item; with [down], they pull down.
     */
    private fun ComposeUiTest.trackpad(vararg units: Float, down: Boolean = false) {
        onNodeWithTag("chat_media_viewer_pager").performMouseInput {
            moveTo(center)
            for (u in units) {
                if (down) scroll(-u, ScrollWheel.Vertical) else scroll(u, ScrollWheel.Horizontal)
                advanceEventTime(8)
            }
        }
    }

    /** A short, quick swipe: the fingers speed up over about 90 dp, then the momentum falls off. */
    private val shortFlick = floatArrayOf(0.5f, 1f, 2f, 2.5f, 3f, 2.8f, 2.65f, 2.5f, 2.37f, 2.25f, 2.1f, 2f)

    private fun ComposeUiTest.awaitPicture() =
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("chat_media_viewer_image").fetchSemanticsNodes().isNotEmpty() }

    /**
     * The fingers are off the pad and no momentum follows: the trackpad goes quiet. The test clock
     * only moves for frames and injected events, so the quiet has to be waited out by hand.
     */
    private fun ComposeUiTest.trackpadGoesQuiet() {
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    /**
     * Where the picture's top-left corner is drawn, transform and all. Its bounds would not do: the
     * pager clips them to the page, so a zoomed picture's bounds are just the page's.
     */
    private fun ComposeUiTest.pictureCorner() = onNodeWithTag("chat_media_viewer_image").fetchSemanticsNode().positionInRoot

    @Test fun a_short_quick_trackpad_swipe_turns_to_the_next_item() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        trackpad(*shortFlick)
        waitForIdle()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun the_next_item_follows_the_fingers_while_they_move() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        val width = onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().width
        mainClock.autoAdvance = false
        trackpad(0.5f, 1f, 2f, 2.5f) // 60 dp, and the fingers are still down
        mainClock.advanceTimeByFrame()
        val left = onNodeWithTag("chat_media_viewer_poster", useUnmergedTree = true).getBoundsInRoot().left
        assertEquals((width - 60.dp).value, left.value, 1f)
    }

    @Test fun one_trackpad_swipe_moves_one_item_at_most() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        // Fast all the way, and far past two items' width.
        trackpad(3f, 6f, 10f, 14f, 18f, 22f, 26f, 30f, 34f, 38f, 42f, 46f, 45f, 43f, 41f, 39f, 37f)
        waitForIdle()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun a_long_swipe_never_shows_past_the_next_item() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        waitForIdle()
        mainClock.autoAdvance = false
        trackpad(3f, 6f, 10f, 14f, 18f, 22f, 26f, 30f, 34f, 38f, 42f, 46f) // past two widths, still moving
        mainClock.advanceTimeByFrame()
        counter().assertTextEquals("2 / 3")
    }

    @Test fun a_small_slow_trackpad_nudge_springs_back() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        awaitPicture()
        val rest = pictureCorner().x
        trackpad(0.3f, 0.3f, 0.4f, 0.3f, 0.2f)
        waitForIdle()
        assertTrue(pictureCorner().x < rest - 10f, "the nudge moved the picture while the fingers were on the pad")
        trackpadGoesQuiet()
        counter().assertTextEquals("1 / 3")
        assertEquals(rest, pictureCorner().x, 0.5f, "back at rest on the first item")
    }

    @Test fun the_viewer_follows_a_two_finger_pull() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        val top = onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().top
        mainClock.autoAdvance = false
        trackpad(1f, 2f, 2f, down = true) // 50 dp, and the fingers are still down
        mainClock.advanceTimeByFrame()
        assertEquals((top + 50.dp).value, onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().top.value, 1f)
    }

    @Test fun a_two_finger_pull_down_closes_it() = runComposeUiTest {
        var closed = false
        setPlatformContent(macTrackpad()) { Viewer(start = imgA, onDismiss = { closed = true }) }
        trackpad(1f, 2f, 4f, 6f, 8f, 9f, 8.5f, 8f, 7.6f, 7.2f, 6.8f, down = true)
        waitForIdle()
        assertTrue(closed)
    }

    @Test fun a_short_slow_pull_springs_back() = runComposeUiTest {
        var closed = false
        setPlatformContent(macTrackpad()) { Viewer(start = imgA, onDismiss = { closed = true }) }
        val top = onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().top
        trackpad(0.3f, 0.4f, 0.3f, 0.2f, down = true)
        waitForIdle()
        assertTrue(onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().top > top, "the pull moved it while the fingers were on the pad")
        trackpadGoesQuiet()
        assertFalse(closed)
        assertEquals(top, onNodeWithTag("chat_media_viewer_pager").getBoundsInRoot().top)
    }

    @Test fun command_scroll_zooms_the_picture_and_turns_no_page() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        awaitPicture()
        val before = pictureCorner()
        onNodeWithTag("chat_media_viewer_pager").performMultiModalInput {
            key { keyDown(Key.MetaLeft) }
            mouse {
                moveTo(center)
                scroll(-2f)
                scroll(3f, ScrollWheel.Horizontal)
            }
            key { keyUp(Key.MetaLeft) }
        }
        waitForIdle()
        counter().assertTextEquals("1 / 3")
        val after = pictureCorner()
        assertTrue(after.x < before.x && after.y < before.y, "⌘ + scroll up zooms in about the middle; $before -> $after")
    }

    // ── A click or tap on the picture hides the bars ──────────────────────────────────────

    /** Long enough for a single tap to tell itself from the first half of a double tap. */
    private fun ComposeUiTest.pastDoubleTap() {
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private fun ComposeUiTest.barsShown(): Boolean =
        onAllNodesWithTag("chat_media_viewer_counter", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun a_click_on_the_picture_hides_the_bars_and_another_brings_them_back() = runComposeUiTest {
        setPlatformContent(pointer = true) { Viewer(start = imgA) }
        awaitPicture()
        assertTrue(barsShown())
        onNodeWithTag("chat_media_viewer_image").performMouseInput { click(center) }
        pastDoubleTap()
        assertFalse(barsShown(), "the top bar and its counter are gone")
        assertEquals(0, onAllNodesWithTag("chat_media_viewer_strip").fetchSemanticsNodes().size, "the strip too")
        assertEquals(0, onAllNodesWithTag("chat_media_viewer_next").fetchSemanticsNodes().size, "and the side arrows")
        onNodeWithTag("chat_media_viewer_image").performMouseInput { click(center) }
        pastDoubleTap()
        assertTrue(barsShown(), "the next click brings them back")
        onNodeWithTag("chat_media_viewer_strip").assertExists()
    }

    @Test fun a_tap_on_the_picture_hides_the_bars_on_a_phone_too() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { Viewer(start = imgA) }
        awaitPicture()
        onNodeWithTag("chat_media_viewer_image").performTouchInput { click(center) }
        pastDoubleTap()
        assertFalse(barsShown())
    }

    @Test fun a_double_click_zooms_and_leaves_the_bars() = runComposeUiTest {
        setPlatformContent(pointer = true) { Viewer(start = imgA) }
        awaitPicture()
        val before = pictureCorner()
        onNodeWithTag("chat_media_viewer_image").performMouseInput { doubleClick(center) }
        pastDoubleTap()
        assertTrue(pictureCorner().x < before.x, "zoomed in")
        assertTrue(barsShown(), "a double click is a zoom, not a hide")
    }

    @Test fun a_swipe_between_items_leaves_the_bars() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) { Viewer(start = imgA) }
        awaitPicture()
        onNodeWithTag("chat_media_viewer_pager").performTouchInput { swipeLeft() }
        pastDoubleTap()
        counter().assertTextEquals("2 / 3")
        assertTrue(barsShown(), "a swipe moves on; it hides nothing")
    }

    @Test fun a_zoomed_picture_pans_under_two_fingers_and_turns_no_page() = runComposeUiTest {
        setPlatformContent(macTrackpad()) { Viewer(start = imgA) }
        awaitPicture()
        onNodeWithTag("chat_media_viewer_zoom_in").performClick()
        onNodeWithTag("chat_media_viewer_zoom_in").performClick()
        waitForIdle()
        val before = pictureCorner().x
        trackpad(*shortFlick)
        trackpadGoesQuiet()
        counter().assertTextEquals("1 / 3")
        assertTrue(pictureCorner().x < before - 10f, "fingers moving left take the zoomed picture left")
    }
}
