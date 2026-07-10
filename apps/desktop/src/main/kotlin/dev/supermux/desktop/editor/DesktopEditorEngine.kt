// M3 editor — the KCEF↔CodeMirror bridge. One [DesktopEditorEngine] drives ONE embedded-Chromium
// browser hosting the SAME committed cm6 bundle iOS/Android ship. It mirrors the Android
// EditorEngine surface (setDocument / revealLine / setFontSize / setLineWrap / get|setScrollTop /
// getContent / ready + onChange/onSave/onReady/onFontSize) so the pane logic ports 1:1 in later
// tasks.
//
// ── Architecture: CLIENT-PER-ENGINE ─────────────────────────────────────────
// Each engine owns its own [KCEFClient] (from [KcefRuntime.newClient]) + [KCEFBrowser]. KCEFClient
// exposes only a SINGLE load handler and a SINGLE display handler (addLoadHandler REPLACES, it does
// not append), so a shared client couldn't host two editors without their handlers stomping each
// other. One client per browser also gives a clean teardown: dispose the client and everything it
// owns dies with it. The CefApp underneath is still the ONE process-global singleton (KcefRuntime);
// only the lightweight client/browser are per-engine.
//
// ── JS→Kotlin: renamed message-router query function ─────────────────────────
// KCEF's own `evaluateJavaScript` reads (getContent/getScrollTop below) ride CEF's DEFAULT
// message-router query function, `cefQuery`. To avoid colliding with that, this engine registers its
// OWN CefMessageRouter under a DISTINCT name, [QUERY_FN] = "smxEditorQuery". The injected shim
// (EditorBridgeShims) routes the bundle's window.AndroidEditor.* / window.webkit…lsp.postMessage
// calls through window.smxEditorQuery, and [onQuery] parses `{fn,arg}` → [BridgeEvent] → engine
// callback. Reads and events therefore never contend for the same query channel.
//
// ── THREADING (per the M2 MuxTtyConnector/PredictionPipeline convention) ──────
// ⚠️ CEF callbacks — onLoadEnd, onQuery (the bridge), the evaluateJavaScript result callback, and
// onConsoleMessage — all arrive on INTERNAL CEF threads, NOT the EDT. This engine's mutable state
// (the [planner], `_ready`) and the user callbacks touch Compose/Swing state, so EVERY CEF callback
// marshals to the EDT via [onEdt] (SwingUtilities.invokeLater — the AWT event-dispatch thread, the
// same thread Compose-for-Desktop renders on and that Dispatchers.Swing dispatches to) before
// touching engine state. onQuery itself returns synchronously (success + true) and does the state
// work on the marshalled continuation. The public Kotlin→JS methods are expected to be called FROM
// the EDT (Compose) and forward JS to the browser (executeJavaScript is thread-safe — it posts to
// the renderer).
package dev.supermux.desktop.editor

import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import dev.datlag.kcef.KCEFFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * Drives one CodeMirror browser. Construct, set the callbacks, then [load] once KCEF is
 * [KcefState.Ready]; embed [uiComponent] in a SwingPanel. Push docs with [setDocument] (queued until
 * cm6 first-paints), and [dispose] on teardown.
 *
 * @param indexUrl `file://<dir>/index.html` — the extracted bundle (see [EditorWebAssets]).
 * @param lineWrap initial soft-wrap state (cmInit arg; live-changeable via [setLineWrap]).
 * @param fontSize initial font px (cmInit arg; live-changeable via [setFontSize]).
 */
