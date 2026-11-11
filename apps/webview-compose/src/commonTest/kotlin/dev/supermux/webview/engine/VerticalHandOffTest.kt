package dev.supermux.webview.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalHandOffTest {
    private fun asked(dy: Float, claims: Boolean): VerticalHandOff {
        val v = VerticalHandOff()
        v.down()
        v.moved(2f, dy, pointers = 1)
        val way = v.ask()
        assertEquals(if (dy < 0f) -1 else 1, way)
        v.answered(way!!, claims)
        return v
    }

    @Test
    fun aVerticalDragOverPlainContentIsOffered() {
        assertTrue(asked(dy = 30f, claims = false).mayOffer())
        assertTrue(asked(dy = -30f, claims = false).mayOffer())
    }

    @Test
    fun aDragOverWhatClaimsItStaysThePagesForTheWholeGesture() {
        val v = asked(dy = 30f, claims = true)
        assertFalse(v.mayOffer())
        // Turning back asks nothing more: a canvas that claims the drag keeps all of it.
        v.moved(0f, -80f, pointers = 1)
        assertNull(v.ask())
        assertFalse(v.mayOffer())
    }

    @Test
    fun nothingIsOfferedUntilThePageAnswers() {
        val v = VerticalHandOff()
        v.down()
        v.moved(0f, 30f, pointers = 1)
        assertEquals(1, v.ask())
        assertNull(v.ask())
        assertFalse(v.mayOffer())
    }

    @Test
    fun aMostlySidewaysDragIsNeitherAskedAboutNorOffered() {
        val v = VerticalHandOff()
        v.down()
        v.moved(-40f, 6f, pointers = 1)
        assertNull(v.ask())
        assertFalse(v.mayOffer())
    }

    @Test
    fun aPinchNeverHandsOff() {
        val v = VerticalHandOff()
        v.down()
        v.moved(0f, 0f, pointers = 2)
        // The second finger lifted: the rest of the gesture is still the pinch's.
        v.moved(0f, 50f, pointers = 1)
        assertNull(v.ask())
        assertFalse(v.mayOffer())
    }

    @Test
    fun anUnclaimedDragThatTurnsBackAsksAgain() {
        // An inner scroller at its top doesn't claim a pull down, but may claim a push up.
        val v = asked(dy = 30f, claims = false)
        v.moved(0f, -80f, pointers = 1)
        assertFalse(v.mayOffer())
        assertEquals(-1, v.ask())
        v.answered(-1, claims = true)
        assertFalse(v.mayOffer())
    }

    @Test
    fun anAnswerForTheOtherWayOrAnOldGestureIsIgnored() {
        val v = VerticalHandOff()
        v.down()
        v.moved(0f, 30f, pointers = 1)
        v.ask()
        v.answered(-1, claims = false)
        assertFalse(v.mayOffer())
        val before = v.gesture
        v.down()
        assertEquals(before + 1, v.gesture)
        v.moved(0f, 30f, pointers = 1)
        // The last gesture's answer would come for the same way: the engine drops it by its gesture count, and until
        // this gesture's own question is asked, nothing is offered.
        v.answered(1, claims = false)
        assertFalse(v.mayOffer())
    }

    @Test
    fun takenStaysTakenUntilTheNextDown() {
        val v = asked(dy = 30f, claims = false)
        v.take()
        assertTrue(v.taken)
        assertFalse(v.mayOffer())
        v.moved(-200f, 0f, pointers = 1)
        assertTrue(v.taken)
        v.down()
        assertFalse(v.taken)
        assertFalse(v.mayOffer())
    }

    @Test
    fun theFingerPxCarryTheirFraction() {
        val v = VerticalHandOff()
        v.down()
        assertEquals(listOf(0, 0, 1), listOf(v.whole(0.4f), v.whole(0.4f), v.whole(0.4f)))
    }

    // A page 1000 tall in a view 400 tall, no insets: it scrolls from 0 to 600.
    @Test
    fun aPageAtItsTopHandsOnAPullDown() {
        assertTrue(VerticalHandOff.atEdge(offsetY = 0.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = 5f))
        assertFalse(VerticalHandOff.atEdge(offsetY = 0.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = -5f))
    }

    @Test
    fun aPageAtItsBottomHandsOnAPushUp() {
        assertTrue(VerticalHandOff.atEdge(offsetY = 600.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = -5f))
        assertFalse(VerticalHandOff.atEdge(offsetY = 600.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = 5f))
    }

    @Test
    fun aPageMidwayKeepsTheDrag() {
        assertFalse(VerticalHandOff.atEdge(offsetY = 300.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = 5f))
        assertFalse(VerticalHandOff.atEdge(offsetY = 300.0, contentHeight = 1000.0, viewHeight = 400.0, fingerDy = -5f))
    }

    @Test
    fun aCardThatFitsIsAtBothEdges() {
        assertTrue(VerticalHandOff.atEdge(offsetY = 0.0, contentHeight = 400.0, viewHeight = 400.0, fingerDy = 5f))
        assertTrue(VerticalHandOff.atEdge(offsetY = 0.0, contentHeight = 380.0, viewHeight = 400.0, fingerDy = -5f))
    }

    // The question: run in a real browser by ClaimsQuestionBrowserTest (wasmJsTest); here, its shape.

    @Test
    fun theQuestionNamesThePointTheAxisAndTheWay() {
        val js = VerticalHandOff.claimsQuestion(x = 120f, y = 340f, axis = PanAxis.Vertical, direction = -1)
        assertTrue(js.contains("elementFromPoint"), js)
        assertTrue(js.endsWith("(120.0,340.0,'y',-1)"), js)
        assertTrue(VerticalHandOff.claimsQuestion(1f, 2f, PanAxis.Horizontal, 1).endsWith("(1.0,2.0,'x',1)"))
    }

    @Test
    fun theQuestionReadsTouchActionAndTheElementsOwnScroll() {
        val js = VerticalHandOff.claimsQuestion(0f, 0f, PanAxis.Vertical, 1)
        assertTrue(js.contains("touchAction"), js)
        // A value that lets the browser pan the axis, one way or both, doesn't claim it.
        assertTrue(js.contains("pan-(y|up|down)") && js.contains("pan-(x|left|right)"), js)
        assertTrue(js.contains("'auto'") && js.contains("'manipulation'"), js)
        assertTrue(js.contains("overflowY") && js.contains("scrollHeight") && js.contains("scrollTop"), js)
        // It stops below the document's own scroller, as the sideways question does.
        assertTrue(js.contains("document.scrollingElement") && js.contains("document.body"), js)
    }

    @Test
    fun thePagesAnswerReadsAsABoolean() {
        assertTrue(VerticalHandOff.readAnswer("true"))
        assertFalse(VerticalHandOff.readAnswer("false"))
        // No answer (no script, an error): nothing claims the drag, the chat may take it.
        assertFalse(VerticalHandOff.readAnswer("null"))
        assertFalse(VerticalHandOff.readAnswer(null))
    }
}
