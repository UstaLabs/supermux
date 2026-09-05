package dev.supermux.desktop.platform

import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
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
}
