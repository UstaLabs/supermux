// `js(…)`/`JsString` interop is still behind the wasm opt-in in Kotlin 2.3; wasmJsMain sets it in
// the build file, the test source set does not.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web

import dev.supermux.web.editor.WebEditorEngine
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement
import kotlin.js.Promise
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
 * Karma serves only its own page, so the cm6 bundle cannot be loaded and the frame is an `srcdoc`
 * stand-in. Its shim, though, is the REAL `apps/web/editor/editor-shim.js`, fetched at run time
 * (the `editorShimTestResource` Copy task in build.gradle.kts puts it on the Karma server, and
 * `karma.config.d/editor-shim.js` gives it this URL) — an inlined copy would let the shipped shim
 * drift while the test kept passing against yesterday's text. Only the BUNDLE is stubbed, and even
 * its `AndroidEditor` hooks are the shared `bridgeShimJs` ones, which the engine evals in verbatim.
 */
class EditorBridgeIframeTest {

    private suspend fun shimJs(): String = fetchText(SHIM_URL).await().toString()

    /**
     * The bundle stand-in: `cmInit` fires `onReady` SYNCHRONOUSLY (cm6-entry.mjs does the same, and
     * that timing is the reason `initScript` concatenates the shim and `cmInit` into one eval), and
     * `cmGetContent` returns whatever was last set — so the round trip proves a real read, not an
     * echo of a constant.
     */
    private fun page(shim: String): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <script>$shim</script>
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

    private fun mount(engine: WebEditorEngine, shim: String): HTMLDivElement {
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
        frame.setAttribute("srcdoc", page(shim))
        div.appendChild(frame)
        return div
    }

    @Test
    fun readyArrivesAndGetContentRoundTrips() = runTest {
        val shim = shimJs()
        val engine = WebEditorEngine(lineWrap = true, fontSize = 15)
        engine.setDocument("notes.txt", "hello from kotlin")
        val div = mount(engine, shim)
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

    /**
     * A forged reply from the WRONG source must not complete a pending read. The engine's listener
     * is on `window`, which hears every `postMessage` in the page — including one the host document
     * sends itself, which is what any other script on the page (or an ad/extension frame) can do.
     * Only `ev.source === iframe.contentWindow` separates them, and this is where that is proved:
     * the read must resolve with the frame's real answer, not the forgery that arrives first.
     */
    @Test
    fun aMessageFromTheWrongSourceDoesNotCompleteARead() = runTest {
        val shim = shimJs()
        val engine = WebEditorEngine(lineWrap = false, fontSize = 13)
        engine.setDocument("real.txt", "the real document")
        val div = mount(engine, shim)
        try {
            engine.ready.first { it }
            val content = CompletableDeferred<String>()
            engine.getContent { content.complete(it) }
            // The engine's first evaluation id is 1; a `window`-sourced reply claiming it would
            // complete the read with the forged value if the source filter were missing.
            postToSelf("""{"fn":"evalResult","arg":"{\"id\":1,\"value\":\"FORGED\"}"}""")
            assertEquals("the real document", content.await(), "the frame's answer wins; the forgery is dropped")
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

    private companion object {
        /** Where `karma.config.d/editor-shim.js` proxies the real shim to. */
        const val SHIM_URL = "/editor-shim.js"
    }
}

/** Fetch as text, rejecting a non-2xx loudly — a silent "" would stub the shim out of the test. */
private fun fetchText(url: String): Promise<JsString> =
    js("fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status + ' fetching ' + url); return r.text(); })")

/** Post a bridge payload at our own window — the wrong `source` for the engine's listener. */
private fun postToSelf(request: String): Unit = js("window.postMessage(request, '*')")
