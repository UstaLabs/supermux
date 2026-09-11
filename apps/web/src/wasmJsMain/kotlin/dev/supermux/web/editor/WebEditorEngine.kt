// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.editor

import dev.supermux.ui.editor.DomEditorEngine
import dev.supermux.ui.editor.engine.BridgeEvent
import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EDITOR_READY_TIMEOUT_MS
import dev.supermux.ui.editor.engine.EditorCallbacks
import dev.supermux.ui.editor.engine.EditorPushPlanner
import dev.supermux.ui.editor.engine.evalResultJs
import dev.supermux.ui.editor.engine.initScript
import dev.supermux.ui.editor.engine.lspConnectJs
import dev.supermux.ui.editor.engine.lspDisconnectJs
import dev.supermux.ui.editor.engine.lspMessageJs
import dev.supermux.ui.editor.engine.parseBridgeEvent
import dev.supermux.ui.editor.engine.parseLspOut
import dev.supermux.ui.editor.engine.showDiffRegionJs
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.events.Event

/**
 * The browser's [dev.supermux.ui.editor.engine.EditorEngine]: the SAME committed cm6 bundle the
 * phones and desktop ship, inside a same-origin `<iframe>`, speaking desktop's bridge protocol
 * verbatim.
 *
 * ── Why an iframe and not the host document ─────────────────────────────────
 * The bundle owns a whole page: it styles `html, body`, installs document-level key and touch
 * handlers for font zoom, and positions `#editor` with `position: fixed; inset: 0`. Dropping that
 * into the Compose host document would fight the canvas for the viewport and for every keystroke.
 * A frame gives it its own document, its own `fixed` viewport and its own key handling, for free —
 * and because it is served from the same origin as the app, nothing about the bridge has to become
 * a cross-document protocol: it is the desktop protocol with a different pipe.
 *
 * ── The pipe ────────────────────────────────────────────────────────────────
 * Desktop has JCEF's message router: a query function injected into the page, and
 * `executeJavaScript` the other way. Wasm has neither, so `editor/editor-shim.js` (loaded by the
 * staged `editor/index.html` BEFORE `cm6.js`) supplies both ends and nothing else:
 *
 *   JS → Kotlin  `window.smxEditorQuery({request})` → `parent.postMessage(request, origin)` →
 *                this engine's `message` listener (filtered by `ev.source === iframe.contentWindow`)
 *                → [parseBridgeEvent] → exactly the desktop dispatch.
 *   Kotlin → JS  [postEval] → `contentWindow.postMessage("__smxEval:" + code, origin)` → the shim's
 *                `(0, eval)`.
 *
 * The bundle's own `window.AndroidEditor` / `window.webkit.messageHandlers.lsp` globals are NOT the
 * shim's business: `bridgeShimJs(QUERY_FN)` in `:ui` already defines them over `smxEditorQuery`,
 * and this engine evals it as the first half of [initScript] just as desktop does. One shared,
 * unit-tested definition of those ten hooks for all four hosts.
 *
 * ── Threading ───────────────────────────────────────────────────────────────
 * Wasm is single-threaded: every callback here (the frame's `load`, the `message` listener, the
 * ready timeout) already arrives on the one thread Compose renders on, so the EDT marshalling that
 * dominates the desktop engine has no counterpart — and no `@Volatile`/`AtomicLong` either.
 *
 * @param lineWrap initial soft-wrap state (a `cmInit` arg; live-changeable via [setLineWrap]).
 * @param fontSize initial font px (a `cmInit` arg; live-changeable via [setFontSize]).
 * @param indexUrl the staged bundle page, relative to the app's own document. Overridden by tests,
 *   which cannot serve a second page and hand the engine an `srcdoc` frame instead.
 */
