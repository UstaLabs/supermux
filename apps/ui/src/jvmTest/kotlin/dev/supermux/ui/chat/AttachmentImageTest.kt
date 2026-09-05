package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.Attachment
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Image attachments must PAINT in the chat stream, not show as a download chip. Hosts the real
 * [AttachmentList] under [runComposeUiTest] with the `loadBytes` seam faked — no broker, no
 * network; bytes decode through Coil's `ByteArray` fetcher exactly as in production.
 *
 * The lightbox tests are new in cluster D2: a tap opens it on BOTH hosts now (Android had it,
 * desktop opened an OS viewer), so it is asserted under a pointer AND under touch.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentImageTest {

    private fun imageAtt(kind: String? = "image", mime: String? = "image/png") =
        Attachment(file_id = "f1", kind = kind, mime = mime, name = "shot.png")

    @Test fun image_attachment_paints_inline() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { TINY_PNG_BYTES },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_image").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size,
            "a decodable image must not also render the download chip",
        )
    }

    @Test fun telegram_photo_kind_paints_inline() = runComposeUiTest {
        // Telegram sends kind="photo" with no mime — the pre-fix chip path covered it, so must this.
        setPlatformContent {
            AttachmentList(
                attachments = listOf(imageAtt(kind = "photo", mime = null)),
                alignEnd = true,
                loadBytes = { TINY_PNG_BYTES },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_image").assertIsDisplayed()
    }

    @Test fun failed_image_load_falls_back_to_download_chip() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { null },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().size,
            "a failed load must not leave an empty image in the stream",
        )
    }

    @Test fun undecodable_bytes_fall_back_to_download_chip() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { byteArrayOf(1, 2, 3, 4) },
            )
        }
        waitUntil(timeoutMillis = 10_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun non_image_attachment_keeps_the_chip() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(
                    Attachment(file_id = "f2", kind = "document", mime = "application/pdf", name = "spec.pdf"),
                ),
                alignEnd = false,
                loadBytes = { TINY_PNG_BYTES },
            )
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().size,
            "a pdf must never be painted as an image",
        )
    }

    @Test fun image_is_shown_before_it_loads_as_a_placeholder_not_a_chip() = runComposeUiTest {
        // Never-resolving loader: the timeline must reserve space, not flash the download chip.
        setPlatformContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { kotlinx.coroutines.awaitCancellation() },
            )
        }
        onNodeWithTag("attachment_image_loading").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
    }

    // ── Lightbox (union: Android's, now on both) ──────────────────────────────────────

    @Test fun tapping_an_image_opens_the_lightbox_under_a_pointer() = runComposeUiTest {
        setPlatformContent(pointer = true) {
            AttachmentList(listOf(imageAtt()), alignEnd = false, loadBytes = { TINY_PNG_BYTES })
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, onAllNodesWithTag("image_lightbox").fetchSemanticsNodes().size)
        onNodeWithTag("attachment_image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("image_lightbox").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("image_lightbox").assertIsDisplayed()
    }

    @Test fun tapping_an_image_opens_the_lightbox_under_touch() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            AttachmentList(listOf(imageAtt()), alignEnd = false, loadBytes = { TINY_PNG_BYTES })
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("image_lightbox").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("image_lightbox").assertIsDisplayed()
    }

    @Test fun the_lightbox_download_hands_the_bytes_to_the_host() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform) {
            AttachmentList(listOf(imageAtt()), alignEnd = false, loadBytes = { TINY_PNG_BYTES })
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("image_lightbox_download").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("image_lightbox_download").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.opened.isNotEmpty() }
        assertEquals(listOf("shot.png|image/png|${TINY_PNG_BYTES.size}"), platform.files.opened)
    }

    // ── The file chip's two modes ─────────────────────────────────────────────────────

    @Test fun file_chip_under_touch_opens_externally() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact) {
            AttachmentList(
                attachments = listOf(Attachment(file_id = "f9", kind = "document", mime = "application/pdf", name = "spec.pdf")),
                alignEnd = false,
                loadBytes = { byteArrayOf(1, 2, 3) },
            )
        }
        onNodeWithTag("attachment_chip").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.opened.isNotEmpty() }
        assertEquals(listOf("spec.pdf|application/pdf|3"), platform.files.opened)
        assertTrue(platform.files.saved.isEmpty(), "a phone has nowhere to Save as… into")
    }

    @Test fun file_chip_under_a_pointer_saves_as_then_opens_what_was_saved() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform, pointer = true) {
            AttachmentList(
                attachments = listOf(Attachment(file_id = "f9", kind = "document", mime = "application/pdf", name = "spec.pdf")),
                alignEnd = false,
                loadBytes = { byteArrayOf(1, 2, 3) },
            )
        }
        onNodeWithTag("attachment_chip").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.saved.isNotEmpty() }
        assertEquals(listOf("spec.pdf|application/pdf|3"), platform.files.saved)
        waitUntil(timeoutMillis = 5_000L) { platform.files.openedSaved.isNotEmpty() }
        assertEquals("spec.pdf", platform.files.openedSaved.single().name)
        assertTrue(platform.files.opened.isEmpty(), "the chip must open the file the user CHOSE")
    }

    @Test fun a_failed_download_flips_the_chip_to_retry() = runComposeUiTest {
        setPlatformContent {
            AttachmentList(
                attachments = listOf(Attachment(file_id = "f9", kind = "document", mime = "application/pdf", name = "spec.pdf")),
                alignEnd = false,
                loadBytes = { null },
            )
        }
        onNodeWithTag("attachment_chip").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Download failed — tap to retry").assertIsDisplayed()
    }
}
