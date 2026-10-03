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
}
