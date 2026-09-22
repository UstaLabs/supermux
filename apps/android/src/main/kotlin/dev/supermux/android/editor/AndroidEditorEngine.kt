// Android's [EditorEngine]: one CodeMirror WebView per editor surface, driving the SAME committed
// cm6 bundle desktop runs under JCEF and speaking the SAME `cm*` JS, built by the shared
// [EditorPushPlanner] and the JS builders in `:ui`'s EditorBridge.kt. Nothing about ordering,
// echo-skip or the queue-until-first-paint lives here any more — this file is the WebView adapter
// and nothing else.
//
// ── The bridge ───────────────────────────────────────────────────────────────
// The bundle calls `window.AndroidEditor.{onChange,onSave,onReady,onFontSize,onDiff*,onComment*,…}`
// and posts LSP through the (iOS-shaped) `window.webkit.messageHandlers.lsp`. Android answers the
// first with a real `@JavascriptInterface` object — no JSON hop, unlike desktop, which has to shim
// those globals into its message router — and the second with [LSP_BRIDGE_SHIM], three lines that
// point WebKit's handler at the same object. [AndroidEditorBridge] must therefore declare EVERY
// callback name the shared `bridgeShimJs` declares; `AndroidEditorBridgeTest` pins that.
//
// ── Threading ────────────────────────────────────────────────────────────────
// @JavascriptInterface methods arrive on the WebView's JS thread. Every one hops to the main thread
// before touching the planner, the flows, or the callbacks.
package dev.supermux.android.editor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dev.supermux.ui.editor.WebViewEditorEngine
import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EditorCallbacks
import dev.supermux.ui.editor.engine.EditorPushPlanner
import dev.supermux.ui.editor.engine.jsQuote
import dev.supermux.ui.editor.engine.lspConnectJs
import dev.supermux.ui.editor.engine.lspDisconnectJs
import dev.supermux.ui.editor.engine.lspMessageJs
import dev.supermux.ui.editor.engine.parseLspOut
import dev.supermux.ui.editor.engine.showDiffRegionJs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * @param context the WINDOW context, not applicationContext: a WebView derives its CSS-px scale
 *   from the density of the display its construction Context lives on. The application context
 *   always carries the DEFAULT (phone) display's density, so on Samsung DeX / external displays
 *   (whose density differs) the editor's web content rendered 2-3x larger than the surrounding
 *   native UI. The engine never outlives its surface (which disposes it), so holding the activity
 *   context is leak-safe, and a DeX attach/detach recreates the activity → a fresh engine.
 * @param onRendererGone told when this WebView's renderer dies, so the platform's factory can stop
 *   handing out engines that will die the same way.
 */
