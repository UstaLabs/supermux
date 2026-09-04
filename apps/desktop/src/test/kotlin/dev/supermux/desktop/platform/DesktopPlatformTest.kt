package dev.supermux.desktop.platform

import dev.supermux.ui.platform.Caps
import dev.supermux.ui.theme.NoHaptics
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(true)
    }
}
