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
import kotlin.test.fail

class OverlayGeometryTest {
    private val page = Rect(100f, 100f, 500f, 400f)
    private val wide = Rect(0f, 0f, 1000f, 1000f)

    /** A page that only moves needs nothing new: its cut-outs are the same in its own coordinates. */
    @Test fun aPageThatOnlyMovesHasTheSameLocalCutouts() {
        fun menu(dy: Float) = OverlayShape(RoundRect(Rect(40.3f, 60.7f + dy, 90.1f, 100.9f + dy), CornerRadius(4f)))
        val scrim = ScrimShape(Rect(0f, 0f, 2_000f, 2_000f), Color.Black)
        fun at(dy: Float, moveMenu: Boolean = true): OverlayCutouts {
            val page = Rect(10.37f, 20.11f + dy, 310.37f, 220.11f + dy)
            return computeCutouts(page, Rect(0f, 0f, 1_000f, 1_000f), listOf(menu(if (moveMenu) dy else 0f)), listOf(scrim))
        }
        val still = at(0f).pageLocal()
        assertEquals(still, at(0.37f).pageLocal())
        assertEquals(still, at(33.3f).pageLocal())
        assertFalse(still == at(33.3f, moveMenu = false).pageLocal(), "the menu stayed while the page moved")
        // Partly scrolled out, it shows less of itself.
        val clipped = computeCutouts(Rect(10f, -50f, 310f, 150f), Rect(0f, 0f, 1_000f, 1_000f), emptyList(), emptyList())
        val whole = computeCutouts(Rect(10f, 50f, 310f, 250f), Rect(0f, 0f, 1_000f, 1_000f), emptyList(), emptyList())
        assertFalse(clipped.pageLocal() == whole.pageLocal())
    }

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

