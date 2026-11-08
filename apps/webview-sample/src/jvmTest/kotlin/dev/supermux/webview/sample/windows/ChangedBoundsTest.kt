package dev.supermux.webview.sample.windows

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangedBoundsTest {
    private val area = Rectangle(100, 200, 60, 50)

    private fun image(fill: Int, block: BufferedImage.() -> Unit = {}) =
        BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, fill)
            block()
        }

    private fun BufferedImage.paint(x: Int, y: Int, w: Int, h: Int, rgb: Int) {
        for (yy in y until y + h) for (xx in x until x + w) setRGB(xx, yy, rgb)
    }

    @Test
    fun whatAppearedIsBoundedOnScreen() {
        val before = image(0x202124)
        val after = image(0x202124) { paint(10, 20, 30, 15, 0xF9F9F9) }
        assertEquals(Rectangle(110, 220, 30, 15), WindowsChecks.changedBounds(before, after, area, except = Rectangle()))
    }

    @Test
    fun theExceptedPlaceAndSmallDifferencesCountForNothing() {
        val before = image(0x202124)
        val after = image(0x202124) {
            paint(0, 0, 20, 10, 0xFFFFFF)       // where the input is: its own text
            paint(30, 30, 5, 5, 0x2A2B2E)       // a shade off: within the tolerance
        }
        assertNull(WindowsChecks.changedBounds(before, after, area, except = Rectangle(100, 200, 20, 10)))
    }
}
