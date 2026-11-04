package dev.supermux.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionPlacementTest {
    // A folded Fold7 in px at density 1: 411 × 960, a 32 status bar, a 48 navigation bar, keyboards ~290 tall.
    private val fold = OverlayScreen(width = 411f, height = 960f, topInset = 32f, bottomInset = 48f, keyboardHeight = 290f)
    private val size = 56f
    private val margin = 12f

    @Test
    fun itRestsOnTheLeftEdgeJustAboveWhereKeyboardsOpen() {
        val p = CompanionPlacement.rest(fold, size, margin, spot = null)
        assertEquals(12f, p.x)
        assertEquals(960f - 48f - 290f - 56f - 12f, p.y)
    }

    @Test
    fun onTheRightEdgeItKeepsTheSameMargin() {
        val p = CompanionPlacement.rest(fold, size, margin, Spot(Edge.Right, 300f))
        assertEquals(411f - 56f - 12f, p.x)
        assertEquals(300f, p.y)
    }

    @Test
    fun itNeverGoesUnderTheStatusBarOrBelowTheKeyboardLine() {
        assertEquals(32f + 12f, CompanionPlacement.rest(fold, size, margin, Spot(Edge.Left, 0f)).y)
        assertEquals(960f - 48f - 290f - 56f - 12f, CompanionPlacement.rest(fold, size, margin, Spot(Edge.Left, 900f)).y)
    }

    @Test
    fun aDropSnapsToTheNearestEdgeAtThatHeight() {
        assertEquals(Spot(Edge.Left, 250f), CompanionPlacement.snap(fold, size, margin, x = 150f, y = 250f))
        assertEquals(Spot(Edge.Right, 250f), CompanionPlacement.snap(fold, size, margin, x = 200f, y = 250f))
    }

    @Test
    fun aDropOutsideTheBandIsPulledBackIntoIt() {
        assertEquals(Spot(Edge.Left, 44f), CompanionPlacement.snap(fold, size, margin, x = 10f, y = -40f))
        assertEquals(Spot(Edge.Right, 554f), CompanionPlacement.snap(fold, size, margin, x = 380f, y = 800f))
    }

    @Test
    fun onAShortScreenTheBandCollapsesToTheTop() {
        val landscape = OverlayScreen(width = 960f, height = 380f, topInset = 32f, bottomInset = 0f, keyboardHeight = 290f)
        assertEquals(44f, CompanionPlacement.rest(landscape, size, margin, spot = null).y)
    }

    @Test
    fun theDismissTargetSitsAboveTheNavigationBarInTheMiddle() {
        val (cx, cy) = CompanionPlacement.dismissCenter(fold, offset = 64f)
        assertEquals(205.5f, cx)
        assertEquals(960f - 48f - 64f, cy)
        assertTrue(CompanionPlacement.overDismiss(fold, cx + 20f, cy - 20f, offset = 64f, radius = 50f))
        assertFalse(CompanionPlacement.overDismiss(fold, cx + 60f, cy, offset = 64f, radius = 50f))
    }

    // ── where it can look, and the frame it shows ──

    private val foldPx = OverlayScreen(width = 1080f, height = 2520f, topInset = 84f, bottomInset = 126f, keyboardHeight = 760f)
    private fun looks(x: Float, y: Float) = CompanionPlacement.inLookZone(foldPx, x, y, edgeBand = 170f, dismissOffset = 168f, dismissRadius = 150f)

    @Test fun overTheMiddleOfTheScreenItLooks() = assertTrue(looks(540f, 1000f))
    @Test fun alongEitherEdgeItIsOnlyMoved() { assertFalse(looks(120f, 1000f)); assertFalse(looks(1000f, 1000f)) }
    @Test fun notOverTheStatusOrNavigationBar() { assertFalse(looks(540f, 40f)); assertFalse(looks(540f, 2480f)) }
    @Test fun notOverTheDismissTarget() = assertFalse(looks(540f, 2240f))

    @Test
    fun theFrameIsCentredWhereItWasDropped() {
        assertEquals(LookRect(140f, 900f, 940f, 1300f), CompanionPlacement.lookFrame(foldPx, 540f, 1100f, 800f, 400f))
    }

    @Test
    fun theFrameStaysOnTheScreen() {
        assertEquals(LookRect(0f, 84f, 800f, 484f), CompanionPlacement.lookFrame(foldPx, 100f, 100f, 800f, 400f))
        assertEquals(LookRect(280f, 1994f, 1080f, 2394f), CompanionPlacement.lookFrame(foldPx, 1060f, 2500f, 800f, 400f))
    }
}
