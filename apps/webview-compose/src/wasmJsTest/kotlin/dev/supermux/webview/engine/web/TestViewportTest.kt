package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun probeElement(id: String): HTMLElement =
    js("(function () { var d = document.createElement('div'); d.id = id; return d; })()")

private fun elementExists(id: String): Boolean = js("document.getElementById(id) !== null")

/**
 * What the web engine relies on in CMP 1.12.2's DOM (plan fact F2): the viewport's first child holds the canvas's
 * shadow host first and the interop container next, and an interop element's wrapper sits in that container.
 */
private fun viewportStructure(container: HTMLElement, id: String): String = js("""(function () {
  var positioning = container.firstElementChild;
  if (!positioning) return 'no positioning container';
  var host = positioning.children[0], interop = positioning.children[1];
  if (!host || !host.shadowRoot || !host.shadowRoot.querySelector('canvas')) return 'no canvas shadow host first';
  if (!interop || getComputedStyle(interop).position !== 'absolute') return 'no absolute interop container second';
  var probe = document.getElementById(id);
  if (!probe || !probe.parentElement || probe.parentElement.parentElement !== interop) return 'probe not in the interop container';
  return 'ok';
})()""")

@OptIn(ExperimentalComposeUiApi::class)
class TestViewportTest {
    @Test fun composeDrawsInKarmaAndInteropElementsSitAboveTheCanvas() = runTest(timeout = 2.minutes) {
        var draws by mutableIntStateOf(0)
        TestViewport.show {
            Box(Modifier.fillMaxSize().drawBehind { draws++ }) {
                HtmlElementView(factory = { probeElement("viewport-probe") }, modifier = Modifier.size(40.dp))
            }
        }
        waitUntil("Compose to draw") { draws > 0 }
        waitUntil("the interop element to reach the DOM") { elementExists("viewport-probe") }
        assertEquals("ok", viewportStructure(TestViewport.element, "viewport-probe"))
        assertEquals("compose", hitAt(400.0, 500.0), "away from the probe, hit testing finds Compose")
        TestViewport.clear()
    }
}
