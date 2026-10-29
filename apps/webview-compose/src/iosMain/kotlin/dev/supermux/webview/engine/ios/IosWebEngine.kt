package dev.supermux.webview.engine.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.LocalWebSurfaceHints
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.decideNavigation
import dev.supermux.webview.monotonicClock
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.Foundation.URLPathAllowedCharacterSet
import platform.Foundation.create
import platform.Foundation.distantPast
import platform.Foundation.setValue
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIUserInterfaceStyle
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKBackForwardListItem
import platform.WebKit.WKContentWorld
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeBackForward
import platform.WebKit.WKNavigationTypeFormResubmitted
import platform.WebKit.WKNavigationTypeFormSubmitted
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKNavigationTypeReload
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The iOS engine: one `WKWebView` (spec §7, "iOS: native view under the canvas"), following the [WebEngine] contract.
 * Main thread only, as WebKit is: every delegate callback arrives there, and so does every event.
 *
 * - **Events** go through an [EngineEventQueue]. An epoch starts at every load, reload, restore and reset. A
 *   navigation's callbacks carry the epoch it started in; the committed document's title and URL changes carry its
 *   commit's; the relay's messages count only from the current document's token ([RelayMessage]). What an earlier
 *   epoch caused is dropped ([push]); focus changes are the view's, not a document's, and only a reset drops them.
 * - **The bridge**: [WebKitScripts], installed by [configure]. Host messages go out through `callAsyncJavaScript` in
 *   the isolated [world], which checks the document's origin when they arrive.
 * - **Local content**: `Html` with `loadHTMLString(_:baseURL:)` on its `https://<hash>.invalid` origin; `Bundle` from
 *   [bundles] on `webview-bundle://<host>.invalid`, reported as `https://<host>.invalid` ([WebKitUrls]).
 * - **Reuse**: [reset] puts the floor state back, so the back-forward list is the floor alone, which no navigation but
 *   a reset reaches; it answers what is pending, drops the lease's scripts and wipes an ephemeral store.
 * - **Parking**: a parked page keeps its web view and document as they are, out of any window and with its media
 *   suspended ([setVisible]). Adopted, it replays its page ([replayState]), and its first paint waits until the web
 *   view, in a window again, has drawn the page there ([awaitRepaint]).
 */
