package dev.supermux.webview.runtime

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayGeometryTest {
    private val page = Rect(100f, 100f, 500f, 400f)

    @Test fun aFullyVisiblePageWithNothingOverItNeedsNoCutouts() {
        assertTrue(computeCutouts(page, page, emptyList(), emptyList()).isNone)
    }

    @Test fun overlaysAwayFromThePageAreIgnored() {
        assertTrue(computeCutouts(page, page, listOf(OverlayShape(Rect(0f, 0f, 50f, 50f))), emptyList()).holes.isEmpty())
    }

    @Test fun anOverlayInsideThePageBecomesAHoleWithItsCorners() {
        val menu = OverlayShape(Rect(200f, 150f, 300f, 250f), CornerRadius(8f))
        assertEquals(listOf(menu), computeCutouts(page, page, listOf(menu), emptyList()).holes)
    }

    @Test fun anOverlayCrossingTheEdgeIsClippedAndLosesItsCorners() {
        val menu = OverlayShape(Rect(450f, 150f, 600f, 250f), CornerRadius(8f))
        assertEquals(
            listOf(OverlayShape(Rect(450f, 150f, 500f, 250f))),
            computeCutouts(page, page, listOf(menu), emptyList()).holes,
        )
    }

    @Test fun overlappingOverlaysMergeIntoOneHole() {
        val a = OverlayShape(Rect(150f, 150f, 250f, 250f), CornerRadius(4f))
        val b = OverlayShape(Rect(200f, 200f, 300f, 300f), CornerRadius(4f))
        assertEquals(listOf(OverlayShape(Rect(150f, 150f, 300f, 300f))), computeCutouts(page, page, listOf(a, b), emptyList()).holes)
    }

    @Test fun aChainOfOverlapsMergesCompletely() {
        val shapes = listOf(Rect(150f, 150f, 200f, 200f), Rect(190f, 190f, 240f, 240f), Rect(230f, 230f, 280f, 280f))
            .map { OverlayShape(it) }
        assertEquals(listOf(OverlayShape(Rect(150f, 150f, 280f, 280f))), computeCutouts(page, page, shapes, emptyList()).holes)
    }

    @Test fun separateOverlaysStaySeparate() {
        val a = OverlayShape(Rect(150f, 150f, 200f, 200f))
        val b = OverlayShape(Rect(300f, 300f, 350f, 350f))
        assertEquals(listOf(a, b), computeCutouts(page, page, listOf(a, b), emptyList()).holes)
    }

    @Test fun aPartlyScrolledPageIsClippedToItsVisibleArea() {
        val c = computeCutouts(page, Rect(100f, 250f, 500f, 400f), emptyList(), emptyList())
        assertFalse(c.isNone)
        assertEquals("M0 150H400V300H0Z", c.toEvenOddPath())
    }

    @Test fun aPageScrolledFullyOutHasNothingVisible() {
        val c = computeCutouts(page, Rect(0f, 0f, 50f, 50f), listOf(OverlayShape(Rect(200f, 150f, 300f, 250f))), emptyList())
        assertEquals(Rect.Zero, c.visibleBounds)
        assertTrue(c.holes.isEmpty())
    }

    @Test fun theLastScrimIsCarried() {
        assertEquals(Color.Red, computeCutouts(page, page, emptyList(), listOf(Color.Black, Color.Red)).scrim)
    }

    @Test fun thePathIsInPageCoordinatesWithRoundedHoles() {
        val menu = OverlayShape(Rect(200f, 150f, 300f, 250f), CornerRadius(10f))
        assertEquals(
            "M0 0H400V300H0Z" +
                "M110 50H190A10 10 0 0 1 200 60V140A10 10 0 0 1 190 150H110A10 10 0 0 1 100 140V60A10 10 0 0 1 110 50Z",
            computeCutouts(page, page, listOf(menu), emptyList()).toEvenOddPath(),
        )
    }

    @Test fun numbersKeepTwoDecimalsAndNeverUseAnExponent() {
        val odd = Rect(0f, 0f, 10.256f, 5.0001f)
        assertEquals("M0 0H10.26V5H0Z", computeCutouts(odd, odd, emptyList(), emptyList()).toEvenOddPath())
        val half = Rect(0f, 0f, 2.5f, 1.05f)
        assertEquals("M0 0H2.5V1.05H0Z", computeCutouts(half, half, emptyList(), emptyList()).toEvenOddPath())
    }

    @Test fun scalingConvertsUnits() {
        val c = computeCutouts(page, page, listOf(OverlayShape(Rect(200f, 150f, 300f, 250f), CornerRadius(8f))), emptyList()).scaled(0.5f)
        assertEquals(Rect(50f, 50f, 250f, 200f), c.pageBounds)
        assertEquals(OverlayShape(Rect(100f, 75f, 150f, 125f), CornerRadius(4f)), c.holes.single())
    }
}
