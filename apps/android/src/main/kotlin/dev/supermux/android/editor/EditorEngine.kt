package dev.supermux.android.editor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * One CodeMirror WebView per editor panel. Loads [cm6.js] once on mount and keeps the
 * renderer alive for instant tab switches via `cmSetContent` / `cmSetLanguage`.
 */
class EditorEngine(
    context: Context,
    private val lineWrap: Boolean,
    fontSize: Int,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onLspOut: (String) -> Unit = {},
    onFontSize: (Int) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val onChangeS = mutableStateOf(onChange)
    private val onSaveS = mutableStateOf(onSave)
    /** Outbound LSP JSON-RPC string `{serverId,message}` posted by cm6's LSPClient. */
    private val onLspOutS = mutableStateOf(onLspOut)
    /** Persist callback for a user zoom (pinch / keyboard) reported by the WebView. */
    private val onFontSizeS = mutableStateOf(onFontSize)
    /** Latest editor font size (px). Mutable so a zoom updates it in place (no WebView
     *  rebuild); used by cmInit and every document push so a re-push keeps the zoom. */
    private var currentFontSize: Int = fontSize

    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        internal set

    private var webView: WebView? = null
    private var lastContent = ""
    private var lastFilename = ""

    fun updateCallbacks(
        onChange: (String) -> Unit,
        onSave: () -> Unit,
        onLspOut: (String) -> Unit = onLspOutS.value,
        onFontSize: (Int) -> Unit = onFontSizeS.value,
    ) {
        onChangeS.value = onChange
        onSaveS.value = onSave
        onLspOutS.value = onLspOut
        onFontSizeS.value = onFontSize
    }

    /** Push a new font size to the live editor WITHOUT rebuilding the WebView. Called
     *  when the settings font size changes; a pinch/keyboard zoom applies itself. */
    fun setFontSize(px: Int) {
        currentFontSize = px.coerceIn(10, 24)
        if (ready) webView?.evaluateJavascript("cmSetFontSize($currentFontSize)", null)
    }

    fun obtainWebView(): WebView {
        webView?.let { return it }
        return createWebView().also { webView = it }
    }

    private var lastScrollTop = 0
    private var pendingReveal: Pair<Int, Int?>? = null

    fun readScrollTop(callback: (Int) -> Unit) {
        val view = webView ?: return callback(lastScrollTop)
        view.evaluateJavascript("cmGetScrollTop()") { raw ->
            val n = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.toDoubleOrNull()?.toInt()
            callback(n ?: lastScrollTop)
        }
    }

    fun setDocument(content: String, filename: String, scrollTop: Int = 0) {
        val pathChanged = filename != lastFilename
        lastFilename = filename
        if (pathChanged) {
            // A different tab/file: push the whole document + language + wrap + font + scroll
            // (parity EditorWebView.swift:72-84). cmSetContent is a JS no-op if unchanged.
            lastContent = content
            lastScrollTop = scrollTop
            if (ready) pushToView(content, filename, scrollTop)
        } else if (content != lastContent) {
            // Same file, content changed out-of-band (disk reload) — or our own echo of a user
            // edit. Push ONLY the text: re-pushing scrollTop/language/font on every keystroke
            // yanks the caret + scroll back (cmSetScrollTop is NOT a no-op). Parity
            // EditorWebView.swift:86-89.
            lastContent = content
            if (ready) webView?.evaluateJavascript("cmSetContent(${q(content)})", null)
        }
    }

    /** Scroll to a 1-indexed line (optional end). Deferred until [ready], like scrollTop. */
    fun revealLine(line: Int, endLine: Int?) {
        pendingReveal = line to endLine
        if (ready) flushReveal()
    }

    private fun flushReveal() {
        val r = pendingReveal ?: return
        pendingReveal = null
        webView?.evaluateJavascript("cmRevealLine(${r.first}, ${r.second ?: -1})", null)
    }

    fun destroy() {
        ready = false
        webView?.destroy()
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        Log.d("EditorEngine", "create WebView")
        return WebView(appContext).apply {
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
                    Log.d(
                        "EditorEngine",
                        "JS[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}",
                    )
                    return true
                }
            }
            addJavascriptInterface(object {
                // Record the edit as the last-known document BEFORE it round-trips back through
                // Compose state, so setDocument's `content != lastContent` guard skips re-pushing
                // the user's own keystroke (parity EditorWebView.swift:203). Without this, fast
                // typing can shove a stale snapshot back and drop characters / jump the caret.
                @JavascriptInterface fun onChange(s: String) { main.post { lastContent = s; onChangeS.value.invoke(s) } }
                @JavascriptInterface fun onSave() { main.post { onSaveS.value.invoke() } }
                // A user zoom (pinch/keyboard) in the WebView: the editor already applied
                // it live; keep our copy in sync and persist it (no rebuild).
                @JavascriptInterface fun onFontSize(px: Int) {
                    main.post {
                        currentFontSize = px.coerceIn(10, 24)
                        onFontSizeS.value.invoke(currentFontSize)
                    }
                }
                @JavascriptInterface fun onReady() {
                    main.post {
                        ready = true
                        pushToView(lastContent, lastFilename, lastScrollTop)
                    }
                }
            }, "AndroidEditor")
            // LSP out channel. cm6's LSPClient posts `{serverId,message}` via the (iOS-only)
            // `window.webkit.messageHandlers.lsp.postMessage`; the document-start shim below
            // routes that to this @JavascriptInterface. Callbacks arrive on the WebView JS
            // thread → hop to main (same pattern as AndroidEditor above).
            addJavascriptInterface(object {
                @JavascriptInterface fun lspOut(payload: String) { main.post { onLspOutS.value.invoke(payload) } }
            }, "AndroidLsp")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d("EditorEngine", "onPageFinished $url")
                    // CRITICAL: install the WebKit-shaped LSP bridge BEFORE cmInit runs, so the
                    // bundle's `window.webkit.messageHandlers.lsp` gate (cm6-entry.mjs:214)
                    // resolves on Android and cmLspConnect does not early-return. The payload `s`
                    // is already JSON.stringify({serverId,message}); forward it verbatim.
                    view?.evaluateJavascript(LSP_BRIDGE_SHIM, null)
                    view?.evaluateJavascript(
                        "cmInit(${q(lastContent)}, ${q(lastFilename)}, $lineWrap, $currentFontSize)",
                    ) { r -> Log.d("EditorEngine", "cmInit returned: $r") }
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    Log.e(
                        "EditorEngine",
                        "onReceivedError ${request?.url} : ${error?.errorCode} ${error?.description}",
                    )
                    if (request?.isForMainFrame == true) failed = true
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    Log.e("EditorEngine", "onRenderProcessGone didCrash=${detail?.didCrash()}")
                    failed = true
                    ready = false
                    return true
                }
            }
            loadUrl("file:///android_asset/editor/index.html")
        }
    }

    private fun pushToView(content: String, filename: String, scrollTop: Int) {
        val view = webView ?: return
        view.evaluateJavascript("cmSetContent(${q(content)})", null)
        view.evaluateJavascript("cmSetLanguage(${q(filename)})", null)
        view.evaluateJavascript("cmSetLineWrap($lineWrap)", null)
        view.evaluateJavascript("cmSetFontSize($currentFontSize)", null)
        view.evaluateJavascript("cmSetScrollTop($scrollTop)", null)
        flushReveal()
    }

    // ── LSP bridge (mirrors cmSet* — drives the cm6 LSPClient over the shim) ────

    /** Connect the cm6 LSP client for the active file. No-op until [ready] (the WebView's
     *  cmLspConnect needs the editor view + the shim, both present after onPageFinished). */
    fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) {
        webView?.evaluateJavascript(
            "window.cmLspConnect(${q(serverId)},${q(rootUri)},${q(fileUri)},${q(languageId)})",
            null,
        )
    }

    /** Deliver an inbound JSON-RPC message string to the cm6 LSP client for [serverId]. */
    fun lspMessage(serverId: String, message: String) {
        webView?.evaluateJavascript("window.cmLspMessage(${q(serverId)},${q(message)})", null)
    }

    /** Tear down all cm6 LSP connections and revert to a plain editor. */
    fun lspDisconnect() {
        webView?.evaluateJavascript("window.cmLspDisconnect && window.cmLspDisconnect()", null)
    }

    private fun q(s: String): String = JSONObject.quote(s)

    companion object {
        /** Maps WebKit's iOS-only `window.webkit.messageHandlers.lsp.postMessage` onto the
         *  Android `AndroidLsp` @JavascriptInterface so the shared cm6 bundle's LSP gate
         *  (cm6-entry.mjs:214) resolves without a rebuild. */
        private const val LSP_BRIDGE_SHIM = """
            window.webkit = window.webkit || {};
            window.webkit.messageHandlers = window.webkit.messageHandlers || {};
            window.webkit.messageHandlers.lsp = { postMessage: function (s) { window.AndroidLsp.lspOut(s); } };
        """
    }
}