class DesktopEditorEngine(
    private val indexUrl: String,
    lineWrap: Boolean,
    fontSize: Int,
) {
    /** cm6 first-paint gate. Flips true once onReady arrives; drives the pane's white-flash cover. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    // Callbacks — settable (Compose updates them per recomposition, like Android's updateCallbacks).
    // EDT-CONFINED BY CONVENTION: written from composition (the EDT) and invoked only on the EDT
    // (every CEF callback marshals via [onEdt] first) — no @Volatile/synchronization needed.
    var onChange: (String) -> Unit = {}
    var onSave: () -> Unit = {}
    var onReady: () -> Unit = {}
    var onFontSize: (Int) -> Unit = {}
    /** Outbound LSP JSON-RPC from cm6's `LSPClient`, parsed to (serverId, message). */
    var onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> }

    private val planner = EditorPushPlanner(lineWrap, fontSize)

    private var client: KCEFClient? = null
    private var router: CefMessageRouter? = null
    private var browser: KCEFBrowser? = null

    /** The AWT child to embed in a SwingPanel; null until [load]. */
    fun uiComponent(): Component? = browser?.uiComponent

    /**
     * Create the client + router + browser and start loading the bundle. No-op if already loaded or
     * if KCEF isn't [KcefState.Ready] (gated in [KcefRuntime.newClient] — callers should observe the
     * state and only call load when ready). The router + load/display handlers are attached to the
     * client BEFORE createBrowser so the query function exists and onLoadEnd fires for this page.
     */
    fun load() {
        if (browser != null) return
        val c = KcefRuntime.newClient() ?: run {
            println("[DesktopEditorEngine] load() skipped — KCEF not ready")
            return
        }
        val r = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig(QUERY_FN, QUERY_CANCEL_FN))
        r.addHandler(bridgeHandler, /* first = */ true)
        c.addMessageRouter(r)
        c.addLoadHandler(loadHandler)
        c.addDisplayHandler(consoleHandler)
        c.addRequestHandler(crashHandler)
        client = c
        router = r
        browser = c.createBrowser(indexUrl)
    }

    // ── Kotlin → JS (called from the EDT/Compose) ────────────────────────────

    /** Push a document. [path] drives the language mode; content-only echoes skip via the planner. */
    fun setDocument(path: String, content: String, scrollTop: Int = 0) =
        emit(planner.setDocument(content, path, scrollTop))

    /** Reveal a 1-indexed [line] (optional [endLine]); queued until [ready] if cm6 hasn't painted. */
    fun revealLine(line: Int, endLine: Int? = null) = emit(planner.revealLine(line, endLine))

    fun setFontSize(px: Int) = emit(planner.setFontSize(px))
    fun setLineWrap(on: Boolean) = emit(planner.setLineWrap(on))
    fun setScrollTop(px: Int) = emit(planner.setScrollTop(px))

    // ── JS → Kotlin reads (async; result marshalled to the EDT) ──────────────

    /**
     * Read the live document. cmGetContent() returns a JS string; KCEF's evaluateJavaScript delivers
     * it to the callback on a CEF thread → marshalled to the EDT here. Fires with "" if not loaded.
     */
    fun getContent(cb: (String) -> Unit) {
        val b = browser ?: return cb("")
        b.evaluateJavaScript(
            "cmGetContent()",
            object : KCEFFrame.EvaluateJavascriptCallback {
                override fun invoke(value: String?) = onEdt { cb(value ?: "") }
            },
        )
    }

    /** Read cm6's scroll offset (px). Same async shape as [getContent]. Fires with 0 if not loaded. */
    fun getScrollTop(cb: (Int) -> Unit) {
        val b = browser ?: return cb(0)
        b.evaluateJavaScript(
            "cmGetScrollTop()",
            object : KCEFFrame.EvaluateJavascriptCallback {
                override fun invoke(value: String?) {
                    // Defensive trim('"'): a number normally arrives bare, but Android saw the JS
                    // eval result arrive quoted (EditorEngine.kt:96) — strip either shape.
                    val n = value?.trim()?.trim('"')?.toDoubleOrNull()?.toInt() ?: 0
                    onEdt { cb(n) }
                }
            },
        )
    }

    // ── LSP bridge (mirrors setDocument/revealLine — drives the cm6 LSP client over the shim) ────

    /** Connect the cm6 LSP client for the active file (port of Android EditorEngine.kt:247-252).
     *  A no-op before the browser exists; cm6's own `window.cmLspConnect` guards against running
     *  before its init (cm6-entry.mjs), so it's safe to call even before [ready]. */
    fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) {
        val b = browser ?: return
        b.executeJavaScript(lspConnectJs(serverId, rootUri, fileUri, languageId), b.url ?: "", 0)
    }

    /** Deliver an inbound JSON-RPC message string to the cm6 LSP client for [serverId]. */
    fun lspMessage(serverId: String, message: String) {
        val b = browser ?: return
        b.executeJavaScript(lspMessageJs(serverId, message), b.url ?: "", 0)
    }

    /** Tear down all cm6 LSP connections and revert to a plain editor. */
    fun lspDisconnect() {
        val b = browser ?: return
        b.executeJavaScript(lspDisconnectJs(), b.url ?: "", 0)
    }

    /**
     * Dispose the browser and the whole client. Teardown order: browser FIRST (KCEFBrowser.dispose
     * force-closes it, stopping the renderer — so no more page JS can post queries into a router
     * we're about to tear down), THEN detach + dispose the router, THEN dispose the client that
     * owned both. Idempotent — every field is nulled so a second call is a no-op.
     */
    fun dispose() {
        _ready.value = false
        browser?.dispose()
        router?.let { r -> client?.removeMessageRouter(r); r.dispose() }
        client?.dispose()
        browser = null
        router = null
        client = null
    }

    // ── CEF handlers (all callbacks arrive OFF the EDT → marshal) ─────────────

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return // ignore subframe loads
            // Marshal to the EDT BEFORE reading planner state (the every-callback-marshals
            // invariant — the planner is EDT-confined). executeJavaScript is safe to call from the
            // EDT: CEF posts it internally to the renderer, from any thread. The hop can't reorder
            // against load timing either — nothing else executes page JS until this init script has
            // run and cmInit fires onReady (all pushes queue in the planner until then).
            onEdt {
                // Inject the bridge shim + cmInit in ONE eval so the shim's window.AndroidEditor
                // exists before cmInit synchronously calls onReady (see initScript's timing note).
                val script = initScript(
                    QUERY_FN,
                    planner.initContent(),
                    planner.initFilename(),
                    planner.lineWrap,
                    planner.fontSize,
                )
                browser?.executeJavaScript(script, browser.url ?: "", 0)
            }
        }

        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (frame?.isMain != true) return
            // ERR_ABORTED fires for superseded navigations and dispose-time force-close — not a
            // renderer loss (classic CEF false positive; load-bearing once a reload/retry exists).
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            rendererLost("onLoadError $errorCode $errorText @$failedUrl")
        }
    }

    /**
     * Renderer-crash detection (Android parity: EditorEngine.kt:222-227 onRenderProcessGone). CEF's
     * hook is CefRequestHandler.onRenderProcessTerminated. Recovery is NOT automatic: [_ready] and
     * the planner flip back to not-ready so the pane's ready-gate stops lying over a dead renderer
     * (pushes queue again, the pane can show its fallback). A future retry affordance would need a
     * fresh [load]/reload — see [EditorPushPlanner.onRendererLost] for the scroll/reveal caveats.
     */
    private val crashHandler = object : CefRequestHandlerAdapter() {
        override fun onRenderProcessTerminated(
            browser: CefBrowser?,
            status: CefRequestHandler.TerminationStatus?,
        ) {
            rendererLost("onRenderProcessTerminated $status")
        }
    }

    /** Mark the renderer dead (on the EDT): un-ready the flow + planner, log — do NOT auto-reload. */
    private fun rendererLost(why: String) {
        println("[DesktopEditorEngine] renderer lost: $why")
        onEdt {
            _ready.value = false
            planner.onRendererLost()
        }
    }

    private val bridgeHandler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser?,
            frame: CefFrame?,
            queryId: Long,
            request: String?,
            persistent: Boolean,
            callback: CefQueryCallback?,
        ): Boolean {
            val event = request?.let { parseBridgeEvent(it) }
            if (event == null) {
                println("[DesktopEditorEngine] ignoring unknown bridge payload: ${request?.take(200)}")
            } else {
                onEdt { applyEvent(event) }
            }
            callback?.success("") // ack synchronously; the state work happens on the EDT continuation
            return true
        }
    }

    private val consoleHandler = object : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser?,
            level: org.cef.CefSettings.LogSeverity?,
            message: String?,
            source: String?,
            line: Int,
        ): Boolean {
            println("[DesktopEditorEngine] JS[$level] $message @${source?.substringAfterLast('/')}:$line")
            return false // let CEF keep its default console handling too
        }
    }

    /** Dispatch a parsed bridge event on the EDT. */
    private fun applyEvent(event: BridgeEvent) {
        when (event) {
            // Echo-skip: record the edit as last-known BEFORE the callback round-trips it back
            // through Compose → setDocument, so our own text push is a no-op (planner.recordEcho).
            is BridgeEvent.Change -> {
                planner.recordEcho(event.content)
                onChange(event.content)
            }
            BridgeEvent.Save -> onSave()
            BridgeEvent.Ready -> {
                emit(planner.onReady()) // flush the queued document + pending reveal
                _ready.value = true
                onReady()
            }
            // The user zoom already applied in-page; keep our copy in sync + persist (no loop-back).
            is BridgeEvent.FontSize -> onFontSize(planner.recordUserFontSize(event.px))
            // Outbound LSP JSON-RPC — parse {serverId,message} and forward to the bridge (M4g-3).
            is BridgeEvent.LspOut -> {
                val parsed = parseLspOut(event.payload)
                if (parsed == null) {
                    println("[DesktopEditorEngine] ignoring malformed lspOut payload (${event.payload.take(200)})")
                } else {
                    onLspOut(parsed.first, parsed.second)
                }
            }
        }
    }

    /** Forward planner-emitted JS statements to the browser, in order. */
    private fun emit(js: List<String>) {
        val b = browser ?: return
        val url = b.url ?: ""
        for (stmt in js) b.executeJavaScript(stmt, url, 0)
    }

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }

    companion object {
        /**
         * The message-router query function this engine injects + listens on. DISTINCT from CEF's
         * default `cefQuery` (which KCEF's evaluateJavaScript reads use) so the two never collide.
         */
        const val QUERY_FN: String = "smxEditorQuery"
        const val QUERY_CANCEL_FN: String = "smxEditorQueryCancel"
    }
}
