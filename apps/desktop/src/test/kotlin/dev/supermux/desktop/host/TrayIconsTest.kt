package dev.supermux.desktop.host

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TrayIconsTest {
    private fun resource(name: String): BufferedImage =
        javaClass.classLoader.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    @Test fun pickVariantTakesTheSmallestThatFits() {
        assertEquals(0, pickVariant(listOf(22, 44), 22f))
        assertEquals(1, pickVariant(listOf(22, 44), 44f))
        assertEquals(1, pickVariant(listOf(22, 44), 33f))
        assertEquals(1, pickVariant(listOf(22, 44), 88f)) // nothing fits: the biggest
        assertEquals(0, pickVariant(listOf(22, 44), 16f))
    }

    @Test fun templatesAreBlackPlusAlphaAt22And44() {
        for ((name, size) in listOf("supermux-tray-template-22.png" to 22, "supermux-tray-template.png" to 44)) {
            val img = resource(name)
            assertEquals(size, img.width, name)
            assertEquals(size, img.height, name)
            var opaque = 0
            for (y in 0 until size) for (x in 0 until size) {
                val argb = img.getRGB(x, y)
                if (argb ushr 24 != 0) {
                    opaque++
                    assertEquals(0, argb and 0xFFFFFF, "$name ($x,$y) is not black")
                }
            }
            assertTrue(opaque > size * size / 4, "$name is mostly empty")
        }
    }

    @Test fun macGetsTheTemplatePainterAndOthersTheColourIcon() {
        assertIs<MultiSizeBitmapPainter>(TrayIcons.painter(mac = true))
        assertIs<BitmapPainter>(TrayIcons.painter(mac = false))
    }

    @Test fun aLightWindowsTaskbarGetsTheBlackGlyph() {
        assertIs<MultiSizeBitmapPainter>(TrayIcons.painter(mac = false, darkGlyph = true))
    }

    @Test fun theTaskbarThemeIsOnlyReadOnWindows() {
        var reads = 0
        val light = { reads++; true }
        assertEquals(true, TrayIcons.darkGlyph(mac = true, windows = false, taskbarIsLight = light))
        assertEquals(false, TrayIcons.darkGlyph(mac = false, windows = false, taskbarIsLight = light))
        assertEquals(0, reads, "no registry read off Windows")
        assertEquals(true, TrayIcons.darkGlyph(mac = false, windows = true, taskbarIsLight = light))
        assertEquals(false, TrayIcons.darkGlyph(mac = false, windows = true, taskbarIsLight = { false }))
        assertEquals(1, reads)
    }

    @Test fun parsesTheTaskbarThemeFromRegQuery() {
        val light = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n" +
            "    SystemUsesLightTheme    REG_DWORD    0x1\r\n\r\n"
        assertEquals(true, TrayIcons.parseSystemUsesLightTheme(light))
        assertEquals(false, TrayIcons.parseSystemUsesLightTheme(light.replace("0x1", "0x0")))
        assertEquals(null, TrayIcons.parseSystemUsesLightTheme("ERROR: The system was unable to find the specified registry key or value."))
    }

    /** What Compose's Tray does with the painter on a Retina Mac: 22 pt at density 2. */
    @Test fun retinaVariantIsThe44pxTemplateOneToOne() {
        val awt = TrayIcons.painter(mac = true).toAwtImage(Density(2f), LayoutDirection.Ltr, Size(22f, 22f))
        val variants = (awt as MultiResolutionImage).resolutionVariants.map { it as BufferedImage }
        assertEquals(listOf(22, 44), variants.map { it.width })
        assertSameAlpha(resource("supermux-tray-template-22.png"), variants[0])
        assertSameAlpha(resource("supermux-tray-template.png"), variants[1])
    }

    private fun assertSameAlpha(want: BufferedImage, got: BufferedImage) {
        assertEquals(want.width, got.width)
        var worst = 0
        for (y in 0 until want.height) for (x in 0 until want.width) {
            worst = maxOf(worst, abs((want.getRGB(x, y) ushr 24) - (got.getRGB(x, y) ushr 24)))
        }
        assertTrue(worst <= 2, "alpha differs by up to $worst")
    }

    /** The theme was read once, on the UI thread, by a reg.exe with no time limit; it now goes through OsEnv's limit. */
    @Test fun theTaskbarThemeIsReadThroughOsEnvWithATimeLimit() {
        val asked = mutableListOf<Pair<List<String>, Long>>()
        val os = object : OsEnv by FakeOs(OsEnv.Os.WINDOWS) {
            override fun runResult(argv: List<String>) = runResult(argv, -1)
            override fun runResult(argv: List<String>, timeoutMs: Long): OsEnv.RunResult {
                asked += argv to timeoutMs
                return OsEnv.RunResult(0, "    SystemUsesLightTheme    REG_DWORD    0x1\r\n", "")
            }
        }
        assertEquals(true, TrayIcons.windowsTaskbarIsLight(os))
        assertEquals("reg", asked.single().first[0])
        assertTrue(asked.single().second in 1..10_000, "a time limit: ${asked.single().second}")
        val failing = object : OsEnv by FakeOs(OsEnv.Os.WINDOWS) {
            override fun runResult(argv: List<String>) = OsEnv.RunResult(-1, "", "timed out")
            override fun runResult(argv: List<String>, timeoutMs: Long) = runResult(argv)
        }
        assertEquals(false, TrayIcons.windowsTaskbarIsLight(failing), "can't tell: the white icon")
    }

    /** Switching Windows between light and dark left the tray glyph invisible until the app restarted. */
    @Test fun aThemeSwitchReachesTheTray() = runTest {
        var light = false
        var reads = 0
        val seen = mutableListOf<Boolean>()
        val job = launch {
            TrayIcons.taskbarIsLight(read = { reads++; light }, everyMs = 30_000, dispatcher = StandardTestDispatcher(testScheduler)).toList(seen)
        }
        runCurrent()
        assertEquals(listOf(false), seen)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(false), seen, "the same theme draws nothing new")
        light = true
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(false, true), seen)
        assertEquals(3, reads)
        job.cancel()
    }
}
