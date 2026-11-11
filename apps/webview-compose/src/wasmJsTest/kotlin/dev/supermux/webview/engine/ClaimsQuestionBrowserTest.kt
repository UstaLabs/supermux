package dev.supermux.webview.engine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun frameWith(html: String): JsAny = js(
    "(function () { var f = document.createElement('iframe'); f.className = 'claims-test'; f.style.width = '300px';" +
        " f.style.height = '400px'; f.style.border = '0'; document.body.appendChild(f); var d = f.contentDocument;" +
        " d.open(); d.write(html); d.close(); return f.contentWindow; })()",
)
private fun evalIn(w: JsAny, code: String): String = js("String(w.eval(code))")
private fun removeFrames(): Unit = js("{ document.querySelectorAll('iframe.claims-test').forEach(function (f) { f.remove(); }); }")

/**
 * [VerticalHandOff.claimsQuestion], executed by a real browser: what claims a vertical drag (a canvas with touch-action
 * none, an inner scroller with room), and what doesn't (plain content, the page's own scroll).
 */
class ClaimsQuestionBrowserTest {
    @AfterTest fun clean() = removeFrames()

    private val style = "<style>html,body{margin:0}div,canvas,button{display:block;box-sizing:border-box}</style>"

    private fun claims(body: String, x: Float, y: Float, axis: PanAxis = PanAxis.Vertical, direction: Int = 1): Boolean {
        val w = frameWith("<!doctype html><html><head>$style</head><body>$body</body></html>")
        return VerticalHandOff.readAnswer(evalIn(w, VerticalHandOff.claimsQuestion(x, y, axis, direction)))
    }

    // A three.js card: the canvas on top, a row of buttons under it.
    private val card = "<canvas style=\"width:300px;height:200px;touch-action:none\"></canvas>" +
        "<div style=\"height:60px\"><button style=\"height:40px\">Spin</button></div>"

    @Test fun aCanvasWithTouchActionNoneClaimsTheDrag() {
        assertEquals(true, claims(card, 100f, 100f))
        assertEquals(true, claims(card, 100f, 100f, direction = -1))
    }

    @Test fun plainContentUnderItDoesNot() {
        assertEquals(false, claims(card, 100f, 220f))
        // Nor does empty space, where the point is on the body.
        assertEquals(false, claims(card, 100f, 380f))
    }

    @Test fun anAncestorsTouchActionClaimsForWhatIsInside() {
        assertEquals(true, claims("<div style=\"touch-action:none;height:100px\"><button>B</button></div>", 10f, 10f))
    }

    @Test fun valuesThatBlockVerticalPanningClaimIt() {
        for (value in listOf("pan-x", "pinch-zoom", "pan-left", "pan-right", "pan-x pinch-zoom")) {
            assertEquals(true, claims("<div style=\"touch-action:$value;height:100px\"></div>", 10f, 10f), value)
        }
    }

    @Test fun valuesThatLetItPanVerticallyDoNot() {
        for (value in listOf("auto", "manipulation", "pan-y", "pan-x pan-y", "pan-left pan-y", "pan-down", "pan-up")) {
            assertEquals(false, claims("<div style=\"touch-action:$value;height:100px\"></div>", 10f, 10f), value)
        }
    }

    @Test fun sidewaysTheAxesSwap() {
        assertEquals(true, claims("<div style=\"touch-action:pan-y;height:100px\"></div>", 10f, 10f, PanAxis.Horizontal))
        assertEquals(false, claims("<div style=\"touch-action:pan-x;height:100px\"></div>", 10f, 10f, PanAxis.Horizontal))
    }

    private val scroller = "<div id=\"s\" style=\"height:100px;overflow-y:auto\"><div style=\"height:300px\"></div></div>"

    @Test fun anInnerScrollerClaimsTheWayItCanStillGo() {
        // At its top: a finger moving up (-1) scrolls it down, it can; one moving down can't.
        assertEquals(true, claims(scroller, 10f, 10f, direction = -1))
        assertEquals(false, claims(scroller, 10f, 10f, direction = 1))
    }

    @Test fun anInnerScrollerAtItsBottomLetsAPushUpGo() {
        val w = frameWith("<!doctype html><html><head>$style</head><body>$scroller</body></html>")
        evalIn(w, "document.getElementById('s').scrollTop = 1000")
        assertEquals("false", evalIn(w, VerticalHandOff.claimsQuestion(10f, 10f, PanAxis.Vertical, -1)))
        assertEquals("true", evalIn(w, VerticalHandOff.claimsQuestion(10f, 10f, PanAxis.Vertical, 1)))
    }

    @Test fun thePagesOwnScrollIsNotAClaim() {
        // The page itself is taller than its frame: its root scrolls, which the engine checks itself (atEdge).
        assertEquals(false, claims("<div style=\"height:2000px\"></div>", 10f, 10f, direction = -1))
    }
}
