package dev.supermux.desktop.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Image attachments must PAINT in the chat stream, not show as a download chip (desktop had only
 * the chip, so a pasted screenshot arrived as a filename). Hosts the real [AttachmentList] under
 * [runComposeUiTest] with the `loadBytes` seam faked — no broker, no network. Clicks are NOT
 * exercised: the production click writes a temp file and hands it to the OS viewer, which would
 * spawn a process from the Gradle test worker.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentImageTest {

    private fun imageAtt(kind: String? = "image", mime: String? = "image/png") =
        Attachment(file_id = "f1", kind = kind, mime = mime, name = "shot.png")

    @Test fun image_attachment_paints_inline() = runComposeUiTest {
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { byteArrayOf(1, 2, 3, 4) },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("attachment_chip").assertIsDisplayed()
    }

    @Test fun non_image_attachment_keeps_the_chip() = runComposeUiTest {
        setContent {
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
        setContent {
            AttachmentList(
                attachments = listOf(imageAtt()),
                alignEnd = false,
                loadBytes = { kotlinx.coroutines.awaitCancellation() },
            )
        }
        onNodeWithTag("attachment_image_loading").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("attachment_chip").fetchSemanticsNodes().size)
    }

    @Test fun openImageBytesExternally_names_the_temp_file_from_the_attachment() {
        // Pure part of the click path: the file lands with a viewer-friendly name + extension.
        val file = assertNotNull(writeImageTempFile(TINY_PNG_BYTES, "reports/2026 shot.png"), "expected a temp file")
        assertEquals("2026 shot.png", file.name, "keep the basename + extension")
        assertTrue(file.readBytes().contentEquals(TINY_PNG_BYTES), "bytes must round-trip")
        file.delete()
    }

    @Test fun writeImageTempFile_defaults_a_missing_extension() {
        val file = assertNotNull(writeImageTempFile(TINY_PNG_BYTES, "clipboard"))
        assertEquals("clipboard.png", file.name, "viewers pick their decoder from the extension")
        file.delete()
    }

    companion object {
        // 2×2 RGB PNG (73 bytes) — enough for Skiko to produce a real ImageBitmap.
        private val TINY_PNG_BYTES = hex(
            "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
                "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082",
        )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
