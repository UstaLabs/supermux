package dev.supermux.webview.engine.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ScrimShape
import dev.supermux.webview.runtime.computeCutouts
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A click at window point ([x], [y]) that goes where hit testing sends it, as a real one would: to Compose's canvas when
 * the point hits Compose (`"compose"`), and nowhere when it hits an iframe (`"iframe:<name>"`), since a synthetic event
 * can't enter another document. Returns what was hit.
 */
private fun clickThroughHitTest(x: Double, y: Double): String = js("""(function () {
  var el = document.elementFromPoint(x, y);
  if (!el) return 'none';
  if (el.localName === 'iframe') return 'iframe:' + (el.getAttribute('data-test-name') || '');
  var canvas = el.shadowRoot ? el.shadowRoot.querySelector('canvas') : null;
  if (!canvas) return el.localName;
  var init = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons: 1 };
  canvas.dispatchEvent(new PointerEvent('pointerdown', init));
  init.buttons = 0;
  canvas.dispatchEvent(new PointerEvent('pointerup', init));
  return 'compose';
})()""")

private fun loadsOf(iframe: HTMLElement): Int = js("iframe.__loads | 0")

private fun lastChildOf(element: HTMLElement): HTMLElement = js("element.lastElementChild")

private fun childCount(element: HTMLElement): Int = js("element.childElementCount")

private fun documentBody(): HTMLElement = js("document.body")

/** Counts the style attribute's mutations on [element] from now: `{count}`. */
private fun watchStyle(element: HTMLElement): JsAny = js("""(function () {
  var log = { count: 0 };
  new MutationObserver(function (list) { log.count += list.length; }).observe(element, { attributes: true, attributeFilter: ['style'] });
  return log;
})()""")

private fun recordsOf(log: JsAny): Int = js("log.count")

private fun countLoads(iframe: HTMLElement): Unit = js("{ iframe.__loads = 0; iframe.addEventListener('load', function () { iframe.__loads++; }); }")

/** A frame's surface where the engine composes it. */
@Composable
private fun FrameSurface(frame: WebFrame, modifier: Modifier) {
    val layer = rememberWebLayer()
    Box(modifier.then(WebSurfaceElement(frame, layer)))
}

class WebFrameTest {
    private val page = Rect(40f, 40f, 640f, 440f)

    private fun frameWithPage(name: String): Pair<WebFrame, HTMLElement> {
        val frame = WebFrame()
        val iframe = newElement("iframe")
        attribute(iframe, "sandbox", "allow-scripts")
        attribute(iframe, "data-test-name", name)
        styleOf(iframe, "position", "absolute")
        styleOf(iframe, "width", "100%")
        styleOf(iframe, "height", "100%")
        styleOf(iframe, "border", "0")
        countLoads(iframe)
        setSrcdoc(iframe, "<!doctype html><body style='margin:0;background:#cfe'>page</body>")
        frame.addIframe(iframe, below = null)
        frame.covered = false
        return frame to iframe
    }

