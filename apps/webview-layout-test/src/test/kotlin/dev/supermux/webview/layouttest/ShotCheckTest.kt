package dev.supermux.webview.layouttest

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFF
private const val CYAN = 0x00E5FF
private const val MAGENTA = 0xFF00FF
private const val BLUE = 0x2040FF

private fun image(width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { fill(it, 0, 0, width, height, WHITE) }

private fun fill(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int) {
    for (y in y0 until y1) for (x in x0 until x1) image.setRGB(x, y, rgb)
}

class ShotCheckTest {
    @Test fun thePaletteClassifies() {
        assertEquals(Paint.Page, Paint.of(BLUE))
        assertEquals(Paint.Band, Paint.of(MAGENTA))
        assertEquals(Paint.Marker, Paint.of(CYAN))
        assertEquals(Paint.Menu, Paint.of(0x00C853))
        assertEquals(Paint.Dialog, Paint.of(0xFF9100))
        assertEquals(Paint.PageDimmed, Paint.of(0x162CAD)) // the page under a 32 % black scrim
        assertNull(Paint.of(WHITE))
        assertNull(Paint.of(0xF2F2F2))
        assertNull(Paint.of(0x000000))
    }

    @Test fun displayP3ShiftedColoursStillClassify() {
        assertEquals(Paint.Page, Paint.of(0x283FF5))
        assertEquals(Paint.Band, Paint.of(0xEA33F6))
        assertEquals(Paint.Marker, Paint.of(0x64E4FA))
        assertEquals(Paint.Menu, Paint.of(0x5FC55F))
        assertEquals(Paint.Dialog, Paint.of(0xF09628))
    }

    @Test fun aProbeAveragesItsPatch() {
        val shot = image(100, 100)
        fill(shot, 0, 0, 100, 100, BLUE)
        shot.setRGB(50, 50, WHITE) // a stray pixel of text doesn't flip the class
        val results = ShotCheck.check(shot, ShotFrame(0, 0, 1.0), listOf(ColorProbe("page", 50f, 50f, Paint.Page), ColorProbe("menu", 50f, 50f, Paint.Menu)))
        assertTrue(results[0].pass, results[0].line())
        assertFalse(results[1].pass)
        assertTrue(results[1].line().startsWith("menu: expected Menu, saw Page (#"), results[1].line())
    }

    @Test fun theFrameMapsWindowPixelsIntoTheImage() {
        val shot = image(100, 140)
        fill(shot, 0, 56, 100, 140, BLUE) // the content starts under a 56 px title bar
        assertTrue(ShotCheck.check(shot, ShotFrame(0, 56, 1.0), listOf(ColorProbe("top-left", 3f, 3f, Paint.Page)))[0].pass)
    }

    private fun top(bandTop: Int, bandOverMarker: Boolean, originY: Int = 0): Int? {
        val shot = image(200, 300 + originY)
        val marker = { fill(shot, 20, 50 + originY, 180, 62 + originY, CYAN) }
        val page = {
            fill(shot, 20, bandTop + originY, 180, 250 + originY, BLUE)
            fill(shot, 20, bandTop + originY, 180, bandTop + 16 + originY, MAGENTA)
        }
        if (bandOverMarker) { marker(); page() } else { page(); marker() }
        return ShotCheck.edgeOffset(shot, ShotFrame(0, originY, 1.0), Edge.Top, line = 100f, from = 0f, to = 299f, markerPx = 12, bandPx = 16)
    }

    @Test fun topInSync() = assertEquals(0, top(62, bandOverMarker = false))

    @Test fun topWithAGap() = assertEquals(5, top(67, bandOverMarker = false))

    @Test fun topOvershootUnderCompose() = assertEquals(-4, top(58, bandOverMarker = false))

    @Test fun topOvershootOverCompose() = assertEquals(-4, top(58, bandOverMarker = true))

    @Test fun topBelowATitleBar() = assertEquals(5, top(67, bandOverMarker = false, originY = 56))

    private fun left(bandStart: Int, bandOverMarker: Boolean): Int? {
        val shot = image(300, 200)
        val marker = { fill(shot, 40, 20, 52, 180, CYAN) }
        val page = {
            fill(shot, bandStart, 20, 280, 180, BLUE)
            fill(shot, bandStart, 20, bandStart + 16, 180, MAGENTA)
        }
        if (bandOverMarker) { marker(); page() } else { page(); marker() }
        return ShotCheck.edgeOffset(shot, ShotFrame(0, 0, 1.0), Edge.Left, line = 100f, from = 0f, to = 299f, markerPx = 12, bandPx = 16)
    }

    @Test fun leftInSync() = assertEquals(0, left(52, bandOverMarker = false))

    @Test fun leftWithAGap() = assertEquals(6, left(58, bandOverMarker = false))

    @Test fun leftOvershootUnderCompose() = assertEquals(-4, left(48, bandOverMarker = false))

    @Test fun leftOvershootOverCompose() = assertEquals(-4, left(48, bandOverMarker = true))

    private fun right(bandEnd: Int, bandOverMarker: Boolean): Int? {
        val shot = image(300, 200)
        val marker = { fill(shot, 250, 20, 262, 180, CYAN) }
        val page = {
            fill(shot, 20, 20, bandEnd, 180, BLUE)
            fill(shot, bandEnd - 16, 20, bandEnd, 180, MAGENTA)
        }
        if (bandOverMarker) { marker(); page() } else { page(); marker() }
        return ShotCheck.edgeOffset(shot, ShotFrame(0, 0, 1.0), Edge.Right, line = 100f, from = 0f, to = 299f, markerPx = 12, bandPx = 16)
    }

    @Test fun rightInSync() = assertEquals(0, right(250, bandOverMarker = false))

    @Test fun rightWithAGap() = assertEquals(3, right(247, bandOverMarker = false))

    @Test fun rightOvershootUnderCompose() = assertEquals(-4, right(254, bandOverMarker = false))

    @Test fun rightOvershootOverCompose() = assertEquals(-4, right(254, bandOverMarker = true))

    @Test fun noMarkerOrNoBandIsUnmeasured() {
        assertNull(ShotCheck.edgeOffset(image(200, 300), ShotFrame(0, 0, 1.0), Edge.Top, 100f, 0f, 299f, 12, 16))
        val markerOnly = image(200, 300).also { fill(it, 20, 50, 180, 62, CYAN) }
        assertNull(ShotCheck.edgeOffset(markerOnly, ShotFrame(0, 0, 1.0), Edge.Top, 100f, 0f, 299f, 12, 16))
    }
}
