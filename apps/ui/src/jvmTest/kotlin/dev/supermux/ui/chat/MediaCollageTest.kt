package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.ui.theme.Media
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two or more pictures/clips in one message are ONE collage: 2 side by side, 3 as one big + two
 * small, 4 as 2×2, more than that with "+N" on the last tile. A clip's tile downloads nothing — the
 * transcript must never eagerly fetch every clip — and a tap opens the chat's viewer at that item.
 */
@OptIn(ExperimentalTestApi::class)
class MediaCollageTest {

    private fun photo(id: String) = Attachment(file_id = id, kind = "photo", mime = "image/png", name = "$id.png")
    private fun clip(id: String) = Attachment(file_id = id, kind = "video", mime = "video/mp4", name = "$id.mp4")

    /** Counts every read: a clip's tile must cause none. */
    private class CountingMedia(private val image: ByteArray = TINY_PNG_BYTES) : AttachmentMedia {
        val reads = AtomicInteger(0)
        val videoReads = AtomicInteger(0)

        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? {
            reads.incrementAndGet()
            if (att.kind == "video") videoReads.incrementAndGet()
            return image
        }

        override suspend fun playableUri(att: Attachment): String? {
            videoReads.incrementAndGet()
            return null
        }
    }

    @Test fun two_media_in_one_message_are_one_collage() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(listOf(photo("a"), photo("b")), alignEnd = false, media = CountingMedia(), entryId = "m1")
        }
        onNodeWithTag("media_collage").assertIsDisplayed()
        onNodeWithTag("media_collage_tile:0").assertIsDisplayed()
        onNodeWithTag("media_collage_tile:1").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("attachment_image").fetchSemanticsNodes().size, "no single-image view as well")
    }

    @Test fun three_and_four_draw_one_tile_each() = runComposeUiTest {
        setPlatformContent {
            Column {
                AttachmentList(listOf(photo("a"), photo("b"), photo("c")), alignEnd = false, media = CountingMedia(), entryId = "m1")
                AttachmentList(listOf(photo("d"), photo("e"), photo("f"), photo("g")), alignEnd = false, media = CountingMedia(), entryId = "m2")
            }
        }
        assertEquals(2, onAllNodesWithTag("media_collage").fetchSemanticsNodes().size)
        assertEquals(2, onAllNodesWithTag("media_collage_tile:2").fetchSemanticsNodes().size, "both collages reach a third tile")
        assertEquals(1, onAllNodesWithTag("media_collage_tile:3").fetchSemanticsNodes().size, "only the 4-item collage has a 4th tile")
        assertEquals(0, onAllNodesWithTag("media_collage_more", useUnmergedTree = true).fetchSemanticsNodes().size, "four fit: no +N")
    }

    @Test fun more_than_four_shows_the_rest_on_the_last_tile() = runComposeUiTest {
        setPlatformContent {
            AttachmentList((1..6).map { photo("p$it") }, alignEnd = false, media = CountingMedia(), entryId = "m1")
        }
        assertEquals(1, onAllNodesWithTag("media_collage_tile:3").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("media_collage_tile:4").fetchSemanticsNodes().size, "only four tiles are drawn")
        onNodeWithTag("media_collage_more", useUnmergedTree = true).assertTextEquals("+3")
    }

    @Test fun a_clips_tile_downloads_nothing() = runComposeUiTest {
        val media = CountingMedia()
        setPlatformContent {
            AttachmentList(listOf(clip("v1"), photo("a"), clip("v2")), alignEnd = false, media = media, entryId = "m1")
        }
        onNodeWithTag("media_collage_video:0", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("media_collage_video:2", useUnmergedTree = true).assertIsDisplayed()
        waitForIdle()
        assertEquals(0, media.videoReads.get(), "a transcript must not eagerly download every clip")
    }

    @Test fun a_tap_opens_the_chat_viewer_at_that_item() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setPlatformContent {
            CompositionLocalProvider(LocalChatMediaViewer provides ChatMediaOpener { opened += it; true }) {
                AttachmentList(listOf(photo("a"), clip("v"), photo("b")), alignEnd = false, media = CountingMedia(), entryId = "m1")
            }
        }
        onNodeWithTag("media_collage_tile:1").performClick()
        onNodeWithTag("media_collage_tile:2").performClick()
        assertEquals(listOf(attachmentMediaKey("m1", "v"), attachmentMediaKey("m1", "b")), opened)
    }

    @Test fun files_beside_the_media_stay_chips() = runComposeUiTest {
        val pdf = Attachment(file_id = "d", kind = "document", mime = "application/pdf", name = "spec.pdf")
        setPlatformContent {
            AttachmentList(listOf(photo("a"), pdf, photo("b")), alignEnd = false, media = CountingMedia(), entryId = "m1")
        }
        onNodeWithTag("media_collage").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("media_collage_tile:2").fetchSemanticsNodes().size, "the pdf is not a tile")
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun a_collage_takes_its_share_of_a_narrow_column_and_stops_at_its_cap_in_a_wide_one() = runComposeUiTest {
        setPlatformContent {
            Column {
                Box(Modifier.requiredWidth(300.dp)) {
                    AttachmentList(listOf(photo("a"), photo("b")), alignEnd = false, media = CountingMedia(), entryId = "narrow")
                }
                Box(Modifier.requiredWidth(1200.dp)) {
                    AttachmentList(listOf(photo("c"), photo("d")), alignEnd = false, media = CountingMedia(), entryId = "wide")
                }
            }
        }
        val (narrow, wide) = onAllNodesWithTag("media_collage").fetchSemanticsNodes().map { it.boundsInRoot }
        val density = this.density
        with(density) {
            assertEquals(210f, narrow.width.toDp().value, 1f, "70 % of a 300 dp column")
            assertEquals(Media.collageMaxWidth.value, wide.width.toDp().value, 1f, "capped, however wide the column")
            assertEquals(wide.width / 2f, wide.height, 2f, "two side by side: twice as wide as tall")
        }
    }

    @Test fun one_media_keeps_the_single_view() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(listOf(photo("a")), alignEnd = false, media = CountingMedia(), entryId = "m1")
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodesWithTag("media_collage").fetchSemanticsNodes().size)
    }

    @Test fun outside_a_chat_a_tap_opens_a_viewer_over_the_messages_media() = runComposeUiTest {
        // No chat viewer (an archived transcript, a preview): the message's own media still open.
        setPlatformContent {
            AttachmentList(listOf(photo("a"), photo("b")), alignEnd = false, media = CountingMedia(), entryId = "m1")
        }
        onNodeWithTag("media_collage_tile:1").performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("chat_media_viewer").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("chat_media_viewer_counter", useUnmergedTree = true).assertTextEquals("2 / 2")
    }
}
