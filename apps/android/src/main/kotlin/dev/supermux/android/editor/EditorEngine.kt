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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    private val fontSize: Int,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val onChangeS = mutableStateOf(onChange)
    private val onSaveS = mutableStateOf(onSave)

    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        internal set

    private var webView: WebView? = null
    private var lastContent = ""
    private var lastFilename = ""

    fun updateCallbacks(onChange: (String) -> Unit, onSave: () -> Unit) {
        onChangeS.value = onChange
        onSaveS.value = onSave
    }

    fun obtainWebView(): WebView {
        webView?.let { return it }
        return createWebView().also { webView = it }
    }

    private var lastScrollTop = 0

    fun readScrollTop(callback: (Int) -> Unit) {
        val view = webView ?: return callback(lastScrollTop)
        view.evaluateJavascript("cmGetScrollTop()") { raw ->
            val n = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.toDoubleOrNull()?.toInt()
            callback(n ?: lastScrollTop)
        }
    }

    fun setDocument(content: String, filename: String, scrollTop: Int = 0) {
        lastContent = content
        lastFilename = filename
        lastScrollTop = scrollTop
        if (ready) pushToView(content, filename, scrollTop)
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
                @JavascriptInterface fun onChange(s: String) { main.post { onChangeS.value.invoke(s) } }
                @JavascriptInterface fun onSave() { main.post { onSaveS.value.invoke() } }
                @JavascriptInterface fun onReady() {
                    main.post {
                        ready = true
                        pushToView(lastContent, lastFilename, lastScrollTop)
                    }
                }
            }, "AndroidEditor")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d("EditorEngine", "onPageFinished $url")
                    view?.evaluateJavascript(
                        "cmInit(${q(lastContent)}, ${q(lastFilename)}, $lineWrap, $fontSize)",
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
        view.evaluateJavascript("cmSetFontSize($fontSize)", null)
        view.evaluateJavascript("cmSetScrollTop($scrollTop)", null)
    }

    private fun q(s: String): String = JSONObject.quote(s)
}

@Composable
fun rememberEditorEngine(
    lineWrap: Boolean,
    fontSize: Int,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
): EditorEngine {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onChangeS = rememberUpdatedState(onChange)
    val onSaveS = rememberUpdatedState(onSave)
    val engine = remember(lineWrap, fontSize) {
        EditorEngine(context, lineWrap, fontSize, onChangeS.value, onSaveS.value)
    }
    engine.updateCallbacks(onChangeS.value, onSaveS.value)
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
        factory = { engine.obtainWebView() },
        update = { /* WebView instance is stable; content pushed via engine.setDocument */ },
        onRelease = { /* destroyed with engine */ },
    )
}