    @Test fun aFrameSitsWhereItsSurfaceIsInALayerBesideComposesInteropContainer() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("placed")
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        assertEquals("40px", styleValue(frame.outer, "left"))
        assertEquals("40px", styleValue(frame.outer, "top"))
        assertEquals("600px", styleValue(frame.outer, "width"))
        assertEquals("400px", styleValue(frame.outer, "height"))
        val layer = checkNotNull(parentOf(frame.outer))
        assertTrue(hasAttribute(layer, WebLayers.LAYER_ATTRIBUTE))
        assertEquals("iframe:placed", hitAt(100.0, 100.0))
        assertEquals("compose", hitAt(700.0, 500.0))
        TestViewport.clear()
        frame.remove()
    }

    @Test fun aScrollContainersClipClipsThePageAndItsClicks() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("clipped")
        // A 200 px tall window onto a 400 px page that starts 50 px above it. The page lays out unbounded, as a list
        // item does: `size` alone would be held to the window's 200 px.
        TestViewport.show {
            Box(Modifier.offset(0.dp, 100.dp).size(800.dp, 200.dp).clipToBounds()) {
                FrameSurface(
                    frame,
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(40.dp, (-50).dp).size(600.dp, 400.dp),
                )
            }
        }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        // Chrome writes inset() back in its shortest form: 50px top, 0px right and left, 150px bottom.
        assertEquals("inset(50px 0px 150px)", styleValue(frame.outer, "clip-path"))
        assertEquals("compose", hitAt(100.0, 90.0), "above the window")
        assertEquals("iframe:clipped", hitAt(100.0, 150.0), "inside it")
        assertEquals("compose", hitAt(100.0, 310.0), "below it")
        TestViewport.clear()
        frame.remove()
    }

    @Test fun anUnplacedOrDisposedSurfaceHidesTheFrameWhichNeverLeavesItsLayer() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("kept")
        var placed by mutableStateOf(true)
        var composed by mutableStateOf(true)
        TestViewport.show {
            if (composed) {
                val skip = if (placed) Modifier else Modifier.layout { m, c -> val p = m.measure(c); layout(p.width, p.height) {} }
                Box(Modifier.offset(40.dp, 40.dp).then(skip)) { FrameSurface(frame, Modifier.size(600.dp, 400.dp)) }
            }
        }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        val layer = checkNotNull(parentOf(frame.outer))
        // Hidden, but laid out where it was: the page keeps its viewport, so it neither reflows nor reloads.
        placed = false
        frames(3)
        assertEquals("hidden", styleValue(frame.outer, "visibility"))
        assertEquals("block", styleValue(frame.outer, "display"))
        placed = true
        frames(3)
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        composed = false
        frames(3)
        assertEquals("hidden", styleValue(frame.outer, "visibility"))
        assertEquals("block", styleValue(frame.outer, "display"))
        composed = true
        frames(3)
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        assertTrue(same(layer, parentOf(frame.outer)), "the frame never moved")
        pause(300)
        assertEquals(1, loadsOf(iframe), "so its page never reloaded")
        TestViewport.clear()
        frame.remove()
    }

    @Test fun aHiddenFrameKeepsItsPlaceAndTakesItsNewOneAsItShowsAgain() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("away")
        var x by mutableIntStateOf(40)
        TestViewport.show { FrameSurface(frame, Modifier.offset(x.dp, 40.dp).size(320.dp, 240.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        assertEquals("40px", styleValue(frame.outer, "left"))
        // Out of the window, so hidden: it stays where it last showed, in the viewport (Chrome 156 shows nothing of a
        // frame hidden out of the viewport for its first frame back), its clip as it was.
        x = 3000
        frames(3)
        assertEquals("hidden", styleValue(frame.outer, "visibility"))
        assertEquals("40px", styleValue(frame.outer, "left"))
        assertEquals("40px", styleValue(frame.outer, "top"))
        assertEquals("inset(0px)", styleValue(frame.outer, "clip-path"))
        assertEquals("320px", styleValue(frame.outer, "width"))
        // Back in the window: it moves in the update that shows it.
        x = 200
        frames(3)
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        assertEquals("200px", styleValue(frame.outer, "left"))
        assertEquals("iframe:away", hitAt(300.0, 100.0))
        // Hidden by its engine (setVisible(false)) while its view moves, then shown: the same.
        frame.shown = false
        x = 120
        frames(3)
        assertEquals("hidden", styleValue(frame.outer, "visibility"))
        assertEquals("200px", styleValue(frame.outer, "left"))
        frame.shown = true
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        assertEquals("120px", styleValue(frame.outer, "left"))
        assertEquals("iframe:away", hitAt(200.0, 100.0))
        pause(300)
        assertEquals(1, loadsOf(iframe), "and its page never reloaded")
        TestViewport.clear()
        frame.remove()
    }

    @Test fun holesAreCutTakeTheClicksAndGo() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("holed")
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        val menu = OverlayShape(Rect(200f, 150f, 440f, 310f), CornerRadius(24f))
        frame.applyCutouts(computeCutouts(page, page, listOf(menu), emptyList()), CutoutMode.ClipPath)
        frames(2)
        assertEquals("compose", hitAt(320.0, 230.0))
        assertEquals("iframe:holed", hitAt(203.0, 153.0), "outside the rounded corner")
        assertEquals("iframe:holed", hitAt(100.0, 100.0))
        frame.applyCutouts(computeCutouts(page, page, emptyList(), emptyList()), CutoutMode.ClipPath)
        frames(2)
        assertEquals("iframe:holed", hitAt(320.0, 230.0))
        TestViewport.clear()
        frame.remove()
    }

    @Test fun aPageWithNoOverlayTakesTheClickAndAHolePassesItToCompose() = runTest(timeout = 1.minutes) {
        // The feasibility test's G2: the outer div takes no hits and the cut div takes them, both written explicitly.
        // Otherwise the outer div fills every hole, or an unstyled cut div inherits `none` and the page never takes one.
        val (frame, iframe) = frameWithPage("clicked")
        var clicks by mutableIntStateOf(0)
        TestViewport.show {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(200.dp, 150.dp).size(240.dp, 160.dp).clickable { clicks++ })
                FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp))
            }
        }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        assertEquals("none", styleValue(frame.outer, "pointer-events"))
        assertEquals("auto", styleValue(frame.cut, "pointer-events"))
        assertEquals("iframe:clicked", clickThroughHitTest(320.0, 230.0), "with no overlay, the page takes the click")
        frames(3)
        assertEquals(0, clicks, "and Compose under it gets nothing")
        val hole = OverlayShape(Rect(200f, 150f, 440f, 310f))
        frame.applyCutouts(computeCutouts(page, page, listOf(hole), emptyList()), CutoutMode.ClipPath)
        frames(2)
        assertEquals("compose", clickThroughHitTest(320.0, 230.0), "a hole passes the click to Compose")
        waitUntil("Compose to take the click") { clicks == 1 }
        frame.applyCutouts(computeCutouts(page, page, emptyList(), emptyList()), CutoutMode.ClipPath)
        frames(2)
        assertEquals("auto", styleValue(frame.cut, "pointer-events"), "written again, never removed")
        assertEquals("iframe:clicked", clickThroughHitTest(320.0, 230.0), "the hole is gone, the page takes it again")
        TestViewport.clear()
        frame.remove()
    }

    @Test fun underAScrimThePageDimsAndTakesNoClicks() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("scrimmed")
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        val scrim = ScrimShape(Rect(0f, 0f, 800f, 600f), Color.Black.copy(alpha = 0.32f))
        frame.applyCutouts(computeCutouts(page, page, emptyList(), listOf(scrim)), CutoutMode.ClipPath)
        frames(2)
        assertEquals("none", styleValue(frame.cut, "pointer-events"))
        assertEquals("compose", hitAt(100.0, 100.0))
        frame.applyCutouts(computeCutouts(page, page, emptyList(), emptyList()), CutoutMode.ClipPath)
        frames(2)
        assertEquals("auto", styleValue(frame.cut, "pointer-events"))
        assertEquals("iframe:scrimmed", hitAt(100.0, 100.0))
        TestViewport.clear()
        frame.remove()
    }

    /** A scrim that moves keeps its div: cut-out updates under a dialog restyle what is there, and recreate nothing. */
    @Test fun aScrimKeepsItsDivAcrossCutoutUpdates() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("scrim-kept")
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        val dim = Color.Black.copy(alpha = 0.32f)
        frame.applyCutouts(computeCutouts(page, page, emptyList(), listOf(ScrimShape(Rect(0f, 0f, 800f, 600f), dim))), CutoutMode.ClipPath)
        val div = lastChildOf(frame.cut)
        frame.applyCutouts(computeCutouts(page, page, emptyList(), listOf(ScrimShape(Rect(0f, 100f, 800f, 600f), dim))), CutoutMode.ClipPath)
        assertTrue(same(div, lastChildOf(frame.cut)), "a new div for the same scrim")
        assertEquals("60px", styleValue(div, "top"), "moved with the scrim")
        frame.applyCutouts(computeCutouts(page, page, emptyList(), emptyList()), CutoutMode.ClipPath)
        assertEquals(0, childCount(frame.cut) - 1, "only the page is left")
        TestViewport.clear()
        frame.remove()
    }

    /**
     * Not a fix, a fact the frame relies on not to cache its writes: setting a property to the value it has changes
     * nothing (CSSOM: no "update style attribute"), so no mutation, and no style invalidation.
     */
    @Test fun settingAStylePropertyToItsValueChangesNothing() = runTest(timeout = 1.minutes) {
        val div = newElement("div")
        addChild(documentBody(), div)
        styleOf(div, "opacity", "1")
        val records = watchStyle(div)
        styleOf(div, "opacity", "1")
        frames(1)
        assertEquals(0, recordsOf(records))
        styleOf(div, "opacity", "0")
        frames(1)
        assertEquals(1, recordsOf(records))
        removeElement(div)
    }

    @Test fun inHidePageModeAPageAnOverlayTouchesHides() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("hidden")
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        val menu = OverlayShape(Rect(200f, 150f, 440f, 310f))
        frame.applyCutouts(computeCutouts(page, page, listOf(menu), emptyList()), CutoutMode.HidePage)
        frames(2)
        assertEquals("hidden", styleValue(frame.outer, "visibility"))
        assertEquals("compose", hitAt(100.0, 100.0))
        TestViewport.clear()
        frame.remove()
    }

    /**
     * Covered, a frame draws in its place but isn't seen, and takes no clicks: its page renders (a frame in a hidden box
     * doesn't), so the frame the runtime's cover gives way to is one the page has drawn.
     */
    @Test fun aCoveredFrameDrawsInItsPlaceUnseenAndTakesNoClicks() = runTest(timeout = 1.minutes) {
        val (frame, iframe) = frameWithPage("covered")
        frame.covered = true
        TestViewport.show { FrameSurface(frame, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) }
        waitUntil("the page to load") { loadsOf(iframe) == 1 }
        assertEquals("block", styleValue(frame.outer, "display"))
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        assertEquals("0", styleValue(frame.outer, "opacity"))
        assertEquals("none", styleValue(frame.cut, "pointer-events"))
        assertEquals("compose", hitAt(100.0, 100.0))
        frame.covered = false
        assertEquals("visible", styleValue(frame.outer, "visibility"))
        assertEquals("1", styleValue(frame.outer, "opacity"))
        assertEquals("auto", styleValue(frame.cut, "pointer-events"))
        assertEquals("iframe:covered", hitAt(100.0, 100.0))
        TestViewport.clear()
        frame.remove()
    }
}
