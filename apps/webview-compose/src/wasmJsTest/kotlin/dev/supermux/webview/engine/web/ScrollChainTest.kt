package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the demos below run to drive themselves, since a test can't reach into a sandboxed frame: on `{wheel, at}` a
 * wheel of `wheel` px down at the element `at`; on `{drag, at}` a touch drag of `drag` px up and its release, a move
 * every 16 ms; on `{click}` a click on the element of that id; on `{focus}` that element's `focus()`, which no user
 * asked for; on `{press}` a pointer press on it, then its `focus()`, as a press gives it; on `{call}` the page's own
 * function of that name; on `{report}` the scroller's position, posted to the parent.
 */
internal const val DRIVER = """<script>
addEventListener('message', function (e) {
  var d = e.data || {};
  if (typeof d.click === 'string') document.getElementById(d.click).click();
  if (typeof d.call === 'string') window[d.call]();
  if (typeof d.focus === 'string') document.getElementById(d.focus).focus();
  if (typeof d.press === 'string') {
    var pressed = document.getElementById(d.press);
    pressed.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, pointerType: 'mouse', isPrimary: true }));
    pressed.focus();
  }
  // An element of that id in the document, or in the open shadow root the page keeps in window.__shadow.
  var at = document.getElementById(d.at) || (window.__shadow && window.__shadow.getElementById(d.at)) || document.body;
  // Composed, as input the browser makes is: it crosses shadow roots.
  if (typeof d.wheel === 'number') at.dispatchEvent(new WheelEvent('wheel', { deltaY: d.wheel, bubbles: true, cancelable: true, composed: true }));
  function touchEvent(type, yy) {
    var t = new Touch({ identifier: 7, target: at, clientX: 100, clientY: yy });
    at.dispatchEvent(new TouchEvent(type, { touches: type === 'touchend' ? [] : [t], targetTouches: type === 'touchend' ? [] : [t], changedTouches: [t], bubbles: true, cancelable: true, composed: true }));
  }
  // A flick: two quick moves of half of `flick` px up, 10 ms apart, then the release.
  if (typeof d.flick === 'number') {
    touchEvent('touchstart', 250);
    setTimeout(function () { touchEvent('touchmove', 250 - d.flick / 2); }, 10);
    setTimeout(function () { touchEvent('touchmove', 250 - d.flick); }, 20);
    setTimeout(function () { touchEvent('touchend', 250 - d.flick); }, 30);
  }
  if (typeof d.drag === 'number') {
    var y = 250, end = 250 - d.drag;
    touchEvent('touchstart', y);
    (function move() {
      y = Math.max(end, y - 20);
      touchEvent('touchmove', y);
      if (y > end) setTimeout(move, 16); else setTimeout(function () { touchEvent('touchend', y); }, 16);
    })();
  }
  if (d.report) {
    var s = document.getElementById('scroller');
    parent.postMessage({ scrollerTop: s ? s.scrollTop : -1 }, '*');
  }
});
</script>"""

/** Synthetic wheels don't scroll anything: the demo's scroller scrolls itself, after the shim saw the wheel. */
private const val EMULATED_SCROLLING = """<script>
document.getElementById('scroller').addEventListener('wheel', function (e) {
  var s = this, dy = e.deltaY;
  if (!e.isTrusted) queueMicrotask(function () { s.scrollTop += dy; });
});
</script>"""

internal fun iframeIn(cut: HTMLElement): HTMLElement = js("cut.querySelector('iframe')")

internal fun tell(iframe: HTMLElement, message: String): Unit = js("{ iframe.contentWindow.postMessage(JSON.parse(message), '*'); }")

/** The last scroller position a demo reported to this window, or -2 before any. */
private fun reportedScrollerTop(): Int = js("(window.__scrollerTop === undefined) ? -2 : window.__scrollerTop")

private fun listenForReports(): Unit = js("""{
  if (!window.__scrollReports) {
    window.__scrollReports = true;
    window.addEventListener('message', function (e) { if (e.data && typeof e.data.scrollerTop === 'number') window.__scrollerTop = e.data.scrollerTop; });
  }
  window.__scrollerTop = undefined;
}""")

/**
 * A live engine in the first item of a list of 300 dp, then forty rows of 100 dp. Density is 1 here. Its [client]
 * records what the page asks of the app. [userActivation] stands in for the app window's transient activation, which
 * Karma can't give; by default the engine reads the browser's own.
 */