class AndroidEditorEngine(
    context: Context,
    lineWrap: Boolean,
    fontSize: Int,
    private val onRendererGone: (String) -> Unit = {},
) : WebViewEditorEngine {

    private val viewContext = context
    private val main = Handler(Looper.getMainLooper())
    private val planner = EditorPushPlanner(lineWrap, fontSize)

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _failed = MutableStateFlow<String?>(null)
    override val failed: StateFlow<String?> = _failed.asStateFlow()

    /** Rebound from composition each recomposition; only ever invoked on the main thread. */
    override var callbacks: EditorCallbacks = EditorCallbacks()

    private var webView: WebView? = null
    private var pendingDiffRegion: DiffRegionRequest? = null

    // ── Kotlin → JS ─────────────────────────────────────────────────────────

    override fun setDocument(path: String, content: String, scrollTop: Int) =
        emit(planner.setDocument(content, path, scrollTop))

    override fun revealLine(line: Int, endLine: Int?) = emit(planner.revealLine(line, endLine))

    override fun setFontSize(px: Int) = emit(planner.setFontSize(px))
    override fun setLineWrap(on: Boolean) = emit(planner.setLineWrap(on))
    override fun setScrollTop(px: Int) = emit(planner.setScrollTop(px))

    /** Switch CodeMirror into the read-only walkthrough renderer. Calls before ready are queued. */
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

    /** Refreshed threads / composer into the SAME region — no scroll restore, so the bundle's own
     *  scroll preservation wins. A no-op before [showDiffRegion] established a region. */
    override fun updateDiffThreads(threads: List<DiffRegionThread>, composer: DiffRegionComposer?) {
        val current = pendingDiffRegion ?: return
        pendingDiffRegion = current.copy(restoreScrollTop = null, threads = threads, composer = composer)
        if (_ready.value) emitDiffRegion()
    }

    // ── JS → Kotlin reads ───────────────────────────────────────────────────

    override fun readScrollTop(cb: (Int) -> Unit) {
        val view = webView ?: return cb(0)
        view.evaluateJavascript("cmGetScrollTop()") { raw ->
            cb(raw?.trim()?.removeSurrounding("\"")?.toDoubleOrNull()?.toInt() ?: 0)
        }
    }

    override fun getContent(cb: (String) -> Unit) {
        val view = webView ?: return cb("")
        // evaluateJavascript hands back a JSON *literal*, so the document arrives escaped — decode
        // it rather than stripping quotes, or every newline in the file comes back as a literal \n.
        view.evaluateJavascript("cmGetContent()") { raw -> cb(decodeJsString(raw)) }
    }

    // ── LSP bridge ──────────────────────────────────────────────────────────

    override fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) {
        webView?.evaluateJavascript(lspConnectJs(serverId, rootUri, fileUri, languageId), null)
    }

    override fun lspMessage(serverId: String, message: String) {
        webView?.evaluateJavascript(lspMessageJs(serverId, message), null)
    }

    override fun lspDisconnect() {
        webView?.evaluateJavascript(lspDisconnectJs(), null)
    }

    override fun dispose() {
        _ready.value = false
        webView?.destroy()
        webView = null
    }

    /** The engine's WebView, created (and its load started) on the first call. */
    override fun obtainWebView(): WebView = webView ?: createWebView().also { webView = it }

    // ── The WebView itself ──────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        Log.d(TAG, "create WebView")
        return WebView(viewContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Pin text to 100%: the editor's own `fontSize` setting is the size control. Without
            // this, WebView multiplies CSS px by the system font scale, so on Samsung DeX / large
            // external displays (which bump the scale) the code renders far too big.
            settings.textZoom = 100
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            setBackgroundColor(0xFF282C34.toInt())
            WebView.setWebContentsDebuggingEnabled(true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "JS[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
            addJavascriptInterface(AndroidEditorBridge(this@AndroidEditorEngine), "AndroidEditor")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "onPageFinished $url")
                    // CRITICAL: install the WebKit-shaped LSP bridge BEFORE cmInit runs, so the
                    // bundle's `window.webkit.messageHandlers.lsp` gate resolves on Android and
                    // cmLspConnect does not early-return.
                    view?.evaluateJavascript(LSP_BRIDGE_SHIM, null)
                    view?.evaluateJavascript(
                        "cmInit(${jsQuote(planner.initContent())},${jsQuote(planner.initFilename())}," +
                            "${planner.lineWrap},${planner.fontSize})",
                    ) { r -> Log.d(TAG, "cmInit returned: $r") }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    Log.e(TAG, "onReceivedError ${request?.url} : ${error?.errorCode} ${error?.description}")
                    if (request?.isForMainFrame == true) {
                        _failed.value = "load error ${error?.errorCode} ${error?.description}"
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    val why = "renderer gone (didCrash=${detail?.didCrash()})"
                    Log.e(TAG, why)
                    _failed.value = why
                    _ready.value = false
                    planner.onRendererLost()
                    onRendererGone(why)
                    return true
                }
            }
            loadUrl("file:///android_asset/editor/index.html")
        }
    }

    // ── Inbound bridge events (already on the main thread) ───────────────────

    internal fun onBridgeReady() {
        _failed.value = null
        _ready.value = true
        emit(planner.onReady())
        emitDiffRegion()
    }

    internal fun onBridgeChange(content: String) {
        // Echo-skip: record the edit as last-known BEFORE it round-trips back through Compose state,
        // so the resulting setDocument sees `content == lastContent` and skips the re-push. Without
        // this, fast typing can shove a stale snapshot back and drop characters / jump the caret.
        planner.recordEcho(content)
        callbacks.onChange(content)
    }

    internal fun onBridgeFontSize(px: Int) {
        // Already applied in-page by the pinch/keyboard zoom: keep our copy in sync and persist it.
        callbacks.onFontSize(planner.recordUserFontSize(px))
    }

    internal fun onBridgeLspOut(payload: String) {
        val parsed = parseLspOut(payload)
        if (parsed == null) Log.w(TAG, "ignoring malformed lspOut payload") else
            callbacks.onLspOut(parsed.first, parsed.second)
    }

    internal fun post(block: () -> Unit) { main.post(block) }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun emitDiffRegion() {
        val r = pendingDiffRegion ?: return
        webView?.evaluateJavascript(
            showDiffRegionJs(r.path, r.content, r.ranges, r.language, r.restoreScrollTop, r.threads, r.composer),
            null,
        )
    }

    /** Forward planner-emitted JS statements to the WebView, in order. */
    private fun emit(js: List<String>) {
        val view = webView ?: return
        for (stmt in js) view.evaluateJavascript(stmt, null)
    }

    private fun decodeJsString(raw: String?): String {
        val value = raw ?: return ""
        return runCatching { Json.parseToJsonElement(value).jsonPrimitive.content }
            .getOrElse { value.trim().removeSurrounding("\"") }
    }

    private companion object {
        const val TAG = "AndroidEditorEngine"

        /** Maps WebKit's iOS-only `window.webkit.messageHandlers.lsp.postMessage` onto the
         *  `AndroidEditor` @JavascriptInterface so the shared cm6 bundle's LSP gate resolves. The
         *  desktop shim does the same thing through its message router (see `bridgeShimJs`). */
        const val LSP_BRIDGE_SHIM = """
            window.webkit = window.webkit || {};
            window.webkit.messageHandlers = window.webkit.messageHandlers || {};
            window.webkit.messageHandlers.lsp = { postMessage: function (s) { window.AndroidEditor.lspOut(s); } };
        """
    }
}

