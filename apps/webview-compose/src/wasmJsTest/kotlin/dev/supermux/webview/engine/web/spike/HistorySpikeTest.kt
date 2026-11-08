package dev.supermux.webview.engine.web.spike

import dev.supermux.webview.engine.web.pause
import dev.supermux.webview.engine.web.waitUntil
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun historyLength(): Int = js("history.length")

private fun pageHref(): String = js("location.href")

private fun historyBack(): Unit = js("{ history.back(); }")

private fun randomName(): String = js("'webview-' + Math.random().toString(36).slice(2)")

/**
 * A sandboxed srcdoc frame (named [frameName] unless it is empty) whose page moves its own fragment when told to
 * (`{go: '#x'}`), and reports every fragment change. Counts its load events and those reports.
 */
private fun historyFrame(testName: String, frameName: String): HTMLElement = js("""(function () {
  var f = document.createElement('iframe');
  f.setAttribute('sandbox', 'allow-scripts');
  f.setAttribute('data-test-name', testName);
  if (frameName) f.setAttribute('name', frameName);
  f.style.position = 'fixed';
  f.style.left = '320px';
  f.style.top = '620px';
  f.style.width = '200px';
  f.style.height = '100px';
  f.style.border = '0';
  f.__loads = 0;
  f.__hashes = 0;
  f.addEventListener('load', function () { f.__loads++; });
  window.addEventListener('message', function (e) { if (e.source === f.contentWindow && e.data && e.data.hashchange) f.__hashes++; });
  f.srcdoc = '<!doctype html><html><body>history<script>addEventListener("message", function (e) { if (e.data && e.data.go) location.hash = e.data.go; }); addEventListener("hashchange", function () { parent.postMessage({ hashchange: true }, "*"); });<\/script></body></html>';
  document.body.appendChild(f);
  return f;
})()""")

private fun loadsOf(frame: HTMLElement): Int = js("frame.__loads | 0")

private fun hashesOf(frame: HTMLElement): Int = js("frame.__hashes | 0")

private fun tell(frame: HTMLElement, hash: String): Unit = js("{ frame.contentWindow.postMessage({ go: hash }, '*'); }")

private fun removeFrame(frame: HTMLElement): Unit = js("{ frame.remove(); }")

/** Feasibility G6: the engine's own documents add no tab history, and nothing old comes back into a new frame. */
class HistorySpikeTest {
    private suspend fun variant(named: Boolean): String {
        val tag = if (named) "named" else "unnamed"
        fun name() = if (named) randomName() else ""
        val href = pageHref()
        val l0 = historyLength()
        var frame = historyFrame("h-$tag-0", name())
        waitUntil("the first frame to load") { loadsOf(frame) == 1 }
        val l1 = historyLength()
        // The engine starts each document in a fresh iframe: five more, each replacing the last.
        repeat(5) { i ->
            removeFrame(frame)
            frame = historyFrame("h-$tag-${i + 1}", name())
            val current = frame
            waitUntil("frame ${i + 1} to load") { loadsOf(current) == 1 }
        }
        val l2 = historyLength()
        // The page navigates itself twice: these entries are the page's, as iframes allow.
        tell(frame, "#a")
        pause(300)
        tell(frame, "#b")
        pause(300)
        val l3 = historyLength()
        // Its frame goes, as reset() makes it go; then a new one comes.
        removeFrame(frame)
        pause(300)
        val l4 = historyLength()
        val fresh = historyFrame("h-$tag-fresh", name())
        waitUntil("the new frame to load") { loadsOf(fresh) == 1 }
        var navigated = -1
        if (l4 > l2) {
            historyBack()
            pause(1_000)
            navigated = (loadsOf(fresh) - 1) + hashesOf(fresh)
        }
        assertEquals(href, pageHref(), "Back must never navigate the test page")
        removeFrame(fresh)
        return "SPIKE G6 variant=$tag engine-start-entries=${l1 - l0} recreate-entries=${l2 - l1} " +
            "page-entries=${l3 - l2} entries-left-after-remove=${l4 - l2} back-navigated-new-frame=$navigated"
    }

    @Test fun engineStartedDocumentsAddNoHistoryAndOldEntriesNeverReachANewFrame() = runTest(timeout = 2.minutes) {
        println(variant(named = true))
        println(variant(named = false))
    }
}
