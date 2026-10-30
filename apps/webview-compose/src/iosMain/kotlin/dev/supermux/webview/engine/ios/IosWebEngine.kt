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
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.WebKitErrors
import dev.supermux.webview.engine.webkit.navigationKindOf
import dev.supermux.webview.engine.webkit.snapshotWidthPoints
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.decideNavigation
import dev.supermux.webview.engine.webkit.redirectOfOwn
import dev.supermux.webview.engine.webkit.sentByTheNetwork
import dev.supermux.webview.monotonicClock
import dev.supermux.webview.runtime.OverlayCutouts
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import platform.Foundation.NSURLRequest
import platform.Foundation.URLPathAllowedCharacterSet
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.create
import platform.Foundation.distantPast
import platform.Foundation.setValue
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKBackForwardListItem
import platform.WebKit.WKContentWorld
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

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
 * - **Painting** (the handoff from the runtime's cover): the web view is never hidden. Composed, it is in its window
 *   and draws under the runtime's cover. A document's [EngineEvent.FirstPaint] waits for its content (the relay's
 *   `paint`: its first contentful paint or its load, and two animation frames; for a document without the relay, its
 *   load; or [CONTENT_WAIT_MILLIS] of nothing), then for [FRAMES_IN_PLACE] display ticks with the web view in its
 *   window ([handOff]): the cover lifts then, and the first frame without it is one the page drew there.
 * - **Parking**: a parked page keeps its web view and document as they are, out of any window and with its media
 *   suspended ([setVisible]). Adopted, it replays its page ([replayState]), and its first paint waits until the web
 *   view, in a window again, has drawn the page there, and has shown it for [FRAMES_IN_PLACE] display ticks
 *   ([awaitRepaint]).
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
    private val ticks: Ticks,
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

    /**
     * A WebKit call's completion that may never come (a page out of any window never draws, so a snapshot or a script
     * waiting on it never ends): what WebKit's block holds instead of the engine and its waiting coroutine. [release]
     * answers every one with null, and the block then holds nothing.
     */
    private class Waiter<T : Any> {
        var continuation: CancellableContinuation<T?>? = null

        fun answer(value: T?) {
            val waiting = continuation ?: return
            continuation = null
            if (waiting.isActive) waiting.resume(value)
        }
    }

    /** A page that floods its bridge while parked goes the way of a crash: discarded, never adopted with less said. */
    private val queue = EngineEventQueue(onOverflow = ::onRendererGone)
    private val clock = monotonicClock()
    private val questions = mutableListOf<Pending>()
    private val waiters = mutableSetOf<Waiter<*>>()
    /** The epoch each navigation WebKit reported started in. */
    private val navigations = NavigationEpochs()
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])

    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null

    /**
     * Whether a document of [content] has committed since [load] or [restoreState] started it. Until one has, what
     * WebKit holds is not the content's: its back-forward list and its committed document are the floor's, or an
     * earlier content's page (the content's load failed, or is under way).
     */
    private var contentCommitted = false

    /** The state [restoreState] put back, until a document of it commits: a retry before then restores it again. */
    private var restoring: NSData? = null
    private var own: OwnNavigation? = null
    private var committedEpoch = 0
    private var committedUrl: String? = null

    /** The title last reported for the committed document: a hint reports only a change ([reportTitle]). */
    private var reportedTitle: String? = null

    /**
     * The current document's token, from its relay's `start`. Also the identity the queue collapses the document's
     * repeated `$ready`s by: one instance per document.
     */
    private var document: String? = null
    private var documentPainted = false

    /** The committed document has content, but its surface isn't in a window: it hands off once it is ([handOff]). */
    private var paintOnScreen = false

    /** Commits so far: which document a handoff, or the wait for content, is for. */
    private var commits = 0

    /** The commit whose handoff is under way ([handOff]), or -1. */
    private var handingOff = -1

    /** The web view's size when the document being loaded started loading: its commit checks it ([sizeAgain]). */
    private var loadSize: Pair<Double, Double>? = null
    private var httpError: WebError? = null
    private var focused: Boolean? = null

    /**
     * When the user last made a gesture in the page (a tap, a click or a key, as the relay saw it), and the document
     * ([document]'s token) they made it in: the next request of that document's to leave, within [GESTURE_MILLIS], is
     * theirs ([userGesture]).
     */
    private var gestureAt: Long? = null
    private var gestureDocument: String? = null
    private var history: EngineEvent.HistoryChanged? = null
    private var floorState: Any? = null
    private var floorDone: CompletableDeferred<Boolean>? = null
    private var baseUserAgent: String? = null
    private var container: EngineContainerView? = null

    /** Whether the page's media is suspended now, while it is hidden ([setVisible]). */
    private var mediaSuspended = false

    /**
     * Whether the page is hidden ([setVisible]). A hidden page completes no handoff and draws no replay again: the view
     * that hides it (to park it) unbinds it in the same turn, while its surface is still in the window until Compose
     * removes it, and a first paint queued then would reach the next view before its replay.
     */
    private var hidden = false

    /** A replayed first paint, waiting for the page to be drawn again in a window ([awaitRepaint]). */
    private var repaint: Repaint? = null

    /** A replay's first paint of the document of [epoch], once it [started] waiting for the page to draw. */
    private class Repaint(val epoch: Int) {
        var started = false
    }

    /**
     * The epoch each navigation started in, by WebKit's navigation object (any object in tests), held weakly: WebKit
     * keeps a navigation while it may still report on it, and the engine keeps none past that, nor its request (a `Url`'s
     * headers), lease after lease.
     */
    @OptIn(ExperimentalNativeApi::class)
    private class NavigationEpochs {
        private val entries = ArrayList<Pair<WeakReference<Any>, Int>>()

        operator fun get(navigation: Any): Int? {
            entries.removeAll { it.first.value == null }
            return entries.firstOrNull { it.first.value === navigation }?.second
        }

        operator fun set(navigation: Any, epoch: Int) {
            entries.removeAll { it.first.value.let { held -> held == null || held === navigation } }
            entries += WeakReference(navigation) to epoch
        }

        operator fun contains(navigation: Any): Boolean = get(navigation) != null

        fun remove(navigation: Any) {
            entries.removeAll { it.first.value.let { held -> held == null || held === navigation } }
        }
    }

    override val mode: EmbeddingMode get() = EmbeddingMode.NativeView
    override val events: Flow<EngineEvent> get() = queue.events
    override val isUsable: Boolean get() = !released && !crashed
    override val needsCutouts: Boolean get() = false

    override var client: EngineClient? = null
        set(value) {
            if (released) return
            // The client that was asked goes (its view parks the page, or lets it go): what it was asked gets the safe
            // answer now, since its dialog went with its view, and the page would wait on it for good, the next view's too.
            if (value !== field) answerAllByDefault()
            field = value
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
        val start = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart
        // The relay reports the user's input, in the main frame and the page's other frames (WebKit doesn't say which
        // navigations are the user's), and hints at title and URL changes (Kotlin/Native can't key-value observe them).
        controller.addUserScript(WKUserScript(scripts.relay(origins, input = true, hints = true), start, true, world))
        controller.addUserScript(WKUserScript(scripts.frameInput(), start, false, world))
        if (setup.javaScriptEnabled) {
            controller.addUserScript(WKUserScript(scripts.shim(origins), start, true, WKContentWorld.pageWorld))
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        val epoch = startDocument(content)
        contentCommitted = false
        restoring = null
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
        // Nothing of the content committed (its load failed): WebKit's reload would reload the document still committed,
        // the floor or an earlier content's page, under this content's name. The content starts again instead.
        if (!contentCommitted) {
            val saved = restoring
            if (saved == null || !restore(shown, saved)) load(shown)
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

    /**
     * The page as WebKit draws it, at the screen's own scale up to 3 pixels a point ([snapshotWidthPoints]), in full
     * colour, its alpha kept for a transparent page. WebKit renders it off the main thread, and its pixels are copied
     * into an image off it too; the runtime scales and encodes what it keeps. Null before the document's paint, out of
     * a window, or without a size.
     */
    override suspend fun snapshot(): ImageBitmap? {
        if (released || !documentPainted) return null
        val view = container ?: return null
        if (view.window == null || webView.superview !== view) return null
        val width = view.bounds.useContents { size.width }
        val height = view.bounds.useContents { size.height }
        if (width < 1.0 || height < 1.0) return null
        val configuration = WKSnapshotConfiguration()
        configuration.snapshotWidth = NSNumber(double = snapshotWidthPoints(width, view.traitCollection.displayScale))
        val image = awaitWebKit<UIImage> { waiter -> webView.takeSnapshotWithConfiguration(configuration) { image, _ -> waiter.answer(image) } }
        if (released || image == null) return null
        return withContext(Dispatchers.Default) { image.toImageBitmap() }
    }

    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val shown = content ?: return null
        // A document of in-memory HTML can't be restored: WebKit would fetch it from its origin, which doesn't resolve.
        if (shown is WebContent.Html) return null
        // Until the content commits, WebKit's state is the floor's or an earlier content's: restored, it would show
        // nothing of this content's (a view of a page that failed would sit under its cover for good).
        if (!contentCommitted || WebKitUrls.isFloor(webView.backForwardList.currentItem?.URL?.absoluteString)) return null
        val state = webView.interactionState as? NSData ?: return null
        return SavedWebKitState.encode(shown.key, state.toByteArray())
    }

    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = SavedWebKitState.decode(state, content.key) ?: return false
        return restore(content, saved.toNSData())
    }

    /**
     * Puts [saved], a state of [content]'s, back into WebKit, which then loads its current page. False when that shows
     * nothing of the content: WebKit ignored the state (it can't decode it: the list still has the item it had), or its
     * page is the floor (a state saved after a load that failed, by an engine before this rule), which the rules refuse.
     */
    private fun restore(content: WebContent, saved: NSData): Boolean {
        startDocument(content)
        contentCommitted = false
        restoring = null
        own = OwnNavigation.Restore
        val before = webView.backForwardList.currentItem
        webView.interactionState = saved
        val current = webView.backForwardList.currentItem
        if (current === before || WebKitUrls.isFloor(current?.URL?.absoluteString)) {
            own = null
            return false
        }
        restoring = saved
        return true
    }

    /**
     * A hidden page (off screen, or parked for the next view of it) has its media suspended: what plays stops, and the
     * page can't start it again until it is shown, when what was playing resumes. Its document stays as it is. WebKit
     * throttles a page out of any window (a parked one) by itself: its timers, and its animation frames, which stop.
     */
    override fun setVisible(visible: Boolean) {
        if (released || crashed) return
        hidden = !visible
        if (mediaSuspended != !visible) {
            mediaSuspended = !visible
            // An empty handler, never null: iOS 16's WebKit calls it unconditionally.
            webView.setAllMediaPlaybackSuspended(!visible) {}
        }
        if (visible) paintWhereShown()
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
        // WebKit's URL now, if the document has moved within itself since the relay last said so.
        val current = webView.URL?.absoluteString
        if (current != null && !webView.loading && !WebKitUrls.isFloor(current)) committedUrl = current
        val url = committedUrl ?: return
        if (WebKitUrls.isFloor(url)) return
        val epoch = queue.epoch
        push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        val list = webView.backForwardList
        val now = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(urls(list.backList)), WebKitUrls.canGoForward(urls(list.forwardList)))
        history = now
        push(epoch, now)
        reportTitle(epoch, always = true)
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
        // And it is shown until its view says otherwise, as a new engine's is.
        hidden = false
        val wiped = config.profile != WebProfile.Ephemeral || wipeStore()
        resetting = false
        if (released || crashed || !floorShown || !wiped) return false
        // The floor's own events, and anything else from before or during the reset, stay behind.
        queue.advance(reset = true)
        own = null
        document = null
        committedUrl = null
        reportedTitle = null
        documentPainted = false
        paintOnScreen = false
        handingOff = -1
        httpError = null
        focused = null
        history = null
        gestureAt = null
        gestureDocument = null
        contentCommitted = false
        restoring = null
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

    /**
     * Destroys the engine now, not when the collector frees it: what waits on WebKit is answered, the page's media stops
     * (picture-in-picture and full screen too, which outlive its view), and the page unloads, so its scripts, timers and
     * loads stop and WebKit ends what it still waits on for it. Nothing WebKit keeps (a completion, a delegate, the
     * message handler) keeps the engine.
     */
    override fun release() {
        if (released) return
        answerAllByDefault()
        released = true
        client = null
        scope.cancel()
        ticks.cancel()
        bundles.bundle = null
        repaint = null
        for (waiter in waiters.toList()) waiter.answer(null)
        waiters.clear()
        webView.stopLoading()
        // Empty handlers, never null: iOS 16's WebKit calls them unconditionally.
        webView.pauseAllMediaPlaybackWithCompletionHandler {}
        webView.closeAllMediaPresentationsWithCompletionHandler {}
        val controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        controller.removeAllScriptMessageHandlers()
        relay.engine = null
        navigationDelegate.engine = null
        uiDelegate.engine = null
        webView.navigationDelegate = null
        webView.UIDelegate = null
        // A blank document, with no delegate left to ask: the page is gone from WebKit too.
        webView.loadHTMLString("", baseURL = null)
        webView.removeFromSuperview()
        container = null
    }

    // --- The surface ---

    /** [view], the surface's container, takes the web view: composed, or in a test's window. */
    internal fun attach(view: EngineContainerView) {
        view.adopt(webView)
        container = view
    }

    /** [view] lets the web view go: its composition ended (the page parked, or released). */
    internal fun detach(view: EngineContainerView) {
        view.letGo(webView)
        if (container === view) container = null
    }

    /** The container the surface composed moved into a window or out of one. */
    internal fun onSurfaceWindowChanged(view: EngineContainerView) {
        if (released || container !== view || view.window == null) return
        paintWhereShown()
    }

    /** The page can be shown now (in its window, and visible): a handoff, or a replay's repaint, that waited for it goes on. */
    private fun paintWhereShown() {
        if (released || !shown()) return
        repaint?.let(::drawAgain)
        if (!paintOnScreen) return
        paintOnScreen = false
        handOff(committedEpoch)
    }

    private fun webViewSize(): Pair<Double, Double> = webView.frame.useContents { size.width to size.height }

    /**
     * Tells WebKit the web view's size again, once a document that started loading at another size has committed. A
     * document of another site loads in a WebContent process of its own, which takes the view's size when the load
     * starts: a resize while it loads (the view laid out after the load began, as a view's first page is) reached the
     * old process only, and on iOS 16 the page committed at the old size and kept it (laid out wider than its place, or,
     * from a web view of no size, zoomed out to a quarter). WebKit hears the size only when it changes: it changes twice.
     */
    private fun sizeAgain() {
        val (width, height) = webViewSize()
        if (width <= 0.0 || height <= 0.0) return
        val (x, y) = webView.frame.useContents { origin.x to origin.y }
        webView.setFrame(CGRectMake(x, y, width + 1.0, height))
        webView.setFrame(CGRectMake(x, y, width, height))
    }

    /** Whether the web view is in its surface, and the surface in a window: where it draws, under the cover or not. */
    private fun surfaceInWindow(): Boolean {
        val view = container ?: return false
        return view.window != null && webView.superview === view
    }

    /** Whether the page is shown: its surface in a window, and the page not hidden. */
    private fun shown(): Boolean = !hidden && surfaceInWindow()

    /**
     * Runs [then] once the web view has been shown in its window for [count] more display ticks: the frames it draws
     * there are on screen, under the runtime's cover. If it leaves its window, or is hidden, first, [onLeft] runs instead.
     */
    private fun afterTicksInWindow(count: Int, onLeft: () -> Unit, then: () -> Unit) {
        ticks.next {
            when {
                released -> Unit
                !shown() -> onLeft()
                count <= 1 -> then()
                else -> afterTicksInWindow(count - 1, onLeft, then)
            }
        }
    }

    /**
     * A replay's [EngineEvent.FirstPaint], once the page has been drawn in the web view's new place: a web view back in
     * a window shows its background until its page draws again, so the view's cover stays up until then. Now if the
     * page is shown already, otherwise once it is ([paintWhereShown]). A later document, or a reset, makes it moot.
     */
    private fun awaitRepaint(epoch: Int) {
        val wait = Repaint(epoch)
        repaint = wait
        if (shown()) drawAgain(wait)
    }

    /**
     * Waits two animation frames in the isolated world, as the relay does for a document's first paint: they run only
     * while the web view is in a window, once the page has been drawn there. A document where no script runs (a PDF)
     * counts as drawn at once, as its first paint did. Then, as for a document's first paint, [FRAMES_IN_PLACE] display
     * ticks with the web view in its window, and the first paint again; if it leaves its window, or is hidden, first, it
     * waits again until it is shown.
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun drawAgain(wait: Repaint) {
        if (wait.started) return
        wait.started = true
        // WebKit keeps the completion until the page has drawn twice, which a page out of any window never does: it
        // holds the engine only weakly, so it never keeps a released one.
        val engine = WeakReference(this)
        webView.callAsyncJavaScript(
            WebKitScripts.REPAINT_BODY,
            arguments = emptyMap<Any?, Any?>(),
            inFrame = null,
            inContentWorld = world,
        ) { _, _ -> engine.value?.onDrawnAgain(wait) }
    }

    /** The page has drawn twice since [drawAgain] for [wait]: its first paint again, once shown in place ([handOff]). */
    private fun onDrawnAgain(wait: Repaint) {
        if (released || repaint !== wait || queue.epoch != wait.epoch) return
        afterTicksInWindow(FRAMES_IN_PLACE, onLeft = { if (repaint === wait) wait.started = false }) {
            if (repaint === wait && queue.epoch == wait.epoch) {
                repaint = null
                push(wait.epoch, EngineEvent.FirstPaint)
            }
        }
    }

    // --- Navigation ---

    internal fun decideAction(
        action: NavigationActionInfo,
        preferences: WKWebpagePreferences,
        decisionHandler: (WKNavigationActionPolicy, WKWebpagePreferences?) -> Unit,
    ) {
        val allow = WKNavigationActionPolicy.WKNavigationActionPolicyAllow
        val cancel = WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        if (released) return decisionHandler(cancel, preferences)
        preferences.allowsContentJavaScript = setup?.javaScriptEnabled ?: true
        val url = action.request.URL?.absoluteString
        val target = action.targetFrame
        val isMainFrame = target?.isMainFrame ?: true
        if (action.shouldPerformDownload && url != null) {
            askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), null, null, null))
            return decisionHandler(cancel, preferences)
        }
        val kind = navigationKindOf(action.navigationType)
        // A request WebKit has sent already: a server redirect's (I6).
        val fromNetwork = sentByTheNetwork(action.request.allHTTPHeaderFields?.keys?.mapNotNull { it as? String }.orEmpty())
        val gesture = userGesture(spend = false)
        val verdict = decideNavigation(url, isMainFrame, target == null, kind, gesture, own, bundle, htmlUrl, fromNetwork)
        when (verdict) {
            NavigationVerdict.Own -> {
                // Until it commits, its server redirects are the engine's own too (I6).
                own = own?.afterAllowed(kind)
                decisionHandler(allow, preferences)
            }
            NavigationVerdict.Allow -> decisionHandler(allow, preferences)
            NavigationVerdict.Refuse -> {
                val redirected = redirectOfOwn(own, isMainFrame, kind, fromNetwork)
                decisionHandler(cancel, preferences)
                if (redirected) {
                    // Redirected off the web: the load ends here, with an error (WebKit's own report of it is ignored).
                    own = null
                    push(queue.epoch, EngineEvent.Progress(null))
                    push(queue.epoch, EngineEvent.Error(WebError.BlockedByPolicy(url?.let { WebKitUrls.toLibraryUrl(it, bundle) })))
                } else if (isMainFrame && WebKitUrls.isFloor(url) && refusable(own)) {
                    // The engine's own navigation reached the floor, which only a reset may: a reload or a restore of
                    // what was the floor's. An error, never a page that sits still under its cover.
                    refusedOwn()
                }
            }
            NavigationVerdict.ReloadHtml -> {
                decisionHandler(cancel, preferences)
                val html = content as? WebContent.Html ?: return
                own = OwnNavigation.Load(html.origin.url)
                webView.loadHTMLString(html.html, baseURL = NSURL.URLWithString(html.origin.url))
            }
            is NavigationVerdict.Ask -> {
                // A gesture of the user's makes one request of the page's to leave theirs, never every one in its second.
                if (gesture && isMainFrame) userGesture(spend = true)
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

    /**
     * The engine's own navigation was refused: the content isn't shown, and the app hears so. A retry loads it again,
     * WebKit's committed document being the floor's.
     */
    private fun refusedOwn() {
        own = null
        contentCommitted = false
        val shown = content ?: return
        push(queue.epoch, EngineEvent.Error(WebError.BlockedByPolicy(libraryUrlOf(shown))))
    }

    /**
     * Whether [own] is a navigation of the engine's not yet allowed, whose refusal [refusedOwn] reports: not the floor's,
     * which is the rules' own, nor one allowed already and waiting for its commit ([OwnNavigation.Redirecting]), which
     * the page's own navigation meanwhile doesn't end.
     */
    private fun refusable(own: OwnNavigation?): Boolean = own != null && own != OwnNavigation.Floor && own !is OwnNavigation.Redirecting

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

    internal fun onStarted(navigation: Any?) {
        if (released) return
        if (navigation != null && navigation !in navigations) navigations[navigation] = queue.epoch
        push(epochOf(navigation), EngineEvent.Progress(START_PROGRESS))
    }

    internal fun onCommitted(navigation: Any?) {
        if (released) return
        val epoch = epochOf(navigation)
        // A commit WebKit reports late, of a navigation from before the committed document's (another process's, after a
        // process swap): the engine stays the later document's, its messages and its first paint still counting.
        if (epoch < committedEpoch) return
        committedEpoch = epoch
        if (epoch == queue.epoch) {
            contentCommitted = true
            restoring = null
        }
        own = null
        if (loadSize != null && loadSize != webViewSize()) sizeAgain()
        loadSize = null
        document = null
        documentPainted = false
        paintOnScreen = false
        commits++
        handingOff = -1
        // A new document paints for itself: a replay's wait for the old one's is moot.
        repaint = null
        // Not its commit: its content says when it can be shown ([handOff]). A document that paints nothing contentful
        // and never loads is shown anyway, after a while: the cover doesn't stay up for good.
        val commit = commits
        CoroutineScope(scope.coroutineContext + lease).launch {
            delay(CONTENT_WAIT_MILLIS)
            if (commits == commit) handOff(epoch)
        }
        val url = webView.URL?.absoluteString
        committedUrl = url
        if (httpError?.url != url?.let { WebKitUrls.toLibraryUrl(it, bundle) }) httpError = null
        push(epoch, EngineEvent.UrlChanged(url?.let { WebKitUrls.toLibraryUrl(it, bundle) }))
        reportTitle(epoch, always = true)
        push(epoch, EngineEvent.Progress(maxOf(COMMIT_PROGRESS, webView.estimatedProgress.toFloat())))
        pushHistory(epoch)
    }

    internal fun onFinished(navigation: Any?) {
        if (released) return
        val epoch = epochOf(navigation)
        navigation?.let(navigations::remove)
        if (WebKitUrls.isFloor(webView.URL?.absoluteString)) floorDone?.complete(true)
        push(epoch, EngineEvent.Progress(null))
        if (epoch == committedEpoch) {
            reportTitle(epoch, always = true)
            // A page that moved within itself while it loaded: WebKit's URL, now that nothing loads.
            onSameDocumentChange(epoch)
        }
        // A document without the relay (a PDF, an image) has no paint report: its load says it has its content.
        if (epoch == committedEpoch && document == null && !documentPainted) handOff(epoch)
    }

    internal fun onFailed(navigation: Any?, error: NSError) {
        if (released) return
        val epoch = epochOf(navigation)
        navigation?.let(navigations::remove)
        // A navigation replaced by another, or one the engine itself turned into a download, isn't an error.
        val ignorable = WebKitErrors.isIgnorable(error.domain ?: "", error.code)
        // A cancellation ends nothing the engine does now: a reset's own stopLoading cancels the page it leaves, and a
        // load the one it replaces, and WebKit may report it of a navigation the engine can't tell (none). It is neither
        // the floor's failure (a floor that never loads times out) nor an end of the engine's own navigation, whose rules
        // must still let it through. Another failure is, if it is of this epoch.
        if (!ignorable && epoch == queue.epoch) {
            own = null
            floorDone?.complete(false)
        }
        push(epoch, EngineEvent.Progress(null))
        if (ignorable) return
        // The URL that failed, as WebKit says it. When it doesn't (a retry after a host that doesn't resolve was seen to),
        // the content the engine is loading, if nothing of it committed in this epoch: WebKit's own URL is the document
        // still committed then, the floor or an earlier page, never the one that failed.
        val failing = (error.userInfo[FAILING_URL_KEY] as? String) ?: (error.userInfo[FAILING_NSURL_KEY] as? NSURL)?.absoluteString
        val url = failing?.let { WebKitUrls.toLibraryUrl(it, bundle) }
            ?: content?.takeIf { epoch == queue.epoch && !contentCommitted }?.let(::libraryUrlOf)
        push(epoch, EngineEvent.Error(WebKitErrors.toWebError(error.domain ?: "", error.code, error.localizedDescription, url)))
    }

    internal fun onRendererGone() {
        if (released || crashed) return
        crashed = true
        floorDone?.complete(false)
        push(queue.epoch, EngineEvent.Crashed)
    }

    internal fun onNewWindow(action: NavigationActionInfo) {
        if (released) return
        val url = action.request.URL?.absoluteString
        if (url.isNullOrEmpty() || WebKitUrls.isPrivate(url) && !(bundle?.let { WebKitUrls.isBundleUrl(url, it) } ?: false)) return
        val decision = try {
            client?.onNewWindow(NewWindowRequest(WebKitUrls.toLibraryUrl(url, bundle), userGesture(spend = true)))
        } catch (e: Exception) {
            null
        }
        if (decision != NewWindowDecision.OpenHere) return
        own = OwnNavigation.Load(url)
        webView.loadRequest(action.request)
    }

    // --- The relay ---

    internal fun onRelayMessage(body: Any?, frame: FrameInfo) {
        if (released) return
        val parsed = RelayMessage.parse(body as? String ?: return) ?: return
        // The page's other frames tell only of the user's input there, which is the page's: anything else of theirs goes.
        if (!frame.isMainFrame) {
            if (parsed is RelayMessage.Input) onInput(parsed.gesture)
            return
        }
        // One document per commit: its relay starts it once, and only a commit (a page back from the back-forward cache
        // commits too) lets the next one start. A relay that started again otherwise re-keys nothing.
        if (parsed is RelayMessage.Start) {
            if (document == null) document = parsed.document
            return
        }
        val current = document
        if (parsed.document != current) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch)
            is RelayMessage.Focus -> setFocused(epoch, parsed.focused)
            is RelayMessage.Title -> lookAgain(epoch) { reportTitle(epoch, always = false) }
            is RelayMessage.Url -> lookAgain(epoch) { onSameDocumentChange(epoch) }
            // The document's own token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(epoch, EngineEvent.PageMessage(originOf(frame.securityOrigin), parsed.json), current)
            is RelayMessage.Input -> onInput(parsed.gesture)
            is RelayMessage.Start -> Unit
        }
    }

    /**
     * The user used the current document, as the relay saw it in trusted events: it is in use (focused, for the pool,
     * which a view's touches never reach), and a [gesture] (a tap, a click or a key) makes its next request to leave the
     * user's ([userGesture]). A touch on the view itself says nothing: Compose hit-tests it for a drag that it keeps to
     * scroll the list as well.
     */
    private fun onInput(gesture: Boolean) {
        val current = document ?: return
        setFocused(committedEpoch, true)
        if (!gesture) return
        gestureAt = clock()
        gestureDocument = current
    }

    /**
     * Whether a request of the current document's is the user's: they made a gesture in this document within
     * [GESTURE_MILLIS]. A request it was credited to [spend]s it: one gesture, one request to leave.
     */
    private fun userGesture(spend: Boolean): Boolean {
        val at = gestureAt ?: return false
        val theirs = document != null && gestureDocument == document && clock() - at <= GESTURE_MILLIS
        if (theirs && spend) gestureAt = null
        return theirs
    }

    /**
     * The committed document has content to show: the relay's `paint` (its first contentful paint or its load, then two
     * animation frames), a document without the relay's load, or [CONTENT_WAIT_MILLIS] of nothing since its commit. It
     * says [EngineEvent.FirstPaint] once the web view has shown it in its window for [FRAMES_IN_PLACE] display ticks,
     * under the runtime's cover, which lifts then: the first frame without the cover is one the page drew in its place,
     * never its bare background. Out of a window, or hidden, it waits until it is shown ([paintWhereShown]); leaving its
     * window or hidden during the ticks, it starts again once shown. Once per document.
     */
    private fun handOff(epoch: Int) {
        if (released || documentPainted || epoch != committedEpoch) return
        val commit = commits
        if (handingOff == commit) return
        if (!shown()) {
            paintOnScreen = true
            return
        }
        handingOff = commit
        val left = {
            if (handingOff == commit) {
                handingOff = -1
                paintOnScreen = true
            }
        }
        afterTicksInWindow(FRAMES_IN_PLACE, onLeft = left) {
            if (commits != commit || documentPainted) return@afterTicksInWindow
            documentPainted = true
            push(epoch, EngineEvent.FirstPaint)
            httpError?.let {
                httpError = null
                push(epoch, EngineEvent.Error(it))
            }
        }
    }

    private fun setFocused(epoch: Int, value: Boolean) {
        if (focused == value) return
        focused = value
        push(epoch, EngineEvent.FocusChanged(value))
    }

    /**
     * The relay saw the page change its title or its URL without a new document: a hint. WebKit's own values count, and
     * can't be observed (Kotlin/Native has no key-value observing); they reach the app on a path of their own, which may
     * come after the hint. A page's first title, which WebKit hands on from a task of its own, was seen to arrive about
     * 300 ms after the relay's hint. So [seen] looks now and, until it sees the change, again over the next 1.5 s.
     */
    private fun lookAgain(epoch: Int, seen: () -> Boolean) {
        if (seen()) return
        CoroutineScope(scope.coroutineContext + lease).launch {
            for (wait in LOOK_AGAIN_MILLIS) {
                delay(wait)
                if (released || queue.epoch != epoch || committedEpoch != epoch || seen()) return@launch
            }
        }
    }

    /** Reports WebKit's title for the committed document: [always], or only if it changed. True if it reported it. */
    private fun reportTitle(epoch: Int, always: Boolean): Boolean {
        val title = webView.title?.takeIf { it.isNotEmpty() }
        if (!always && title == reportedTitle) return false
        reportedTitle = title
        push(epoch, EngineEvent.TitleChanged(title))
        return true
    }

    /**
     * Reports WebKit's URL if the document moved within itself (`pushState`, a fragment, a same-document history step):
     * once WebKit's URL differs from the committed one while no document loads, which would make it a provisional
     * navigation's. Not by WebKit's history: in-memory HTML has no entry there, its current item stays what was there
     * before. True if it reported a change.
     */
    private fun onSameDocumentChange(epoch: Int): Boolean {
        val url = webView.URL?.absoluteString
        val moved = url != null && url != committedUrl && !webView.loading && !WebKitUrls.isFloor(url)
        if (moved && url != null) {
            committedUrl = url
            push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        }
        pushHistory(epoch)
        return moved
    }

    /** Reports WebKit's history if it changed, for a document of [epoch]: never into a later epoch. */
    private fun pushHistory(epoch: Int) {
        if (epoch != queue.epoch) return
        val list = webView.backForwardList
        val next = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(urls(list.backList)), WebKitUrls.canGoForward(urls(list.forwardList)))
        if (next == history) return
        history = next
        push(epoch, next)
    }

    // --- Client requests ---

    internal fun onAlert(message: String, frame: FrameInfo, completionHandler: () -> Unit) =
        ask({ completionHandler() }, { it.onDialog(WebDialog.Alert(message, frameUrl(frame))) }) { completionHandler() }

    internal fun onConfirm(message: String, frame: FrameInfo, completionHandler: (Boolean) -> Unit) =
        ask({ completionHandler(false) }, { it.onDialog(WebDialog.Confirm(message, frameUrl(frame))) }) {
            completionHandler(it == DialogResult.Confirmed)
        }

    internal fun onPrompt(message: String, defaultText: String?, frame: FrameInfo, completionHandler: (String?) -> Unit) =
        ask({ completionHandler(null) }, { it.onDialog(WebDialog.Prompt(message, defaultText ?: "", frameUrl(frame))) }) {
            completionHandler(
                when (it) {
                    is DialogResult.Text -> it.value
                    DialogResult.Confirmed -> defaultText ?: ""
                    DialogResult.Dismissed -> null
                },
            )
        }

    internal fun onMediaCapture(origin: SecurityOriginInfo, type: WKMediaCaptureType, decisionHandler: (WKPermissionDecision) -> Unit) {
        val kinds = when (type) {
            WKMediaCaptureType.WKMediaCaptureTypeCamera -> setOf(PermissionKind.Camera)
            WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> setOf(PermissionKind.Microphone)
            WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> setOf(PermissionKind.Camera, PermissionKind.Microphone)
            else -> setOf(PermissionKind.Other)
        }
        askPermission(PermissionRequest(originOf(origin), kinds), decisionHandler)
    }

    internal fun onMotion(origin: SecurityOriginInfo, decisionHandler: (WKPermissionDecision) -> Unit) =
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
        loadSize = webViewSize()
        this.content = content
        bundles.bundle = content as? WebContent.Bundle
        documentPainted = false
        paintOnScreen = false
        handingOff = -1
        repaint = null
        httpError = null
        document = null
        history = null
        gestureAt = null
        gestureDocument = null
        return epoch
    }

    private fun epochOf(navigation: Any?): Int = navigation?.let(navigations::get) ?: queue.epoch

    private fun originOf(origin: SecurityOriginInfo): String = WebKitUrls.libraryOrigin(origin.protocol, origin.host, origin.port, bundle)

    private fun frameUrl(frame: FrameInfo): String? = frame.url?.let { WebKitUrls.toLibraryUrl(it, bundle) }

    /** [content]'s URL in the library's terms: where the app asked it to be. */
    private fun libraryUrlOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url
        is WebContent.Html -> content.origin.url
        is WebContent.Bundle -> WebKitUrls.toLibraryUrl(WebKitUrls.bundleUrl(content.origin, escapePath(content.entry)), content.origin)
    }

    private suspend fun evaluateIn(world: WKContentWorld, script: String): String? = awaitWebKit<String> { waiter ->
        webView.evaluateJavaScript(script, inFrame = null, inContentWorld = world) { result, error ->
            waiter.answer(if (error != null) null else jsonElementOf(result)?.toString())
        }
    }

    /**
     * Calls WebKit with a [Waiter] for its completion, and waits for its answer: null if the engine is released first,
     * or released meanwhile. [call] hands WebKit a block that holds the waiter only, never the engine.
     */
    private suspend fun <T : Any> awaitWebKit(call: (Waiter<T>) -> Unit): T? {
        if (released) return null
        val waiter = Waiter<T>()
        waiters += waiter
        try {
            return suspendCancellableCoroutine { continuation ->
                waiter.continuation = continuation
                continuation.invokeOnCancellation { waiter.continuation = null }
                call(waiter)
            }
        } finally {
            waiters -= waiter
        }
    }

    companion object {
        /** How long after the user's gesture in a page a request of the page's counts as theirs (WebKit doesn't say). */
        private const val GESTURE_MILLIS = 1_000L
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val WARM_UP_MILLIS = 10_000L
        // The pool bounds a reset at 10 s; these two stay within it. A cold simulator once needed more than 3 s.
        private const val FLOOR_MILLIS = 4_000L
        private const val WIPE_MILLIS = 5_000L
        private const val FAILING_URL_KEY = "NSErrorFailingURLStringKey"
        private const val FAILING_NSURL_KEY = "NSErrorFailingURLKey"

        /** When a hint's change isn't WebKit's yet, how long to wait before each look again ([lookAgain]): 1.5 s in all. */
        private val LOOK_AGAIN_MILLIS = longArrayOf(50, 100, 200, 400, 750)

        /**
         * How long a committed document may go without content (the relay's `paint`, or its load without the relay)
         * before it is shown anyway: the cover doesn't stay up for good over a page that never paints anything.
         */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** The display ticks a document is shown in its window, under the cover, before it says it painted. */
        private const val FRAMES_IN_PLACE = 2

        /**
         * A new engine of [config]: its own configuration, data store, scheme handlers and isolated world. [ticks] are
         * the screen's display ticks; tests tick by hand.
         */
        fun create(config: EngineConfig, ticks: Ticks = DisplayTicks()): IosWebEngine {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val scripts = WebKitScripts.random()
            val world = WKContentWorld.worldWithName("webview")
            val bundles = BundleSchemeHandler(scope)
            val relay = RelayHandler()
            val configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = dataStoreFor(config.profile, namedStores = osAtLeast(17))
            configuration.mediaTypesRequiringUserActionForPlayback =
                if (config.mediaPlaybackRequiresGesture) WKAudiovisualMediaTypeAll else WKAudiovisualMediaTypeNone
            configuration.allowsInlineMediaPlayback = true
            configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
            configuration.setURLSchemeHandler(bundles, forURLScheme = WebKitUrls.BUNDLE_SCHEME)
            configuration.setURLSchemeHandler(FloorSchemeHandler(), forURLScheme = WebKitUrls.FLOOR_SCHEME)
            configuration.userContentController.addScriptMessageHandler(relay, contentWorld = world, name = scripts.handlerName)
            // The screen's size until a view gives it its own, never none: a page that commits in a web view of no size
            // is zoomed out to WebKit's least scale (0.25), and iOS 16 keeps that scale once the view has its size, so
            // the page, and its cover, showed in a quarter of its place. The screen's is the most a view takes.
            val webView = WKWebView(frame = UIScreen.mainScreen.bounds, configuration = configuration)
            webView.allowsBackForwardNavigationGestures = false
            webView.allowsLinkPreview = false
            webView.scrollView.contentInsetAdjustmentBehavior =
                platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
            return IosWebEngine(config, webView, world, scripts, bundles, relay, NavigationDelegate(), UiDelegate(), scope, ticks)
        }

        private fun urls(items: List<*>): List<String?> = items.map { (it as? WKBackForwardListItem)?.URL?.absoluteString }

        /** Escapes [path] for a URL, keeping its slashes. */
        private fun escapePath(path: String): String =
            NSString.create(string = path).stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLPathAllowedCharacterSet) ?: path
    }
}
