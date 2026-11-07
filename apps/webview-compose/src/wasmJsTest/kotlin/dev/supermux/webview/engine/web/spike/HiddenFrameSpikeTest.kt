package dev.supermux.webview.engine.web.spike

import dev.supermux.webview.engine.web.pause
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A frame in a wrapper with [wrapperStyle] whose page reports its load, its first animation frame and, when asked, its
 * document id and whether `prefers-color-scheme: dark` matches. [colorScheme] goes on the iframe element.
 */
private fun probeFrame(name: String, wrapperStyle: String, colorScheme: String): HTMLElement = js("""(function () {
  var w = document.createElement('div');
  w.setAttribute('style', 'position:fixed;left:540px;top:620px;width:200px;height:100px;' + wrapperStyle);
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.setAttribute('data-test-name', name);
  f.style.width = '200px';
  f.style.height = '100px';
  f.style.border = '0';
  if (colorScheme) f.style.colorScheme = colorScheme;
  f.__state = { load: 0, raf: 0, id: '', dark: '' };
  f.addEventListener('load', function () { f.__state.load++; });
  window.addEventListener('message', function (e) {
    if (e.source !== f.contentWindow || !e.data) return;
    if (e.data.raf) f.__state.raf++;
    if (typeof e.data.id === 'string') { f.__state.id = e.data.id; f.__state.dark = String(e.data.dark); }
  });
  f.srcdoc = '<!doctype html><html><body>probe<script>var id = String(Math.random()); requestAnimationFrame(function () { parent.postMessage({ raf: true }, "*"); }); addEventListener("message", function () { parent.postMessage({ id: id, dark: matchMedia("(prefers-color-scheme: dark)").matches }, "*"); });<\/script></body></html>';
  w.appendChild(f);
  document.body.appendChild(w);
  return f;
})()""")

private fun stateOf(frame: HTMLElement, key: String): String = js("String(frame.__state[key])")

private fun ask(frame: HTMLElement): Unit = js("{ frame.contentWindow.postMessage('ask', '*'); }")

private fun restyle(frame: HTMLElement, wrapperStyle: String): Unit =
    js("{ frame.parentElement.setAttribute('style', 'position:fixed;left:540px;top:620px;width:200px;height:100px;' + wrapperStyle); }")

private fun removeProbe(frame: HTMLElement): Unit = js("{ frame.parentElement.remove(); }")

/** Informational: what hidden frames do, whether toggling `display` keeps the document, and the colour scheme. */
class HiddenFrameSpikeTest {
    @Test fun hiddenFramesLoadAndTogglingDisplayKeepsTheDocument() = runTest(timeout = 2.minutes) {
        for (mode in listOf("visibility:hidden", "display:none", "opacity:0")) {
            val frame = probeFrame("hidden", mode, "")
            pause(1_500)
            println("SPIKE info hidden mode=$mode load=${stateOf(frame, "load")} first-raf=${stateOf(frame, "raf")}")
            removeProbe(frame)
        }

        val shown = probeFrame("toggle", "", "")
        pause(1_000)
        ask(shown)
        pause(300)
        val before = stateOf(shown, "id")
        restyle(shown, "display:none")
        pause(300)
        restyle(shown, "")
        pause(300)
        ask(shown)
        pause(300)
        val after = stateOf(shown, "id")
        println("SPIKE info display-toggle same-document=${before == after} loads=${stateOf(shown, "load")}")
        removeProbe(shown)
        assertTrue(before.isNotEmpty(), "the page answered")

        for (scheme in listOf("dark", "light")) {
            val frame = probeFrame("scheme", "", scheme)
            pause(1_000)
            ask(frame)
            pause(300)
            println("SPIKE info color-scheme iframe=$scheme prefers-dark=${stateOf(frame, "dark")}")
            removeProbe(frame)
        }
    }
}
