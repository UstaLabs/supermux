package dev.supermux.desktop.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.awt.Image
import java.awt.image.ImageObserver
import java.awt.image.BufferedImage
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Paste-image contract for [DesktopComposer]: pure key/MIME helpers, clipboard Transferable
 * extraction, caps/cleanup, and the Attach-menu "Paste image" path that drives the SAME
 * [stageFiles] funnel (via [launchPasteImages] / the `pasteImageFiles` seam). Never touches the
 * real system clipboard — tests inject [DesktopComposer]'s `pasteImageFiles` seam or a fake
 * [Transferable].
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerPasteTest {

    // ── pure key predicate ──────────────────────────────────────────────────────
    @Test fun pasteKey_ctrlOrMeta_v_down_isPaste() {
        // Ctrl path (Windows/Linux)
        assertTrue(
            isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlOrMeta = true, shiftPressed = false),
        )
        // Meta path (macOS Cmd) is the same flag — production passes isCtrlPressed || isMetaPressed
        assertTrue(
            isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlOrMeta = true, shiftPressed = false),
        )
    }

    @Test fun pasteKey_v_without_modifier_isNotPaste() {
        assertFalse(isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlOrMeta = false))
    }

    @Test fun pasteKey_shift_v_isNotPaste_fallsThroughForPlainText() {
        // Ctrl/Cmd+Shift+V = paste as plain text / match style — must NOT be consumed.
        assertFalse(
            isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlOrMeta = true, shiftPressed = true),
        )
    }

    @Test fun pasteKey_otherKeys_areNotPaste() {
        assertFalse(isComposerPasteKey(Key.V, KeyEventType.KeyUp, ctrlOrMeta = true))
        assertFalse(isComposerPasteKey(Key.C, KeyEventType.KeyDown, ctrlOrMeta = true))
        assertFalse(isComposerPasteKey(Key.Enter, KeyEventType.KeyDown, ctrlOrMeta = true))
    }

    // ── image-file classification ───────────────────────────────────────────────
    @Test fun isComposerImageFile_accepts_png_extension() {
        val png = tempNamed("shot.png") { writeBytes(tinyPng()) }
        assertTrue(isComposerImageFile(png))
    }

    @Test fun isComposerImageFile_rejects_text_and_missing() {
        val txt = tempNamed("note.txt") { writeText("hi") }
        assertFalse(isComposerImageFile(txt))
        assertFalse(isComposerImageFile(File("/nonexistent/nope.png")))
    }

    // ── clipboard transferable helpers ──────────────────────────────────────────
    @Test fun clipboardTransferable_fileList_keepsOnlyImageFiles() {
        val png = tempNamed("a.png") { writeBytes(tinyPng()) }
        val txt = tempNamed("b.txt") { writeText("nope") }
        val missing = File(png.parentFile, "gone.png")
        val got = composerFilesFromClipboardTransferable(FakeFileListTransferable(listOf(png, txt, missing)))
        assertEquals(listOf(png.absoluteFile), got.map { it.absoluteFile })
    }

    @Test fun clipboardTransferable_rasterImage_writesTempPng() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).also { bi ->
            for (y in 0 until 4) for (x in 0 until 4) bi.setRGB(x, y, 0xFF00FF00.toInt())
        }
        val got = composerFilesFromClipboardTransferable(FakeImageTransferable(img))
        assertEquals(1, got.size)
        assertTrue(got[0].isFile)
        assertTrue(got[0].name.endsWith(".png"))
        assertTrue(isComposerPasteTempFile(got[0]))
        // Round-trip: the temp file is a real PNG ImageIO can re-read.
        val reloaded = ImageIO.read(got[0])
        assertTrue(reloaded != null && reloaded.width == 4 && reloaded.height == 4)
        cleanupComposerPasteTemp(got[0])
        assertFalse(got[0].exists())
    }

    @Test fun clipboardTransferable_empty_when_textOnly() {
        val got = composerFilesFromClipboardTransferable(FakeTextTransferable("hello"))
        assertTrue(got.isEmpty())
    }

    @Test fun transferableLikelyHasImage_textOnly_isFalse() {
        assertFalse(transferableLikelyHasImage(FakeTextTransferable("hello")))
    }

    @Test fun transferableLikelyHasImage_raster_isTrue() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        assertTrue(transferableLikelyHasImage(FakeImageTransferable(img)))
    }

    @Test fun clipboardImageToTempFile_encodesBufferedImage() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val file = clipboardImageToTempFile(img)
        assertTrue(file != null && file.isFile)
        assertTrue(file!!.length() > 0)
        cleanupComposerPasteTemp(file)
    }

    // ── caps: dimension + pixel + reject without huge alloc ─────────────────────
    @Test fun clipboardImageWithinCaps_rejectsOversizeEdgeAndPixels() {
        assertTrue(clipboardImageWithinCaps(4, 4))
        assertTrue(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE, 1))
        assertFalse(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE + 1, 1))
        assertFalse(clipboardImageWithinCaps(1, PASTE_IMAGE_MAX_EDGE + 1))
        // Pixel cap: even if each edge is under the edge cap, w*h can exceed.
        val edge = (kotlin.math.sqrt(PASTE_IMAGE_MAX_PIXELS.toDouble()) + 100).toInt()
            .coerceAtMost(PASTE_IMAGE_MAX_EDGE)
        if (edge.toLong() * edge > PASTE_IMAGE_MAX_PIXELS) {
            assertFalse(clipboardImageWithinCaps(edge, edge))
        }
        assertFalse(clipboardImageWithinCaps(0, 10))
        assertFalse(clipboardImageWithinCaps(-1, 10))
    }

    @Test fun clipboardImageToTempFile_rejectsHugeDimsWithoutEncoding() {
        // Fake Image reports absurd dimensions without allocating a pixel buffer.
        val huge = DimensionOnlyImage(width = 50_000, height = 50_000)
        val start = System.nanoTime()
        val file = clipboardImageToTempFile(huge)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(file, "oversize paste must be rejected before encode")
        // Must be essentially free — not multi-second PNG encode.
        assertTrue(elapsedMs < 500, "oversize reject took ${elapsedMs}ms (expected <500ms)")
    }

    // ── temp-file cleanup ───────────────────────────────────────────────────────
    @Test fun cleanupComposerPasteTemp_deletesFileAndEmptyParent() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val file = clipboardImageToTempFile(img)
        assertNotNull(file)
        val parent = file!!.parentFile
        assertTrue(file.exists())
        assertTrue(parent != null && parent.exists())
        cleanupComposerPasteTemp(file)
        assertFalse(file.exists())
        assertFalse(parent!!.exists())
    }

    @Test fun cleanupComposerPasteTemp_ignoresNonPasteFiles() {
        val regular = tempNamed("not-paste.png") { writeBytes(tinyPng()) }
        assertFalse(isComposerPasteTempFile(regular))
        cleanupComposerPasteTemp(regular)
        assertTrue(regular.exists(), "non-paste files must not be deleted")
    }

    // ── stage-or-fallthrough decision (drives the Ctrl/Cmd+V handler) ───────────
    @Test fun shouldStageClipboardPaste_requiresUploadAndFiles() {
        val png = tempNamed("a.png") { writeBytes(tinyPng()) }
        assertTrue(shouldStageClipboardPaste(uploadBound = true, files = listOf(png)))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = listOf(png)))
        assertFalse(shouldStageClipboardPaste(uploadBound = true, files = emptyList()))
        assertFalse(shouldStageClipboardPaste(uploadBound = false, files = emptyList()))
    }

    @Test fun textOnlyPaste_doesNotStage_fallsThroughToField() {
        // Production wiring: empty pasteImageFiles → shouldStage false → key handler returns false
        // so the OutlinedTextField keeps Ctrl/Cmd+V for text. Prove the pure decision + seam.
        val files = composerFilesFromClipboardTransferable(FakeTextTransferable("hello world"))
        assertTrue(files.isEmpty())
        assertFalse(shouldStageClipboardPaste(uploadBound = true, files = files))
    }

    /**
     * Production wiring: Attach menu → "Paste image" → [launchPasteImages] → `pasteImageFiles`
     * seam → [stageFiles]. This is the mouse-discoverable path and exercises the real seam
     * (unlike the old test that only clicked Attach/picker).
     */
    @Test fun pasteImage_viaAttachMenu_uploadsAndEnablesSend() = runComposeUiTest {
        val png = tempNamed("pasted.png") { writeBytes(tinyPng()) }
        val uploaded = mutableListOf<String>()
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(png) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("pasted.png").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("pasted.png"), uploaded)
        onNodeWithTag("composer-send").assertIsEnabled()
    }

    /**
     * Temp cleanup after the chip is removed: paste-origin PNG must not linger for the app lifetime.
     */
    @Test fun pasteTemp_cleanedAfterChipRemoved() = runComposeUiTest {
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        val temp = clipboardImageToTempFile(img)!!
        assertTrue(temp.exists())
        setContent {
            DesktopComposer(
                draft = "",
                onDraftChange = {},
                sending = false,
                agentWorking = false,
                onSend = { _, _ -> },
                onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "file-1" },
                pickFiles = { emptyList() },
                pasteImageFiles = { listOf(temp) },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        onNodeWithTag("composer-paste-image").performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip").fetchSemanticsNodes().isNotEmpty()
        }
        // Wait until upload settles so the × remove is visible.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("composer-chip-remove").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer-chip-remove").performClick()
        waitUntil(timeoutMillis = 2_000L) { !temp.exists() }
        assertFalse(temp.exists(), "paste temp must be deleted when the chip is removed")
    }

    // ── large-paste path must not block the caller when run on IO (measurement) ──
    @Test fun largeRasterEncode_onIoDispatcher_completesWithoutHangingCaller() {
        // 1024² is large enough to exercise encode work but cheap enough for CI. The production
        // path runs this on Dispatchers.IO; we assert the same hop + that caps allow this size.
        val img = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB)
        assertTrue(clipboardImageWithinCaps(1024, 1024))
        val fileRef = AtomicReference<File?>(null)
        val latch = CountDownLatch(1)
        val start = System.nanoTime()
        // Simulate the production key-handler hop: encode off the "UI" thread.
        Thread({
            try {
                fileRef.set(clipboardImageToTempFile(img))
            } finally {
                latch.countDown()
            }
        }, "paste-encode-worker").start()
        // Caller (UI thread stand-in) is not blocked for multi-second work — we only wait for the
        // worker, which is what launch(Dispatchers.IO) does. Assert encode finished in a bound.
        assertTrue(latch.await(30, TimeUnit.SECONDS), "encode worker did not finish")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val file = fileRef.get()
        assertNotNull(file, "1024² paste within caps must encode")
        assertTrue(file!!.isFile && file.length() > 0)
        cleanupComposerPasteTemp(file)
        // Sanity: even on a slow host this should be well under the old 3s UI freeze for 4k².
        assertTrue(elapsedMs < 15_000, "encode took ${elapsedMs}ms")
    }

    // ── fixtures ────────────────────────────────────────────────────────────────
    private fun tempNamed(name: String, write: File.() -> Unit): File {
        // Prefix must NOT start with COMPOSER_PASTE_TEMP_PREFIX ("composer-paste"), or
        // isComposerPasteTempFile would treat fixtures as paste temps.
        val dir = Files.createTempDirectory("cmp-paste-fixture").toFile().apply { deleteOnExit() }
        return File(dir, name).apply {
            write()
            deleteOnExit()
        }
    }

    private fun tinyPng(): ByteArray {
        // 2×2 RGB PNG
        val hex =
            "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
                "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082"
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Image that only reports dimensions — used to prove oversize rejection without allocating. */
    private class DimensionOnlyImage(private val width: Int, private val height: Int) : Image() {
        override fun getWidth(observer: ImageObserver?): Int = width
        override fun getHeight(observer: ImageObserver?): Int = height
        override fun getSource() = throw UnsupportedOperationException()
        override fun getGraphics() = throw UnsupportedOperationException()
        override fun getProperty(name: String?, observer: ImageObserver?) = UndefinedProperty
        @Deprecated("Deprecated in Java")
        override fun flush() {}
    }

    /** Minimal Transferable that only exposes [DataFlavor.javaFileListFlavor]. */
    private class FakeFileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
            return files
        }
    }

    /** Minimal Transferable that only exposes [DataFlavor.imageFlavor]. */
    private class FakeImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    /** Text-only Transferable — paste-image must ignore it. */
    private class FakeTextTransferable(private val text: String) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor?): Any {
            if (flavor != DataFlavor.stringFlavor) throw UnsupportedFlavorException(flavor)
            return text
        }
    }
}
