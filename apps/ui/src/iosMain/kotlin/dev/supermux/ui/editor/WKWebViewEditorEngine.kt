// iOS's [EditorEngine]: one CodeMirror `WKWebView` per editor surface, running the SAME committed
// cm6 bundle Android loads from its assets and desktop loads under JCEF, and speaking the SAME `cm*`
// JS built by the shared [EditorPushPlanner] and the JS builders in `EditorBridge.kt`.
//
// ── Why this is Kotlin/Native (and not, as the terminal once was, Swift) ─────
// The terminal used to be Swift-vended: its emulator had no Kotlin binding and ~470 lines of iOS
// input policy sat on top of it. (Plan 4 removed even that — the terminal is the shared Compose
// renderer on every host now, and this is the only interop surface iOS has left.)
// The editor always had the opposite shape: the bridge is ALREADY pure shared
// Kotlin (`bridgeShimJs`, `initScript`, `parseBridgeEvent`, `EditorPushPlanner` — all unit-tested),
// the bundle's LSP hook is already `window.webkit.messageHandlers.lsp` (it was written FOR WKWebView
// and Android is the one that shims it), and `WKWebView` is a plain ObjC class cinterop covers
// completely. The Swift `EditorHost` only ever implemented 4 of the 12 bridge callbacks; porting it
// would have meant porting that gap too.
//
// ── The bridge ───────────────────────────────────────────────────────────────
// A `WKUserScript` at DOCUMENT START installs the shared [bridgeShimJs], pointed at a global that
// forwards to `webkit.messageHandlers.editor`. So all twelve callbacks — including the seven
// diff/comment hooks the old Swift host never had — arrive as ONE parsed [BridgeEvent] stream, the
// same one desktop's message router feeds. `lsp` is registered natively as well, as a fallback for
// the case where the shim cannot overwrite WebKit's own `messageHandlers.lsp` property.
//
// ── Threading ────────────────────────────────────────────────────────────────
// `WKWebView` is main-thread only, and so is everything here: the engine is created and driven from
// composition, and WebKit delivers script messages and navigation callbacks on the main thread.
package dev.supermux.ui.editor

import dev.supermux.ui.editor.engine.BridgeEvent
import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EditorCallbacks
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorPushPlanner
import dev.supermux.ui.editor.engine.bridgeShimJs
import dev.supermux.ui.editor.engine.jsQuote
import dev.supermux.ui.editor.engine.lspConnectJs
import dev.supermux.ui.editor.engine.lspDisconnectJs
import dev.supermux.ui.editor.engine.lspMessageJs
import dev.supermux.ui.editor.engine.parseBridgeEvent
import dev.supermux.ui.editor.engine.parseLspOut
import dev.supermux.ui.editor.engine.showDiffRegionJs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * What the iOS view host needs beyond [EditorEngine]: the engine's live `WKWebView`, created on the
 * first ask. iOS's engine implements this; nothing in `commonMain` knows it exists.
 */
interface IosWebEditorEngine : EditorEngine {
    /** The engine's web view, created (and its page load started) on the first call. */
    fun obtainWebView(): WKWebView
}

/** The JS global the shim posts through — one name, defined by [POST_SHIM] before the shim runs. */
private const val POST_FN = "__supermuxEditorPost"

/** Message-handler names registered natively on the user-content controller. */
private const val HANDLER_EDITOR = "editor"
private const val HANDLER_LSP = "lsp"

/**
 * Defines [POST_FN] so the SHARED [bridgeShimJs] can be reused verbatim: it marshals every callback
 * through `window.<queryFn>({request})`, which on desktop is CEF's message-router function and here
 * is this three-line forwarder onto a WebKit message handler.
 */