internal class IosWebEngine private constructor(
    val config: EngineConfig,
    internal val webView: WKWebView,
    private val world: WKContentWorld,
    private val scripts: WebKitScripts,
    private val bundles: BundleSchemeHandler,
    private val relay: RelayHandler,
    private val navigationDelegate: NavigationDelegate,
    private val uiDelegate: UiDelegate,
    private val scope: CoroutineScope,
) : WebEngine {
    /** A question the engine asked its client, which WebKit waits on: answered once, by the client or by default. */
    private inner class Pending(private val byDefault: () -> Unit) {
        var job: Job? = null
        private var answered = false

        fun answer(block: () -> Unit) {
            if (answered) return
            answered = true
            questions -= this
            block()
        }

        fun answerByDefault() = answer(byDefault)
    }

    private val queue = EngineEventQueue()
    private val clock = monotonicClock()
    private val questions = mutableListOf<Pending>()
    private val navigations = HashMap<WKNavigation, Int>()
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])

    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null
    private var own: OwnNavigation? = null
    private var committedEpoch = 0
    private var committedUrl: String? = null

    /**
     * The current document's token, from its relay's `start`. Also the identity the queue collapses the document's
     * repeated `$ready`s by: one instance per document.
     */
    private var document: String? = null
    private var documentPainted = false
    private var paintOnScreen = false
    private var httpError: WebError? = null
    private var focused: Boolean? = null
    private var lastTouch: Long? = null
    private var history: EngineEvent.HistoryChanged? = null
    private var floorState: Any? = null
    private var floorDone: CompletableDeferred<Boolean>? = null
    private var baseUserAgent: String? = null
    private var container: EngineContainerView? = null

    /** Whether the page's media is suspended now, while it is hidden ([setVisible]). */
    private var mediaSuspended = false

    /** A replayed first paint, waiting for the page to be drawn again in a window ([awaitRepaint]). */
    private var repaint: Repaint? = null

    /** A replay's first paint of the document of [epoch], once it [started] waiting for the page to draw. */
    private class Repaint(val epoch: Int) {
        var started = false
    }

    override val mode: EmbeddingMode get() = EmbeddingMode.NativeView
    override val events: Flow<EngineEvent> get() = queue.events
    override val isUsable: Boolean get() = !released && !crashed
    override val needsCutouts: Boolean get() = false

    override var client: EngineClient? = null
        set(value) {
            if (!released) field = value
        }

    private val bundle: WebOrigin? get() = (content as? WebContent.Bundle)?.origin
    private val htmlUrl: String? get() = (content as? WebContent.Html)?.origin?.url

    init {
        relay.engine = this
        navigationDelegate.engine = this
        uiDelegate.engine = this
        webView.navigationDelegate = navigationDelegate
        webView.UIDelegate = uiDelegate
    }

    /** Loads the floor and keeps its state for [reset], and learns the default user agent. False if the engine can't. */
    internal suspend fun warmUp(): Boolean {
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(WebKitUrls.FLOOR_URL)!!))
        val loaded = withTimeoutOrNull(WARM_UP_MILLIS) { done.await() } ?: false
        floorDone = null
        if (!loaded || released || crashed) return false
        floorState = webView.interactionState
        baseUserAgent = evaluateIn(WKContentWorld.defaultClientWorld, "navigator.userAgent")
            ?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.content }
        // Nothing from before the first lease reaches it: as after a reset.
        queue.advance(reset = true)
        return floorState != null
    }

    // --- The lease ---

    override fun configure(setup: PageSetup) {
        if (!leaseCallAllowed()) return
        this.setup = setup
        webView.overrideUserInterfaceStyle =
            if (setup.dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        val background = when {
            setup.transparentBackground -> UIColor.clearColor
            setup.dark -> UIColor.blackColor
            else -> UIColor.whiteColor
        }
        webView.setOpaque(!setup.transparentBackground)
        webView.backgroundColor = background
        webView.scrollView.backgroundColor = background
        webView.customUserAgent = setup.userAgentSuffix?.let { suffix -> listOfNotNull(baseUserAgent, suffix).joinToString(" ") }
        if (osAtLeast(16, 4)) webView.setInspectable(setup.inspectable)
        val controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        val origins = WebKitUrls.pageOrigins(setup.bridgeOrigins)
        controller.addUserScript(WKUserScript(scripts.relay(origins), WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true, world))
        if (setup.javaScriptEnabled) {
            controller.addUserScript(
                WKUserScript(scripts.shim(origins), WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true, WKContentWorld.pageWorld),
            )
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        val epoch = startDocument(content)
        val navigation = when (content) {
            is WebContent.Url -> {
                val url = NSURL.URLWithString(content.url)
                if (url == null) {
                    push(epoch, EngineEvent.Error(WebError.Network(content.url, "WebKit can't parse this URL")))
                    return
                }
                own = OwnNavigation.Load(content.url)
                val request = NSMutableURLRequest(uRL = url)
                for ((name, value) in content.headers) request.setValue(value, forHTTPHeaderField = name)
                webView.loadRequest(request)
            }
            is WebContent.Html -> {
                own = OwnNavigation.Load(content.origin.url)
                webView.loadHTMLString(content.html, baseURL = NSURL.URLWithString(content.origin.url))
            }
            is WebContent.Bundle -> {
                val url = WebKitUrls.bundleUrl(content.origin, escapePath(content.entry))
                own = OwnNavigation.Load(url)
                webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!))
            }
        }
        if (navigation != null) navigations[navigation] = epoch
    }

    override fun reload() {
        if (released || resetting) return
        val shown = content ?: return
        // WebKit would fetch in-memory HTML from its origin, which doesn't resolve: load it again instead.
        if (shown is WebContent.Html) {
            load(shown)
            return
        }
        val epoch = startDocument(shown)
        own = OwnNavigation.Reload
        webView.reload()?.let { navigations[it] = epoch }
    }

    override fun goBack() {
        if (released || resetting || !WebKitUrls.canGoBack(urls(webView.backForwardList.backList))) return
        own = OwnNavigation.History
        webView.goBack()?.let { navigations[it] = queue.epoch }
    }

    override fun goForward() {
        if (released || resetting || !WebKitUrls.canGoForward(urls(webView.backForwardList.forwardList))) return
        own = OwnNavigation.History
        webView.goForward()?.let { navigations[it] = queue.epoch }
    }

    override fun stop() {
        if (!released) webView.stopLoading()
    }

    override suspend fun evaluate(script: String): String? = if (released) null else evaluateIn(WKContentWorld.pageWorld, script)

    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        val arguments = mapOf<Any?, Any?>("json" to json, "target" to WebKitUrls.pageOrigin(targetOrigin, bundle), "name" to scripts.inEvent)
        webView.callAsyncJavaScript(scripts.deliveryBody, arguments = arguments, inFrame = null, inContentWorld = world, completionHandler = null)
    }

    override suspend fun snapshot(): ImageBitmap? {
        if (released || !documentPainted) return null
        val view = container ?: return null
        if (view.window == null || webView.superview !== view) return null
        val width = view.bounds.useContents { size.width }
        val height = view.bounds.useContents { size.height }
        if (width < 1.0 || height < 1.0) return null
        val configuration = WKSnapshotConfiguration()
        configuration.snapshotWidth = NSNumber(double = snapshotWidthPoints(width, view.traitCollection.displayScale))
        val image = suspendCancellableCoroutine<UIImage?> { continuation ->
            webView.takeSnapshotWithConfiguration(configuration) { image, _ -> if (continuation.isActive) continuation.resume(image) }
        }
        if (released || image == null) return null
        return image.toImageBitmap()
    }

    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val shown = content ?: return null
        // A document of in-memory HTML can't be restored: WebKit would fetch it from its origin, which doesn't resolve.
        if (shown is WebContent.Html) return null
        val state = webView.interactionState as? NSData ?: return null
        return SavedWebKitState.encode(shown.key, state.toByteArray())
    }

    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = SavedWebKitState.decode(state, content.key) ?: return false
        startDocument(content)
        own = OwnNavigation.Restore
        val before = webView.backForwardList.currentItem
        webView.interactionState = saved.toNSData()
        // WebKit ignores state it can't decode; the list then still has the item it had.
        if (webView.backForwardList.currentItem === before) {
            own = null
            return false
        }
        return true
    }

    /**
     * A hidden page (off screen, or parked for the next view of it) has its media suspended: what plays stops, and the
     * page can't start it again until it is shown, when what was playing resumes. Its document stays as it is. WebKit
     * throttles a page out of any window (a parked one) by itself: its timers, and its animation frames, which stop.
     */
    override fun setVisible(visible: Boolean) {
        if (released || crashed || mediaSuspended == !visible) return
        mediaSuspended = !visible
        // An empty handler, never null: iOS 16's WebKit calls it unconditionally.
        webView.setAllMediaPlaybackSuspended(!visible) {}
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]): its URL (a same-document change included),
     * its history and its title, and, if the document had painted, its first paint again once the web view, in a window
     * again, has drawn it there ([awaitRepaint]). The size is the shim's to tell, again when the page is asked to
     * announce itself. Nothing starts: no epoch, so the relay's token still names the document. Nothing without a
     * committed document of the current epoch: a load under way reports its own commit.
     */
    override fun replayState() {
        if (!leaseCallAllowed() || crashed || content == null || committedEpoch != queue.epoch) return
        val url = committedUrl ?: return
        if (WebKitUrls.isFloor(url)) return
        val epoch = queue.epoch
        push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        val list = webView.backForwardList
        val now = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(urls(list.backList)), WebKitUrls.canGoForward(urls(list.forwardList)))
        history = now
        push(epoch, now)
        push(epoch, EngineEvent.TitleChanged(webView.title?.takeIf { it.isNotEmpty() }))
        // The new view has heard no focus yet: the page's next one is news to it.
        focused = null
        if (documentPainted) awaitRepaint(epoch)
    }

    /**
     * Nothing to do: `takeSnapshot` renders the page itself, never what the app draws over it, so nothing over the page
     * can end up in a snapshot.
     */
    override fun overlaysAtSnapshot(cutouts: OverlayCutouts) = Unit

    override fun requestFocus() {
        if (!released) webView.becomeFirstResponder()
    }

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        val inList = LocalWebSurfaceHints.current.inScrollingList
        val properties = remember(inList) {
            UIKitInteropProperties(
                // An embed in a list lets a drag that starts on it scroll the list; a pane gives its page every touch.
                interactionMode = if (inList) UIKitInteropInteractionMode.Cooperative() else UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            )
        }
        UIKitView(
            factory = { EngineContainerView(this).also(::attach) },
            modifier = modifier,
            onRelease = ::detach,
            properties = properties,
        )
    }

    // --- Reuse ---

    override suspend fun reset(): Boolean {
        if (released || crashed || resetting) return false
        resetting = true
        resetFailed = true
        // A reset's epochs drop the last lease's queued focus changes too: the next lease's view would take them.
        queue.advance(reset = true)
        client = null
        setup = null
        content = null
        bundles.bundle = null
        answerAllByDefault()
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        webView.stopLoading()
        // Empty handlers, never null: iOS 16's WebKit calls them unconditionally, and a null one kills the app.
        webView.pauseAllMediaPlaybackWithCompletionHandler {}
        webView.closeAllMediaPresentationsWithCompletionHandler {}
        webView.configuration.userContentController.removeAllUserScripts()
        val floorShown = showFloor()
        repaint = null
        // The next lease's page plays its media: what was suspended while hidden went with the last page.
        if (mediaSuspended) {
            mediaSuspended = false
            webView.setAllMediaPlaybackSuspended(false) {}
        }
        val wiped = config.profile != WebProfile.Ephemeral || wipeStore()
        resetting = false
        if (released || crashed || !floorShown || !wiped) return false
        // The floor's own events, and anything else from before or during the reset, stay behind.
        queue.advance(reset = true)
        own = null
        document = null
        committedUrl = null
        documentPainted = false
        paintOnScreen = false
        httpError = null
        focused = null
        history = null
        resetFailed = false
        return true
    }

    /** Puts the floor state back: the back-forward list is the floor alone, then. True once it has loaded. */
    private suspend fun showFloor(): Boolean {
        val state = floorState ?: return false
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        webView.interactionState = state
        val shown = withTimeoutOrNull(FLOOR_MILLIS) { done.await() } ?: false
        floorDone = null
        return shown && urls(webView.backForwardList.backList).isEmpty() && WebKitUrls.isFloor(webView.URL?.absoluteString)
    }

    /** Wipes this engine's own ephemeral store: no other engine uses it. */
    private suspend fun wipeStore(): Boolean = withTimeoutOrNull(WIPE_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            webView.configuration.websiteDataStore.removeDataOfTypes(
                WKWebsiteDataStore.allWebsiteDataTypes(),
                modifiedSince = NSDate.distantPast,
            ) { if (continuation.isActive) continuation.resume(true) }
        }
    } ?: false

    override fun release() {
        if (released) return
        answerAllByDefault()
        released = true
        client = null
        scope.cancel()
        bundles.bundle = null
        webView.stopLoading()
        val controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        controller.removeAllScriptMessageHandlers()
        relay.engine = null
        navigationDelegate.engine = null
        uiDelegate.engine = null
        webView.navigationDelegate = null
        webView.UIDelegate = null
        webView.removeFromSuperview()
        container = null
    }

    // --- The surface ---

    private fun attach(view: EngineContainerView) {
        view.adopt(webView)
        container = view
    }

    private fun detach(view: EngineContainerView) {
        view.letGo(webView)
        if (container === view) container = null
    }

    /** The container the surface composed moved into a window or out of one. */
    internal fun onSurfaceWindowChanged(view: EngineContainerView) {
        if (released || container !== view || view.window == null) return
        repaint?.let(::drawAgain)
        if (!paintOnScreen) return
        paintOnScreen = false
        markPainted(committedEpoch)
    }

    /**
     * A replay's [EngineEvent.FirstPaint], once the page has been drawn in the web view's new place: a web view back in
     * a window shows its background until its page draws again, so the view's cover stays up until then. Now if the
     * surface is in a window already, otherwise once it is ([onSurfaceWindowChanged]). A later document, or a reset,
     * makes it moot.
     */
    private fun awaitRepaint(epoch: Int) {
        val wait = Repaint(epoch)
        repaint = wait
        val view = container
        if (view != null && view.window != null && webView.superview === view) drawAgain(wait)
    }

    /**
     * Waits two animation frames in the isolated world, as the relay does for a document's first paint: they run only
     * while the web view is in a window, once the page has been drawn there. A document where no script runs (a PDF)
     * counts as drawn at once, as its first paint did.
     */
    private fun drawAgain(wait: Repaint) {
        if (wait.started) return
        wait.started = true
        webView.callAsyncJavaScript(
            REPAINT_BODY,
            arguments = emptyMap<Any?, Any?>(),
            inFrame = null,
            inContentWorld = world,
        ) { _, _ ->
            if (!released && repaint === wait && queue.epoch == wait.epoch) {
                repaint = null
                push(wait.epoch, EngineEvent.FirstPaint)
            }
        }
    }

    /** The user touched the page: it is in use, and a navigation it starts in the next second is theirs. */
    internal fun onTouch() {
        if (released) return
        lastTouch = clock()
        setFocused(queue.epoch, true)
    }

    // --- Navigation ---

    internal fun decideAction(
        action: WKNavigationAction,
        preferences: WKWebpagePreferences,
        decisionHandler: (WKNavigationActionPolicy, WKWebpagePreferences?) -> Unit,
    ) {
        val allow = WKNavigationActionPolicy.WKNavigationActionPolicyAllow
        val cancel = WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        if (released) return decisionHandler(cancel, preferences)
        preferences.allowsContentJavaScript = setup?.javaScriptEnabled ?: true
        val url = action.request.URL?.absoluteString
        val target = action.targetFrame
        val isMainFrame = target?.mainFrame ?: true
        if (action.shouldPerformDownload && url != null) {
            askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), null, null, null))
            return decisionHandler(cancel, preferences)
        }
        val verdict = decideNavigation(url, isMainFrame, target == null, kindOf(action.navigationType), isUserGesture(), own, bundle, htmlUrl)
        when (verdict) {
            NavigationVerdict.Own -> {
                own = null
                decisionHandler(allow, preferences)
            }
            NavigationVerdict.Allow -> decisionHandler(allow, preferences)
            NavigationVerdict.Refuse -> decisionHandler(cancel, preferences)
            NavigationVerdict.ReloadHtml -> {
                decisionHandler(cancel, preferences)
                val html = content as? WebContent.Html ?: return
                own = OwnNavigation.Load(html.origin.url)
                webView.loadHTMLString(html.html, baseURL = NSURL.URLWithString(html.origin.url))
            }
            is NavigationVerdict.Ask -> {
                // Synchronous, as WebKit's policy questions are answered: the runtime's client answers at once.
                val decision = try {
                    client?.onNavigation(verdict.request)
                } catch (e: Exception) {
                    null
                }
                decisionHandler(if (decision == NavigationDecision.Allow) allow else cancel, preferences)
            }
        }
    }

    internal fun decideResponse(response: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
        val allow = WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
        if (released) return decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
        val http = response.response as? NSHTTPURLResponse
        val disposition = http?.allHeaderFields?.entries?.firstOrNull { (it.key as? String).equals("Content-Disposition", ignoreCase = true) }?.value as? String
        val attachment = disposition?.trim()?.startsWith("attachment", ignoreCase = true) == true
        if (!response.forMainFrame || response.canShowMIMEType && !attachment) {
            if (response.forMainFrame && http != null && http.statusCode >= 400) {
                httpError = WebError.Http(response.response.URL?.absoluteString?.let { WebKitUrls.toLibraryUrl(it, bundle) }, http.statusCode.toInt())
            }
            return decisionHandler(allow)
        }
        // A download. The client hears of it; the engine saves nothing, either way (as Android's DownloadListener).
        val url = response.response.URL?.absoluteString
        if (url != null) {
            val length = response.response.expectedContentLength.takeIf { it >= 0 }
            askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), response.response.suggestedFilename, response.response.MIMEType, length))
        }
        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
    }

    internal fun onStarted(navigation: WKNavigation?) {
        if (released) return
        if (navigation != null && navigation !in navigations) navigations[navigation] = queue.epoch
        push(epochOf(navigation), EngineEvent.Progress(START_PROGRESS))
    }

    internal fun onCommitted(navigation: WKNavigation?) {
        if (released) return
        val epoch = epochOf(navigation)
        committedEpoch = epoch
        own = null
        document = null
        documentPainted = false
        paintOnScreen = false
        // A new document paints for itself: a replay's wait for the old one's is moot.
        repaint = null
        val url = webView.URL?.absoluteString
        committedUrl = url
        if (httpError?.url != url?.let { WebKitUrls.toLibraryUrl(it, bundle) }) httpError = null
        push(epoch, EngineEvent.UrlChanged(url?.let { WebKitUrls.toLibraryUrl(it, bundle) }))
        push(epoch, EngineEvent.TitleChanged(webView.title?.takeIf { it.isNotEmpty() }))
        push(epoch, EngineEvent.Progress(maxOf(COMMIT_PROGRESS, webView.estimatedProgress.toFloat())))
        pushHistory()
    }

    internal fun onFinished(navigation: WKNavigation?) {
        if (released) return
        val epoch = epochOf(navigation)
        navigation?.let(navigations::remove)
        if (WebKitUrls.isFloor(webView.URL?.absoluteString)) floorDone?.complete(true)
        push(epoch, EngineEvent.Progress(null))
        if (epoch == committedEpoch) push(epoch, EngineEvent.TitleChanged(webView.title?.takeIf { it.isNotEmpty() }))
        // A document without the relay (a PDF, an image) has no paint report: it counts as painted once on screen.
        if (epoch == committedEpoch && document == null && !documentPainted) {
            if (container?.window != null) markPainted(epoch) else paintOnScreen = true
        }
    }

    internal fun onFailed(navigation: WKNavigation?, error: NSError) {
        if (released) return
        val epoch = epochOf(navigation)
        navigation?.let(navigations::remove)
        floorDone?.complete(false)
        own = null
        push(epoch, EngineEvent.Progress(null))
        // A navigation replaced by another, or one the engine itself turned into a download, isn't an error.
        if (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) return
        if (error.domain == WEBKIT_ERROR_DOMAIN && error.code == FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE) return
        val failing = (error.userInfo[FAILING_URL_KEY] as? String) ?: webView.URL?.absoluteString
        push(epoch, EngineEvent.Error(webErrorOf(error, failing?.let { WebKitUrls.toLibraryUrl(it, bundle) })))
    }

    internal fun onRendererGone() {
        if (released || crashed) return
        crashed = true
        floorDone?.complete(false)
        push(queue.epoch, EngineEvent.Crashed)
    }

    internal fun onNewWindow(action: WKNavigationAction) {
        if (released) return
        val url = action.request.URL?.absoluteString
        if (url.isNullOrEmpty() || WebKitUrls.isPrivate(url) && !(bundle?.let { WebKitUrls.isBundleUrl(url, it) } ?: false)) return
        val decision = try {
            client?.onNewWindow(NewWindowRequest(WebKitUrls.toLibraryUrl(url, bundle), isUserGesture()))
        } catch (e: Exception) {
            null
        }
        if (decision != NewWindowDecision.OpenHere) return
        own = OwnNavigation.Load(url)
        webView.loadRequest(action.request)
    }

    // --- The relay ---

    internal fun onRelayMessage(message: WKScriptMessage) {
        if (released || !message.frameInfo.mainFrame) return
        val parsed = RelayMessage.parse(message.body as? String ?: return) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        val current = document
        if (parsed.document != current) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> markPainted(epoch)
            is RelayMessage.Focus -> setFocused(epoch, parsed.focused)
            is RelayMessage.Title -> push(epoch, EngineEvent.TitleChanged(webView.title?.takeIf { it.isNotEmpty() }))
            is RelayMessage.Url -> onSameDocumentChange(epoch)
            // The document's own token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(epoch, EngineEvent.PageMessage(originOf(message.frameInfo.securityOrigin), parsed.json), current)
            is RelayMessage.Start -> Unit
        }
    }

    private fun markPainted(epoch: Int) {
        if (documentPainted) return
        documentPainted = true
        push(epoch, EngineEvent.FirstPaint)
        httpError?.let {
            httpError = null
            push(epoch, EngineEvent.Error(it))
        }
    }

    private fun setFocused(epoch: Int, value: Boolean) {
        if (focused == value) return
        focused = value
        push(epoch, EngineEvent.FocusChanged(value))
    }

    /**
     * The relay saw the URL change without a new document (`pushState`, a fragment, a same-document history step). WebKit's
     * own URL counts, and only once it names the current history item, as it does for a same-document navigation.
     */
    private fun onSameDocumentChange(epoch: Int) {
        val url = webView.URL?.absoluteString
        if (url != null && url != committedUrl && url == webView.backForwardList.currentItem?.URL?.absoluteString) {
            committedUrl = url
            push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        }
        pushHistory()
    }

    private fun pushHistory() {
        val list = webView.backForwardList
        val next = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(urls(list.backList)), WebKitUrls.canGoForward(urls(list.forwardList)))
        if (next == history) return
        history = next
        push(queue.epoch, next)
    }

    // --- Client requests ---

    internal fun onAlert(message: String, frame: WKFrameInfo, completionHandler: () -> Unit) =
        ask({ completionHandler() }, { it.onDialog(WebDialog.Alert(message, frameUrl(frame))) }) { completionHandler() }

    internal fun onConfirm(message: String, frame: WKFrameInfo, completionHandler: (Boolean) -> Unit) =
        ask({ completionHandler(false) }, { it.onDialog(WebDialog.Confirm(message, frameUrl(frame))) }) {
            completionHandler(it == DialogResult.Confirmed)
        }

    internal fun onPrompt(message: String, defaultText: String?, frame: WKFrameInfo, completionHandler: (String?) -> Unit) =
        ask({ completionHandler(null) }, { it.onDialog(WebDialog.Prompt(message, defaultText ?: "", frameUrl(frame))) }) {
            completionHandler(
                when (it) {
                    is DialogResult.Text -> it.value
                    DialogResult.Confirmed -> defaultText ?: ""
                    DialogResult.Dismissed -> null
                },
            )
        }

    internal fun onMediaCapture(origin: WKSecurityOrigin, type: WKMediaCaptureType, decisionHandler: (WKPermissionDecision) -> Unit) {
        val kinds = when (type) {
            WKMediaCaptureType.WKMediaCaptureTypeCamera -> setOf(PermissionKind.Camera)
            WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> setOf(PermissionKind.Microphone)
            WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> setOf(PermissionKind.Camera, PermissionKind.Microphone)
            else -> setOf(PermissionKind.Other)
        }
        askPermission(PermissionRequest(originOf(origin), kinds), decisionHandler)
    }

    internal fun onMotion(origin: WKSecurityOrigin, decisionHandler: (WKPermissionDecision) -> Unit) =
        askPermission(PermissionRequest(originOf(origin), setOf(PermissionKind.Other)), decisionHandler)

    private fun askPermission(request: PermissionRequest, decisionHandler: (WKPermissionDecision) -> Unit) =
        ask({ decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny) }, { it.onPermission(request) }) {
            decisionHandler(if (it) WKPermissionDecision.WKPermissionDecisionGrant else WKPermissionDecision.WKPermissionDecisionDeny)
        }

    /** Tells the client of a download, which the engine has already cancelled: nothing waits on the answer. */
    private fun askAboutDownload(request: DownloadRequest) {
        val asked = client ?: return
        CoroutineScope(scope.coroutineContext + lease).launch {
            try {
                asked.onDownload(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A client that throws has said no.
            }
        }
    }

    /**
     * Asks the client [question] on main, and hands its answer to [answer]; with no client, or one that fails, or
     * when a reset or release comes first, WebKit gets [byDefault], the safe answer. Exactly one of them runs.
     */
    private fun <T : Any> ask(byDefault: () -> Unit, question: suspend (EngineClient) -> T, answer: (T) -> Unit) {
        val pending = Pending(byDefault)
        val asked = client
        if (asked == null || released || resetting) return pending.answerByDefault()
        questions += pending
        pending.job = CoroutineScope(scope.coroutineContext + lease).launch {
            val result = try {
                question(asked)
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                null
            }
            if (result == null) pending.answerByDefault() else pending.answer { answer(result) }
        }
    }

    private fun answerAllByDefault() {
        for (pending in questions.toList()) {
            pending.job?.cancel()
            pending.answerByDefault()
        }
    }

    // --- Helpers ---

    private fun leaseCallAllowed(): Boolean = !released && !resetting && !resetFailed

    /**
     * Queues [event], caused in [epoch]. What an earlier epoch caused is dropped here, before it is queued: the queue
     * only knows the epoch it is in. The engine's own events pass whatever the epoch: a crash, and focus changes, which
     * are the view's and which the queue drops only at a reset ([EngineEventQueue.advance]).
     */
    private fun push(epoch: Int, event: EngineEvent, document: Any? = null) {
        if (epoch != queue.epoch && event != EngineEvent.Crashed && event !is EngineEvent.FocusChanged) return
        queue.offer(event, document)
    }

    /** A new document of [content] starts: nothing an earlier one caused is delivered from now on. */
    private fun startDocument(content: WebContent): Int {
        val epoch = queue.advance()
        this.content = content
        bundles.bundle = content as? WebContent.Bundle
        documentPainted = false
        paintOnScreen = false
        repaint = null
        httpError = null
        document = null
        history = null
        return epoch
    }

    private fun epochOf(navigation: WKNavigation?): Int = navigation?.let(navigations::get) ?: queue.epoch

    private fun isUserGesture(): Boolean = lastTouch?.let { clock() - it <= GESTURE_MILLIS } ?: false

    private fun originOf(origin: WKSecurityOrigin): String =
        WebKitUrls.libraryOrigin(origin.protocol, origin.host, origin.port.toInt(), bundle)

    private fun frameUrl(frame: WKFrameInfo): String? = frame.request.URL?.absoluteString?.let { WebKitUrls.toLibraryUrl(it, bundle) }

    private suspend fun evaluateIn(world: WKContentWorld, script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavaScript(script, inFrame = null, inContentWorld = world) { result, error ->
            if (continuation.isActive) continuation.resume(if (error != null) null else jsonOf(result))
        }
    }

    private fun jsonOf(value: Any?): String? = jsonElementOf(value)?.toString()

    companion object {
        /** How long after a touch a navigation counts as the user's (WebKit doesn't say). */
        private const val GESTURE_MILLIS = 1_000L
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val WARM_UP_MILLIS = 10_000L
        // The pool bounds a reset at 10 s; these two stay within it. A cold simulator once needed more than 3 s.
        private const val FLOOR_MILLIS = 4_000L
        private const val WIPE_MILLIS = 5_000L
        private const val WEBKIT_ERROR_DOMAIN = "WebKitErrorDomain"
        private const val FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE = 102L
        private const val FAILING_URL_KEY = "NSErrorFailingURLStringKey"

        /** Resolves after two animation frames, which run only once the page has been drawn in a window. */
        private const val REPAINT_BODY =
            "await new Promise(function (r) { requestAnimationFrame(function () { requestAnimationFrame(r); }); }); return true;"

        /** A new engine of [config]: its own configuration, data store, scheme handlers and isolated world. */
        fun create(config: EngineConfig): IosWebEngine {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val scripts = WebKitScripts.random()
            val world = WKContentWorld.worldWithName("webview")
            val bundles = BundleSchemeHandler(scope)
            val relay = RelayHandler()
            val configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = when (val profile = config.profile) {
                WebProfile.Default -> WKWebsiteDataStore.defaultDataStore()
                WebProfile.Ephemeral -> WKWebsiteDataStore.nonPersistentDataStore()
                is WebProfile.Named ->
                    if (osAtLeast(17)) WKWebsiteDataStore.dataStoreForIdentifier(profileUuid(profile.id)) else WKWebsiteDataStore.defaultDataStore()
            }
            configuration.mediaTypesRequiringUserActionForPlayback =
                if (config.mediaPlaybackRequiresGesture) WKAudiovisualMediaTypeAll else WKAudiovisualMediaTypeNone
            configuration.allowsInlineMediaPlayback = true
            configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
            configuration.setURLSchemeHandler(bundles, forURLScheme = WebKitUrls.BUNDLE_SCHEME)
            configuration.setURLSchemeHandler(FloorSchemeHandler(), forURLScheme = WebKitUrls.FLOOR_SCHEME)
            configuration.userContentController.addScriptMessageHandler(relay, contentWorld = world, name = scripts.handlerName)
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            webView.allowsBackForwardNavigationGestures = false
            webView.allowsLinkPreview = false
            webView.scrollView.contentInsetAdjustmentBehavior =
                platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
            return IosWebEngine(config, webView, world, scripts, bundles, relay, NavigationDelegate(), UiDelegate(), scope)
        }

        private fun urls(items: List<*>): List<String?> = items.map { (it as? WKBackForwardListItem)?.URL?.absoluteString }

        private fun kindOf(type: Long): NavigationKind = when (type) {
            WKNavigationTypeLinkActivated -> NavigationKind.Link
            WKNavigationTypeFormSubmitted -> NavigationKind.FormSubmit
            WKNavigationTypeBackForward -> NavigationKind.BackForward
            WKNavigationTypeReload -> NavigationKind.Reload
            WKNavigationTypeFormResubmitted -> NavigationKind.FormResubmit
            else -> NavigationKind.Other
        }

        /** Escapes [path] for a URL, keeping its slashes. */
        private fun escapePath(path: String): String =
            NSString.create(string = path).stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLPathAllowedCharacterSet) ?: path

        private fun webErrorOf(error: NSError, url: String?): WebError = when {
            error.domain == NSURLErrorDomain && error.code in -1206L..-1200L -> WebError.Certificate(url, error.localizedDescription)
            error.domain == WEBKIT_ERROR_DOMAIN && error.code == 101L -> WebError.UnsupportedOnPlatform(url)
            else -> WebError.Network(url, error.localizedDescription)
        }
    }
}