internal class ListHarness(trustSyntheticInput: Boolean, userActivation: (() -> Boolean)? = null) {
    val engine = if (userActivation == null) {
        IframeWebEngine(EngineConfig(WebProfile.Default), trustSyntheticInput = trustSyntheticInput)
    } else {
        IframeWebEngine(EngineConfig(WebProfile.Default), trustSyntheticInput = trustSyntheticInput, userActivation = userActivation)
    }
    val state = LazyListState()
    val events = ArrayList<EngineEvent>()
    val client = RecordingEngineClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        engine.client = client
        scope.launch { engine.events.collect { events += it } }
    }

    /** How far the list has scrolled, in px. */
    val scrolled: Int
        get() = (if (state.firstVisibleItemIndex == 0) 0 else 300 + (state.firstVisibleItemIndex - 1) * 100) +
            state.firstVisibleItemScrollOffset

    /** The demo's origin, once [live]. */
    var origin: String = ""
        private set

    /** A demo of [body], with [DRIVER]. */
    suspend fun live(body: String) = live(WebContent.Html("<!doctype html><html><body style=\"margin:0\">$body$DRIVER</body></html>"))

    /** [content], live; a demo runs the shim unless [bridged] is false (its origin may not talk). */
    suspend fun live(content: WebContent, bridged: Boolean = true) {
        origin = (content as? WebContent.Html)?.origin?.origin ?: ""
        engine.configure(
            PageSetup(
                javaScriptEnabled = true,
                inspectable = false,
                dark = false,
                transparentBackground = false,
                userAgentSuffix = null,
                bridgeOrigins = (content as? WebContent.Html)?.takeIf { bridged }?.let { setOf(it.origin.origin) } ?: emptySet(),
            ),
        )
        engine.load(content)
        engine.setVisible(true)
        TestViewport.show {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                item { Box(Modifier.fillMaxWidth().height(300.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
                items(40) { BasicText("row $it", Modifier.height(100.dp)) }
            }
        }
        waitUntil("the first paint", 20_000) { EngineEvent.FirstPaint in events }
        // The page's ready came with its port: input from now on can reach the app.
        pause(200)
    }

    fun tell(message: String) = tell(iframeIn(engine.frame.cut), message)

    suspend fun close() {
        TestViewport.clear()
        scope.cancel()
        engine.release()
    }
}

/**
 * A live demo, its surface 300 dp tall, under a nested-scroll parent that records the scroll and the flings the page's
 * shim passes on ([scrolls], [flings], in Compose's signs), and takes none of it. Density is 1 here.
 */
private class RecorderHarness {
    val engine = IframeWebEngine(EngineConfig(WebProfile.Default), trustSyntheticInput = true)
    val events = ArrayList<EngineEvent>()
    val scrolls = ArrayList<Offset>()
    val flings = ArrayList<Velocity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            scrolls += available
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            flings += available
            return Velocity.Zero
        }
    }

    init {
        scope.launch { engine.events.collect { events += it } }
    }

    suspend fun live(body: String) {
        val content = WebContent.Html("<!doctype html><html><body style=\"margin:0\">$body$DRIVER</body></html>")
        engine.configure(PageSetup(true, false, false, false, null, setOf(content.origin.origin)))
        engine.load(content)
        engine.setVisible(true)
        TestViewport.show { Box(Modifier.fillMaxSize().nestedScroll(recorder)) { engine.Surface(Modifier.fillMaxWidth().height(300.dp)) } }
        waitUntil("the first paint", 20_000) { EngineEvent.FirstPaint in events }
        pause(200)
    }

    fun tell(message: String) = tell(iframeIn(engine.frame.cut), message)

    suspend fun close() {
        TestViewport.clear()
        scope.cancel()
        engine.release()
    }
}