class WebEditorEngine(
    lineWrap: Boolean,
    fontSize: Int,
    private val indexUrl: String = DEFAULT_INDEX_URL,
) : DomEditorEngine {

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _failed = MutableStateFlow<String?>(null)
    override val failed: StateFlow<String?> = _failed.asStateFlow()

    override var callbacks: EditorCallbacks = EditorCallbacks()

    private val planner = EditorPushPlanner(lineWrap, fontSize)

    private var frame: HTMLIFrameElement? = null
    private var messageListener: ((Event) -> Unit)? = null
    private var readyTimeout: Int = 0
    private var nextEvaluationId: Long = 1
    private val pendingEvaluations = mutableMapOf<Long, (String) -> Unit>()
    private var pendingDiffRegion: DiffRegionRequest? = null

    // ── Mount / unmount ──────────────────────────────────────────────────────

    /**
     * Mount the frame into the container Compose positioned for us. Called once the container is in
     * the document AT A NON-ZERO SIZE (`EditorEngineHost`'s attach-once-when-sized rule): cm6
     * measures the viewport on `cmInit`, and a frame born in a 0×0 box first-paints an editor with
     * no visible lines.
     *
     * A caller may hand us a frame directly ([adopt], used by the Karma test where only the test
     * page itself is served); attach then only wires the transport up to whatever is already there.
     */
    override fun attach(container: HTMLElement) {
        if (frame != null) return
        val f = document.createElement("iframe") as HTMLIFrameElement
        f.style.width = "100%"
        f.style.height = "100%"
        f.style.setProperty("border", "0")
        // The bundle's own One-Dark backing, painted before cm6 gets a chance to: without it the
        // frame flashes white over the dark pane on every open.
        f.style.setProperty("background", "#282c34")
        // `display:block` — an iframe is inline by default and would leave a text-baseline gap
        // under it, which in a 100%-height box means a permanent scrollbar in the container.
        f.style.setProperty("display", "block")
        f.setAttribute("title", "Code editor")
        adopt(f)
        f.src = indexUrl
        container.appendChild(f)
    }

    /**
     * Wire the transport to [f] and start the ready clock, without creating or inserting anything.
     * The seam the test uses: Karma serves only its own page, so the bundle stand-in is an `srcdoc`
     * frame the test builds and mounts itself — everything downstream of `load` is then identical.
     */
    fun adopt(f: HTMLIFrameElement) {
        frame = f
        val listener: (Event) -> Unit = { ev -> onFrameMessage(ev) }
        messageListener = listener
        window.addEventListener("message", listener)
        f.addEventListener("load", { onFrameLoad() })
        armReadyTimeout()
    }

    /**
     * Unmount: the frame goes, the listener goes, and the planner un-readies so a later [attach]
     * re-pushes the whole document into a fresh page (state — content, filename, wrap, font — is
     * deliberately KEPT on the planner for exactly that).
     */
    override fun detach() {
        clearReadyTimeout()
        messageListener?.let { window.removeEventListener("message", it) }
        messageListener = null
        frame?.let { removeFromParent(it) }
        frame = null
        _ready.value = false
        planner.onRendererLost()
        // Nobody is going to answer these now; fire the fallback values rather than leaking the
        // continuations (desktop's dispose does the same).
        pendingEvaluations.values.toList().forEach { it("") }
        pendingEvaluations.clear()
    }

    /** Idempotent teardown. Same work as [detach] — there is no per-engine runtime to release. */
    override fun dispose() = detach()

    // ── Kotlin → JS ──────────────────────────────────────────────────────────

    override fun setDocument(path: String, content: String, scrollTop: Int) =
        emit(planner.setDocument(content, path, scrollTop))

    override fun revealLine(line: Int, endLine: Int?) = emit(planner.revealLine(line, endLine))

    override fun setFontSize(px: Int) = emit(planner.setFontSize(px))
    override fun setLineWrap(on: Boolean) = emit(planner.setLineWrap(on))
    override fun setScrollTop(px: Int) = emit(planner.setScrollTop(px))

    override fun showDiffRegion(
        path: String,
        content: String,
        ranges: List<DiffRegionRange>,
        language: String,
        restoreScrollTop: Int?,
        threads: List<DiffRegionThread>,
        composer: DiffRegionComposer?,
    ) {
        pendingDiffRegion = DiffRegionRequest(path, content, ranges, language, restoreScrollTop, threads, composer)
        if (_ready.value) emitDiffRegion()
    }

    override fun updateDiffThreads(threads: List<DiffRegionThread>, composer: DiffRegionComposer?) {
        val current = pendingDiffRegion ?: return
        pendingDiffRegion = current.copy(restoreScrollTop = null, threads = threads, composer = composer)
        if (_ready.value) emitDiffRegion()
    }

    override fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) =
        postEval(lspConnectJs(serverId, rootUri, fileUri, languageId))

    override fun lspMessage(serverId: String, message: String) = postEval(lspMessageJs(serverId, message))

    override fun lspDisconnect() = postEval(lspDisconnectJs())

    // ── JS → Kotlin reads ────────────────────────────────────────────────────

    override fun getContent(cb: (String) -> Unit) = evaluateJavaScript("cmGetContent()", cb)

    override fun readScrollTop(cb: (Int) -> Unit) =
        evaluateJavaScript("cmGetScrollTop()") { value ->
            cb(value.trim().trim('"').toDoubleOrNull()?.toInt() ?: 0)
        }

    // ── Frame events ─────────────────────────────────────────────────────────

    /**
     * The page (and with it the shim) is live: inject the shared bridge shim + `cmInit` as ONE eval,
     * so `window.AndroidEditor` exists before `cmInit` synchronously calls `onReady` — the same
     * single-eval timing guarantee [initScript] documents for desktop.
     */
    private fun onFrameLoad() {
        postEval(
            initScript(
                QUERY_FN,
                planner.initContent(),
                planner.initFilename(),
                planner.lineWrap,
                planner.fontSize,
            ),
        )
    }

    private fun onFrameMessage(ev: Event) {
        val f = frame ?: return
        val data = frameMessageData(ev, f)?.toString() ?: return
        val event = parseBridgeEvent(data)
        if (event == null) {
            logLine("[WebEditorEngine] ignoring unknown bridge payload: ${data.take(200)}")
            return
        }
        applyEvent(event)
    }

    private fun applyEvent(event: BridgeEvent) {
        when (event) {
            // Echo-skip: record the edit as last-known BEFORE the callback round-trips it back
            // through Compose → setDocument, so our own re-push is a no-op.
            is BridgeEvent.Change -> {
                planner.recordEcho(event.content)
                callbacks.onChange(event.content)
            }
            BridgeEvent.Save -> callbacks.onSave()
            BridgeEvent.Ready -> {
                clearReadyTimeout()
                emit(planner.onReady())
                _failed.value = null
                _ready.value = true
                emitDiffRegion()
            }
            is BridgeEvent.FontSize -> callbacks.onFontSize(planner.recordUserFontSize(event.px))
            is BridgeEvent.LspOut -> {
                val parsed = parseLspOut(event.payload)
                if (parsed == null) {
                    logLine("[WebEditorEngine] ignoring malformed lspOut payload (${event.payload.take(200)})")
                } else {
                    callbacks.onLspOut(parsed.first, parsed.second)
                }
            }
            is BridgeEvent.DiffLineClick -> callbacks.onDiffLineClick(event.line)
            is BridgeEvent.DiffExpand -> callbacks.onDiffExpand(event.direction)
            is BridgeEvent.DiffPage -> callbacks.onDiffPage(event.direction)
            is BridgeEvent.CommentSubmit -> callbacks.onCommentSubmit(event.line, event.text)
            is BridgeEvent.ReplySubmit -> callbacks.onReplySubmit(event.threadId, event.text)
            is BridgeEvent.ResolveThread -> callbacks.onResolveThread(event.threadId)
            is BridgeEvent.ComposerState -> callbacks.onComposerState(event.line, event.text)
            is BridgeEvent.EvalResult -> pendingEvaluations.remove(event.id)?.invoke(event.value)
        }
    }

    /**
     * The frame has 8 s to first-paint. The surface has its own identical deadline (it falls back to
     * the native editor), but only the engine can say WHY — a `failed` reason reaches the strip,
     * where a bare timeout would just show an empty fallback. A 404 on `editor/index.html` lands
     * here too: the frame "loads" an error page whose `cmInit` never runs.
     */
    private fun armReadyTimeout() {
        clearReadyTimeout()
        readyTimeout = scheduleTimeout(EDITOR_READY_TIMEOUT_MS.toInt()) {
            readyTimeout = 0
            if (!_ready.value) {
                _failed.value = "the editor did not load (no response from $indexUrl within " +
                    "${EDITOR_READY_TIMEOUT_MS / 1000} s)"
            }
        }
    }

    private fun clearReadyTimeout() {
        if (readyTimeout != 0) cancelTimeout(readyTimeout)
        readyTimeout = 0
    }

    private fun emitDiffRegion() {
        val request = pendingDiffRegion ?: return
        postEval(
            showDiffRegionJs(
                request.path, request.content, request.ranges, request.language,
                request.restoreScrollTop, request.threads, request.composer,
            ),
        )
    }

    /** One internal expression, whose string value comes back through [QUERY_FN] as an EvalResult. */
    private fun evaluateJavaScript(expression: String, cb: (String) -> Unit) {
        if (frame == null) return cb("")
        val id = nextEvaluationId++
        pendingEvaluations[id] = cb
        postEval(evalResultJs(QUERY_FN, id, expression))
    }

    /** Forward planner-emitted JS statements to the page, in order. */
    private fun emit(js: List<String>) {
        for (stmt in js) postEval(stmt)
    }

    /**
     * Desktop's `executeJavaScript`, as a message. Dropped on the floor before [attach] — every
     * caller above either queues in the planner or holds its request in [pendingDiffRegion], so a
     * pre-mount call is never the only chance the page gets to hear it.
     */
    private fun postEval(code: String) {
        val f = frame ?: return
        postEvalToFrame(f, EVAL_PREFIX + code, window.location.origin)
    }

    companion object {
        /** The staged bundle, relative to the app document (`build.gradle.kts`'s `stageForBroker`). */
        const val DEFAULT_INDEX_URL: String = "editor/index.html"

        /** The query function name desktop injects and listens on; the shim defines the same one. */
        const val QUERY_FN: String = "smxEditorQuery"

        /** Marks a host→page message as "run this"; anything else on the channel is ignored. */
        const val EVAL_PREFIX: String = "__smxEval:"
    }
}