@Composable
fun rememberEditorEngine(
    lineWrap: Boolean,
    fontSize: Int,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onLspOut: (String) -> Unit = {},
    onFontSize: (Int) -> Unit = {},
): EditorEngine {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onChangeS = rememberUpdatedState(onChange)
    val onSaveS = rememberUpdatedState(onSave)
    val onLspOutS = rememberUpdatedState(onLspOut)
    val onFontSizeS = rememberUpdatedState(onFontSize)
    // NOT keyed on fontSize: a zoom pushes the size in place (engine.setFontSize)
    // rather than rebuilding the WebView, so a pinch/shortcut never reloads the file.
    val engine = remember(lineWrap) {
        EditorEngine(context, lineWrap, fontSize, onChangeS.value, onSaveS.value, onLspOutS.value, onFontSizeS.value)
    }
    engine.updateCallbacks(onChangeS.value, onSaveS.value, onLspOutS.value, onFontSizeS.value)
    // Push settings-driven size changes to the live editor (initial size comes from cmInit).
    LaunchedEffect(engine, fontSize) { engine.setFontSize(fontSize) }
    DisposableEffect(engine) {
        onDispose { engine.destroy() }
    }
    return engine
}

/** Hosts the persistent [EditorEngine] WebView. Mount as soon as the editor panel opens. */
@Composable
fun EditorWebViewHost(
    engine: EditorEngine,
    modifier: Modifier = Modifier,
) {
    if (engine.failed) return

    AndroidView(
        modifier = modifier,
        // Keep the WebView INVISIBLE until cm6 has first-painted (`ready`). A WebView draws its
        // raw surface WHITE for the first frames of a fresh load — before the page's own dark
        // background applies — which no setBackgroundColor reliably prevents. While invisible the
        // dark backing Box behind shows through, so the first open of each session reads as the
        // editor's dark, not a white flash; we reveal only once it's already painted dark.
        factory = { engine.obtainWebView().also { it.visibility = if (engine.ready) View.VISIBLE else View.INVISIBLE } },
        update = { it.visibility = if (engine.ready) View.VISIBLE else View.INVISIBLE },
        onRelease = { /* destroyed with engine */ },
    )
}
