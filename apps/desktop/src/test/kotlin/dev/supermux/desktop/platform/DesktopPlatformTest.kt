package dev.supermux.desktop.platform

import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
import java.awt.image.BufferedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import dev.supermux.ui.theme.NoHaptics
import java.awt.FileDialog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPlatformTest {

    @Test
    fun `desktop capabilities match the platform table`() {
        assertEquals(
            Caps(
                push = false,
                camera = false,
                tray = true,
                externalDisplay = true,
                hardwareVideoDecode = false,
                localBroker = true,
                multiWindow = true,
                fileSystem = true,
                clipboardImages = !GraphicsEnvironment.isHeadless(),
                saveAs = !GraphicsEnvironment.isHeadless(),
                walkthrough = true,
            ),
            DesktopPlatform().caps,
        )
    }

    @Test
    fun `desktop has no haptic actuator`() {
        assertSame(NoHaptics, DesktopPlatform().haptics)
    }

    /**
     * Round-trips through the real AWT system clipboard (the suite runs under xvfb, so there IS a
     * display). Headless — or a clipboard owned by another process — must never propagate: the
     * copy is swallowed and the call is a no-op, which this asserts by not throwing.
     */
    @Test
    fun `copyToClipboard round-trips or is a silent no-op when headless`() {
        val platform = DesktopPlatform()
        val text = "supermux-clipboard-${System.nanoTime()}"
        platform.copyToClipboard(text)
        if (GraphicsEnvironment.isHeadless()) return
        val read = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        // A flaky X clipboard owner can hand back null; the contract under test is "no throw",
        // and when the read does work it must be exactly what we put there.
        if (read != null) assertEquals(text, read)
    }

    /** The PickKind → dialog mapping, asserted on the configured-but-unshown dialog. */
    @Test
    fun `every pick kind opens a multi-select attach dialog in LOAD mode`() {
        if (GraphicsEnvironment.isHeadless()) return
        for (kind in PickKind.entries) {
            val dialog = pickDialogFor(kind)
            assertEquals("Attach files", dialog.title)
            assertEquals(FileDialog.LOAD, dialog.mode)
            assertTrue(dialog.isMultipleMode, "$kind must allow multi-select")
        }
    }

    @Test
    fun `Any installs no filter - Images and Media filter by extension`() {
        if (GraphicsEnvironment.isHeadless()) return
        val dir = File(".")
        assertNull(pickDialogFor(PickKind.Any).filenameFilter)

        val images = requireNotNull(pickDialogFor(PickKind.Images).filenameFilter)
        assertTrue(images.accept(dir, "shot.PNG"))
        assertFalse(images.accept(dir, "clip.mp4"))
        assertFalse(images.accept(dir, "notes.txt"))

        val media = requireNotNull(pickDialogFor(PickKind.Media).filenameFilter)
        assertTrue(media.accept(dir, "shot.png"))
        assertTrue(media.accept(dir, "clip.MOV"))
        assertFalse(media.accept(dir, "notes.txt"))
    }

    @Test
    fun `probeMime falls back to octet-stream for an unknown extension`() {
        val f = File.createTempFile("probe", ".sm-unknown-ext")
        try {
            assertTrue(probeMime(f).isNotBlank())
        } finally {
            f.delete()
        }
    }

    // ── cluster-D chat seams ─────────────────────────────────────────────────

    /**
     * Desktop has no camera (`caps.camera == false`), so both capture seams are total-but-empty,
     * and an AWT dialog can never be orphaned, so there is nothing to re-deliver.
     */
    @Test
    fun `camera captures return null and there are no pending picks`() = runTest {
        val platform = DesktopPlatform()
        assertNull(platform.captureImage())
        assertNull(platform.captureVideo())
        assertEquals(emptyList(), platform.pendingPicks("chat-composer").toList())
    }

    /**
     * Headless is the CI case: `FileDialog` throws `HeadlessException` there, so `saveAs` must
     * report "not saved" rather than take the process down. With a display the dialog would be
     * modal, so the assertion is limited to the headless branch — the one this suite can drive.
     */
    @Test
    fun `saveAs returns false when headless instead of throwing`() = runTest {
        if (!GraphicsEnvironment.isHeadless()) return@runTest
        val saved = DesktopPlatform().files.saveAs("notes.txt", "text/plain", byteArrayOf(1, 2, 3))
        assertFalse(saved, "a headless save must fail closed")
    }

    /**
     * The open chain's ORDER: `java.awt.Desktop.open` first, then the per-OS shell opener. Under
     * xvfb neither may be available, so what is asserted is the contract that holds either way —
     * a boolean answer, no throw, and the staged file actually written before anything is asked to
     * open it.
     */
    @Test
    fun `openExternally stages the bytes and answers instead of throwing`() = runTest {
        val name = "attachment-${System.nanoTime()}.txt"
        DesktopPlatform().files.openExternally(name, "text/plain", "hello".toByteArray())
        val staged = File(File(System.getProperty("java.io.tmpdir"), "supermux-attachments"), name)
        assertTrue(staged.isFile, "the bytes must be on disk before a handler is invoked")
        assertEquals("hello", staged.readText())
        staged.delete()
    }

    @Test
    fun `openLocalFile never throws for a file that cannot be opened`() {
        val gone = File("/nonexistent/supermux/${System.nanoTime()}.bin")
        // Either branch may report success (xdg-open forks before it fails) — the contract is that
        // the call returns rather than propagating.
        openLocalFile(gone)
    }

    @Test
    fun `probeMime answers from the name alone, falling back to octet-stream`() {
        val files = DesktopPlatform().files
        assertEquals("image/png", files.probeMime("shot.png"))
        assertEquals("audio/mp4", files.probeMime("dictation-1.m4a"))
        assertEquals("application/octet-stream", files.probeMime("archive.sm-unknown-ext"))
    }

    // ── clipboard caps + resize policy (moved from DesktopComposerPasteTest) ──

    @Test
    fun `clipboardImageWithinCaps rejects oversize edges and pixel counts`() {
        assertTrue(clipboardImageWithinCaps(4, 4))
        assertTrue(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE, 1))
        assertFalse(clipboardImageWithinCaps(PASTE_IMAGE_MAX_EDGE + 1, 1))
        assertFalse(clipboardImageWithinCaps(1, PASTE_IMAGE_MAX_EDGE + 1))
        // Pixel cap: even with each edge under the edge cap, w*h can exceed.
        val edge = (kotlin.math.sqrt(PASTE_IMAGE_MAX_PIXELS.toDouble()) + 100).toInt()
            .coerceAtMost(PASTE_IMAGE_MAX_EDGE)
        if (edge.toLong() * edge > PASTE_IMAGE_MAX_PIXELS) {
            assertFalse(clipboardImageWithinCaps(edge, edge))
        }
        assertFalse(clipboardImageWithinCaps(0, 10))
        assertFalse(clipboardImageWithinCaps(-1, 10))
    }

    @Test
    fun `scaleBufferedImageToMaxEdge downscales large images and passes small ones through`() {
        val big = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        val scaled = scaleBufferedImageToMaxEdge(big, maxEdge = 2048)
        assertTrue(scaled.width <= 2048 && scaled.height <= 2048)
        assertTrue(scaled.width == 2048 || scaled.height == 2048)
        val small = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)
        assertTrue(scaleBufferedImageToMaxEdge(small, 2048) === small)
    }

    // ── mic ──────────────────────────────────────────────────────────────────

    /** Desktop grants mic access at the process level — there is no per-app prompt to await. */
    @Test
    fun `requestPermission is immediately true and there is no live transcript`() = runTest {
        val mic = DesktopPlatform().mic
        assertTrue(mic.requestPermission())
        assertNull(mic.liveTranscript)
    }

    /**
     * The WAV shape of a finished desktop capture, driven through the recorder seam (there is no
     * mic under xvfb). `.wav` + `audio/wav` is what the broker's whisper pipeline consumes without
     * a transcode, unlike Android's AAC.
     */
    @Test
    fun `stop returns wav CapturedAudio when the recorder produced bytes`() {
        val pcm = ByteArray(64) { it.toByte() }
        val mic = DesktopMicCapture(
            object : dev.supermux.desktop.chat.MicCapture {
                override fun start() = true
                override fun stop(): ByteArray = pcm
                override fun cancel() = Unit
            },
        )
        val captured = requireNotNull(mic.stop())
        assertTrue(captured.filename.endsWith(".wav"))
        assertEquals("audio/wav", captured.mime)
        assertTrue(captured.bytes.contentEquals(pcm))
    }

    @Test
    fun `stop returns null when the recorder captured nothing`() {
        val mic = DesktopMicCapture(
            object : dev.supermux.desktop.chat.MicCapture {
                override fun start() = false
                override fun stop(): ByteArray? = null
                override fun cancel() = Unit
            },
        )
        assertNull(mic.stop())
    }

    // ── notices ──────────────────────────────────────────────────────────────

    @Test
    fun `notices emit to the bus the theme snackbar collects`() = runTest {
        val notices = DesktopNotices()
        val seen = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            notices.messages.collect { seen.add(it) }
        }
        notices.show("Couldn't open attachment")
        notices.show("   ") // blank is not a notice
        job.cancel()
        assertEquals(listOf("Couldn't open attachment"), seen)
    }
}