private val POST_SHIM = """
    window.$POST_FN = function (o) {
      try { window.webkit.messageHandlers.$HANDLER_EDITOR.postMessage(String(o && o.request ? o.request : "")); } catch (e) {}
    };
""".trimIndent()

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class WKWebViewEditorEngine(
    lineWrap: Boolean,
    fontSize: Int,
    /** Told when this web view's content process dies, so the factory can log it (Android parity). */
    private val onRendererGone: (String) -> Unit = {},
) : IosWebEditorEngine {

    private val planner = EditorPushPlanner(lineWrap, fontSize)

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _failed = MutableStateFlow<String?>(null)
    override val failed: StateFlow<String?> = _failed.asStateFlow()

    /** Rebound from composition each recomposition; only ever invoked on the main thread. */
    override var callbacks: EditorCallbacks = EditorCallbacks()

    private var webView: WKWebView? = null
    private var pendingDiffRegion: DiffRegionRequest? = null
    private var disposed = false

    /**
     * Held for the life of the engine rather than reached through `webView.configuration`, so
     * [dispose] can unregister the handlers even if the view is gone, and so the retain-cycle
     * ownership is visible in one place.
     */
    private val contentController = WKUserContentController()

    /** The bundled page, resolved once — the reload path below needs the URL, not just the view. */
    private val pageUrl: NSURL? = NSBundle.mainBundle.URLForResource(
        name = "index",
        withExtension = "html",
        subdirectory = "EditorWeb",
    )

    /**
     * Consecutive content-process terminations with no [BridgeEvent.Ready] in between. One is
     * routine (the system reclaimed a backgrounded renderer) and is recovered from silently; a
     * SECOND with no successful load between them means the page itself is killing the renderer,
     * and reloading forever would spin. That case latches [failed] so this one surface drops to the
     * native fallback — the factory's process-wide state still never latches.
     */
    private var consecutiveTerminations = 0

    // ── Kotlin → JS ─────────────────────────────────────────────────────────

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

    // ── JS → Kotlin reads ───────────────────────────────────────────────────
    //
    // WKWebView's evaluateJavaScript HAS a completion handler that carries the value, so — unlike
    // desktop, which has to round-trip an `evalResult` back through the bridge — these read the
    // answer directly. The value arrives as an ObjC object: a JS number bridges to NSNumber, a
    // string to NSString (mapped to kotlin.String).

    override fun readScrollTop(cb: (Int) -> Unit) {
        val view = webView ?: return cb(0)
        view.evaluateJavaScript("cmGetScrollTop()") { value, _ ->
            cb(
                when (value) {
                    is NSNumber -> value.intValue
                    is String -> value.trim().toDoubleOrNull()?.toInt() ?: 0
                    else -> 0
                },
            )
        }
    }

    override fun getContent(cb: (String) -> Unit) {
        val view = webView ?: return cb("")
        // No JSON literal to unescape here (that is `WebView.evaluateJavascript`'s Android quirk):
        // WebKit hands back the JS string itself, so the document arrives with real newlines.
        view.evaluateJavaScript("cmGetContent()") { value, _ -> cb(value as? String ?: "") }
    }

    // ── LSP bridge ──────────────────────────────────────────────────────────

    override fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) =
        eval(lspConnectJs(serverId, rootUri, fileUri, languageId))

    override fun lspMessage(serverId: String, message: String) = eval(lspMessageJs(serverId, message))

    override fun lspDisconnect() = eval(lspDisconnectJs())

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun dispose() {
        if (disposed) return
        disposed = true
        _ready.value = false
        val view = webView ?: return
        // BOTH handlers must come off, or the user-content controller keeps holding this engine
        // (the classic WKScriptMessageHandler retain cycle — the Swift EditorHost.stop() did the
        // same). Then stop loading and drop the delegate so nothing calls back into a dead engine.
        contentController.removeScriptMessageHandlerForName(HANDLER_EDITOR)
        contentController.removeScriptMessageHandlerForName(HANDLER_LSP)
        view.navigationDelegate = null
        view.stopLoading()
        view.removeFromSuperview()
        webView = null
    }

    override fun obtainWebView(): WKWebView = webView ?: createWebView().also { webView = it }

    // ── The web view itself ─────────────────────────────────────────────────

    private fun createWebView(): WKWebView {
        val controller = contentController
        // Document START: the bundle's own script runs after this, so `window.AndroidEditor` and
        // the LSP hook exist before cm6 can touch either. This is also why the shim is NOT part of
        // the didFinish eval the way desktop concatenates it into one — an injected user script
        // cannot be raced by page load order at all.
        controller.addUserScript(
            WKUserScript(
                source = POST_SHIM + "\n" + bridgeShimJs(POST_FN),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        controller.addScriptMessageHandler(messageHandler, HANDLER_EDITOR)
        // The bundle posts LSP to `webkit.messageHandlers.lsp` directly. The shim tries to replace
        // that property with its own forwarder; if WebKit refuses (it is a native object), the
        // ORIGINAL handler must exist or every LSP message would throw into the void. Registering
        // both means exactly one of the two paths delivers, and both end at onLspOut.
        controller.addScriptMessageHandler(messageHandler, HANDLER_LSP)

        val config = WKWebViewConfiguration().apply { userContentController = controller }

        return WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
            navigationDelegate = navDelegate
            // The cm6 bundle sizes itself to the viewport and scrolls inside CodeMirror's own
            // scroller; the enclosing scroll view bouncing would drag the whole page instead.
            scrollView.bounces = false
            // One-Dark's backing, so the first frames are dark rather than the WKWebView's white.
            setOpaque(false)
            // iOS 16.4+; the app's deployment target is 26.0. Web Inspector over Safari is how the
            // bundle is debugged on device — in a debug binary only: a release build must not leave
            // the editor's document tree open to any attached Safari.
            setInspectable(Platform.isDebugBinary)

            loadPage(this)
        }
    }

    /**
     * Load (or RE-load) the bundled page.
     *
     * Never `reload()`: a `file://` load carries a read-access scope that is an argument to
     * `loadFileURL`, not a property of the view, and a reload after a content-process crash comes
     * back without it — cm6.js is a sibling of index.html, so the page would come up blank. The only
     * correct recovery is to re-issue the original call with the directory scope.
     */
    private fun loadPage(view: WKWebView) {
        val url = pageUrl
        if (url == null) {
            _failed.value = "EditorWeb/index.html missing from the app bundle"
            return
        }
        // Read access to the DIRECTORY, not the file: cm6.js is a sibling, and a file URL scoped to
        // index.html alone cannot load it.
        view.loadFileURL(url, allowingReadAccessToURL = url.URLByDeletingLastPathComponent!!)
    }

    /** One object for both handler names; [WKScriptMessage.name] says which arrived. */
    private val messageHandler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            val body = didReceiveScriptMessage.body as? String ?: return
            when (didReceiveScriptMessage.name) {
                // The native `lsp` handler: the body is the raw `{serverId,message}` payload the
                // bundle posts, NOT a wrapped bridge event.
                HANDLER_LSP -> onLspOut(body)
                else -> onBridgeEvent(body)
            }
        }
    }

    private val navDelegate = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            // The shim is already in the page (document-start user script), so this is only cmInit
            // — with whatever document was pushed before the page finished loading.
            eval(
                "cmInit(${jsQuote(planner.initContent())},${jsQuote(planner.initFilename())}," +
                    "${planner.lineWrap},${planner.fontSize})",
            )
        }

        /**
         * PROVISIONAL failure only, and that is not an omission.
         *
         * `didFailNavigation:` and `didFailProvisionalNavigation:` are two ObjC selectors with the
         * SAME Kotlin signature `(WKWebView, WKNavigation?, NSError)` — parameter names do not
         * distinguish an overload — so Kotlin/Native accepts exactly one of them. The provisional
         * one is the right half to keep: it is the phase in which a `file://` load of a bundled
         * page fails (missing resource, no read access to the directory), which is the only load
         * failure this engine can actually have. There is no network to fail after commit.
         */
        override fun webView(
            webView: WKWebView,
            didFailProvisionalNavigation: WKNavigation?,
            withError: NSError,
        ) {
            _failed.value = "load error ${withError.code}: ${withError.localizedDescription}"
        }

        /**
         * The content process died — iOS's `onRenderProcessGone`, and just as routine (the system
         * reclaims a backgrounded renderer). Android's rule applies verbatim: recover rather than
         * latch a failure, and tell the planner so every push queues again until a fresh page fires
         * `onReady`.
         *
         * Recovery re-issues [loadPage], not `reload()` — see that function for why. And it gives
         * up after the SECOND consecutive termination: at that point the page is crashing its own
         * renderer, and this surface latches [failed] instead of looping.
         */
        override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
            consecutiveTerminations++
            val why = "web content process terminated (x$consecutiveTerminations)"
            _ready.value = false
            planner.onRendererLost()
            onRendererGone(why)
            if (consecutiveTerminations >= 2) {
                _failed.value = "the editor's web content process died twice without loading"
            } else {
                loadPage(webView)
            }
        }
    }

    // ── Inbound bridge events (already on the main thread) ───────────────────

    private fun onBridgeEvent(request: String) {
        when (val event = parseBridgeEvent(request)) {
            null -> Unit // malformed / unknown fn: log-and-ignore, exactly as the other two hosts
            is BridgeEvent.Ready -> {
                // A page that reached ready clears the crash streak: the NEXT termination is a
                // first one again, and recoverable.
                consecutiveTerminations = 0
                _failed.value = null
                _ready.value = true
                emit(planner.onReady())
                emitDiffRegion()
            }
            is BridgeEvent.Change -> {
                // Echo-skip: record the edit as last-known BEFORE it round-trips back through
                // Compose state, so the resulting setDocument sees no change and skips the push.
                planner.recordEcho(event.content)
                callbacks.onChange(event.content)
            }
            is BridgeEvent.Save -> callbacks.onSave()
            is BridgeEvent.FontSize -> callbacks.onFontSize(planner.recordUserFontSize(event.px))
            is BridgeEvent.LspOut -> onLspOut(event.payload)
            is BridgeEvent.DiffLineClick -> callbacks.onDiffLineClick(event.line)
            is BridgeEvent.DiffExpand -> callbacks.onDiffExpand(event.direction)
            is BridgeEvent.DiffPage -> callbacks.onDiffPage(event.direction)
            is BridgeEvent.CommentSubmit -> callbacks.onCommentSubmit(event.line, event.text)
            is BridgeEvent.ReplySubmit -> callbacks.onReplySubmit(event.threadId, event.text)
            is BridgeEvent.ResolveThread -> callbacks.onResolveThread(event.threadId)
            is BridgeEvent.ComposerState -> callbacks.onComposerState(event.line, event.text)
            // Desktop's async-read round trip; WKWebView reads through its completion handler and
            // never emits one.
            is BridgeEvent.EvalResult -> Unit
        }
    }

    private fun onLspOut(payload: String) {
        val parsed = parseLspOut(payload) ?: return
        callbacks.onLspOut(parsed.first, parsed.second)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun emitDiffRegion() {
        val r = pendingDiffRegion ?: return
        eval(showDiffRegionJs(r.path, r.content, r.ranges, r.language, r.restoreScrollTop, r.threads, r.composer))
    }

    /** Forward planner-emitted JS statements to the page, in order. */
    private fun emit(js: List<String>) {
        for (stmt in js) eval(stmt)
    }

    private fun eval(js: String) {
        webView?.evaluateJavaScript(js, null)
    }
}

private data class DiffRegionRequest(
    val path: String,
    val content: String,
    val ranges: List<DiffRegionRange>,
    val language: String,
    val restoreScrollTop: Int?,
    val threads: List<DiffRegionThread>,
    val composer: DiffRegionComposer?,
)
