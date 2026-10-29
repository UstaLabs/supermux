package dev.supermux.webview.engine.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.supermux.webview.ConsoleLevel
import dev.supermux.webview.ConsoleMessage
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** The engine's logcat tag. */
internal const val TAG = "WebViewEngine"

/** How long a bundle's provider may take for one file before the request is a 404. */
private const val BUNDLE_FILE_MILLIS = 10_000L

/**
 * The Android engine (spec §7): one system WebView, hosted natively in the composition, pooled and reused.
 *
 * Main thread only, but for `shouldInterceptRequest`, which WebView calls on a background thread, and which reads only
 * [served].
 * - **Documents.** [gate] decides which callbacks speak for a document the runtime may hear; [queue] buffers what they
 *   say and drops what an earlier epoch left. Every boundary (load, reload, restore, reset) starts an epoch.
 * - **Painting.** The WebView is INVISIBLE from its creation, and after a reset, until its first document's visual state
 *   callback completes ([awaitPaint]). Between documents WebView itself holds the old frame.
 * - **Context.** The WebView is built on [context], a themed wrapper around the Activity that shows web views, never the
 *   application context (DeX/XREAL density). The Surface moves it to its own Activity; an engine whose Activity died, or
 *   that showed on another display, serves no further lease ([retired]).
 * - **Local content** is served from `https://<origin>.invalid/` ([answerFor]); every other `.invalid` request is a 404.
 */
