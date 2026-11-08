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
        // At the screen's own density, a width in dp (in floating point) gives back the width it came from.
        assertEquals(IntSize(1_080, 2_520), snapshotSize(1_080, 2_520, viewWidthDp = 1_080 / 2.625f, maxScale = 2.625f))
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
        assumeRealImages()
        val image = solid(800, 600, Color.Red)
        assertSame(image, keptSnapshot(image, viewWidthDp = 400f))
    }

    @Test fun nothingIsKeptIn16BitPixels() {
        assumeRealImages()
        val sixteen = solid(800, 600, Color.Red, ImageBitmapConfig.Rgb565)
        val kept = keptSnapshot(sixteen, viewWidthDp = 400f)
        assertEquals(IntSize(800, 600), IntSize(kept.width, kept.height))
        assertEquals(ImageBitmapConfig.Argb8888, kept.config)
        assertEquals(Color.Red, kept.toPixelMap()[400, 300])
    }

    @Test fun aSnapshotDenserThanThreePixelsADpIsScaledDownInFullColour() {
        assumeRealImages()
        // 200 dp at density 4.
        val kept = keptSnapshot(solid(800, 600, Color.Blue), viewWidthDp = 200f)
        assertEquals(IntSize(600, 450), IntSize(kept.width, kept.height))
        assertEquals(ImageBitmapConfig.Argb8888, kept.config)
        assertEquals(Color.Blue, kept.toPixelMap()[300, 225])
    }

    /**
     * A page's own draw that took too long tells how many pixels fit the bound, planned at 80 % of it: a view as big
     * then steps down to the largest of 3, 2.5, 2 and 1.5 pixels a dp that fits, and a smaller one stays at the
     * screen's density.
     */
    @Test fun aDrawThatTookTooLongTellsHowManyPixelsFit() {
        val budget = 32_000_000L
        // The phone's cover screen, 411.43 × 960 dp at 2.625: 2,721,600 pixels drawn in 34 ms. 25.6 ms fit 2,049,204.
        val within = pixelsWithin(1_080L * 2_520, 34_000_000, budget)
        assertTrue(within in 2_049_000..2_049_300, "$within")
        // 2.5 would be 2,468,580 pixels; 2 is 1,579,891.
        assertEquals(2f, drawScale(411.43f, 960f, 2.625f, within))
        // A chat's embed, 411 × 300 dp, is 849,623 pixels at 2.625: it stays at the screen's density.
        assertEquals(2.625f, drawScale(411f, 300f, 2.625f, within))
        // Nothing has overrun yet.
        assertEquals(2.625f, drawScale(411.43f, 960f, 2.625f, null))
        // Far too slow for any: the smallest.
        assertEquals(1.5f, drawScale(411.43f, 960f, 2.625f, pixelsWithin(2_721_600, 500_000_000, budget)))
        // Never up: a screen of density 1.25 stays there.
        assertEquals(1.25f, drawScale(400f, 800f, 1.25f, 1_000))
    }

    /** The phone's cover screen, drawn whole: 2,721,600 pixels. */
    private val screen = 1_080L * 2_520

    /**
     * A document's first draw counts too, at a third of its time (it is two to four times slower than the next): one
     * slow even so lowers the budget, as the review asked; one within three bounds leaves it, as before.
     */
    @Test fun aDocumentsFirstDrawCountsAtAThirdOfItsTime() {
        val budget = DrawBudget(32_000_000)
        assertEquals(null, budget.drawn(screen, 90_000_000, cold = true), "90 ms counts as 30: within the bound")
        val lowered = budget.drawn(screen, 120_000_000, cold = true)
        assertEquals(pixelsWithin(screen, 40_000_000, 32_000_000), lowered, "120 ms counts as 40: over it")
        assertEquals(2f, drawScale(411.43f, 960f, 2.625f, lowered))
    }

    /** A first draw, however fast, never raises a budget: its time is only a guess at the next one's. */
    @Test fun aFirstDrawNeverRaisesTheBudget() {
        val budget = DrawBudget(32_000_000)
        val lowered = budget.drawn(screen, 40_000_000, cold = false)
        assertEquals(lowered, budget.drawn(screen / 2, 3_000_000, cold = true))
    }

    /**
     * After a slow moment, warm draws within the bound raise the budget again, at most doubling it each time, until a
     * view drawn whole fits again: a busy or hot phone doesn't keep every later snapshot small.
     */
    @Test fun theBudgetRecoversAfterGoodDraws() {
        val budget = DrawBudget(32_000_000)
        val low = budget.drawn(screen, 100_000_000, cold = false)!!
        assertEquals(1.5f, drawScale(411.43f, 960f, 2.625f, low), "a draw three times too slow")
        // 1.5 px a dp: 888,768 pixels, in 6 ms now.
        val next = budget.drawn(888_768, 6_000_000, cold = false)!!
        assertEquals(2 * low, next, "at most doubled")
        var now = next
        repeat(4) { now = budget.drawn(screen, 20_000_000, cold = false)!! }
        assertEquals(2.625f, drawScale(411.43f, 960f, 2.625f, now), "drawn whole again")
        assertEquals(now, budget.drawn(screen, 20_000_000, cold = false), "and it stays")
        assertTrue(budget.drawn(screen, 33_000_000, cold = false)!! < now, "a draw over the bound lowers it at once")
    }
}
