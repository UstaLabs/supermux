package dev.supermux.webview

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * C2: a cover is a page's picture at its true size, top-left, the rest the page's own background, on every engine: a
 * page that reflows keeps its text size when its viewport changes, so a scaled picture is never what the page will
 * show. Never magnified, never squeezed.
 */
class CoverDrawingTest {
    /** A [width] × [height] page: [background], a [marker]-coloured square at ([x], [y]) of [side] px. */
    private fun page(width: Int, height: Int, background: Color, marker: Color = Color.Red, x: Int = 4, y: Int = 4, side: Int = 4): ImageBitmap {
        val image = ImageBitmap(width, height)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = background })
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat(), Paint().apply { color = marker })
        return image
    }

    private fun draw(width: Int, height: Int, block: DrawScope.() -> Unit): PixelMap {
        val target = ImageBitmap(width, height)
        Canvas(target).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.Green })
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat()), block)
        return target.toPixelMap()
    }

    @Test fun aCoverIsItsTrueSizeTopLeftAndTheRestThePagesBackground() {
        // The reviewer's probe: an embed grown taller than its picture. The old cover filled the slot by its larger ratio.
        val picture = page(40, 20, Color.Blue)
        val pixels = draw(80, 80) { drawCover(picture, scale = 1f, background = Color.Blue) }
        assertEquals(Color.Red, pixels[5, 5], "the marker where the page has it")
        assertEquals(Color.Blue, pixels[17, 17], "and nowhere a magnified picture would put it")
        assertEquals(Color.Blue, pixels[60, 10], "the right: the page's background")
        assertEquals(Color.Blue, pixels[10, 60], "below: the page's background")
        assertEquals(Color.Blue, pixels[79, 79], "nothing of what is under it shows")
    }

    @Test fun itsScaleIsTheScreensNotTheSlots() {
        val picture = page(40, 20, Color.Blue)
        val pixels = draw(100, 30) { drawCover(picture, scale = 2f, background = Color.White) }
        assertEquals(Color.Red, pixels[9, 9], "the marker at twice its pixels: (8, 8) to (16, 16)")
        assertEquals(Color.Blue, pixels[20, 20], "the page's own pixels, cut at the slot's bottom")
        assertEquals(Color.White, pixels[90, 10], "past the picture: the background given")
    }

    @Test fun thePagesBackgroundIsTheColourAlongItsEdges() {
        // A page whose header runs into its top corners: its edges are still mostly its background.
        val picture = page(60, 40, Color(0xFF1E3A8A), marker = Color(0xFFF59E0B), x = 0, y = 0, side = 12)
        assertEquals(Color(0xFF1E3A8A), coverBackgroundOf(picture))
        assertEquals(Color.Transparent, coverBackgroundOf(ImageBitmap(1, 1)), "a transparent page fills nothing")
    }

    @Test fun aViewOfThePicturesShapeIsFilledAndAnyOtherKeepsItsTrueSize() {
        // Unchanged (a cover is the same size as its view), whatever its pixels a dp: filled exactly.
        assertEquals(1f, coverScale(400, 300, Size(400f, 300f), density = 2f, imageDensity = 2f))
        assertEquals(400f / 300f, coverScale(300, 225, Size(400f, 300f), density = 2f, imageDensity = 2f), "drawn at 1.5 px a dp")
        // Grown taller (an embed), or wider (a pane): its true size, at the density it was taken at.
        assertEquals(1f, coverScale(400, 300, Size(400f, 500f), density = 2f, imageDensity = 2f))
        assertEquals(1f, coverScale(400, 300, Size(600f, 300f), density = 2f, imageDensity = 2f))
        // Moved to a screen of another density: still its size in dp.
        assertEquals(0.5f, coverScale(400, 300, Size(200f, 200f), density = 1f, imageDensity = 2f))
    }
}
