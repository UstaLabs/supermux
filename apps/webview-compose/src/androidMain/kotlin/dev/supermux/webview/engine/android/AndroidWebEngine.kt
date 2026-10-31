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
import android.view.Choreographer
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.Page
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
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
import dev.supermux.webview.runtime.MAX_SNAPSHOT_SCALE
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.drawScale
import dev.supermux.webview.runtime.snapshotSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.math.min
import kotlin.math.roundToInt

/** The engine's logcat tag. */
internal const val TAG = "WebViewEngine"

/** How long a bundle's provider may take for one file before the request is a 404. */
private const val BUNDLE_FILE_MILLIS = 10_000L

/** How long a new window may take to navigate before the WebView catching its URL is let go. */
private const val NEW_WINDOW_MILLIS = 10_000L

/** The most console lines a document may write before the posted completion of its commit runs; later ones are dropped. */
private const val MAX_EARLY_CONSOLE_LINES = 64

/** The most WebView state kept per page (engine history and scroll); older history entries are dropped first. */
private const val MAX_STATE_BYTES = 64 * 1024

/** How long a reset's profile wipe may take before the reset fails. */
private const val RESET_WIPE_MILLIS = 5_000L

/**
 * How long a document drawn under the cover may go without painting content (its first contentful paint, or its load
 * event) before it is shown anyway: the cover doesn't stay up for good over a page that never paints anything.
 */
private const val CONTENT_WAIT_MILLIS = 3_000L

/** The frames a document is drawn in its place, with its content, under the cover before it says it painted. */
private const val FRAMES_IN_PLACE = 2

