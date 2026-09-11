package dev.supermux.web

import dev.supermux.web.editor.WebEditorEngine
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WebEditorEngine]'s half of the bridge, against a REAL cross-document `postMessage` pipe in
 * headless Chrome — the only place the transport is actually proved. Everything ABOVE the pipe (the
 * JS the planner emits, the `{fn,arg}` parse) is already unit-tested in `:ui`; what cannot be
 * tested without a browser is that an eval posted at a frame arrives, runs, and that the reply
 * finds its way back into `pendingEvaluations`.
 *
 * Karma serves only its own page, so the cm6 bundle cannot be loaded: the frame is an `srcdoc`
 * stand-in carrying the SAME transport shim (`apps/web/editor/editor-shim.js`, copied below — if
 * you change one, change the other) plus a two-function stub for the bundle. That is enough,
 * because the real bundle's `AndroidEditor` hooks are defined by `:ui`'s `bridgeShimJs`, which this
 * test does run verbatim: the engine evals it into the frame exactly as it will in production.
 */
class EditorBridgeIframeTest {

    /** The transport shim, verbatim from `apps/web/editor/editor-shim.js` (comments stripped). */
    private val shimJs = """
        (function () {
          var origin = window.location.origin && window.location.origin !== "null" ? window.location.origin : "*";
          window.smxEditorQuery = function (q) {
            try { window.parent.postMessage(String(q && q.request != null ? q.request : ""), origin); } catch (e) {}
          };
          window.smxEditorQueryCancel = function () {};
          window.addEventListener("message", function (ev) {
            if (ev.source !== window.parent) return;
            if (typeof ev.data !== "string" || ev.data.indexOf("__smxEval:") !== 0) return;
            try { (0, eval)(ev.data.slice(10)); } catch (e) {
              try { window.smxEditorQuery({ request: JSON.stringify({ fn: "evalError", arg: String(e) }) }); } catch (e2) {}
            }
          });
        })();
    """.trimIndent()

    /**
     * The bundle stand-in: `cmInit` fires `onReady` SYNCHRONOUSLY (cm6-entry.mjs does the same, and
     * that timing is the reason `initScript` concatenates the shim and `cmInit` into one eval), and
     * `cmGetContent` returns whatever was last set — so the round trip proves a real read, not an
     * echo of a constant.
     */
    private fun page(): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <script>$shimJs</script>
        <script>
          var doc = "";
          function cmInit(content, filename, lineWrap, fontSize) {
            doc = content;
            window.cmInitArgs = filename + "|" + lineWrap + "|" + fontSize;
            window.AndroidEditor.onReady();
          }
          function cmSetContent(s) { doc = s; }
          function cmSetLanguage() {}
          function cmSetLineWrap() {}
          function cmSetFontSize() {}
          function cmSetScrollTop() {}
          function cmGetContent() { return doc; }
        </script>
        </body></html>
    """.trimIndent()

    private fun mount(engine: WebEditorEngine): Pair<HTMLDivElement, HTMLIFrameElement> {
        val div = document.createElement("div") as HTMLDivElement
        div.style.width = "600px"
        div.style.height = "300px"
        document.body!!.appendChild(div)
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.style.width = "100%"
        frame.style.height = "100%"
        // `adopt` BEFORE the frame is in the document: the `load` listener has to be installed
        // before the parser can fire it, or the engine never injects `cmInit`.
        engine.adopt(frame)
        frame.setAttribute("srcdoc", page())
        div.appendChild(frame)
        return div to frame
    }

    @Test
    fun readyArrivesAndGetContentRoundTrips() = runTest {
        val engine = WebEditorEngine(lineWrap = true, fontSize = 15)
        engine.setDocument("notes.txt", "hello from kotlin")
        val (div, _) = mount(engine)
        try {
            // No explicit deadline: Karma's own per-test timeout is shorter than the engine's 8 s
            // ready timeout, and a `withTimeout` here would run on `runTest`'s virtual clock and
            // fire instantly.
            engine.ready.first { it }
            assertTrue(engine.ready.value, "engine reports ready once the page's onReady arrives")
            assertEquals(null, engine.failed.value, "a ready page must not also report a failure")

            val content = CompletableDeferred<String>()
            engine.getContent { content.complete(it) }
            assertEquals(
                "hello from kotlin",
                content.await(),
                "the document queued before the page loaded was flushed by cmInit and read back",
            )

            // A live push takes the same pipe and the read reflects it.
            engine.setDocument("notes.txt", "second revision")
            val updated = CompletableDeferred<String>()
            engine.getContent { updated.complete(it) }
            assertEquals("second revision", updated.await())
        } finally {
            engine.dispose()
            document.body!!.removeChild(div)
        }
    }

    /** A read issued with no frame mounted must still call back (with the documented ""), never hang. */
    @Test
    fun getContentBeforeAttachFiresTheFallback() = runTest {
        val engine = WebEditorEngine(lineWrap = false, fontSize = 13)
        val content = CompletableDeferred<String>()
        engine.getContent { content.complete(it) }
        assertEquals("", content.await())
    }
}