    @Test fun holesCarryRadiiFittedAfterClipping() {
        // Clipped to 30 px high, the two 40 px top radii no longer fit the left and right sides: they shrink to 30.
        val menu = OverlayShape(RoundRect(Rect(200f, 150f, 300f, 250f), topLeft = CornerRadius(40f), topRight = CornerRadius(40f)))
        val holes = computeCutouts(page, Rect(100f, 100f, 500f, 180f), listOf(menu), emptyList()).holes
        assertEquals(
            listOf(OverlayShape(RoundRect(Rect(200f, 150f, 300f, 180f), topLeft = CornerRadius(30f), topRight = CornerRadius(30f)))),
            holes,
        )
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

    /** A 300×200 dialog whose corners all differ, so a check against the wrong corner's box misses. */
    private val cornered = OverlayShape(
        RoundRect(
            Rect(150f, 150f, 450f, 350f),
            topLeft = CornerRadius(16f), topRight = CornerRadius(24f), bottomRight = CornerRadius(32f), bottomLeft = CornerRadius(40f),
        ),
    )

    @Test fun aNestedOverlayInTheContainersCornerFallsBackToRects() {
        // Each 60×60 menu reaches 4 px into one corner's box, where that rounded corner wouldn't cover it. The radii
        // differ, so a check made against a corner with a smaller radius would miss the menu.
        for (menu in listOf(
            Rect(162f, 162f, 222f, 222f),
            Rect(370f, 170f, 430f, 230f),
            Rect(362f, 262f, 422f, 322f),
            Rect(186f, 254f, 246f, 314f),
        )) {
            assertEquals(
                listOf(OverlayShape(Rect(150f, 150f, 450f, 350f))),
                computeCutouts(page, page, listOf(cornered, OverlayShape(menu)), emptyList()).holes,
                "menu at $menu",
            )
        }
    }

    @Test fun aNestedOverlayTouchingACornerBoxKeepsTheContainer() {
        // Each menu ends exactly where one corner's box begins: touching isn't entering.
        for (menu in listOf(
            Rect(166f, 152f, 226f, 212f),
            Rect(366f, 152f, 426f, 212f),
            Rect(380f, 258f, 440f, 318f),
            Rect(152f, 250f, 212f, 310f),
        )) {
            assertEquals(listOf(cornered), computeCutouts(page, page, listOf(cornered, OverlayShape(menu)), emptyList()).holes, "menu at $menu")
        }
    }

    @Test fun aDialogRegisteredTwiceKeepsItsRadii() {
        val menu = OverlayShape(Rect(200f, 200f, 300f, 300f), CornerRadius(4f))
        assertEquals(listOf(cornered), computeCutouts(page, page, listOf(cornered, cornered), emptyList()).holes)
        assertEquals(listOf(cornered), computeCutouts(page, page, listOf(cornered, menu, cornered.copy()), emptyList()).holes)
    }

    @Test fun touchingHolesStaySeparate() {
        val a = OverlayShape(Rect(0f, 0f, 100f, 100f))
        val b = OverlayShape(Rect(100f, 0f, 200f, 100f))
        assertEquals(listOf(a, b), computeCutouts(wide, wide, listOf(a, b), emptyList()).holes)
    }

    @Test fun overlaysMeetingAtAnEdgeLeaveNoEmptyHole() {
        // Two rects meet along y = 200 and a third joins them, so the sweep meets y = 200 twice: once as an edge.
        val upper = OverlayShape(Rect(100f, 100f, 200f, 200f))
        val lower = OverlayShape(Rect(200f, 200f, 300f, 300f))
        val joint = OverlayShape(Rect(150f, 150f, 250f, 250f))
        assertEquals(
            listOf(Rect(100f, 100f, 200f, 150f), Rect(100f, 150f, 250f, 200f), Rect(150f, 200f, 300f, 250f), Rect(200f, 250f, 300f, 300f))
                .map { OverlayShape(it) },
            computeCutouts(page, page, listOf(upper, lower, joint), emptyList()).holes,
        )
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
            val visibleBounds = if (case % 4 < 2) wide else {
                val x0 = random.nextFloat() * 1200f - 100f; val x1 = random.nextFloat() * 1200f - 100f
                val y0 = random.nextFloat() * 1200f - 100f; val y1 = random.nextFloat() * 1200f - 100f
                Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            }
            val cutouts = computeCutouts(wide, visibleBounds, overlays, emptyList())
            fun where() = "case $case: overlays=${overlays.map { it.bounds }} visible=$visibleBounds holes=${cutouts.holes.map { it.bounds }}"
            assertWellFormed(cutouts.holes, ::where)
            assertPairwiseDisjoint(cutouts.holes, ::where)
            if (!cutouts.holes.all { it.shape.isSquare() }) fail("rects stay square; ${where()}")
            for (i in 0 until 50) for (j in 0 until 50) {
                val x = 10f + 20f * i
                val y = 10f + 20f * j
                val covered = wide.containsHalfOpen(x, y) && visibleBounds.containsHalfOpen(x, y) &&
                    overlays.any { it.bounds.containsHalfOpen(x, y) }
                val hits = cutouts.holes.count { it.bounds.containsHalfOpen(x, y) }
                if (hits != (if (covered) 1 else 0)) fail("($x, $y) is in $hits holes, covered=$covered; ${where()}")
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
            val overlays = ArrayList<OverlayShape>()
            val samples = ArrayList<Offset>(3_600)
            for (i in 0 until 50) for (j in 0 until 50) samples += Offset(10f + 20f * i, 10f + 20f * j)
            if (case % 2 == 0) {
                // A dialog alone with one menu pushed into one of its corners: into the corner box or short of it, so
                // the container rule decides (a second menu in another box would hide a mistake). The radii fit, so
                // the boxes are radius-sized; they are sampled densely, because a container that wrongly keeps a
                // rounded corner under-cuts exactly there.
                val width = 200f + random.nextFloat() * 500f
                val height = 200f + random.nextFloat() * 500f
                val left = random.nextFloat() * (1000f - width)
                val top = random.nextFloat() * (1000f - height)
                fun dialogRadius() = if (random.nextInt(8) == 0) CornerRadius.Zero else CornerRadius(8f + random.nextFloat() * 52f, 8f + random.nextFloat() * 52f)
                val dialog = RoundRect(left, top, left + width, top + height, dialogRadius(), dialogRadius(), dialogRadius(), dialogRadius())
                overlays += OverlayShape(dialog)
                overlays += OverlayShape(menuNearACorner(dialog, random.nextInt(4), random))
                for (corner in 0 until 4) samples += cornerBoxSamples(dialog, corner)
            } else {
                repeat(random.nextInt(1, 7)) { overlays += OverlayShape(RoundRect(rect(), radius(), radius(), radius(), radius())) }
            }
            val visibleBounds = if (case % 4 < 2) wide else rect()
            val cutouts = computeCutouts(wide, visibleBounds, overlays, emptyList())
            fun where() = "case $case: overlays=$overlays visible=$visibleBounds holes=${cutouts.holes}"
            assertWellFormed(cutouts.holes, ::where)
            assertPairwiseDisjoint(cutouts.holes, ::where)
            for (p in samples) {
                val visible = visibleBounds.containsHalfOpen(p.x, p.y) && wide.containsHalfOpen(p.x, p.y)
                val hits = cutouts.holes.count { it.shape.contains(p) }
                val problem = when {
                    hits > 1 -> "in $hits holes"
                    hits == 0 && visible && overlays.any { it.shape.contains(p) } -> "under an overlay as drawn, but in no hole"
                    hits == 1 && !(visible && overlays.any { it.bounds.containsHalfOpen(p.x, p.y) }) -> "in a hole, but under no overlay"
                    else -> null
                }
                if (problem != null) fail("$p is $problem; ${where()}")
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

    @Test fun aRadiusThatIsNotFiniteSquaresEveryCorner() {
        // Skia draws such a shape as a plain rect (SkRRect::setRectRadii), so rounded holes would under-cut it.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val menu = OverlayShape(
                RoundRect(
                    Rect(200f, 150f, 300f, 250f),
                    topLeft = CornerRadius(8f), topRight = CornerRadius(8f, bad), bottomRight = CornerRadius(8f), bottomLeft = CornerRadius(8f),
                ),
            )
            val c = computeCutouts(page, page, listOf(menu), emptyList())
            assertEquals(listOf(OverlayShape(Rect(200f, 150f, 300f, 250f))), c.holes, "radius $bad")
            assertFalse('A' in OverlayCutouts(page, page, listOf(menu), emptyList()).toEvenOddPath(), "a hand-built hole with radius $bad")
        }
    }

    @Test fun aCornerIsRoundOnlyWithAPositiveRadiusOnBothAxes() {
        val menu = OverlayShape(
            RoundRect(
                Rect(200f, 150f, 300f, 250f),
                topLeft = CornerRadius(8f, 0f), topRight = CornerRadius(0f, 8f), bottomRight = CornerRadius(-8f), bottomLeft = CornerRadius(8f),
            ),
        )
        val c = computeCutouts(page, page, listOf(menu), emptyList())
        assertEquals(listOf(OverlayShape(RoundRect(Rect(200f, 150f, 300f, 250f), bottomLeft = CornerRadius(8f)))), c.holes)
        assertEquals(1, c.toEvenOddPath().count { it == 'A' })
    }

    @Test fun aZeroWidthVisibleAreaHidesEverything() {
        val page = Rect(0f, 0f, 100f, 100f)
        val c = computeCutouts(page, Rect(10f, 10f, 10f, 100f), listOf(OverlayShape(Rect(20f, 20f, 60f, 60f))), emptyList())
        assertTrue(c.holes.isEmpty())
        assertFalse(c.isNone)
        assertEquals("M0 0H0V0H0Z", c.toEvenOddPath())
    }

    @Test fun anEmptyVisibleAreaIsNeverNone() {
        // A page that isn't laid out yet is 0×0 at the window origin: it must hide, not show unclipped.
        val unlaid = computeCutouts(Rect.Zero, Rect.Zero, emptyList(), emptyList())
        assertFalse(unlaid.isNone)
        assertEquals("M0 0H0V0H0Z", unlaid.toEvenOddPath())
        val flat = Rect(0f, 0f, 100f, 0f)
        assertFalse(computeCutouts(flat, flat, emptyList(), emptyList()).isNone)
        // The same goes for the constant: no page, nothing visible.
        assertFalse(OverlayCutouts.Hidden.isNone)
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

    @Test fun fittedRadiiNeverOverrunTheirSide() {
        // Scaled to fit, radii round in floats and can end up an ulp too long together:
        // - on a 10 px side, two 39 px radii scale by 10/78 to 5.0000005 each;
        // - this tab's top radii (found by a random search) still overrun after a second scaling.
        val sliver = OverlayShape(Rect(200f, 150f, 210f, 250f), CornerRadius(39f))
        val tab = OverlayShape(
            RoundRect(Rect(0f, 100f, 350.4066467285156f, 500f), topLeft = CornerRadius(668.9267578125f), topRight = CornerRadius(689.4722290039062f)),
        )
        for (shape in listOf(sliver, tab)) {
            val holes = computeCutouts(wide, wide, listOf(shape), emptyList()).holes
            assertWellFormed(holes) { "$holes" }
            assertEquals(shape.bounds, holes.single().bounds)
        }
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
        assertEquals("0", n(Float.NaN))
        for (radius in listOf(CornerRadius(0.001f), CornerRadius(10f, 0.001f), CornerRadius(0.004f, 10f))) {
            val path = computeCutouts(page, page, listOf(OverlayShape(Rect(200f, 150f, 300f, 250f), radius)), emptyList()).toEvenOddPath()
            assertFalse('A' in path, "radius $radius gave $path")
        }
    }

    private fun assertPairwiseDisjoint(holes: List<OverlayShape>, where: () -> String = { "" }) {
        for (i in holes.indices) for (j in i + 1 until holes.size) {
            if (holes[i].bounds.overlaps(holes[j].bounds)) fail("${holes[i].bounds} overlaps ${holes[j].bounds}; ${where()}")
        }
    }

    /**
     * What [OverlayCutouts.holes] promise, since the macOS engine builds its mask from them: finite, with area, each
     * corner square or positive on both axes, and on every side two radii that add up to at most its length.
     */
    private fun assertWellFormed(holes: List<OverlayShape>, where: () -> String = { "" }) {
        for (hole in holes) {
            val s = hole.shape
            val tl = s.topLeftCornerRadius
            val tr = s.topRightCornerRadius
            val br = s.bottomRightCornerRadius
            val bl = s.bottomLeftCornerRadius
            val wellFormed = s.left.isFinite() && s.top.isFinite() && s.right.isFinite() && s.bottom.isFinite() &&
                s.left < s.right && s.top < s.bottom &&
                listOf(tl, tr, br, bl).all { it == CornerRadius.Zero || (it.x > 0f && it.y > 0f && it.x.isFinite() && it.y.isFinite()) } &&
                tl.x + tr.x <= s.width && bl.x + br.x <= s.width && tl.y + bl.y <= s.height && tr.y + br.y <= s.height
            if (!wellFormed) fail("malformed hole $s; ${where()}")
        }
    }

    /**
     * A menu inside [dialog] near [corner] (0 top-left, then clockwise): half the time within half that corner's radii
     * of it, where its own corner pokes out of the dialog's rounded one, otherwise within 1.5 radii.
     */
    private fun menuNearACorner(dialog: RoundRect, corner: Int, random: Random): RoundRect {
        val radius = dialog.radiusAt(corner)
        val reach = if (random.nextBoolean()) 0.5f else 1.5f
        val inX = random.nextFloat() * reach * radius.x
        val inY = random.nextFloat() * reach * radius.y
        val width = 20f + random.nextFloat() * (dialog.width / 2 - 20f)
        val height = 20f + random.nextFloat() * (dialog.height / 2 - 20f)
        val left = if (corner == 0 || corner == 3) dialog.left + inX else dialog.right - inX - width
        val top = if (corner < 2) dialog.top + inY else dialog.bottom - inY - height
        val bounds = Rect(max(left, dialog.left), max(top, dialog.top), min(left + width, dialog.right), min(top + height, dialog.bottom))
        return RoundRect(bounds, if (random.nextBoolean()) CornerRadius.Zero else CornerRadius(random.nextFloat() * 8f))
    }

    /** A 16×16 grid over [dialog]'s box at [corner], the radius-sized square its rounded corner cuts into. */
    private fun cornerBoxSamples(dialog: RoundRect, corner: Int): List<Offset> {
        val radius = dialog.radiusAt(corner)
        val boxWidth = min(radius.x, dialog.width / 2)
        val boxHeight = min(radius.y, dialog.height / 2)
        if (!(boxWidth > 0f && boxHeight > 0f)) return emptyList()
        val fromLeft = corner == 0 || corner == 3
        val fromTop = corner < 2
        return List(256) { k ->
            val dx = (k % 16 + 0.5f) / 16 * boxWidth
            val dy = (k / 16 + 0.5f) / 16 * boxHeight
            Offset(if (fromLeft) dialog.left + dx else dialog.right - dx, if (fromTop) dialog.top + dy else dialog.bottom - dy)
        }
    }

    private fun RoundRect.radiusAt(corner: Int): CornerRadius = when (corner) {
        0 -> topLeftCornerRadius
        1 -> topRightCornerRadius
        2 -> bottomRightCornerRadius
        else -> bottomLeftCornerRadius
    }

    /** Edges at left/top are in, right/bottom out, so abutting rects never share a point. */
    private fun Rect.containsHalfOpen(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    private fun RoundRect.isSquare() = listOf(topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)
        .all { it == CornerRadius.Zero }
}