private data class DiffRegionRequest(
    val path: String,
    val content: String,
    val ranges: List<DiffRegionRange>,
    val language: String,
    val restoreScrollTop: Int?,
    val threads: List<DiffRegionThread> = emptyList(),
    val composer: DiffRegionComposer? = null,
)

// ── JS helpers ───────────────────────────────────────────────────────────────
// `MessageEvent.source`/`data` are not on kotlinx-browser 0.5.0's event types, `Window.postMessage`
// is not on its `Window`, and `ChildNode.remove` does not exist either — so each of these crosses
// into JS rather than through a typed member that is not there.

/** The message's string payload IFF it came from [f]'s document; null otherwise (and for the
 *  structured-clone traffic of unrelated libraries, which is never a string). */
private fun frameMessageData(ev: Event, f: HTMLIFrameElement): JsString? =
    js("(ev.source === f.contentWindow && typeof ev.data === 'string') ? ev.data : null")

/** Post to the frame's own document. A frame that has not navigated yet has no `contentWindow`. */
private fun postEvalToFrame(f: HTMLIFrameElement, message: String, origin: String): Unit =
    js("{ var w = f.contentWindow; if (w) w.postMessage(message, origin); }")

private fun removeFromParent(f: HTMLIFrameElement): Unit =
    js("{ if (f.parentNode) f.parentNode.removeChild(f); }")

private fun scheduleTimeout(ms: Int, cb: () -> Unit): Int = js("setTimeout(cb, ms)")

private fun cancelTimeout(id: Int): Unit = js("clearTimeout(id)")

private fun logLine(message: String): Unit = js("console.log(message)")