/**
 * The Android engine (spec §7): one system WebView, hosted natively in the composition, pooled and reused.
 *
 * Main thread only, but for `shouldInterceptRequest`, which WebView calls on a background thread, and which reads only
 * [served].
 * - **Documents.** [gate] decides which callbacks speak for a document the runtime may hear; [queue] buffers what they
 *   say and drops what an earlier epoch left. Every boundary (load, reload, restore, reset) starts an epoch.
 * - **Painting.** The WebView is INVISIBLE from its creation, and after a reset, until its first document's visual state
 *   callback completes; from then on it draws under the runtime's cover, and [EngineEvent.FirstPaint] waits until it
 *   has drawn the document's content in its place ([awaitPaint]). Between documents WebView itself holds the old frame.
 * - **Parking.** A parked page ([WebEngine.replayState]) keeps its WebView, document and [gate] as they are, paused
 *   ([setVisible]) and out of any window while no Surface shows it. Adopted, it replays its page, and its first paint
 *   waits until the WebView, in a window again, has drawn the document there ([awaitRepaint]). One whose renderer dies
 *   meanwhile, or that is [retired], tells the pool at once ([onUnusable]).
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
        set(value) {
            // The client that was asked goes (its view parks the page, or lets it go): what it was asked gets its safe
            // default now, since its dialog went with its view, and the page would wait on it for good, the next view's
            // page too. Its asking stops with it.
            if (value !== field && !released) pending.answerAll()
            field = value
        }

    private val main = Handler(Looper.getMainLooper())

    /** Posts behind everything WebView has posted already: the order [gate] relies on. */
    private val behindQueued = Executor { main.post(it) }

    /** Runs at once, inside WebView's own call: when the browser commits, for the callbacks WebView makes at once. */
    private val atOnce = Executor { it.run() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the page says while parked is dropped as it comes: however chatty, it stays adoptable (contract **Buffering**). */
    private val queue = EngineEventQueue()
    private val gate = DocumentGate()
    internal val context = EngineContext(activity, dark = false)

    /** The density the WebView was built for: it keeps its first Activity's window and display. */
    private val builtDensityDpi = activity.resources.configuration.densityDpi

    /** What the Surface hosts. It outlives the WebViews in it: every reset builds a fresh one ([reset]). */
    private val container = FrameLayout(context)
    internal var webView: EngineWebView = newWebView()
        private set

    override val events: Flow<EngineEvent> = queue.events

    private var released = false
    private var crashed = false
    private var resetting = false

    /** Its Activity is gone, or it showed on another display: it serves no further lease ([retire]). */
    internal var retired = false
        private set

    /**
     * False once released, crashed or [retired]: a retired engine still shows its page for the lease it has, but the
     * pool must neither park it nor hand it on, as a parked page adopted by another view would be.
     */
    override val isUsable: Boolean get() = !released && !crashed && !retired

    /**
     * Whom to tell when the engine turns unusable by itself, its renderer dead ([crash]) or itself [retired]: the pool,
     * while the page is parked. Called on the main thread, once, as [isUsable] turns false, after the crash is queued
     * if there is one; never for a [release].
     */
    override var onUnusable: (() -> Unit)? = null

    /** The content whose local files are served. Read on WebView's background thread. */
    @Volatile private var served: WebContent? = null
    private var setup: PageSetup? = null
    private var token: String = newToken()

    /** The bridge's channel on this WebView, whether its listener is added, and the shim injected now. */
    private val channels = BridgeChannels()
    private var listening = false
    private var shim: ScriptHandler? = null

    /**
     * The reply proxy of the last message the main frame's document sent, that document's origin and the token it signs
     * with: [postMessage] uses it while [gate] hears that token, so a fragment-only load keeps it with its document.
     */
    private var lastReply: Reply? = null

    /** The page's requests the client hasn't answered yet: each gets its safe default if it never does. */
    private val pending = PendingRequests()

    /** The WebViews catching new windows' URLs now ([Chrome.onCreateWindow]): all let go at a boundary or release. */
    private val catchers = ArrayList<Catcher>()

    /**
     * Console output our document wrote before the posted completion of its commit ran (WebView reports console output
     * at once): delivered right after the commit's events, so the runtime hears the document commit before it speaks.
     */
    private val earlyConsole = ArrayList<EngineEvent.Console>()

    /** The geolocation prompt open now, which WebView may withdraw. */
    private var geolocationPrompt: Any? = null

    /** What a running reset waits for (its profile wipe): completed with false if the engine is released or crashes. */
    private var resetWait: CompletableDeferred<Boolean>? = null

    /** How many WebViews the engine has built: one at its creation, and one at every reset (or restore into a used one). */
    internal var webViewsBuilt = 0
        private set

    /** How many times a snapshot drew the page on the main thread ([drawPage]), for tests. */
    internal var pageDraws = 0
        private set

    private class Reply(val proxy: JavaScriptReplyProxy, val origin: String, val token: String)

    /** Whether the WebView has never started a document. */
    private var pristine = true

    /** Whether history begins with the next committed document: after creation and after a reset. */
    private var freshHistory = true

    /** Counts committed documents: a paint belongs to the document it was awaited for. */
    private var document = 0
    private var documentPainted = false

    /** The document the page last drew itself for a snapshot: a document's first draw is cold, and slower. */
    private var drawnDocument = -1

    // The handoff from the runtime's cover to the page ([awaitPaint]), by document.
    /** The document drawing VISIBLE under the cover: its commit's visual state is on screen. */
    private var drawnUnderCover = -1

    /** The document that has painted content: its first contentful paint, or its load event. */
    private var contentPainted = -1

    /** The document whose handoff (a visual state callback, then [FRAMES_IN_PLACE] frames) is under way. */
    private var handingOff = -1

    /** The current document's page, as the navigation listener names it, and the last page that painted content. */
    private var currentPage: Page? = null
    private var pageWithContent: Page? = null

    /** How many Surfaces are composed: 0 or 1, and briefly 2 while the engine moves within a frame. */
    private var surfaces = 0

    /** Whether the page can go back, and whether it has the keyboard focus: the Surface's back handler runs while both are. */
    private var backAvailable by mutableStateOf(false)
    private var pageFocused by mutableStateOf(false)

    /** Whether this WebView shrinks its visual viewport for the keyboard itself, given the IME inset (M139+). */
    private val imeResizesViewport = (webViewMajor(Features.version(activity)) ?: 0) >= WEBVIEW_IME_VIEWPORT_MAJOR

    /** Whether the runtime said, for the next snapshot, that nothing it knows of is over the page. */
    private var nothingOverPage = false

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
        webViewsBuilt++
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
            if (view === webView && asked != null && hears(Delivery.Posted)) {
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
        WebViewCompat.addNavigationListener(view, atOnce, LiveNavigations(view))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(view, behindQueued, Hangs(view))
        }
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
        // Mid-lease, a new list applies to the documents that start from now on (WebView can't inject into one that has;
        // origins the channel doesn't cover yet, from the next load on), and the current document keeps its channel
        // while its origin stays on the list.
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
     * collects between leases, and the next lease's view would take that focus for its own. The last reply proxy stays
     * with its document, which a fragment-only load keeps, but is used only while [gate] hears its token.
     *
     * A navigation still loading a document is stopped first ([DocumentGate.loadingNewDocument]): WebView gave the
     * frame it made for that document the last epoch's shim, which [renewShim] doesn't reach, and a navigation that
     * replaces it commits in that frame when it is of the same site. On the phone, a step back while a load was under
     * way, and a load replacing one, each brought a page that signed with the last epoch's token, never heard.
     */
    private fun boundary(content: WebContent?, reset: Boolean = false) {
        if (gate.loadingNewDocument) webView.stopLoading()
        token = newToken()
        val epoch = gate.boundary(token)
        queue.advance(reset = reset)
        documentPainted = false
        lastFailure = null
        if (reset) lastReply = null
        backAvailable = false
        earlyConsole.clear()
        // The document goes: what it asked and nobody answered gets its safe default, so it never blocks the next one.
        pending.answerAll()
        // And the windows it opened are nobody's to hear of now: the catch is dropped, whatever navigates there later.
        letGoCatchers()
        served = content
        // The document goes: a listener that doesn't cover the bridge's origins is replaced now (a reset's WebView goes).
        if (!reset) channels.boundary()?.let(::listen)
        renewShim()
        main.post { if (!released) gate.markerRan(epoch) }
    }

    /**
     * Injects the bridge into [setup]'s bridge origins from now on: without JavaScript, or with no http(s) origin on the
     * list, documents that start get no shim. The channel's listener stays while it covers the list, so a document whose
     * origin stays on it keeps its channel, and [Messages] drops what origins taken off it send; one that doesn't cover
     * it is replaced only when no document can be using it ([BridgeChannels]).
     */
    private fun installBridge(setup: PageSetup) {
        val rules = if (setup.javaScriptEnabled) allowedOriginRules(setup.bridgeOrigins) else emptySet()
        channels.update(rules, idle = pristine)?.let(::listen)
        renewShim()
    }

    /** (Re)adds the channel's listener, for [rules]: there is only ever one ([BridgeChannels]). */
    private fun listen(rules: Set<String>) {
        if (listening) WebViewCompat.removeWebMessageListener(webView, CHANNEL_NAME)
        WebViewCompat.addWebMessageListener(webView, CHANNEL_NAME, rules, Messages(webView))
        listening = true
    }

    /** The shim, for the origins allowed now, with this epoch's token: the documents that start from now on sign with it. */
    private fun renewShim() {
        shim?.remove()
        shim = null
        val rules = channels.shimRules()
        if (rules.isNotEmpty()) shim = WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript(token), rules)
    }

    /**
     * How WebView delivers a callback, which decides when [gate] hears it (measured on WebView 156: see [DocumentGate]).
     * Getting it wrong either way is a bug: a posted callback judged at once could be an earlier document's, and a
     * callback made at once judged in posted order gets its safe default before our commit's completion runs.
     */
    private enum class Delivery {
        /** Posted to the main looper (JS dialogs, downloads), maybe by an earlier document just before our commit. */
        Posted,

        /** Called at once from the browser's task (console, prompts, file choosers, new windows, page navigations). */
        AtOnce,
    }

    /** Whether a callback delivered so speaks for a document the runtime may hear now. */
    private fun hears(delivery: Delivery): Boolean = when (delivery) {
        Delivery.Posted -> gate.acceptsDocumentEvents
        Delivery.AtOnce -> gate.acceptsSynchronousCallbacks
    }

    /**
     * Asks the lease's client about one of the page's requests, on main: [default] answers it at once if there is no
     * client or the runtime can't hear the document ([delivery] decides how that is judged), and later if the document
     * goes or the engine resets or dies first ([pending]); otherwise the client's answer does, once. Nothing the client
     * throws reaches the page or the app.
     */
    private fun <T> ask(key: Any, delivery: Delivery, default: () -> Unit, question: suspend (EngineClient) -> T, answer: (T) -> Unit) {
        val asked = client
        if (asked == null || !usableNow() || !hears(delivery)) {
            runCatching(default)
            return
        }
        pending.open(key, default)
        val asking = scope.launch(start = CoroutineStart.LAZY) {
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
        // Closed without the client's answer (its document went, the page withdrew it): the client stops asking, so
        // the app's dialog or prompt goes with it.
        pending.asking(key) { asking.cancel() }
        asking.start()
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
        if (usableNow() && webView.canGoBack()) step { webView.goBack() }
    }

    override fun goForward() {
        if (usableNow() && webView.canGoForward()) step { webView.goForward() }
    }

    /**
     * A step through history, by [go]. Once our navigation has committed, the page that comes back is a later commit
     * of the epoch, which [gate] hears. Before (it failed, or still loads), the gate hears nothing more in this epoch, so
     * the step starts one, and its navigation is ours: the page that comes back has a URL, a title and its bridge. A
     * load still under way is stopped by then ([boundary]), so that page signs with the new epoch's token.
     */
    private fun step(go: () -> Unit) {
        if (!gate.oursCommitted) boundary(served)
        go()
    }

    /**
     * Reloads our document. Before our navigation has committed (it failed, or still loads), WebView would reload the
     * document shown before it, an earlier page or a fresh WebView's nothing: the content loads again instead.
     */
    override fun reload() {
        if (!usableNow()) return
        if (!gate.oursCommitted) {
            served?.let(::load)
            return
        }
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
     * Only while [gate] hears that document's token: not from a boundary until its own navigation commits, nor after a
     * new document (a new token); but on, with its document, after a fragment-only load.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!usableNow()) return
        val reply = lastReply ?: return
        if (reply.origin != targetOrigin || !gate.acceptsMessage(reply.token)) return
        try {
            reply.proxy.postMessage(json)
        } catch (e: RuntimeException) {
            Log.w(TAG, "a reply proxy refused a message", e)
        }
    }

    /**
     * The page as it is on screen, at the screen's own density, 3 pixels a dp at most ([snapshotSize]), in full colour:
     * only the page, never what the app draws over it (its header, a menu, a dialog, the runtime's cover). Null until
     * the current document's first paint, and while no Surface is composed.
     * - When the runtime said nothing it knows of is over the page ([overlaysAtSnapshot]) and the view is wholly on
     *   screen, a copy of the window ([copyPixels]) is the page as it shows. PixelCopy makes it on the render thread,
     *   while a draw takes the main thread: 13 to 28 ms for a screenful on the user's phone. It also has what a draw
     *   can't reach (video, WebGL).
     * - Otherwise the page draws itself ([drawPage]), on the main thread. Every draw counts toward [PageDrawBudget], a
     *   document's first at a third of its time: one that takes longer than [MAX_PAGE_DRAW_MILLIS] is kept, since the
     *   main thread has waited for it already, but views as big are drawn smaller from then on, until good draws raise
     *   the budget again.
     * - A draw that comes out all one colour (video, WebGL: content the software path can't draw), or throws, is
     *   discarded: there is none, and the last good one stays.
     * The runtime asks for snapshots only once the view has been still a while, never while its list scrolls.
     */
    override suspend fun snapshot(): ImageBitmap? {
        val nothingOver = nothingOverPage
        nothingOverPage = false
        if (!usableNow() || !documentPainted || surfaces == 0) return null
        val view = webView
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 || view.visibility != View.VISIBLE) return null
        val density = view.resources.displayMetrics.density
        val viewWidthDp = view.width / density
        if (nothingOver && wholeOnScreen(view)) {
            // A frame first, so the runtime's cover, lifted at the paint, is gone from the window.
            awaitFrames(1)
            if (view !== webView || !documentPainted || !usableNow()) return null
            val copy = copyPixels(view, snapshotSize(view.width, view.height, viewWidthDp))
            if (copy != null) {
                Log.d(TAG, "a snapshot copied off the main thread")
                return copy.asImageBitmap()
            }
            if (view !== webView || !documentPainted || !usableNow() || !view.isAttachedToWindow) return null
        }
        val full = min(density, MAX_SNAPSHOT_SCALE)
        val scale = drawScale(viewWidthDp, view.height / density, full, PageDrawBudget.maxPixels)
        val size = snapshotSize(view.width, view.height, viewWidthDp, maxScale = scale)
        pageDraws++
        val draw = drawPage(view, size)
        val cold = drawnDocument != document
        drawnDocument = document
        Log.d(TAG, "a snapshot drawn: ${draw.nanos / 100_000 / 10.0} ms on the main thread")
        when (draw) {
            is PageDraw.Picture -> {
                counted(draw, size, cold)
                return draw.bitmap.asImageBitmap()
            }
            is PageDraw.Blank -> counted(draw, size, cold)
            is PageDraw.Failed -> Log.w(TAG, "the page failed to draw itself", draw.error)
        }
        return null
    }

    /** Counts [draw], of [size], toward [PageDrawBudget] ([cold]: its document's first), and says when it changed it. */
    private fun counted(draw: PageDraw, size: IntSize, cold: Boolean) {
        val before = PageDrawBudget.maxPixels
        val after = PageDrawBudget.drawn(size.width.toLong() * size.height, draw.nanos, cold)
        if (after == before) return
        val first = if (cold) ", its document's first draw" else ""
        Log.i(TAG, "the page took ${draw.nanos / 1_000_000} ms to draw itself at ${size.width}x${size.height}$first: at most $after pixels a draw from now on")
    }

    /** What the runtime said is over the page, for the snapshot that follows: a copy of the window only with nothing. */
    override fun overlaysAtSnapshot(cutouts: OverlayCutouts) {
        nothingOverPage = cutouts.isNone
    }

    /**
     * WebView's own state (history, scroll, form data), after a header naming the content it is of. None until the
     * content's own navigation has committed: before that (its load failed without a commit, or is under way), WebView
     * still shows the page before it, whose history saved under this content's key would restore that page in its place.
     */
    override suspend fun saveState(): ByteArray? {
        if (!usableNow()) return null
        val content = served ?: return null
        if (!gate.oursCommitted) return null
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
     * but a lone initial one), so it needs a WebView that has never navigated: a new engine's, or a reset one's, which
     * the reset built ([reset]). Only a used engine that wasn't reset builds one here, configured as the lease is.
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

    /**
     * Replaces the WebView with a fresh one in [container], set up as the current lease is, if there is one. The old one
     * is destroyed, and with it its document, history, scripts and listeners.
     */
    private fun rebuild() {
        val old = webView
        channels.clear()
        listening = false
        shim = null
        lastReply = null
        currentPage = null
        pageWithContent = null
        geolocationPrompt = null
        destroy(old)
        webView = newWebView()
        pristine = true
        freshHistory = true
        documentPainted = false
        val current = setup ?: return
        setup = null
        configure(current)
    }

    /**
     * A hidden page (off screen, or parked for the next view of it) is paused: WebView's `onPause` stops its animations,
     * geolocation and media, and tells the page it is hidden. Its JavaScript keeps running (pausing timers is
     * process-wide on Android), and its document stays as it is. Shown again, it resumes.
     */
    override fun setVisible(visible: Boolean) {
        if (!usableNow()) return
        if (visible) webView.onResume() else webView.onPause()
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]): its URL and history and its title, and its
     * first paint once it has drawn again ([awaitRepaint]). The size isn't the engine's to tell: the shim reports it,
     * again when the page is asked to announce itself. Nothing starts: no boundary, so [gate] keeps hearing the
     * document. Nothing for an engine that never started a document.
     */
    override fun replayState() {
        if (!usableNow() || pristine) return
        val view = webView
        val url = view.url ?: return
        emit(EngineEvent.UrlChanged(url))
        emit(EngineEvent.HistoryChanged(view.canGoBack(), view.canGoForward()))
        emit(EngineEvent.TitleChanged(pageTitle(view.title, url)))
        if (documentPainted) awaitRepaint(view)
    }

    /**
     * The current document's [EngineEvent.FirstPaint] again, once [view] has drawn it in a window: a WebView back in one
     * draws its background before its first new frame, so it stays INVISIBLE, and the runtime's cover stays up, until
     * its visual state callback completes there; then it draws under the cover for [FRAMES_IN_PLACE] frames, as a new
     * document does ([awaitPaint]). A later document, or a reset, makes it moot.
     */
    private fun awaitRepaint(view: EngineWebView) {
        view.visibility = View.INVISIBLE
        val painting = document
        val epoch = gate.epoch
        val request = (epoch.toLong() shl 32) or (painting.toLong() and 0xffffffffL)
        val repaint = {
            if (!released && view === webView && painting == document && epoch == gate.epoch) {
                WebViewCompat.postVisualStateCallback(view, request) { id ->
                    if (!released && view === webView && id == request && painting == document && epoch == gate.epoch) {
                        view.visibility = View.VISIBLE
                        // Drawn in its new place, under the cover, before it says so.
                        afterFrames(FRAMES_IN_PLACE) {
                            if (!released && view === webView && painting == document && epoch == gate.epoch) {
                                documentPainted = true
                                emit(EngineEvent.FirstPaint)
                            }
                        }
                    }
                }
            }
        }
        if (view.isAttachedToWindow) {
            repaint()
            return
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    repaint()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            },
        )
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
        // A configuration change that keeps the Activity (another display's density, with configChanges declared)
        // retires the engine as a move to another display does: its WebView keeps the density it was built for.
        val densityDpi = LocalConfiguration.current.densityDpi
        SideEffect { if (!released && densityDpi != 0 && densityDpi != builtDensityDpi) retire() }
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val host = LocalView.current
        var bottom by remember { mutableIntStateOf(0) }
        val sized = if (imeResizesViewport) {
            modifier
        } else {
            // An older WebView keeps its visual viewport under the keyboard: the view ends above the keyboard instead.
            val covered = imeOverlap(bottom, host.rootView.height, imeBottom)
            modifier
                .onGloballyPositioned { bottom = it.boundsInWindow().bottom.roundToInt() }
                .padding(bottom = with(density) { covered.toDp() })
        }
        AndroidView(
            factory = {
                // An engine can move between views within one frame: leave the old parent first (Plan 1's deferred
                // row), or Android throws "The specified child already has a parent".
                (container.parent as? ViewGroup)?.removeView(container)
                container
            },
            modifier = sized,
        )
        // Unconsumed, whatever the views above did with the insets: WebView 139+ shrinks its visual viewport by it.
        SideEffect { if (imeResizesViewport && !released) webView.imeBottom = imeBottom }
        // System back goes back in the page while the user is in it and it can; otherwise the app's back runs.
        BackHandler(enabled = backAvailable && pageFocused) { goBack() }
    }

    /**
     * Readies the engine for another lease, keeping every postcondition of [WebEngine.reset] or returning false (see
     * Task 15 of Plan 2a for how each is met). The runtime never cancels it; the pool bounds it at 10 s.
     * - The lease's WebView goes, and a new one takes its place: no document, no history, no scripts or listeners,
     *   nothing of the lease. A restore needs such a WebView, so building it here, as the engine is given back, means a
     *   restore into a recycled engine builds none, in the frame a view scrolls back. It replaces the about:blank load
     *   and its wait for a commit.
     * - Then an Ephemeral profile is wiped, with none of its pages left.
     * - A release, or the renderer's death, ends it at once: false.
     * - An engine that serves no further lease ([retired]) builds nothing: false.
     */
    override suspend fun reset(): Boolean {
        if (released || crashed || retired) return false
        resetting = true
        try {
            boundary(content = null, reset = true)
            client = null
            setup = null
            Inspector.want(this, false)
            rebuild()
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
        // A WebView that can't wipe it whole fails the reset: the pool destroys the engine instead of reusing its data.
        if (!EphemeralProfiles.canWipe) return false
        val profile = WebViewCompat.getProfile(webView)
        val wait = CompletableDeferred<Boolean>()
        resetWait = wait
        EphemeralProfiles.wipe(profile) { wait.complete(true) }
        val wiped = withTimeoutOrNull(RESET_WIPE_MILLIS) { wait.await() } ?: false
        resetWait = null
        if (!wiped || released || crashed) return false
        // The HTTP cache of this WebView's own browser context: its profile's, no other's (AwContents::ClearCache).
        webView.clearCache(true)
        return true
    }

    override fun release() {
        if (released) return
        released = true
        resetWait?.complete(false)
        pending.answerAll()
        letGoCatchers()
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
        if (activity.resources.configuration.densityDpi != builtDensityDpi) retire()
    }

    /**
     * An Activity was destroyed. If it is this engine's, nothing may keep it: the context lets it go, and the engine
     * serves no further lease. Idle, it goes now (spec §7: "idle spares are dropped when their Activity is destroyed").
     */
    private fun activityDestroyed(activity: Activity) {
        if (released || context.activity !== activity) return
        context.detach()
        retire()
        if (client == null && surfaces == 0) release()
    }

    /**
     * The engine serves no further lease ([retired]): its Activity is gone, or it showed on another display. A pool that
     * parked its page is told ([onUnusable]), unless the engine was unusable already.
     */
    private fun retire() {
        if (retired) return
        val dies = isUsable
        retired = true
        if (dies) onUnusable?.invoke()
    }

    /**
     * The engine is unusable for good, and says so ([EngineEvent.Crashed]): its renderer died. What the page asked gets
     * its safe default, a reset under way fails, the windows it opened are let go, and a pool that parked its page is
     * told ([onUnusable]), unless the engine was [retired] already. The WebView itself stays, for [release] to destroy:
     * a dead renderer's WebView is destroyed by the callback that reports it.
     */
    private fun crash() {
        if (released || crashed) return
        val dies = isUsable
        crashed = true
        resetWait?.complete(false)
        pending.answerAll()
        letGoCatchers()
        emit(EngineEvent.Crashed)
        // Last: a pool that parked the page releases the engine for it.
        if (dies) onUnusable?.invoke()
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
        pageFocused = focused
        emit(EngineEvent.FocusChanged(focused))
    }

    /**
     * A main-frame commit the runtime may hear: by this epoch's own navigation ([ours]), or after it. Ours that kept the
     * document (a load that only changed the fragment) paints again ([awaitPaint]): the runtime covered it at the load,
     * and only its [EngineEvent.FirstPaint] lifts the cover. A document that has painted its content already says so
     * as soon as it is drawn in place.
     */
    private fun committed(navigation: Navigation, sameDocument: Boolean, ours: Boolean) {
        val url = navigation.url
        if (!sameDocument) {
            document++
            documentPainted = false
            currentPage = navigation.page
            // Its content may have painted before this completion ran.
            if (currentPage != null && currentPage === pageWithContent) contentPainted = document
            if (freshHistory) {
                // History begins with the lease's first document: a reset's about:blank is not behind it.
                webView.clearHistory()
                freshHistory = false
            }
        }
        emit(EngineEvent.UrlChanged(url))
        emit(EngineEvent.HistoryChanged(webView.canGoBack(), webView.canGoForward()))
        backAvailable = webView.canGoBack()
        // What the document logged before this completion ran, now that the runtime has heard it commit.
        earlyConsole.forEach { emit(it) }
        earlyConsole.clear()
        if (sameDocument) {
            if (ours) {
                handingOff = -1
                awaitPaint()
            }
            return
        }
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

    /**
     * Shows the committed document, and says so ([EngineEvent.FirstPaint]) once it is drawn in its place with its content:
     * the runtime lifts its cover then, and no frame shows less than the page.
     * - Its commit's visual state callback makes the WebView VISIBLE: it draws under the cover from then on. That
     *   callback only promises the document as it was at commit, which is its background: drawn then, the first frame
     *   without the cover showed the page's bare background for a frame (white, or a dark page's own dark).
     * - Its first contentful paint, or its load event, says it has content; or [CONTENT_WAIT_MILLIS] after it is drawn,
     *   with nothing, it is shown anyway.
     * - Then a visual state callback, posted after the content, and [FRAMES_IN_PLACE] frames drawn after it: the WebView
     *   has drawn that content in its place, under the cover.
     */
    private fun awaitPaint() {
        val view = webView
        val painting = document
        val request = (gate.epoch.toLong() shl 32) or (painting.toLong() and 0xffffffffL)
        WebViewCompat.postVisualStateCallback(view, request) { id ->
            if (id != request || !stillPainting(view, painting)) return@postVisualStateCallback
            view.visibility = View.VISIBLE
            drawnUnderCover = painting
            main.postDelayed({
                if (stillPainting(view, painting) && contentPainted != painting) {
                    contentPainted = painting
                    handOff(view, painting)
                }
            }, CONTENT_WAIT_MILLIS)
            handOff(view, painting)
        }
    }

    /** Whether [view] still shows the document [painting], which hasn't said it painted, and the runtime hears it. */
    private fun stillPainting(view: EngineWebView, painting: Int): Boolean =
        !released && view === webView && painting == document && !documentPainted && gate.acceptsDocumentEvents

    /** [page] painted content: its first contentful paint, or its load event. */
    private fun contentFrom(page: Page) {
        pageWithContent = page
        if (page !== currentPage || contentPainted == document) return
        contentPainted = document
        handOff(webView, document)
    }

    /**
     * Once [painting] is drawn under the cover and has content: a visual state callback, then [FRAMES_IN_PLACE] frames,
     * and its [EngineEvent.FirstPaint]. Once per document.
     */
    private fun handOff(view: EngineWebView, painting: Int) {
        if (!stillPainting(view, painting) || drawnUnderCover != painting || contentPainted != painting) return
        if (handingOff == painting) return
        handingOff = painting
        val request = (gate.epoch.toLong() shl 32) or (painting.toLong() and 0xffffffffL)
        WebViewCompat.postVisualStateCallback(view, request) { id ->
            if (id != request || !stillPainting(view, painting)) return@postVisualStateCallback
            afterFrames(FRAMES_IN_PLACE) {
                if (!stillPainting(view, painting)) return@afterFrames
                documentPainted = true
                emit(EngineEvent.FirstPaint)
            }
        }
    }

    /** Runs [then] on main once [frames] more frames have been drawn: at the start of the frame after the last of them. */
    private fun afterFrames(frames: Int, then: () -> Unit) {
        Choreographer.getInstance().postFrameCallback {
            if (frames <= 1) then() else afterFrames(frames - 1, then)
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

    /**
     * A WebView catching the first navigation of a window the page opened in [epoch], as [onCreateWindow] hands it over.
     * - The catch is the lease's that heard the page ask: heard only in the epoch that opened it, so a window that
     *   navigates after a boundary or a reset (a meta refresh, a script) reaches no client, least of all the next lease's.
     *   Every boundary, reset and release lets the catchers go anyway.
     * - It shares the app's one renderer: when that dies, it goes too, and says so (`onRenderProcessGone` true), or
     *   WebView would kill the app.
     */
    private inner class Catcher(private val epoch: Int, private val gesture: Boolean) {
        val view = WebView(context)
        private var done = false

        init {
            profileName?.let { WebViewCompat.setProfile(view, it) }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!done && epoch == gate.epoch && usableNow()) newWindow(request.url.toString(), gesture)
                    main.post(::letGo)
                    return true
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    letGo()
                    return true
                }
            }
        }

        fun letGo() {
            if (done) return
            done = true
            catchers.remove(this)
            try {
                view.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "destroying a new window's WebView failed", e)
            }
        }
    }

    /** Lets every new window's catcher go: what navigates there from now on is nobody's to hear of. */
    private fun letGoCatchers() {
        if (catchers.isEmpty()) return
        catchers.toList().forEach(Catcher::letGo)
    }

    /**
     * A page that blocks the renderer stalls every web view on it: Android shares one renderer across an app's WebViews
     * (spec §4). Once WebView has found it unresponsive (5 s), the engine kills it; every engine on it then reports
     * Crashed, and the views on screen go live again by themselves.
     */
    private inner class Hangs(private val owner: WebView) : WebViewRenderProcessClient() {
        override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
            if (owner !== webView || released || renderer == null) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) return
            Log.w(TAG, "the renderer stopped answering: terminating it")
            renderer.terminate()
        }

        override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
    }

    /**
     * Page messages: the main frame's, signed with this epoch's token, from a document the gate lets the runtime hear,
     * of an origin on the bridge's list now (a channel stays when its origin is taken off: [BridgeChannels]).
     */
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
            if (origin !in channels.allowed) return
            lastReply = Reply(replyProxy, origin, signedWith)
            emit(EngineEvent.PageMessage(origin, json), document = replyProxy)
        }
    }

    /**
     * The same navigations, heard at once rather than posted: when our navigation commits in the browser, the callbacks
     * WebView makes at once speak for our document from then on ([DocumentGate.acceptsSynchronousCallbacks]).
     */
    private inner class LiveNavigations(private val owner: WebView) : NavigationListener {
        override fun onNavigationStarted(navigation: Navigation) {
            if (owner !== webView || released) return
            gate.liveNavigationStarted(navigation, navigation.wasInitiatedByPage(), navigation.isSameDocument)
        }

        override fun onNavigationCompleted(navigation: Navigation) {
            if (owner !== webView || released) return
            gate.liveNavigationCompleted(navigation, navigation.wasInitiatedByPage(), navigation.didCommit(), navigation.isSameDocument)
        }
    }

    private inner class Navigations(private val owner: WebView) : NavigationListener {
        override fun onNavigationStarted(navigation: Navigation) {
            if (owner !== webView || released) return
            gate.navigationStarted(navigation, navigation.wasInitiatedByPage())
        }

        override fun onFirstContentfulPaintMillis(page: Page, millis: Long) {
            if (owner !== webView || released) return
            contentFrom(page)
        }

        override fun onPageLoadEvent(page: Page) {
            if (owner !== webView || released) return
            contentFrom(page)
        }

        override fun onNavigationCompleted(navigation: Navigation) {
            if (owner !== webView || released) return
            val completion = gate.navigationCompleted(
                navigation,
                byPage = navigation.wasInitiatedByPage(),
                didCommit = navigation.didCommit(),
                sameDocument = navigation.isSameDocument,
            )
            // Nothing of a reset is reported.
            if (resetting) return
            when (completion) {
                DocumentGate.Completion.Ignore -> Unit
                DocumentGate.Completion.OursFailed -> {
                    emit(EngineEvent.Error(failureFor(navigation.url)))
                    // Its load is over: the progress heard of it ends too, since none of the page left is heard.
                    emit(EngineEvent.Progress(null))
                }
                is DocumentGate.Completion.Ours -> committed(navigation, completion.sameDocument, ours = true)
                is DocumentGate.Completion.Later -> committed(navigation, completion.sameDocument, ours = false)
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            localResponse(request.url.toString(), request.method)

        /**
         * A navigation the page started, or a server redirect of any navigation (WebView asks for no other: not for
         * loadUrl, reload or history themselves).
         * - A main-frame redirect of a navigation the app started (load, reload, restore, back, forward) is followed
         *   without asking: the client decides no navigation the app starts. To the web only: a redirect to any other
         *   scheme ends the load. Without this, any URL that redirects (`/docs` to `/docs/`, a short link, a login page)
         *   failed to load, since WebView asks before our commit, while the client can't be.
         * - Otherwise the client answers synchronously; anything but Allow cancels it, OpenExternally included, since
         *   the runtime opens the URL. A document the runtime can't hear, or a lease without a client, gets the safe
         *   default: blocked. WebView asks at once, so it is the current document's from our commit on.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== webView || !usableNow()) return true
            if (request.isForMainFrame && request.isRedirect && gate.appNavigationLoading) return !isWebUrl(request.url.toString())
            val asked = client
            if (asked == null || !hears(Delivery.AtOnce)) return true
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
            if (view === webView) crash()
            (view as? EngineWebView)?.let(::destroy)
            return true
        }
    }

    private inner class Chrome(private val owner: WebView) : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ask(result, Delivery.Posted, default = { result.confirm() }, question = { it.onDialog(WebDialog.Alert(message.orEmpty(), url)) }) {
                result.confirm()
            }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ask(result, Delivery.Posted, default = { result.cancel() }, question = { it.onDialog(WebDialog.Confirm(message.orEmpty(), url)) }) { answer ->
                if (answer == DialogResult.Confirmed) result.confirm() else result.cancel()
            }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            val dialog = WebDialog.Prompt(message.orEmpty(), defaultValue.orEmpty(), url)
            ask(result, Delivery.Posted, default = { result.cancel() }, question = { it.onDialog(dialog) }) { answer ->
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
            ask(result, Delivery.Posted, default = { result.confirm() }, question = { it.onDialog(WebDialog.BeforeUnload(message.orEmpty(), url)) }) { answer ->
                if (answer == DialogResult.Dismissed) result.cancel() else result.confirm()
            }
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
            val request = FileChooserRequest(params.acceptTypes.filter { it.isNotBlank() }, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            ask(callback, Delivery.AtOnce, default = { callback.onReceiveValue(null) }, question = { it.onFileChooser(request) }) { chosen ->
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
            ask(request, Delivery.AtOnce, default = { request.deny() }, question = { it.onPermission(asked) }) { granted ->
                if (granted) request.grant(request.resources) else request.deny()
            }
        }

        override fun onPermissionRequestCanceled(request: AndroidPermissionRequest) = pending.withdraw(request)

        /** Never retained: grants end with the prompt, so none outlives the lease. */
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            geolocationPrompt = callback
            val asked = PermissionRequest(originOf(origin), setOf(PermissionKind.Geolocation))
            ask(callback, Delivery.AtOnce, default = { callback.invoke(origin, false, false) }, question = { it.onPermission(asked) }) { granted ->
                callback.invoke(origin, granted, false)
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            geolocationPrompt?.let(pending::withdraw)
            geolocationPrompt = null
        }

        /**
         * window.open and target=_blank. WebView reports the new window's URL only through its first navigation, so a
         * throwaway WebView (same context, same profile) catches it, and is destroyed ([Catcher]).
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (view !== webView || client == null || !hears(Delivery.AtOnce)) return false
            val catcher = Catcher(gate.epoch, isUserGesture)
            catchers += catcher
            // A window that never navigates (window.open() and document.write) is let go after a while.
            main.postDelayed(catcher::letGo, NEW_WINDOW_MILLIS)
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher.view
            resultMsg.sendToTarget()
            return true
        }
        /** Our load's progress, and its documents': not an earlier document's, nor what still loads after ours failed. */
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== webView || !gate.acceptsProgress) return
            emit(EngineEvent.Progress(if (newProgress >= 100) null else newProgress / 100f))
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (view !== webView || !gate.acceptsDocumentEvents) return
            emit(EngineEvent.TitleChanged(pageTitle(title, view.url)))
        }

        /**
         * WebView reports console output at once, so it is the current document's from our commit on. What comes before
         * the posted completion of that commit runs waits for it ([earlyConsole]): the runtime hears the commit first.
         */
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (owner !== webView || !hears(Delivery.AtOnce) || documentHasShim()) return true
            val line = EngineEvent.Console(consoleMessage(message))
            when {
                hears(Delivery.Posted) -> emit(line)
                earlyConsole.size < MAX_EARLY_CONSOLE_LINES -> earlyConsole += line
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
