package dev.supermux.webview.engine.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest as AndroidPermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.supermux.webview.ConsoleLevel
import dev.supermux.webview.ConsoleMessage
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

/** How long a new window may take to navigate before the WebView catching its URL is let go. */
private const val NEW_WINDOW_MILLIS = 10_000L

/** The most WebView state kept per page (engine history and scroll); older history entries are dropped first. */
private const val MAX_STATE_BYTES = 64 * 1024

/** How long a reset's about:blank may take to commit, and its profile wipe to finish, before the reset fails. */
private const val RESET_BLANK_MILLIS = 5_000L
private const val RESET_WIPE_MILLIS = 5_000L

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

    /** The origin rules the channel and the shim are injected with, while they are: the lease's bridge origins. */
    private var channelRules: Set<String>? = null
    private var shimScript: ScriptHandler? = null

    /** The reply proxy of the last message the main frame's document sent, and that document's origin. */
    private var lastReply: Reply? = null

    /** The page's requests the client hasn't answered yet: each gets its safe default if it never does. */
    private val pending = PendingRequests()

    /** The geolocation prompt open now, which WebView may withdraw. */
    private var geolocationPrompt: Any? = null

    /** A running reset's about:blank: completed with whether it committed. */
    private var blankCommitted: CompletableDeferred<Boolean>? = null

    private class Reply(val proxy: JavaScriptReplyProxy, val origin: String)

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
        view.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            val asked = client
            if (view === webView && asked != null && gate.acceptsDocumentEvents) {
                val request = DownloadRequest(
                    url = url,
                    suggestedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType),
                    mimeType = mimeType?.ifBlank { null },
                    contentLength = contentLength.takeIf { it >= 0 },
                )
                // On Android the app downloads: WebView has nothing to proceed with, whatever the answer.
                scope.launch {
                    try {
                        asked.onDownload(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "the client failed on a download", e)
                    }
                }
            }
        }
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
        val before = this.setup
        this.setup = setup
        val settings = webView.settings
        settings.javaScriptEnabled = setup.javaScriptEnabled
        settings.userAgentString = setup.userAgentSuffix?.let { "${WebSettings.getDefaultUserAgent(context)} $it" }
        webView.setBackgroundColor(if (setup.transparentBackground) Color.TRANSPARENT else Color.WHITE)
        Inspector.want(this, setup.inspectable)
        if (before?.dark != setup.dark) applyTheme(setup.dark)
        // Mid-lease, a new list applies to the documents that start from now on: WebView can't inject into one that has.
        if (before == null || before.javaScriptEnabled != setup.javaScriptEnabled || before.bridgeOrigins != setup.bridgeOrigins) {
            installBridge(setup)
        }
    }

    /**
     * What `prefers-color-scheme` says from now on. WebView reads `android:isLightTheme` from its context's theme each
     * time it recomputes its preferences, and recomputes them whenever a setting changes (`AwSettings`): flipping the
     * default text encoding between two names of UTF-8, which changes nothing else, makes it recompute now, so the page
     * shown follows too.
     */
    private fun applyTheme(dark: Boolean) {
        context.dark = dark
        val settings = webView.settings
        settings.defaultTextEncodingName = if (settings.defaultTextEncodingName == "UTF-8") "utf-8" else "UTF-8"
    }

    /**
     * Starts an epoch: a load, reload, restore or [reset]. Nothing queued from before is delivered, and [gate] hears
     * nothing until the marker posted here runs and this boundary's own navigation commits. [content] is served from
     * now on. A reset's epochs drop the last lease's queued focus changes too ([EngineEventQueue.advance]): nobody
     * collects between leases, and the next lease's view would take that focus for its own.
     */
    private fun boundary(content: WebContent?, reset: Boolean = false) {
        token = newToken()
        val epoch = gate.boundary(token)
        queue.advance(reset = reset)
        documentPainted = false
        lastFailure = null
        lastReply = null
        // The document goes: what it asked and nobody answered gets its safe default, so it never blocks the next one.
        pending.answerAll()
        served = content
        renewShim()
        main.post { if (!released) gate.markerRan(epoch) }
    }

    /**
     * Injects the channel and the shim into [setup]'s bridge origins, in place of what an earlier setup injected. Without
     * JavaScript, or with no http(s) origin on the list, documents get neither.
     */
    private fun installBridge(setup: PageSetup) {
        removeBridge()
        val rules = allowedOriginRules(setup.bridgeOrigins)
        if (!setup.javaScriptEnabled || rules.isEmpty()) return
        WebViewCompat.addWebMessageListener(webView, CHANNEL_NAME, rules, Messages(webView))
        channelRules = rules
        shimScript = WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript(token), rules)
    }

    /** Takes the channel and the shim away: documents that start from now on get neither. */
    private fun removeBridge() {
        if (channelRules != null) WebViewCompat.removeWebMessageListener(webView, CHANNEL_NAME)
        channelRules = null
        shimScript?.remove()
        shimScript = null
    }

    /** A boundary's new token: the documents that start from now on sign with it. */
    private fun renewShim() {
        val rules = channelRules ?: return
        shimScript?.remove()
        shimScript = WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript(token), rules)
    }

    /**
     * Asks the lease's client about one of the page's requests, on main: [default] answers it at once if there is no
     * client or the runtime can't hear the document, and later if the document goes or the engine resets or dies first
     * ([pending]); otherwise the client's answer does, once. Nothing the client throws reaches the page or the app.
     */
    private fun <T> ask(key: Any, default: () -> Unit, question: suspend (EngineClient) -> T, answer: (T) -> Unit) {
        val asked = client
        if (asked == null || !usableNow() || !gate.acceptsDocumentEvents) {
            runCatching(default)
            return
        }
        pending.open(key, default)
        scope.launch {
            val result = try {
                question(asked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the client failed to answer the page", e)
                pending.answer(key, default)
                return@launch
            }
            pending.answer(key) { answer(result) }
        }
    }

    /** The page asked for a new window on [url]: "open here" loads it here, after the policy allowed it there too. */
    private fun newWindow(url: String, gesture: Boolean) {
        val asked = client ?: return
        val decision = try {
            asked.onNewWindow(NewWindowRequest(url, gesture))
        } catch (e: Exception) {
            Log.w(TAG, "the client failed to decide a new window", e)
            NewWindowDecision.Block
        }
        if (decision == NewWindowDecision.OpenHere && usableNow()) webView.loadUrl(url)
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

    /**
     * Delivers through the reply proxy of the last message the main frame's document sent: it reaches that document
     * only, and only while it is there (Chromium builds a proxy per document, and one whose document went drops what it
     * gets). Its origin is the one WebView reported for that document, so a document of another origin gets nothing.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!usableNow()) return
        val reply = lastReply ?: return
        if (reply.origin != targetOrigin) return
        try {
            reply.proxy.postMessage(json)
        } catch (e: RuntimeException) {
            Log.w(TAG, "a reply proxy refused a message", e)
        }
    }

    /**
     * The page as it is on screen: null until the current document's first paint, while no Surface is composed, or
     * while the view isn't wholly visible ([copyPixels]). It waits a frame first, so the runtime's cover, lifted at the
     * paint, is gone from the window. Kept at a density of 2 at most ([snapshotScale]).
     */
    override suspend fun snapshot(): ImageBitmap? {
        if (!usableNow() || !documentPainted || surfaces == 0) return null
        val view = webView
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 || view.visibility != View.VISIBLE) return null
        awaitFrames(1)
        if (view !== webView || !documentPainted || !usableNow()) return null
        return copyPixels(view, snapshotScale(view.resources.displayMetrics.density))?.asImageBitmap()
    }

    /** WebView's own state (history, scroll, form data), after a header naming the content it is of. */
    override suspend fun saveState(): ByteArray? {
        if (!usableNow()) return null
        val content = served ?: return null
        val bundle = Bundle()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
            WebViewCompat.saveState(webView, bundle, MAX_STATE_BYTES, true)
        } else if (webView.saveState(bundle) == null) {
            return null
        }
        if (bundle.isEmpty) return null
        val payload = marshall(bundle) ?: return null
        // Without SAVE_STATE nothing caps WebView's own state: an outsize one isn't kept, rather than filling page memory.
        if (payload.size > 2 * MAX_STATE_BYTES) return null
        return SavedStateCodec.encode(content.key, payload)
    }

    /**
     * Restores [state] if it is [content]'s. Chromium appends restored history to a used WebView's (it keeps every entry
     * but a lone initial one), so a used engine restores into a fresh WebView, configured as the lease is.
     */
    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!usableNow()) return false
        val payload = SavedStateCodec.decode(state, content.key) ?: return false
        val bundle = unmarshall(payload) ?: return false
        if (!pristine) rebuild()
        boundary(content)
        pristine = false
        // The restored history is the page's history: the first commit must not clear it.
        freshHistory = false
        return webView.restoreState(bundle) != null
    }

    /** Replaces the WebView with a fresh one in [container], set up as the current lease is. The old one is destroyed. */
    private fun rebuild() {
        val old = webView
        channelRules = null
        shimScript = null
        destroy(old)
        webView = newWebView()
        pristine = true
        freshHistory = true
        documentPainted = false
        val current = setup ?: return
        setup = null
        configure(current)
    }

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

    /**
     * Readies the engine for another lease, keeping every postcondition of [WebEngine.reset] or returning false (see
     * Task 15 of Plan 2a for how each is met). The runtime never cancels it; the pool bounds it at 10 s.
     */
    override suspend fun reset(): Boolean {
        if (released || crashed) return false
        resetting = true
        try {
            boundary(content = null, reset = true)
            client = null
            removeBridge()
            setup = null
            Inspector.want(this, false)
            val view = webView
            view.stopLoading()
            view.visibility = View.INVISIBLE
            view.onResume()
            val blank = CompletableDeferred<Boolean>()
            blankCommitted = blank
            view.loadUrl("about:blank")
            pristine = false
            val committed = withTimeoutOrNull(RESET_BLANK_MILLIS) { blank.await() } ?: false
            blankCommitted = null
            if (!committed || released || crashed) return false
            view.clearHistory()
            freshHistory = true
            if (config.profile == WebProfile.Ephemeral && !wipeProfile()) return false
            // Nothing from during the reset is heard either.
            boundary(content = null, reset = true)
            return !retired && !released && !crashed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a reset failed", e)
            return false
        } finally {
            resetting = false
        }
    }

    /** Wipes the Ephemeral engine's own profile: cookies, web storage, geolocation, HTTP cache. True when done in time. */
    private suspend fun wipeProfile(): Boolean {
        val profile = WebViewCompat.getProfile(webView)
        val wiped = withTimeoutOrNull(RESET_WIPE_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                EphemeralProfiles.wipe(profile) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        if (wiped == null) return false
        // The HTTP cache of this WebView's own browser context: its profile's, no other's (AwContents::ClearCache).
        webView.clearCache(true)
        return true
    }

    override fun release() {
        if (released) return
        released = true
        pending.answerAll()
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

    /** Page messages: the main frame's, signed with this epoch's token, from a document the gate lets the runtime hear. */
    private inner class Messages(private val owner: WebView) : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy,
        ) {
            if (owner !== webView || released || !isMainFrame) return
            if (message.type != WebMessageCompat.TYPE_STRING) return
            val (signedWith, json) = splitSigned(message.data ?: return) ?: return
            if (!gate.acceptsMessage(signedWith)) return
            val origin = messageOrigin(sourceOrigin.toString())
            lastReply = Reply(replyProxy, origin)
            emit(EngineEvent.PageMessage(origin, json), document = replyProxy)
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
            if (resetting) {
                // The reset's own about:blank: nothing of it is reported.
                when (completion) {
                    DocumentGate.Completion.OursFailed -> blankCommitted?.complete(false)
                    is DocumentGate.Completion.Ours -> blankCommitted?.complete(true)
                    else -> Unit
                }
                return
            }
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

        /**
         * A navigation the page started (WebView asks for no other: not for loadUrl, reload or history). The client
         * answers synchronously; anything but Allow cancels it, OpenExternally included, since the runtime opens the
         * URL. A document the runtime can't hear, or a lease without a client, gets the safe default: blocked.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val asked = client
            if (view !== webView || asked == null || !usableNow() || !gate.acceptsDocumentEvents) return true
            return try {
                asked.onNavigation(NavigationRequest(request.url.toString(), request.isForMainFrame, request.hasGesture())) !=
                    NavigationDecision.Allow
            } catch (e: Exception) {
                Log.w(TAG, "the client failed to decide a navigation", e)
                true
            }
        }

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
                pending.answerAll()
                emit(EngineEvent.Crashed)
            }
            (view as? EngineWebView)?.let(::destroy)
            return true
        }
    }

    private inner class Chrome(private val owner: WebView) : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ask(result, default = { result.confirm() }, question = { it.onDialog(WebDialog.Alert(message.orEmpty(), url)) }) {
                result.confirm()
            }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ask(result, default = { result.cancel() }, question = { it.onDialog(WebDialog.Confirm(message.orEmpty(), url)) }) { answer ->
                if (answer == DialogResult.Confirmed) result.confirm() else result.cancel()
            }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            val dialog = WebDialog.Prompt(message.orEmpty(), defaultValue.orEmpty(), url)
            ask(result, default = { result.cancel() }, question = { it.onDialog(dialog) }) { answer ->
                when (answer) {
                    is DialogResult.Text -> result.confirm(answer.value)
                    DialogResult.Confirmed -> result.confirm(dialog.defaultValue)
                    DialogResult.Dismissed -> result.cancel()
                }
            }
            return true
        }

        /** Leaving is the default, so a page can't trap the user: confirm() leaves, cancel() stays. */
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ask(result, default = { result.confirm() }, question = { it.onDialog(WebDialog.BeforeUnload(message.orEmpty(), url)) }) { answer ->
                if (answer == DialogResult.Dismissed) result.cancel() else result.confirm()
            }
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
            val request = FileChooserRequest(params.acceptTypes.filter { it.isNotBlank() }, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            ask(callback, default = { callback.onReceiveValue(null) }, question = { it.onFileChooser(request) }) { chosen ->
                val uris = chosen?.map(android.net.Uri::parse)
                callback.onReceiveValue(uris?.let { if (request.allowMultiple) it.toTypedArray() else it.take(1).toTypedArray() })
            }
            return true
        }

        override fun onPermissionRequest(request: AndroidPermissionRequest) {
            val kinds = request.resources.mapTo(LinkedHashSet()) { resource ->
                when (resource) {
                    AndroidPermissionRequest.RESOURCE_VIDEO_CAPTURE -> PermissionKind.Camera
                    AndroidPermissionRequest.RESOURCE_AUDIO_CAPTURE -> PermissionKind.Microphone
                    else -> PermissionKind.Other
                }
            }
            val asked = PermissionRequest(originOf(request.origin.toString()), kinds)
            ask(request, default = { request.deny() }, question = { it.onPermission(asked) }) { granted ->
                if (granted) request.grant(request.resources) else request.deny()
            }
        }

        override fun onPermissionRequestCanceled(request: AndroidPermissionRequest) = pending.withdraw(request)

        /** Never retained: grants end with the prompt, so none outlives the lease. */
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            geolocationPrompt = callback
            val asked = PermissionRequest(originOf(origin), setOf(PermissionKind.Geolocation))
            ask(callback, default = { callback.invoke(origin, false, false) }, question = { it.onPermission(asked) }) { granted ->
                callback.invoke(origin, granted, false)
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            geolocationPrompt?.let(pending::withdraw)
            geolocationPrompt = null
        }

        /**
         * window.open and target=_blank. WebView reports the new window's URL only through its first navigation, so a
         * throwaway WebView (same context, same profile) catches it, and is destroyed.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (view !== webView || client == null || !gate.acceptsDocumentEvents) return false
            val catcher = WebView(context)
            var caught = false
            val letGo = Runnable {
                if (!caught) {
                    caught = true
                    catcher.destroy()
                }
            }
            profileName?.let { WebViewCompat.setProfile(catcher, it) }
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!caught) newWindow(request.url.toString(), isUserGesture)
                    main.post(letGo)
                    return true
                }
            }
            // A window that never navigates (window.open() and document.write) is let go after a while.
            main.postDelayed(letGo, NEW_WINDOW_MILLIS)
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }
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

/** [bundle] as bytes. Kept in memory only: Parcel bytes are valid for this platform version alone. */
private fun marshall(bundle: Bundle): ByteArray? {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(bundle)
        parcel.marshall()
    } catch (e: RuntimeException) {
        null
    } finally {
        parcel.recycle()
    }
}

private fun unmarshall(bytes: ByteArray): Bundle? {
    val parcel = Parcel.obtain()
    return try {
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        parcel.readBundle(AndroidWebEngine::class.java.classLoader)
    } catch (e: RuntimeException) {
        null
    } finally {
        parcel.recycle()
    }
}
