package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayIcons
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SniPixmapTest {
    @Test fun argb32IsBigEndianRowMajor() {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0x80FF0000.toInt()) // half-transparent red
        img.setRGB(1, 0, 0xFF00FF00.toInt()) // opaque green
        img.setRGB(0, 1, 0xFF0000FF.toInt()) // opaque blue
        img.setRGB(1, 1, 0x00000000) // clear
        val bytes = SniPixmap.argb32(img)
        assertEquals(2 * 2 * 4, bytes.size)
        assertContentEquals(
            byteArrayOf(
                0x80.toByte(), 0xFF.toByte(), 0, 0,
                0xFF.toByte(), 0, 0xFF.toByte(), 0,
                0xFF.toByte(), 0, 0, 0xFF.toByte(),
                0, 0, 0, 0,
            ),
            bytes,
        )
    }

    @Test fun anImageWithoutAlphaIsOpaque() {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0x123456)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x12, 0x34, 0x56), SniPixmap.argb32(img))
    }

    @Test fun iconsComeAtEverySize() {
        val src = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val icons = SniPixmap.icons(src)
        assertEquals(listOf(16, 22, 32, 48, 64), icons.map { it.width })
        assertEquals(listOf(16, 22, 32, 48, 64), icons.map { it.height })
        assertEquals(listOf(16, 22, 32, 48, 64).map { it * it * 4 }, icons.map { it.argb.size })
    }

    @Test fun theColourTrayIconLoads() {
        val icons = SniPixmap.fromResource(TrayIcons.COLOUR)
        assertEquals(SniPixmap.SIZES, icons.map { it.width })
        // Not blank: some pixel is opaque.
        assertTrue(icons.last().argb.withIndex().any { (i, b) -> i % 4 == 0 && b == 0xFF.toByte() })
    }

    @Test fun aMissingResourceIsNoIcons() {
        assertEquals(emptyList(), SniPixmap.fromResource("no-such-icon.png"))
    }
}