/**
 * The `window.AndroidEditor` object the cm6 bundle calls.
 *
 * Its method set is a CONTRACT: it must cover every callback the shared `bridgeShimJs` declares (the
 * desktop half of the same bridge), because the bundle is one file shipped to both. A method missing
 * here is not a compile error — it is a silently dead in-editor affordance on phones only, which is
 * exactly how Android went years without in-editor comments. `AndroidEditorBridgeTest` fails if the
 * two ever drift.
 *
 * Every method hops to the main thread: these arrive on the WebView's JS thread.
 */
internal class AndroidEditorBridge(private val engine: AndroidEditorEngine) {
    @JavascriptInterface fun onChange(s: String) = engine.post { engine.onBridgeChange(s) }
    @JavascriptInterface fun onSave() = engine.post { engine.callbacks.onSave() }
    @JavascriptInterface fun onReady() = engine.post { engine.onBridgeReady() }
    @JavascriptInterface fun onFontSize(px: Int) = engine.post { engine.onBridgeFontSize(px) }
    @JavascriptInterface fun onDiffLineClick(line: Int) =
        engine.post { if (line > 0) engine.callbacks.onDiffLineClick(line) }
    @JavascriptInterface fun onDiffExpand(direction: String) =
        engine.post { if (direction == "up" || direction == "down") engine.callbacks.onDiffExpand(direction) }
    @JavascriptInterface fun onDiffPage(direction: String) =
        engine.post {
            if (direction == "previous" || direction == "next") engine.callbacks.onDiffPage(direction)
        }
    @JavascriptInterface fun onCommentSubmit(line: Int, text: String) =
        engine.post { if (line > 0 && text.isNotBlank()) engine.callbacks.onCommentSubmit(line, text) }
    @JavascriptInterface fun onReplySubmit(threadId: String, text: String) =
        engine.post {
            if (threadId.isNotEmpty() && text.isNotBlank()) engine.callbacks.onReplySubmit(threadId, text)
        }
    @JavascriptInterface fun onResolveThread(threadId: String) =
        engine.post { if (threadId.isNotBlank()) engine.callbacks.onResolveThread(threadId) }
    @JavascriptInterface fun onComposerState(line: Int, text: String) =
        engine.post { if (line >= 0) engine.callbacks.onComposerState(line, text) }
    /** cm6's LSPClient output, routed here by [AndroidEditorEngine.LSP_BRIDGE_SHIM]. */
    @JavascriptInterface fun lspOut(payload: String) = engine.post { engine.onBridgeLspOut(payload) }
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
