package dev.supermux.webview.engine.web

import androidx.compose.ui.platform.WindowInfo
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A viewport, as far as [WebLayers] keys its layers. */
private class FakeWindow : WindowInfo {
    override val isWindowFocused: Boolean get() = true
}

/**
 * A viewport's DOM as CMP 1.12.2 builds it, off screen: a positioning host, then its interop container, which holds an
 * element's wrapper, which holds the anchor. Returns `[host, anchor]`.
 */
private fun viewportDom(): JsArray<HTMLElement> = js("""(function () {
  var host = document.createElement('div'), interop = document.createElement('div');
  var wrapper = document.createElement('div'), anchor = document.createElement('div');
  host.style.position = 'fixed';
  host.style.left = '-2000px';
  host.appendChild(interop);
  interop.appendChild(wrapper);
  wrapper.appendChild(anchor);
  document.body.appendChild(host);
  return [host, anchor];
})()""")

class WebLayersTest {
    /** Viewports come and go (a dialog's, a window's): the layer of one that is gone is forgotten, not kept forever. */
    @Test fun theLayerOfAViewportThatIsGoneIsForgotten() {
        val gone = FakeWindow()
        val first = viewportDom()
        assertNotNull(WebLayers.discover(gone, first[1]!!))
        assertTrue(WebLayers.remembers(gone))
        removeElement(first[0]!!)
        val next = viewportDom()
        assertNotNull(WebLayers.discover(FakeWindow(), next[1]!!))
        assertFalse(WebLayers.remembers(gone), "a gone viewport's layer is still held")
        removeElement(next[0]!!)
    }
}