internal class AndroidWebEngine(
    private val config: EngineConfig,
    activity: Activity,
    /** WebView's profile, or null for its default one. */
    private val profileName: String?,
    /** Whether [profileName] was another engine's: its HTTP cache is cleared before anything loads. */
    reusedProfile: Boolean = false,
) : WebEngine {
    override val mode: EmbeddingMode = EmbeddingMode.NativeView
    override val needsCutouts: Boolean get() = false
    override var client: EngineClient? = null

    private val main = Handler(Looper.getMainLooper())

    /** Posts behind everything WebView has posted already: the order [gate] relies on. */
    private val behindQueued = Executor { main.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = EngineEventQueue()
    private val gate = DocumentGate()
    internal val context = EngineContext(activity, dark = false)

    /** The density the WebView was built for: it keeps its first Activity's window and display. */
    private val builtDensityDpi = activity.resources.configuration.densityDpi

    /** What the Surface hosts. It outlives the WebViews in it: a restore into a used engine builds a fresh one. */
    private val container = FrameLayout(context)
    internal var webView: EngineWebView = newWebView()
        private set

    override val events: Flow<EngineEvent> = queue.events

    private var released = false
    private var crashed = false
    private var resetting = false

    /** Its Activity is gone, or it showed on another display: it serves no further lease. */
    internal var retired = false
        private set

    override val isUsable: Boolean get() = !released && !crashed

    /** The content whose local files are served. Read on WebView's background thread. */
    @Volatile private var served: WebContent? = null
    private var setup: PageSetup? = null
    private var token: String = newToken()

    /** Whether the WebView has never started a document. */
    private var pristine = true

    /** Whether history begins with the next committed document: after creation and after a reset. */
    private var freshHistory = true

    /** Counts committed documents: a paint belongs to the document it was awaited for. */
    private var document = 0
    private var documentPainted = false

    /** How many Surfaces are composed: 0 or 1, and briefly 2 while the engine moves within a frame. */
    private var surfaces = 0

    /** The last main-frame load failure WebView reported: it reports one just before the navigation completes. */
    private var lastFailure: LoadFailure? = null
    private val stopWatching: () -> Unit = ActivityRegistry.onDestroyed(::activityDestroyed)

    private class LoadFailure(val url: String, val error: WebError)

    init {
        if (reusedProfile) webView.clearCache(true)
    }

    /** Builds a WebView in [container], set up as every lease starts: safe defaults, no page, invisible. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): EngineWebView {
        val view = EngineWebView(context, ::onFocus)
        // Before anything else touches it (WebViewCompat.setProfile).
        profileName?.let { WebViewCompat.setProfile(view, it) }
        view.settings.run {
            javaScriptEnabled = false
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // So window.open and target=_blank reach onCreateWindow, and the client decides; pop-ups need a gesture.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = config.mediaPlaybackRequiresGesture
            setGeolocationEnabled(true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        // A page restored from the back-forward cache would sign with an earlier epoch's token, and stay deaf.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            WebSettingsCompat.setBackForwardCacheEnabled(view.settings, false)
        }
        view.setBackgroundColor(Color.WHITE)
        view.visibility = View.INVISIBLE
        view.webViewClient = Client()
        view.webChromeClient = Chrome(view)
        WebViewCompat.addNavigationListener(view, behindQueued, Navigations(view))
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return view
    }

    private fun usableNow(): Boolean = !released && !crashed && !resetting

    /** Queues [event]; nothing while a reset runs or after release, but the engine's own [EngineEvent.Crashed]. */
    private fun emit(event: EngineEvent, document: Any? = null) {
        if (released) return
        if (resetting && event != EngineEvent.Crashed) return
        queue.offer(event, document)
    }

    override fun configure(setup: PageSetup) {
        if (!usableNow()) return
        this.setup = setup
        val settings = webView.settings
        settings.javaScriptEnabled = setup.javaScriptEnabled
        settings.userAgentString = setup.userAgentSuffix?.let { "${WebSettings.getDefaultUserAgent(context)} $it" }
        webView.setBackgroundColor(if (setup.transparentBackground) Color.TRANSPARENT else Color.WHITE)
        Inspector.want(this, setup.inspectable)
    }

    /**
     * Starts an epoch: a load, reload, restore or reset. Nothing queued from before is delivered, and [gate] hears
     * nothing until the marker posted here runs and this boundary's own navigation commits. [content] is served from
     * now on.
     */
    private fun boundary(content: WebContent?) {
        token = newToken()
        val epoch = gate.boundary(token)
        queue.advance()
        documentPainted = false
        lastFailure = null
        served = content
        main.post { if (!released) gate.markerRan(epoch) }
    }

    override fun load(content: WebContent) {
        if (!usableNow()) return
        boundary(content)
        pristine = false
        when (content) {
            is WebContent.Url -> webView.loadUrl(content.url, content.headers)
            is WebContent.Html -> webView.loadUrl(content.origin.url)
            is WebContent.Bundle -> webView.loadUrl(content.origin.url + content.entry)
        }
    }

    override fun goBack() {
        if (usableNow() && webView.canGoBack()) webView.goBack()
    }

    override fun goForward() {
        if (usableNow() && webView.canGoForward()) webView.goForward()
    }

    override fun reload() {
        if (!usableNow()) return
        boundary(served)
        webView.reload()
    }

    override fun stop() {
        if (usableNow()) webView.stopLoading()
    }

    override suspend fun evaluate(script: String): String? {
        if (!usableNow()) return null
        val view = webView
        return suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
        }
    }

    override fun postMessage(json: String, targetOrigin: String) = Unit

    override suspend fun snapshot(): ImageBitmap? = null

    override suspend fun saveState(): ByteArray? = null

    override fun restoreState(state: ByteArray, content: WebContent): Boolean = false

    override fun setVisible(visible: Boolean) {
        if (!usableNow()) return
        if (visible) webView.onResume() else webView.onPause()
    }

    override fun requestFocus() {
        if (usableNow()) webView.requestFocus()
    }

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }
        DisposableEffect(activity) {
            surfaces++
            if (activity != null) showIn(activity)
            onDispose { surfaces-- }
        }
        AndroidView(
            factory = {
                // An engine can move between views within one frame: leave the old parent first (Plan 1's deferred
                // row), or Android throws "The specified child already has a parent".
                (container.parent as? ViewGroup)?.removeView(container)
                container
            },
            modifier = modifier,
        )
    }

    override suspend fun reset(): Boolean = false

    override fun release() {
        if (released) return
        released = true
        client = null
        stopWatching()
        Inspector.want(this, false)
        scope.cancel()
        destroy(webView)
        container.removeAllViews()
        if (config.profile == dev.supermux.webview.WebProfile.Ephemeral && profileName != null) {
            EphemeralProfiles.giveBack(profileName)
        }
    }

    /** The Surface shows in [activity]: the WebView's context follows it; another display retires the engine. */
    private fun showIn(activity: Activity) {
        if (released || context.activity === activity) return
        context.moveTo(activity)
        if (activity.resources.configuration.densityDpi != builtDensityDpi) retired = true
    }

    /**
     * An Activity was destroyed. If it is this engine's, nothing may keep it: the context lets it go, and the engine
     * serves no further lease. Idle, it goes now (spec §7: "idle spares are dropped when their Activity is destroyed").
     */
    private fun activityDestroyed(activity: Activity) {
        if (released || context.activity !== activity) return
        context.detach()
        retired = true
        if (client == null && surfaces == 0) release()
    }

    private fun destroy(view: EngineWebView) {
        if (view.gone) return
        view.gone = true
        try {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "destroying a WebView failed", e)
        }
    }

    private fun onFocus(focused: Boolean) {
        emit(EngineEvent.FocusChanged(focused))
    }

    /** A main-frame commit the runtime may hear: by this epoch's own navigation, or after it. */
    private fun committed(navigation: Navigation, sameDocument: Boolean) {
        val url = navigation.url
        if (!sameDocument) {
            document++
            documentPainted = false
            if (freshHistory) {
                // History begins with the lease's first document: a reset's about:blank is not behind it.
                webView.clearHistory()
                freshHistory = false
            }
        }
        emit(EngineEvent.UrlChanged(url))
        emit(EngineEvent.HistoryChanged(webView.canGoBack(), webView.canGoForward()))
        if (sameDocument) return
        when {
            navigation.didCommitErrorPage() -> emit(EngineEvent.Error(failureFor(url)))
            navigation.statusCode >= 400 -> emit(EngineEvent.Error(WebError.Http(url, navigation.statusCode)))
        }
        awaitPaint()
    }

    /** The error WebView reported for the main-frame load of [url], or a plain network error. */
    private fun failureFor(url: String): WebError {
        val failure = lastFailure
        lastFailure = null
        return failure?.takeIf { it.url == url }?.error ?: WebError.Network(url, "the page did not load")
    }

    /** Shows the WebView once the committed document's first frame is ready, and says so ([EngineEvent.FirstPaint]). */
    private fun awaitPaint() {
        val view = webView
        val painting = document
        val request = (gate.epoch.toLong() shl 32) or (painting.toLong() and 0xffffffffL)
        WebViewCompat.postVisualStateCallback(view, request) { id ->
            if (released || view !== webView || id != request || painting != document || !gate.acceptsDocumentEvents) {
                return@postVisualStateCallback
            }
            view.visibility = View.VISIBLE
            documentPainted = true
            emit(EngineEvent.FirstPaint)
        }
    }

    /** Whether the current document got the bridge shim: its native console output then isn't forwarded too. */
    private fun documentHasShim(): Boolean {
        val setup = setup ?: return false
        val url = webView.url ?: return false
        return setup.javaScriptEnabled && originOf(url) in setup.bridgeOrigins
    }

    /** The answer for a request WebView is about to make. Runs on WebView's background thread. */
    private fun localResponse(url: String, method: String): WebResourceResponse? =
        when (val answer = answerFor(url, method, served)) {
            LocalAnswer.Network -> null
            LocalAnswer.NotFound -> plainResponse(404, "Not Found")
            LocalAnswer.MethodNotAllowed -> plainResponse(405, "Method Not Allowed")
            is LocalAnswer.Html -> response(200, "OK", "text/html", answer.html.encodeToByteArray(), emptyMap())
            is LocalAnswer.BundleFile -> {
                val resource = try {
                    runBlocking { withTimeoutOrNull(BUNDLE_FILE_MILLIS) { answer.resources.load(answer.path) } }
                } catch (e: Exception) {
                    Log.w(TAG, "a bundle's resource provider failed for ${answer.path}", e)
                    null
                }
                if (resource == null) plainResponse(404, "Not Found")
                else response(200, "OK", resource.mimeType, resource.bytes, resource.headers)
            }
        }

    private inner class Navigations(private val owner: WebView) : NavigationListener {
        override fun onNavigationStarted(navigation: Navigation) {
            if (owner !== webView || released) return
            gate.navigationStarted(navigation, navigation.wasInitiatedByPage())
        }

        override fun onNavigationCompleted(navigation: Navigation) {
            if (owner !== webView || released) return
            val completion = gate.navigationCompleted(
                navigation,
                byPage = navigation.wasInitiatedByPage(),
                didCommit = navigation.didCommit(),
                sameDocument = navigation.isSameDocument,
            )
            when (completion) {
                DocumentGate.Completion.Ignore -> Unit
                DocumentGate.Completion.OursFailed -> emit(EngineEvent.Error(failureFor(navigation.url)))
                is DocumentGate.Completion.Ours -> committed(navigation, completion.sameDocument)
                is DocumentGate.Completion.Later -> committed(navigation, completion.sameDocument)
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            localResponse(request.url.toString(), request.method)

        /** Page-started navigations: blocked, the safe default, until the client decides them. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            val url = request.url.toString()
            lastFailure = LoadFailure(url, loadError(url, error.errorCode, error.description.toString()))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Certificate errors are never bypassed.
            handler.cancel()
            if (view === webView) {
                lastFailure = LoadFailure(error.url, WebError.Certificate(error.url, "certificate error ${error.primaryError}"))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // True keeps the app alive. The WebView must never be used again (spec §7): the engine is unusable for good.
            if (view === webView && !released) {
                crashed = true
                emit(EngineEvent.Crashed)
            }
            (view as? EngineWebView)?.let(::destroy)
            return true
        }
    }

    private inner class Chrome(private val owner: WebView) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== webView || !gate.afterMarker) return
            emit(EngineEvent.Progress(if (newProgress >= 100) null else newProgress / 100f))
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (view !== webView || !gate.acceptsDocumentEvents) return
            emit(EngineEvent.TitleChanged(pageTitle(title, view.url)))
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (owner === webView && gate.acceptsDocumentEvents && !documentHasShim()) {
                emit(EngineEvent.Console(consoleMessage(message)))
            }
            return true
        }
    }
}

private val random = SecureRandom()
private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/** A fresh epoch token: [TOKEN_LENGTH] letters and digits nobody can guess. */
private fun newToken(): String = String(CharArray(TOKEN_LENGTH) { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] })

private fun consoleMessage(message: android.webkit.ConsoleMessage): ConsoleMessage = ConsoleMessage(
    level = when (message.messageLevel()) {
        android.webkit.ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warn
        android.webkit.ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
        android.webkit.ConsoleMessage.MessageLevel.DEBUG, android.webkit.ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.Debug
        else -> ConsoleLevel.Log
    },
    message = message.message(),
    source = message.sourceId(),
    line = message.lineNumber(),
)

private fun response(status: Int, reason: String, mimeType: String, body: ByteArray, headers: Map<String, String>): WebResourceResponse {
    val type = mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
    val declared = mimeType.substringAfter("charset=", "").trim().ifEmpty { null }
    val textual = type.startsWith("text/") || type == "application/javascript" || type == "application/json"
    val all = LinkedHashMap<String, String>()
    all["Cache-Control"] = "no-store"
    all["X-Content-Type-Options"] = "nosniff"
    all.putAll(headers)
    return WebResourceResponse(type, declared ?: if (textual) "utf-8" else null, status, reason, all, ByteArrayInputStream(body))
}

private fun plainResponse(status: Int, reason: String): WebResourceResponse =
    response(status, reason, "text/plain", reason.encodeToByteArray(), emptyMap())
