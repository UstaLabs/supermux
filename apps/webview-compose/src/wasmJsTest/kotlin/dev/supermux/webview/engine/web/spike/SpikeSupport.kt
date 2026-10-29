package dev.supermux.webview.engine.web.spike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.HtmlElementView
import dev.supermux.webview.engine.web.now
import dev.supermux.webview.runtime.OverlayCutouts
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

// Document starts, counted per item from every spike page's first script.

internal fun installStartCounter(): Unit = js("""{
  if (!window.__spikeStarts) {
    window.__spikeStarts = {};
    window.addEventListener('message', function (e) {
      var d = e.data;
      if (d && typeof d === 'object' && typeof d.spikeStart === 'number') window.__spikeStarts[d.spikeStart] = (window.__spikeStarts[d.spikeStart] || 0) + 1;
    });
  }
}""")

internal fun resetStarts(): Unit = js("{ if (window.__spikeStarts) { for (var k in window.__spikeStarts) delete window.__spikeStarts[k]; } }")

internal fun startsOf(item: Int): Int = js("(window.__spikeStarts && window.__spikeStarts[item]) || 0")

/** A sandboxed srcdoc iframe whose page tells the parent, once per document, that it started. */
internal fun spikeIframe(item: Int, name: String): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.setAttribute('data-test-name', name);
  f.style.border = '0';
  f.style.display = 'block';
  f.style.position = 'absolute';
  f.style.left = '0px';
  f.style.top = '0px';
  f.style.width = '100%';
  f.style.height = '100%';
  f.srcdoc = '<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;height:100%;font:13px system-ui;background:#e8eefc}</style></head><body><b>item ' + item + '</b><script>parent.postMessage({spikeStart: ' + item + '}, "*");<\/script></body></html>';
  return f;
})()""")

/** Iframes created, per item. Document starts beyond them are reloads. */
internal class SpikeCounts {
    private val creations = LinkedHashMap<Int, Int>()

    fun created(item: Int) {
        creations[item] = (creations[item] ?: 0) + 1
    }

    val created: Int get() = creations.values.sum()

    val reloads: Int get() = creations.entries.sumOf { (item, made) -> (startsOf(item) - made).coerceAtLeast(0) }
}

// Strategy A: the iframe is the HtmlElementView's own element.

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FrameA(item: Int, counts: SpikeCounts, modifier: Modifier) {
    HtmlElementView(factory = { counts.created(item); spikeIframe(item, "a$item") }, modifier = modifier)
}

// Strategy B: our own layer next to Compose's interop container, and frames placed in it, never moved.

private fun newDiv(): HTMLElement = js("document.createElement('div')")

/** The layer beside the interop container that holds [anchor]'s wrapper; made the first time (plan fact F2). */
private fun spikeLayerBeside(anchor: HTMLElement): HTMLElement? = js("""(function () {
  var wrapper = anchor.parentElement;
  var interop = wrapper ? wrapper.parentElement : null;
  var host = interop ? interop.parentElement : null;
  if (!host) return null;
  var next = interop.nextElementSibling;
  if (next && next.hasAttribute('data-spike-layer')) return next;
  var layer = document.createElement('div');
  layer.setAttribute('data-spike-layer', '');
  layer.style.position = 'absolute';
  layer.style.left = '0px';
  layer.style.top = '0px';
  layer.style.width = '0px';
  layer.style.height = '0px';
  host.insertBefore(layer, interop.nextSibling);
  return layer;
})()""")

private fun assembleFrame(outer: HTMLElement, cut: HTMLElement, iframe: HTMLElement): Unit = js("""{
  outer.setAttribute('data-webview-frame', '');
  outer.style.position = 'absolute';
  outer.style.visibility = 'hidden';
  cut.style.position = 'absolute';
  cut.style.left = '0px';
  cut.style.top = '0px';
  cut.style.width = '100%';
  cut.style.height = '100%';
  cut.appendChild(iframe);
  outer.appendChild(cut);
}""")

private fun placeFrame(
    layer: HTMLElement,
    outer: HTMLElement,
    left: Double,
    top: Double,
    width: Double,
    height: Double,
    clipTop: Double,
    clipRight: Double,
    clipBottom: Double,
    clipLeft: Double,
    visible: Boolean,
): Unit = js("""{
  if (outer.parentElement !== layer) layer.appendChild(outer);
  outer.style.left = left + 'px';
  outer.style.top = top + 'px';
  outer.style.width = width + 'px';
  outer.style.height = height + 'px';
  outer.style.clipPath = 'inset(' + clipTop + 'px ' + clipRight + 'px ' + clipBottom + 'px ' + clipLeft + 'px)';
  outer.style.visibility = visible ? 'visible' : 'hidden';
}""")

private fun hideFrame(outer: HTMLElement): Unit = js("{ outer.style.visibility = 'hidden'; }")

private fun removeFrame(outer: HTMLElement): Unit = js("{ if (outer.parentNode) outer.parentNode.removeChild(outer); }")

private fun clipCut(cut: HTMLElement, clipPath: String): Unit = js("{ cut.style.clipPath = clipPath; }")

/** The JS time strategy B's placement takes, and how many frames exist now. */
internal object SpikeCost {
    var liveFrames: Int = 0
    private var pending = 0.0

    fun add(millis: Double) {
        pending += millis
    }

    /** The time spent since the last take. */
    fun take(): Double = pending.also { pending = 0.0 }
}

/** Strategy B's frame: an iframe in two divs, appended to the layer once and then only restyled. */
internal class SpikeFrame(iframe: HTMLElement) {
    private val outer: HTMLElement = newDiv()
    private val cut: HTMLElement = newDiv()
    private var density = 1f
    private var destroyed = false

    init {
        assembleFrame(outer, cut, iframe)
        SpikeCost.liveFrames++
    }

    fun place(layer: HTMLElement, coordinates: LayoutCoordinates, density: Float) {
        if (destroyed) return
        this.density = density
        val position = coordinates.positionInWindow()
        val size = coordinates.size
        val visible = coordinates.boundsInWindow()
        val d = density.toDouble()
        placeFrame(
            layer, outer,
            position.x / d, position.y / d, size.width / d, size.height / d,
            maxOf(0.0, (visible.top - position.y) / d),
            maxOf(0.0, (position.x + size.width - visible.right) / d),
            maxOf(0.0, (position.y + size.height - visible.bottom) / d),
            maxOf(0.0, (visible.left - position.x) / d),
            visible.width >= 1f && visible.height >= 1f,
        )
    }

    fun hide() {
        if (!destroyed) hideFrame(outer)
    }

    /** Cuts the holes of [cutouts] (window px) out of the page, as the engine will: against the whole page. */
    fun cutOut(cutouts: OverlayCutouts) {
        if (destroyed) return
        val css = cutouts.scaled(1f / density)
        val path = OverlayCutouts(css.pageBounds, css.pageBounds, css.holes, emptyList()).toEvenOddPath()
        clipCut(cut, if (css.holes.isEmpty()) "none" else "path(evenodd, '$path')")
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        SpikeCost.liveFrames--
        removeFrame(outer)
    }
}

/** The spike's layer, once found. One viewport serves the whole run. */
internal object SpikeLayer {
    var current: HTMLElement? = null
}

/** The layer, found through a 0×0 anchor the first time; null until then. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberSpikeLayer(): HTMLElement? {
    var layer by remember { mutableStateOf(SpikeLayer.current) }
    if (layer == null) {
        HtmlElementView(
            factory = { newDiv() },
            modifier = Modifier.size(0.dp),
            update = { anchor -> if (layer == null) layer = spikeLayerBeside(anchor)?.also { SpikeLayer.current = it } },
        )
    }
    return layer
}

private class SpikePlacementElement(
    private val frame: SpikeFrame,
    private val layer: HTMLElement?,
    private val onBounds: ((page: Rect, visible: Rect) -> Unit)?,
) : ModifierNodeElement<SpikePlacementNode>() {
    override fun create() = SpikePlacementNode(frame, layer, onBounds)

    override fun update(node: SpikePlacementNode) {
        node.frame = frame
        node.onBounds = onBounds
        node.layer = layer
    }

    override fun equals(other: Any?): Boolean =
        other is SpikePlacementElement && other.frame === frame && other.onBounds === onBounds &&
            (other.layer == null) == (layer == null)

    override fun hashCode(): Int = frame.hashCode()
}

private class SpikePlacementNode(
    var frame: SpikeFrame,
    layer: HTMLElement?,
    var onBounds: ((page: Rect, visible: Rect) -> Unit)?,
) : Modifier.Node(), GlobalPositionAwareModifierNode, UnplacedAwareModifierNode {
    private var last: LayoutCoordinates? = null

    /** Found after the node was placed: the frame is placed at once, as no layout pass may come. */
    var layer: HTMLElement? = layer
        set(value) {
            field = value
            last?.takeIf { it.isAttached }?.let(::placeAt)
        }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        last = coordinates
        placeAt(coordinates)
    }

    private fun placeAt(coordinates: LayoutCoordinates) {
        val target = layer ?: return
        val start = now()
        frame.place(target, coordinates, requireDensity().density)
        SpikeCost.add(now() - start)
        onBounds?.invoke(Rect(coordinates.positionInWindow(), coordinates.size.toSize()), coordinates.boundsInWindow())
    }

    override fun onUnplaced() = frame.hide()

    override fun onDetach() {
        last = null
        frame.hide()
    }
}

@Composable
internal fun FrameB(
    item: Int,
    counts: SpikeCounts,
    modifier: Modifier,
    onBounds: ((page: Rect, visible: Rect) -> Unit)? = null,
): SpikeFrame {
    val layer = rememberSpikeLayer()
    val frame = remember(item) {
        counts.created(item)
        SpikeFrame(spikeIframe(item, "b$item"))
    }
    DisposableEffect(frame) { onDispose { frame.destroy() } }
    Box(modifier.then(SpikePlacementElement(frame, layer, onBounds)))
    return frame
}

// Measurement helpers.

internal fun percentile(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).roundToInt()]
}

/** Milliseconds with three decimals. */
internal fun Double.ms(): String = ((this * 1000).roundToInt() / 1000.0).toString()

/**
 * Calls [onPrePaint] once per rendered frame, after every animation-frame callback of that frame (Compose's included)
 * and their microtasks, and before paint: a ResizeObserver on an element whose width changes every frame.
 */
internal fun installPrePaintProbe(onPrePaint: () -> Unit): JsAny = js("""(function () {
  var el = document.createElement('div');
  el.style.position = 'fixed';
  el.style.left = '-10px';
  el.style.top = '0px';
  el.style.width = '1px';
  el.style.height = '1px';
  document.body.appendChild(el);
  var running = true, w = 1;
  var ro = new ResizeObserver(function () { if (running) onPrePaint(); });
  ro.observe(el);
  function tick() { if (running) { w = w === 1 ? 2 : 1; el.style.width = w + 'px'; requestAnimationFrame(tick); } }
  requestAnimationFrame(tick);
  return { stop: function () { running = false; ro.disconnect(); el.remove(); } };
})()""")

internal fun stopPrePaintProbe(probe: JsAny): Unit = js("{ probe.stop(); }")