class ScrollChainTest {
    @Test fun aWheelOverADemoThatCantScrollScrollsTheList() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<p id='x'>a short demo</p>")
        repeat(5) { h.tell("""{"wheel": 40, "at": "x"}""") }
        waitUntil("the list to take the wheel") { h.scrolled == 200 }
        pause(300)
        assertEquals(200, h.scrolled, "every px the demo couldn't take, and no more")
        h.close()
    }

    @Test fun aWheelOverAScrollableDemoScrollsItFirstThenTheListAtItsEdge() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        listenForReports()
        // 100 px of room in the demo's scroller, then its edge.
        h.live("<div id='scroller' style='height:300px;overflow:auto'><div id='x' style='height:400px'>tall</div></div>$EMULATED_SCROLLING")
        repeat(5) { h.tell("""{"wheel": 40, "at": "x"}""") }
        waitUntil("the list to take what the demo couldn't") { h.scrolled == 100 }
        h.tell("""{"report": true}""")
        waitUntil("the demo's report") { reportedScrollerTop() >= 0 }
        assertEquals(100, reportedScrollerTop(), "the demo scrolled to its edge first")
        pause(300)
        assertEquals(100, h.scrolled)
        h.close()
    }

    @Test fun aPageCannotScrollTheListWithoutUserInput() = runTest(timeout = 1.minutes) {
        // The engine as it ships: only input the browser made counts. The page makes its own wheels and drags, and tries
        // the shim's message by hand; none of it moves the list.
        val h = ListHarness(trustSyntheticInput = false)
        h.live(
            "<p id='x'>a pushy demo</p><script>" +
                "parent.postMessage({ webviewScroll: [0, 500] }, '*');" +
                "</script>",
        )
        repeat(5) { h.tell("""{"wheel": 120, "at": "x"}""") }
        h.tell("""{"drag": 200, "at": "x"}""")
        pause(1_000)
        assertEquals(0, h.scrolled)
        h.close()
    }

    @Test fun aFrameOfScrollIsCapped() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<p id='x'>a demo with a heavy wheel</p>")
        h.tell("""{"wheel": 100000, "at": "x"}""")
        waitUntil("the list to move") { h.scrolled > 0 }
        pause(300)
        assertEquals(IframeWebEngine.MAX_SCROLL_PER_FRAME_CSS.toInt(), h.scrolled)
        h.close()
    }

    @Test fun aTouchDragOverADemoThatCantScrollScrollsTheListAndItsReleaseFlingsIt() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<div id='x' style='height:280px'>a demo to drag</div>")
        h.tell("""{"drag": 120, "at": "x"}""")
        waitUntil("the drag to reach the list") { h.scrolled >= 100 }
        // Released at speed, the list goes on by itself, past where the finger left it.
        waitUntil("the fling", 5_000) { h.scrolled > 140 }
        h.close()
    }

    /**
     * Two deltas of ±1e39 in one frame summed to NaN (each is past a Float's range), which the cap let through to
     * Compose. Nothing but finite deltas reaches the list.
     */
    @Test fun aFrameOfScrollThatAddsUpToNothingFiniteReachesTheListAsNothing() = runTest(timeout = 1.minutes) {
        val h = RecorderHarness()
        h.live("<p id='x'>huge wheels</p>")
        h.tell("""{"wheel": 1e39, "at": "x"}""")
        h.tell("""{"wheel": -1e39, "at": "x"}""")
        h.tell("""{"wheel": 40, "at": "x"}""")
        waitUntil("a delta to reach the list") { h.scrolls.isNotEmpty() }
        pause(300)
        assertTrue(h.scrolls.all { it.x.isFinite() && it.y.isFinite() }, "${h.scrolls}")
        h.close()
    }

    /** However fast a drag that chains is released, the list flings at [IframeWebEngine.MAX_FLING_CSS] at most. */
    @Test fun aFlingIsCapped() = runTest(timeout = 1.minutes) {
        val h = RecorderHarness()
        h.live("<div id='x' style='height:280px'>flick me</div>")
        // 400 px in 20 ms: 20,000 px/s.
        h.tell("""{"flick": 400, "at": "x"}""")
        waitUntil("the fling") { h.flings.isNotEmpty() }
        assertTrue(h.flings.all { kotlin.math.abs(it.y) <= IframeWebEngine.MAX_FLING_CSS }, "${h.flings}")
        assertTrue(h.flings.any { kotlin.math.abs(it.y) >= IframeWebEngine.MAX_FLING_CSS }, "a fling this fast is at the cap: ${h.flings}")
        h.close()
    }

    /**
     * A canvas that takes touches itself (`touch-action: none`: a three.js scene, a map, a drawing) keeps the drag: the
     * browser doesn't pan it, so the list doesn't either.
     */
    @Test fun aDragOverACanvasThatTakesItsTouchesDoesNotScrollTheList() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<canvas id='x' width='300' height='280' style='display:block;touch-action:none'></canvas>")
        h.tell("""{"drag": 120, "at": "x"}""")
        pause(1_000)
        assertEquals(0, h.scrolled)
        h.close()
    }

    /** `touch-action` that lets the browser pan up and down lets the list scroll with a vertical drag. */
    @Test fun aDragOverAnElementThatPansVerticallyStillScrollsTheList() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<div id='x' style='height:280px;touch-action:pan-y'>pans up and down</div>")
        h.tell("""{"drag": 120, "at": "x"}""")
        waitUntil("the drag to reach the list") { h.scrolled >= 100 }
        h.close()
    }

    /**
     * A scroller inside a shadow root: the wheel reaches the window retargeted to the shadow host, whose ancestors don't
     * include the scroller. It scrolls first, and the list takes only what it couldn't.
     */
    @Test fun aWheelOverAScrollerInAShadowRootScrollsItFirst() = runTest(timeout = 1.minutes) {
        val h = ListHarness(trustSyntheticInput = true)
        listenForReports()
        h.live(
            "<div id='host'></div><script>" +
                "var root = window.__shadow = document.getElementById('host').attachShadow({ mode: 'open' });" +
                "root.innerHTML = \"<div id='scroller' style='height:300px;overflow:auto'><div id='x' style='height:400px'>tall</div></div>\";" +
                "var s = root.getElementById('scroller');" +
                // Synthetic wheels don't scroll: the scroller scrolls itself, after the shim saw the wheel.
                "s.addEventListener('wheel', function (e) { var dy = e.deltaY; if (!e.isTrusted) queueMicrotask(function () { s.scrollTop += dy; }); });" +
                "addEventListener('message', function (e) { if (e.data && e.data.reportShadow) parent.postMessage({ scrollerTop: s.scrollTop }, '*'); });" +
                "</script>",
        )
        repeat(5) { h.tell("""{"wheel": 40, "at": "x"}""") }
        waitUntil("the list to take what the scroller couldn't") { h.scrolled == 100 }
        h.tell("""{"reportShadow": true}""")
        waitUntil("the scroller's report") { reportedScrollerTop() >= 0 }
        assertEquals(100, reportedScrollerTop(), "the scroller in the shadow root scrolled to its edge first")
        pause(300)
        assertEquals(100, h.scrolled)
        h.close()
    }
}
