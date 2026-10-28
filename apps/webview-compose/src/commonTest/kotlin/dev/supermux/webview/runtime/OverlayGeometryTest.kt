package dev.supermux.webview.runtime

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayGeometryTest {
    private val page = Rect(100f, 100f, 500f, 400f)
    private val wide = Rect(0f, 0f, 1000f, 1000f)

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

    @Test fun anOverlayCrossingTheEdgeIsClippedAndLosesTheCornersOnThatEdge() {
        val menu = OverlayShape(Rect(450f, 150f, 600f, 250f), CornerRadius(8f))
        assertEquals(
            listOf(OverlayShape(RoundRect(Rect(450f, 150f, 500f, 250f), topLeft = CornerRadius(8f), bottomLeft = CornerRadius(8f)))),
            computeCutouts(page, page, listOf(menu), emptyList()).holes,
        )
    }

    @Test fun overlappingOverlaysMergeIntoDisjointRects() {
        val a = OverlayShape(Rect(150f, 150f, 250f, 250f), CornerRadius(4f))
        val b = OverlayShape(Rect(200f, 200f, 300f, 300f), CornerRadius(4f))
        assertEquals(
            listOf(Rect(150f, 150f, 250f, 200f), Rect(150f, 200f, 300f, 250f), Rect(200f, 250f, 300f, 300f)).map { OverlayShape(it) },
            computeCutouts(page, page, listOf(a, b), emptyList()).holes,
        )
    }

    @Test fun aChainOfOverlapsMergesCompletely() {
        val shapes = listOf(Rect(150f, 150f, 200f, 200f), Rect(190f, 190f, 240f, 240f), Rect(230f, 230f, 280f, 280f))
            .map { OverlayShape(it) }
        assertEquals(
            listOf(
                Rect(150f, 150f, 200f, 190f),
                Rect(150f, 190f, 240f, 200f),
                Rect(190f, 200f, 240f, 230f),
                Rect(190f, 230f, 280f, 240f),
                Rect(230f, 240f, 280f, 280f),
            ).map { OverlayShape(it) },
            computeCutouts(page, page, shapes, emptyList()).holes,
        )
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

    @Test fun everyScrimIsCarriedInPaintOrder() {
        val scrims = listOf(ScrimShape(page, Color.Black), ScrimShape(page, Color.Red))
        assertEquals(scrims, computeCutouts(page, page, emptyList(), scrims).scrims)
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

    @Test fun aHoleKeepsItsOwnCornerRadii() {
        // A bottom sheet's shape: rounded on top, square at the bottom.
        val sheet = OverlayShape(RoundRect(Rect(200f, 150f, 300f, 250f), topLeft = CornerRadius(12f), topRight = CornerRadius(12f)))
        val c = computeCutouts(page, page, listOf(sheet), emptyList())
        assertEquals(listOf(sheet), c.holes)
        val path = c.toEvenOddPath()
        assertEquals(2, path.count { it == 'A' })
        assertEquals("M0 0H400V300H0Z" + "M112 50H188A12 12 0 0 1 200 62V150H100V62A12 12 0 0 1 112 50Z", path)
    }

    @Test fun aClippedHoleKeepsTheCornersStillInside() {
        // The page's right quarter is scrolled out of view; the menu hangs off the visible area's right edge.
        val visible = Rect(100f, 100f, 400f, 400f)
        val menu = OverlayShape(Rect(350f, 150f, 450f, 250f), CornerRadius(8f))
        val c = computeCutouts(page, visible, listOf(menu), emptyList())
        assertEquals(
            listOf(OverlayShape(RoundRect(Rect(350f, 150f, 400f, 250f), topLeft = CornerRadius(8f), bottomLeft = CornerRadius(8f)))),
            c.holes,
        )
        assertEquals("M0 0H300V300H0Z" + "M258 50H300V150H258A8 8 0 0 1 250 142V58A8 8 0 0 1 258 50Z", c.toEvenOddPath())
    }

    @Test fun overlappingOverlaysBecomeDisjointHolesCoveringExactlyTheirUnion() {
        val dialog = OverlayShape(Rect(100f, 100f, 500f, 400f))
        val snackbar = OverlayShape(Rect(450f, 380f, 700f, 450f))
        val holes = computeCutouts(wide, wide, listOf(dialog, snackbar), emptyList()).holes
        assertPairwiseDisjoint(holes)
        assertEquals(400f * 300f + 250f * 70f - 50f * 20f, holes.sumOf { (it.bounds.width * it.bounds.height).toDouble() }.toFloat())
        // Inside the pair's bounding box, but in neither overlay.
        assertTrue(holes.none { it.bounds.containsHalfOpen(690f, 120f) })
    }

    @Test fun aContainerKeepsItsRadiiOverANestedOverlay() {
        val dialog = OverlayShape(Rect(150f, 150f, 450f, 350f), CornerRadius(16f))
        val menu = OverlayShape(Rect(200f, 200f, 300f, 300f), CornerRadius(4f))
        assertEquals(listOf(dialog), computeCutouts(page, page, listOf(dialog, menu), emptyList()).holes)
        assertEquals(listOf(dialog), computeCutouts(page, page, listOf(menu, dialog), emptyList()).holes)
    }

    @Test fun aNestedOverlayInTheContainersCornerFallsBackToRects() {
        val dialog = OverlayShape(Rect(150f, 150f, 450f, 350f), CornerRadius(16f))
        // Reaches into the dialog's top-left 16×16 corner box, where the dialog's rounded corner would not cover it.
        val menu = OverlayShape(Rect(152f, 152f, 252f, 252f))
        val holes = computeCutouts(page, page, listOf(dialog, menu), emptyList()).holes
        assertEquals(listOf(OverlayShape(Rect(150f, 150f, 450f, 350f))), holes)
    }

    @Test fun touchingHolesStaySeparate() {
        val a = OverlayShape(Rect(0f, 0f, 100f, 100f))
        val b = OverlayShape(Rect(100f, 0f, 200f, 100f))
        assertEquals(listOf(a, b), computeCutouts(wide, wide, listOf(a, b), emptyList()).holes)
    }

    @Test fun holesAreDisjointAndCoverExactlyTheOverlays() {
        val random = Random(42)
        repeat(200) { case ->
            // Half the cases on a 25 px grid, so edges coincide, touch and nest; half anywhere.
            val coordinate: () -> Float =
                if (case % 2 == 0) ({ random.nextInt(0, 41) * 25f }) else ({ random.nextFloat() * 1000f })
            val overlays = List(random.nextInt(1, 13)) {
                val x0 = coordinate(); val x1 = coordinate(); val y0 = coordinate(); val y1 = coordinate()
                OverlayShape(Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
            }
            val visibleBounds = if (case % 4 == 1) wide else {
                val x0 = random.nextFloat() * 1200f - 100f; val x1 = random.nextFloat() * 1200f - 100f
                val y0 = random.nextFloat() * 1200f - 100f; val y1 = random.nextFloat() * 1200f - 100f
                Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            }
            val cutouts = computeCutouts(wide, visibleBounds, overlays, emptyList())
            assertPairwiseDisjoint(cutouts.holes, "case $case")
            assertTrue(cutouts.holes.all { it.shape.isSquare() }, "case $case: rects stay square")
            for (i in 0 until 50) for (j in 0 until 50) {
                val x = 10f + 20f * i
                val y = 10f + 20f * j
                val covered = wide.containsHalfOpen(x, y) && visibleBounds.containsHalfOpen(x, y) &&
                    overlays.any { it.bounds.containsHalfOpen(x, y) }
                assertEquals(
                    if (covered) 1 else 0,
                    cutouts.holes.count { it.bounds.containsHalfOpen(x, y) },
                    "case $case at ($x, $y): overlays=${overlays.map { it.bounds }} visible=$visibleBounds holes=${cutouts.holes.map { it.bounds }}",
                )
            }
        }
    }

    @Test fun radiiAreFittedToTheShapeBeforeItIsClipped() {
        // As drawn, the left radii (80 + 80 > 100) shrink every radius by 0.625: the bottom-right corner is 37.5, not 60.
        val sheet = OverlayShape(
            RoundRect(Rect(50f, 150f, 250f, 250f), topLeft = CornerRadius(80f), bottomRight = CornerRadius(60f), bottomLeft = CornerRadius(80f)),
        )
        assertEquals(
            listOf(OverlayShape(RoundRect(Rect(100f, 150f, 250f, 250f), bottomRight = CornerRadius(37.5f)))),
            computeCutouts(page, page, listOf(sheet), emptyList()).holes,
        )
    }

    @Test fun holesCoverRoundedOverlaysAsDrawn() {
        val random = Random(42)
        fun radius() = if (random.nextInt(4) == 0) CornerRadius.Zero else CornerRadius(random.nextFloat() * 120f, random.nextFloat() * 120f)
        fun rect(): Rect {
            val x0 = random.nextFloat() * 1000f; val x1 = random.nextFloat() * 1000f
            val y0 = random.nextFloat() * 1000f; val y1 = random.nextFloat() * 1000f
            return Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
        }
        repeat(200) { case ->
            val overlays = MutableList(random.nextInt(1, 7)) { OverlayShape(RoundRect(rect(), radius(), radius(), radius(), radius())) }
            if (case % 2 == 0) {
                // A menu inside a dialog: clear of the dialog's corners or not, depending on the radii.
                val outer = overlays[0].bounds
                val inset = Rect(outer.width * 0.3f + outer.left, outer.height * 0.3f + outer.top, outer.right - outer.width * 0.3f, outer.bottom - outer.height * 0.3f)
                overlays += OverlayShape(RoundRect(inset, radius(), radius(), radius(), radius()))
            }
            val visibleBounds = if (case % 4 == 1) wide else rect()
            val cutouts = computeCutouts(wide, visibleBounds, overlays, emptyList())
            assertPairwiseDisjoint(cutouts.holes, "case $case")
            for (i in 0 until 50) for (j in 0 until 50) {
                val p = Offset(10f + 20f * i, 10f + 20f * j)
                val visible = visibleBounds.containsHalfOpen(p.x, p.y)
                val hits = cutouts.holes.count { it.shape.contains(p) }
                val where = "case $case at $p: overlays=$overlays visible=$visibleBounds holes=${cutouts.holes}"
                assertTrue(hits <= 1, where)
                if (visible && overlays.any { it.shape.contains(p) }) assertEquals(1, hits, "uncovered $where")
                if (hits == 1) assertTrue(visible && overlays.any { it.bounds.containsHalfOpen(p.x, p.y) }, "over-cut $where")
            }
        }
    }

    @Test fun zeroAreaAndNonFiniteOverlaysAreIgnored() {
        val overlays = listOf(
            OverlayShape(Rect(200f, 150f, 200f, 250f)),
            OverlayShape(Rect(Float.NaN, 150f, 300f, 250f)),
            OverlayShape(Rect(200f, 150f, Float.POSITIVE_INFINITY, 250f)),
        )
        val scrims = listOf(ScrimShape(Rect(200f, 150f, 300f, 150f), Color.Black), ScrimShape(Rect(0f, Float.NaN, 600f, 500f), Color.Black))
        val c = computeCutouts(page, page, overlays, scrims)
        assertTrue(c.holes.isEmpty())
        assertTrue(c.scrims.isEmpty())
        assertTrue(c.isNone)
    }

    @Test fun aZeroWidthVisibleAreaHidesEverything() {
        val page = Rect(0f, 0f, 100f, 100f)
        val c = computeCutouts(page, Rect(10f, 10f, 10f, 100f), listOf(OverlayShape(Rect(20f, 20f, 60f, 60f))), emptyList())
        assertTrue(c.holes.isEmpty())
        assertFalse(c.isNone)
        assertEquals("M0 0H0V0H0Z", c.toEvenOddPath())
    }

    @Test fun aNonFinitePageOrVisibleAreaHidesEverything() {
        val menu = OverlayShape(Rect(200f, 150f, 300f, 250f))
        for ((pageBounds, visibleBounds) in listOf(
            Rect(Float.NaN, 100f, 500f, 400f) to page,
            page to Rect(100f, 100f, Float.POSITIVE_INFINITY, 400f),
            page to Rect(100f, Float.NEGATIVE_INFINITY, 500f, 400f),
        )) {
            val c = computeCutouts(pageBounds, visibleBounds, listOf(menu), listOf(ScrimShape(page, Color.Black)))
            assertTrue(c.holes.isEmpty())
            assertTrue(c.scrims.isEmpty())
            assertFalse(c.isNone)
            assertEquals("M0 0H0V0H0Z", c.toEvenOddPath())
        }
    }

    @Test fun scrimsAreClippedOrderedAndCompound() {
        val dim = ScrimShape(Rect(0f, 0f, 1000f, 1000f), Color.Black.copy(alpha = 0.3f))
        val elsewhere = ScrimShape(Rect(0f, 0f, 50f, 50f), Color.Blue)
        val half = ScrimShape(Rect(300f, 0f, 1000f, 1000f), Color.Black.copy(alpha = 0.5f))
        val shown = computeCutouts(page, page, emptyList(), listOf(dim, elsewhere, half))
        assertEquals(listOf(ScrimShape(page, dim.color), ScrimShape(Rect(300f, 100f, 500f, 400f), half.color)), shown.scrims)
        assertFalse(shown.isNone)
        val scrolled = computeCutouts(page, Rect(100f, 250f, 500f, 400f), emptyList(), listOf(dim, elsewhere, half))
        assertEquals(
            listOf(ScrimShape(Rect(100f, 250f, 500f, 400f), dim.color), ScrimShape(Rect(300f, 250f, 500f, 400f), half.color)),
            scrolled.scrims,
        )
    }

    @Test fun radiiLargerThanTheShapeAreClampedUniformly() {
        val pill = OverlayShape(Rect(200f, 150f, 300f, 190f), CornerRadius(30f))
        assertEquals(
            "M0 0H400V300H0Z" +
                "M120 50H180A20 20 0 0 1 200 70V70A20 20 0 0 1 180 90H120A20 20 0 0 1 100 70V70A20 20 0 0 1 120 50Z",
            computeCutouts(page, page, listOf(pill), emptyList()).toEvenOddPath(),
        )
    }

    @Test fun scaledScalesEverythingAndRejectsBadFactors() {
        val menu = OverlayShape(RoundRect(Rect(200f, 250f, 300f, 350f), topLeft = CornerRadius(8f, 6f)))
        val c = computeCutouts(page, Rect(100f, 200f, 500f, 400f), listOf(menu), listOf(ScrimShape(page, Color.Black))).scaled(0.5f)
        assertEquals(Rect(50f, 50f, 250f, 200f), c.pageBounds)
        assertEquals(Rect(50f, 100f, 250f, 200f), c.visibleBounds)
        assertEquals(listOf(OverlayShape(RoundRect(Rect(100f, 125f, 150f, 175f), topLeft = CornerRadius(4f, 3f)))), c.holes)
        assertEquals(listOf(ScrimShape(Rect(50f, 100f, 250f, 200f), Color.Black)), c.scrims)
        for (bad in listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("factor $bad") { c.scaled(bad) }
        }
    }

    @Test fun numbersAreWrittenWithoutExponentsOrNegativeZero() {
        assertEquals("0", n(-0.004f))
        assertEquals("-0.01", n(-0.006f))
        assertEquals("1234567", n(1234567f))
        assertEquals("-12.5", n(-12.5f))
        for (radius in listOf(CornerRadius(0.001f), CornerRadius(10f, 0.001f), CornerRadius(0.004f, 10f))) {
            val path = computeCutouts(page, page, listOf(OverlayShape(Rect(200f, 150f, 300f, 250f), radius)), emptyList()).toEvenOddPath()
            assertFalse('A' in path, "radius $radius gave $path")
        }
    }

    private fun assertPairwiseDisjoint(holes: List<OverlayShape>, message: String = "") {
        for (i in holes.indices) for (j in i + 1 until holes.size) {
            assertFalse(holes[i].bounds.overlaps(holes[j].bounds), "$message: ${holes[i].bounds} overlaps ${holes[j].bounds}")
        }
    }

    /** Edges at left/top are in, right/bottom out, so abutting rects never share a point. */
    private fun Rect.containsHalfOpen(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    private fun RoundRect.isSquare() = listOf(topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)
        .all { it == CornerRadius.Zero }
}
