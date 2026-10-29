package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Sharp snapshots, Task 3: a cover is kept at the screen's own density, up to three pixels a dp, and in full colour,
 * so it looks like the page; storing it encoded keeps it small (Task 2).
 */
class SnapshotScalingTest {
    private fun solid(
        width: Int,
        height: Int,
        color: Color,
        config: ImageBitmapConfig = ImageBitmapConfig.Argb8888,
    ): ImageBitmap {
        val image = ImageBitmap(width, height, config, hasAlpha = config != ImageBitmapConfig.Rgb565)
        Canvas(image).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
        return image
    }

    @Test fun aSnapshotIsKeptAtTheScreensDensityUpToThreePixelsADp() {
        // A phone's embed, 411 × 300 dp at density 2.625: as it was taken.
        assertEquals(IntSize(1_079, 788), snapshotSize(1_079, 788, viewWidthDp = 411f))
        // 400 × 300 dp at density 3.5: three pixels a dp.
        assertEquals(IntSize(1_200, 900), snapshotSize(1_400, 1_050, viewWidthDp = 400f))
        // An engine that draws its own at most at 2 pixels a dp, after a draw that took too long.
        assertEquals(IntSize(822, 600), snapshotSize(1_079, 788, viewWidthDp = 411f, maxScale = 2f))
    }

    @Test fun aHugeSnapshotIsKeptWithinThePixelCapAndItsAspectRatio() {
        // A desktop pane, 3,000 × 2,000 dp at density 1: 6 million pixels.
        val pane = snapshotSize(3_000, 2_000, viewWidthDp = 3_000f)
        assertTrue(pane.width.toLong() * pane.height <= MAX_SNAPSHOT_PIXELS, "$pane")
        assertTrue(pane.width >= 2_440, "$pane")
        assertEquals(1.5f, pane.width.toFloat() / pane.height, 0.01f)
        assertEquals(4_000_000, MAX_SNAPSHOT_PIXELS)
    }

    @Test fun aSnapshotIsNeverScaledUp() {
        assertEquals(IntSize(200, 100), snapshotSize(200, 100, viewWidthDp = 400f))
        assertEquals(IntSize(500, 700), snapshotSize(500, 700, viewWidthDp = 0f))
        assertEquals(IntSize(0, 0), snapshotSize(0, 0, viewWidthDp = 100f))
    }

    @Test fun aSnapshotAtTheScreensDensityInFullColourIsKeptAsItIs() {
        val image = solid(800, 600, Color.Red)
        assertSame(image, keptSnapshot(image, viewWidthDp = 400f))
    }

    @Test fun nothingIsKeptIn16BitPixels() {
        val sixteen = solid(800, 600, Color.Red, ImageBitmapConfig.Rgb565)
        val kept = keptSnapshot(sixteen, viewWidthDp = 400f)
        assertEquals(IntSize(800, 600), IntSize(kept.width, kept.height))
        assertEquals(ImageBitmapConfig.Argb8888, kept.config)
        assertEquals(Color.Red, kept.toPixelMap()[400, 300])
    }

    @Test fun aSnapshotDenserThanThreePixelsADpIsScaledDownInFullColour() {
        // 200 dp at density 4.
        val kept = keptSnapshot(solid(800, 600, Color.Blue), viewWidthDp = 200f)
        assertEquals(IntSize(600, 450), IntSize(kept.width, kept.height))
        assertEquals(ImageBitmapConfig.Argb8888, kept.config)
        assertEquals(Color.Blue, kept.toPixelMap()[300, 225])
    }

    /**
     * A page's own draw that took too long: the next ones are taken at the largest of 3, 2.5, 2 and 1.5 pixels a dp
     * that would have fit, its time growing with the pixels, the square of the scale.
     */
    @Test fun aDrawThatOverranStepsDownToTheLargestScaleThatWouldHaveFit() {
        val budget = 32_000_000L
        // 45 ms at 2.625: 2.5 would have taken 40.8 ms, 2 26.1 ms.
        assertEquals(2f, scaleAfterOverrun(2.625f, 45_000_000, budget))
        // 34 ms at 3: 2.5 would have taken 23.6 ms.
        assertEquals(2.5f, scaleAfterOverrun(3f, 34_000_000, budget))
        // Far too slow for any: the smallest.
        assertEquals(1.5f, scaleAfterOverrun(2.625f, 500_000_000, budget))
        // Never up: a draw at 1.25 that took too long stays there.
        assertEquals(1.25f, scaleAfterOverrun(1.25f, 50_000_000, budget))
    }
}
