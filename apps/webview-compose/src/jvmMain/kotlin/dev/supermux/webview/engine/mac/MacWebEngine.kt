package dev.supermux.webview.engine.mac

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.coverBackgroundOf
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.LocalWebSurfaceHints
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.webkit.NavigationVerdict
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitErrors
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.decideNavigation
import dev.supermux.webview.engine.webkit.escapeUrlPath
import dev.supermux.webview.engine.webkit.redirectOfOwn
import dev.supermux.webview.engine.webkit.snapshotWidthPoints
import dev.supermux.webview.monotonicClock
import dev.supermux.webview.normalizeBundlePath
import dev.supermux.webview.runtime.OverlayCutouts
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.coroutines.resume

/**
 * The macOS engine: one `WKWebView` under the Compose canvas (spec §7, layout A), through the glue, following the
 * [WebEngine] contract as `IosWebEngine` does; that class's KDoc holds here, with these differences:
 * - **Threads (D3).** State lives on the main thread (the EDT). Every WebKit call goes through [glue] and runs later
 *   on AppKit's thread, in order; every WebKit callback arrives as a [GlueEvent] in [onGlueEvent]. WebKit's questions
 *   wait in the glue until [glue]'s answer methods are called; [release] answers what is left.
 * - **Epochs (D4).** The glue stamps every navigation callback, relay message and state change with the epoch it
 *   belongs to; one that isn't the current epoch is dropped before it touches any state.
 * - **WebKit's own values (M13).** URL, title, loading state and history come from key-value observation ([page]); the
 *   relay's title and URL hints are not needed.
 * - **The surface (D1, D2, D7).** The page's view sits under the canvas, at the part of its slot that shows (I2), at
 *   the window's backing scale (I3); [MacSurfaceNode] clears a hole over it. A motion that makes the page reflow while
 *   WebKit draws it again (a new width or scale, a pane's new height, shown again, a new window) covers the slot with
 *   [motionCover], at its true size, and stops input, until the settle has the page drawn in place again: its content
 *   trails inside its view until then. A page that only moves (a list's scroll, a new clip), and an embed that only
 *   grows or shrinks in height (its page shows more or less of itself), stays live and takes input throughout.
 *   [shown] is false while the runtime covers the page itself, and once it has crashed.
 * - **The handoff (D6).** `FirstPaint` once the document has content, the surface is placed and still, and two display
 *   ticks have passed with the view in its window.
 */
internal class MacWebEngine(
    val config: EngineConfig,
    private val glue: PageGlue,
    private val scripts: WebKitScripts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = monotonicClock(),
    private val windowHandle: (java.awt.Window?) -> Long = ::composeWindowHandle,
    private val onReleased: (MacWebEngine) -> Unit = {},
) : WebEngine, SurfaceHost {
    /** A question the engine asked its client for WebKit: answered once, by the client or by default. */
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

    /** A replay's first paint of the document of [epoch], once it [started] waiting for the page to be in place. */
    private class Repaint(val epoch: Int) {
        var started = false
    }

    /** What the page says while parked is dropped as it comes: however chatty, it stays adoptable (contract **Buffering**). */
    private val queue = EngineEventQueue()
    private val questions = mutableListOf<Pending>()
    private val requests = HashMap<Long, CancellableContinuation<GlueEvent?>>()
    private var nextRequest = 1L
    private val tasks = HashMap<Long, Job>()
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])

    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null

    /**
     * Whether a document of [content] has committed since [load] or [restoreState] started it (iOS's rule). Until one
     * has, what WebKit holds is not the content's: its back-forward list and its committed document are the floor's,
     * or an earlier content's page (the content's load failed, or is under way).
     */
    private var contentCommitted = false

    /** The state [restoreState] put back, until a document of it commits: a retry before then restores it again. */
    private var restoring: ByteArray? = null
    private var own: OwnNavigation? = null
    private var committedEpoch = 0
    private var committedUrl: String? = null
    private var reportedTitle: String? = null
    private var document: String? = null
    private var documentPainted = false
    private var paintOnScreen = false
    private var commits = 0
    private var handingOff = -1
    private var httpError: WebError? = null
    private var focused: Boolean? = null
    /** When the user last pressed a key or the page: the next request of the page's to leave, within a second, is theirs. */
    private var lastInput: Long? = null
    private var history: EngineEvent.HistoryChanged? = null
    private var floorDone: CompletableDeferred<Boolean>? = null
    private var baseUserAgent: String? = null
    private var mediaSuspended = false
    private var repaint: Repaint? = null

    /** WebKit's page as the glue last reported it (M13). */
    private var page = WebKitPage.Empty

    // --- The surface (D1, D2, D7) ---
    private var surface: MacSurfaceNode? = null
    private var window = 0L
    private var bounds: Rect? = null

    /** The part of [bounds] that shows: the native view is placed at it, and clipped to it (I2). */
    private var visible: Rect? = null

    /** The window's backing scale, window px a point (I3): Compose's window pixels are the window's backing pixels. */
    private var scale = 0f
    private var placed = false

    /** The page has been drawn where its view is now, for two ticks, and nothing has moved since (D2). */
    private var still = false

    /** The page must draw again before it is still: resized, shown again, or in a new window. */
    private var needsRepaint = true

    /**
     * The motion under way makes the page reflow while WebKit draws it again: the motion cover stands in, and the
     * page takes no input, until it is still.
     */
    private var motionNeedsCover = false
    private var lastPlacedAt: Long? = null
    private var placement = 0
    private var settling: Job? = null

    /** The runtime's cover is lifted for its current document: the page shows, and takes input but while it reflows. */
    private var shown = false
    private var region = InputRegion.None
    private var wheelToPage = false
    private var sentInput: List<Any>? = null
    private var motionCover: ImageBitmap? = null

    /** The slot's width in points when [motionCover] was taken: the picture's true size (C2). */
    private var motionCoverWidthPoints = 0f

    /** The page's background along [motionCover]'s edges: what the slot shows past the picture. */
    private var motionCoverBackground = Color.Transparent

    /**
     * The height WebKit has drawn an embed at (window px): what it grows by past it shows the page's background until
     * WebKit has drawn it ([followGrowth]); 0 until the slot is first still.
     */
    private var drawnHeight = 0f
    private var growthRepaint: Job? = null
    private var motionCoverAt = 0L
    private var refreshingCover = false

    override val mode: EmbeddingMode get() = EmbeddingMode.NativeView
    override val events: Flow<EngineEvent> get() = queue.events
    override val isUsable: Boolean get() = !released && !crashed

    /**
     * Whom to tell when the page's process dies ([onRendererGone]): the pool, while the page is parked. Called on the
     * main thread, as every upcall runs, once, after the crash is queued; never for a [release].
     */
    override var onUnusable: (() -> Unit)? = null

    /** Compose draws over the page, but the marked overlays still decide who takes a press (D1). */
    override val needsCutouts: Boolean get() = true

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

    /** Loads the floor, which the glue keeps for [reset], and learns the default user agent. False if the engine can't. */
    internal suspend fun warmUp(): Boolean {
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        glue.loadUrl(queue.epoch, WebKitUrls.FLOOR_URL, emptyMap())
        val loaded = withTimeoutOrNull(WARM_UP_MILLIS) { done.await() } ?: false
        floorDone = null
        if (!loaded || released || crashed) return false
        baseUserAgent = evaluateIn(pageWorld = false, script = "navigator.userAgent")
            ?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.content }
        // Nothing from before the first lease reaches it: as after a reset.
        queue.advance(reset = true)
        documentPainted = false
        paintOnScreen = false
        handingOff = -1
        return true
    }

    // --- The lease ---

    override fun configure(setup: PageSetup) {
        if (!leaseCallAllowed()) return
        this.setup = setup
        val origins = WebKitUrls.pageOrigins(setup.bridgeOrigins)
        glue.configure(
            dark = setup.dark,
            transparent = setup.transparentBackground,
            userAgent = setup.userAgentSuffix?.let { suffix -> listOfNotNull(baseUserAgent, suffix).joinToString(" ") },
            inspectable = setup.inspectable,
            relay = scripts.relay(origins),
            shim = if (setup.javaScriptEnabled) scripts.shim(origins) else null,
        )
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        cover()
        startLoad(content)
    }

    /** A document of [content], the runtime's or the engine's own (a reload of `Html`, or of content that never committed). */
    private fun startLoad(content: WebContent) {
        val epoch = startDocument(content)
        contentCommitted = false
        restoring = null
        when (content) {
            is WebContent.Url -> {
                own = OwnNavigation.Load(content.url)
                glue.loadUrl(epoch, content.url, content.headers)
            }
            is WebContent.Html -> {
                own = OwnNavigation.Load(content.origin.url)
                glue.loadHtml(epoch, content.html, content.origin.url)
            }
            is WebContent.Bundle -> {
                val url = WebKitUrls.bundleUrl(content.origin, escapeUrlPath(content.entry))
                own = OwnNavigation.Load(url)
                glue.loadUrl(epoch, url, emptyMap())
            }
        }
    }

    override fun reload() {
        if (released || resetting) return
        val current = content ?: return
        // WebKit would fetch in-memory HTML from its origin, which doesn't resolve: load it again instead.
        if (current is WebContent.Html) {
            startLoad(current)
            return
        }
        // Nothing of the content committed (its load failed): WebKit's reload would reload the document still committed,
        // the floor or an earlier content's page, under this content's name. The content starts again instead.
        if (!contentCommitted) {
            val saved = restoring
            if (saved == null || !restore(current, saved)) startLoad(current)
            return
        }
        val epoch = startDocument(current)
        own = OwnNavigation.Reload
        glue.reload(epoch)
    }

    override fun goBack() {
        if (released || resetting || !WebKitUrls.canGoBack(page.back)) return
        own = OwnNavigation.History
        glue.goBack()
    }

    override fun goForward() {
        if (released || resetting || !WebKitUrls.canGoForward(page.forward)) return
        own = OwnNavigation.History
        glue.goForward()
    }

    override fun stop() {
        if (!released) glue.stop()
    }

    override suspend fun evaluate(script: String): String? = if (released) null else evaluateIn(pageWorld = true, script = script)

    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        glue.deliver(scripts.deliveryBody, json, WebKitUrls.pageOrigin(targetOrigin, bundle), scripts.inEvent)
    }

    /**
     * The page as WebKit draws it, at the screen's own scale ([snapshotWidthPoints]), in full colour, its alpha kept;
     * WebKit renders it off the main thread, and the glue copies its pixels off AppKit's. Null before the document's
     * paint, without a placed surface, or without a size. It is also the next motion cover (D2).
     */
    override suspend fun snapshot(): ImageBitmap? {
        if (released || !documentPainted || surface == null || window == 0L || !placed) return null
        val slot = bounds ?: return null
        val pxPerPoint = scale
        if (pxPerPoint <= 0f || slot.width / pxPerPoint < 1f || slot.height / pxPerPoint < 1f) return null
        val widthPoints = slot.width / pxPerPoint
        val shot = takeSnapshot(snapshotWidthPoints(widthPoints.toDouble(), pxPerPoint.toDouble())) ?: return null
        keepMotionCover(shot, widthPoints)
        return shot.image
    }

    /** [shot], of a slot [widthPoints] wide, is the next motion cover (D2). */
    private fun keepMotionCover(shot: Shot, widthPoints: Float) {
        motionCover = shot.image
        motionCoverWidthPoints = widthPoints
        motionCoverBackground = shot.background
        motionCoverAt = clock()
    }

    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val current = content ?: return null
        // A document of in-memory HTML can't be restored: WebKit would fetch it from its origin, which doesn't resolve.
        if (current is WebContent.Html) return null
        // Until the content commits, WebKit's state is the floor's or an earlier content's: restored, it would show
        // nothing of this content's (a view of a page that failed would sit under its cover for good).
        if (!contentCommitted || WebKitUrls.isFloor(committedUrl)) return null
        val result = withTimeoutOrNull(SAVE_MILLIS) { request { glue.saveState(it) } } as? GlueEvent.Result ?: return null
        val state = result.data ?: return null
        return SavedWebKitState.encode(current.key, state)
    }

    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = SavedWebKitState.decode(state, content.key) ?: return false
        cover()
        return restore(content, saved)
    }

    /**
     * Puts [saved], a state of [content]'s, back into WebKit, which then loads its current page. False when that shows
     * nothing of the content: WebKit ignored the state (it can't decode it), or its page is the floor (a state saved
     * after a load that failed, by an engine before this rule), which the glue refuses as the rules would.
     */
    private fun restore(content: WebContent, saved: ByteArray): Boolean {
        val epoch = startDocument(content)
        contentCommitted = false
        restoring = null
        own = OwnNavigation.Restore
        if (!glue.restoreState(epoch, saved)) {
            own = null
            return false
        }
        restoring = saved
        return true
    }

    /**
     * A hidden page has its media suspended: what plays stops, and resumes when shown. Its document stays as it is;
     * WebKit throttles a hidden view (`NSView.hidden`, D1) or one out of any window (a parked page) by itself.
     */
    override fun setVisible(visible: Boolean) {
        if (released || crashed || mediaSuspended == !visible) return
        mediaSuspended = !visible
        glue.setMediaSuspended(!visible)
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]): its URL, history and title as WebKit last
     * reported them, and, if the document had painted, its first paint again once it is drawn in the new view's place:
     * the surface's settle draws it again there, then two ticks pass ([awaitRepaint]). Nothing starts.
     */
    override fun replayState() {
        if (!leaseCallAllowed() || crashed || content == null || committedEpoch != queue.epoch) return
        val current = page.url
        if (current != null && !page.loading && !WebKitUrls.isFloor(current)) committedUrl = current
        val url = committedUrl ?: return
        if (WebKitUrls.isFloor(url)) return
        val epoch = queue.epoch
        cover()
        push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        val now = historyOf(page)
        history = now
        push(epoch, now)
        reportTitle(epoch, always = true)
        // The new view has heard no focus yet: the page's next one is news to it.
        focused = null
        if (documentPainted) awaitRepaint(epoch)
    }

    /** Nothing to do: `takeSnapshot` renders the page itself, never what Compose draws over it. */
    override fun overlaysAtSnapshot(cutouts: OverlayCutouts) = Unit

    override fun requestFocus() {
        if (!released) glue.focus()
    }

    override fun applyOverlays(cutouts: OverlayCutouts) {
        if (released) return
        region = InputRegion.of(cutouts)
        updateInput()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        val inList = LocalWebSurfaceHints.current.inScrollingList
        Box(modifier.then(MacSurfaceElement(this, inList)))
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
        answerAllByDefault()
        cancelTasks()
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        glue.stop()
        glue.stopMedia()
        glue.removeScripts()
        cover()
        val floorShown = showFloor()
        // Released while it waited: the glue is gone, and nothing may reach it (Minor 2).
        if (released) return false
        repaint = null
        // The next lease's page plays its media: what was suspended while hidden went with the last page.
        if (mediaSuspended) {
            mediaSuspended = false
            glue.setMediaSuspended(false)
        }
        val wiped = config.profile != WebProfile.Ephemeral || wipeStore()
        if (released) return false
        resetting = false
        if (released || crashed || !floorShown || !wiped) return false
        // The floor's own events, and anything else from before or during the reset, stay behind.
        queue.advance(reset = true)
        own = null
        contentCommitted = false
        restoring = null
        document = null
        committedUrl = null
        reportedTitle = null
        documentPainted = false
        paintOnScreen = false
        handingOff = -1
        httpError = null
        focused = null
        history = null
        motionCover = null
        resetFailed = false
        return true
    }

    /** Puts the floor's state back: the back-forward list is the floor alone. True once it has loaded so. */
    private suspend fun showFloor(): Boolean {
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        if (!glue.showFloor(queue.epoch)) {
            floorDone = null
            own = null
            return false
        }
        val loaded = withTimeoutOrNull(FLOOR_MILLIS) { done.await() } ?: false
        floorDone = null
        return loaded && page.back.isEmpty() && WebKitUrls.isFloor(page.url)
    }

    /** Wipes this engine's own ephemeral store: no other engine uses it. */
    private suspend fun wipeStore(): Boolean =
        withTimeoutOrNull(WIPE_MILLIS) { (request { glue.wipeData(it) } as? GlueEvent.Result)?.ok } ?: false

    override fun release() {
        if (released) return
        answerAllByDefault()
        client = null
        released = true
        cancelTasks()
        val waiting = requests.values.toList()
        requests.clear()
        for (continuation in waiting) continuation.resume(null)
        // A reset waiting for the floor ends now, not at its timeout.
        floorDone?.complete(false)
        settling?.cancel()
        scope.cancel()
        motionCover = null
        surface = null
        glue.release()
        onReleased(this)
    }

    // --- The surface (SurfaceHost) ---

    override fun windowHandleOf(window: java.awt.Window?): Long = windowHandle(window)

    override fun scaleOf(window: java.awt.Window?): Float = backingScaleOf(window)

    override fun surfaceAttached(node: MacSurfaceNode) {
        if (released) return
        surface = node
        wheelToPage = !node.inList
        still = false
        needsRepaint = true
        updateInput()
    }

    override fun surfaceDetached(node: MacSurfaceNode) {
        if (surface !== node) return
        surface = null
        settling?.cancel()
        still = false
        placed = false
        bounds = null
        visible = null
        lastPlacedAt = null
        needsRepaint = true
        motionNeedsCover = false
        motionCover = null
        drawnHeight = 0f
        growthRepaint?.cancel()
        if (window != 0L && !released) glue.detach(window)
        window = 0L
        updateInput()
    }

    override fun surfacePlaced(node: MacSurfaceNode, window: Long, bounds: Rect, visible: Rect, scale: Float): Boolean {
        if (released || surface !== node || window == 0L) return false
        val newWindow = window != this.window
        if (newWindow) {
            if (this.window != 0L) glue.detach(this.window)
            this.window = window
            glue.attach(window)
            needsRepaint = true
            this.bounds = null
        }
        val nowPlaced = visible.width >= 1f && visible.height >= 1f && bounds.width >= 1f && bounds.height >= 1f
        if (bounds == this.bounds && visible == this.visible && scale == this.scale && nowPlaced == placed) return false
        val old = this.bounds
        val newWidth = old == null || old.width != bounds.width || scale != this.scale
        val newHeight = old == null || old.height != bounds.height
        val shownAgain = nowPlaced && !placed
        // WebKit draws the page again at a new size or scale, once shown again, and in a new window.
        val redraw = newWidth || newHeight || shownAgain || newWindow
        // Its content reflows meanwhile, but for an embed's new height alone: a page as tall as its content only shows
        // more or less of itself, and nothing it shows moves (C2).
        val reflows = redraw && (newWidth || shownAgain || newWindow || !node.inList)
        this.bounds = bounds
        this.visible = visible
        this.scale = scale
        if (redraw) needsRepaint = true
        placed = nowPlaced
        glue.place(bounds, visible, scale, hidden = !nowPlaced)
        moved(reflows)
        if (!reflows && newHeight && drawnHeight > 0f) {
            if (bounds.height > drawnHeight) followGrowth() else drawnHeight = bounds.height
        }
        return true
    }

    /**
     * An embed grew past what WebKit has drawn: until it has, the grown part shows the page's background ([drawFor]),
     * not its view's own white. WebKit is asked at once (a repaint: two frames), and again while it keeps growing, so new
     * content shows within frames, never only once the motion stops.
     */
    private fun followGrowth() {
        if (growthRepaint?.isActive == true) return
        growthRepaint = scope.launch {
            while (!released) {
                val height = bounds?.height ?: return@launch
                withTimeoutOrNull(REPAINT_MILLIS) { request { glue.repaint(it, WebKitScripts.REPAINT_BODY) } }
                if (released) return@launch
                if (height > drawnHeight) drawnHeight = height
                surface?.redraw()
                if ((bounds?.height ?: 0f) <= drawnHeight) return@launch
            }
        }
    }

    override fun surfaceUnplaced(node: MacSurfaceNode): Boolean {
        if (released || surface !== node || !placed) return false
        placed = false
        val slot = bounds ?: return true
        glue.place(slot, visible ?: slot, scale, hidden = true)
        moved(reflows = false)
        return true
    }

    /** The glue's requests that wait for their answer: for the tests. */
    internal val pendingRequests: Int get() = requests.size

    override fun drawFor(node: MacSurfaceNode): SurfaceDraw {
        if (released || surface !== node || !placed) return SurfaceDraw.Nothing
        val cover = motionCover
        // A dead page's view shows nothing worth seeing: its last picture, or Compose's own content (Minor 1).
        if (crashed) return if (cover != null) coverOf(cover) else SurfaceDraw.Nothing
        if (!still && motionNeedsCover && shown && cover != null && motionCoverEnabled) {
            coverDraws++
            return coverOf(cover)
        }
        val slot = bounds
        if (slot != null && node.inList && shown && drawnHeight > 0f && slot.height > drawnHeight + 0.5f && setup?.transparentBackground != true) {
            val background = motionCoverBackground.takeIf { it.alpha > 0f } ?: if (setup?.dark == true) Color.Black else Color.White
            return SurfaceDraw.Grown(drawnHeight, background)
        }
        return SurfaceDraw.Hole
    }

    /** [image] as the motion cover: at its true size, its slot's width in points then, at the window's scale now (C2). */
    private fun coverOf(image: ImageBitmap): SurfaceDraw.Cover {
        val width = motionCoverWidthPoints * scale
        return SurfaceDraw.Cover(image, if (width > 0f && image.width > 0) width / image.width else 1f, motionCoverBackground)
    }

    /**
     * The slot moved, resized, or was hidden or shown, and isn't still until drawn there again (D2). When this placement
     * makes the page reflow while WebKit draws it again ([reflows]: a new width or scale, a pane's new height, shown
     * again, a new window), its content trails inside its view until WebKit has: the motion cover stands in for it,
     * and the page takes no input, until the motion settles. A page that only moves stays live and keeps its input
     * (Minor 8): its view keeps up with its slot, and nothing trails behind a move. So does an embed that only grows.
     */
    private fun moved(reflows: Boolean) {
        val now = clock()
        val inMotion = lastPlacedAt?.let { now - it < MOTION_GAP_MILLIS } ?: false
        lastPlacedAt = now
        still = false
        placement++
        if (reflows && !motionNeedsCover) {
            motionNeedsCover = true
            if (shown && placed && motionCoverEnabled && (motionCover == null || now - motionCoverAt > COVER_STALE_MILLIS)) refreshMotionCover()
        }
        updateInput()
        settle(placement, if (inMotion) MOTION_SETTLE_MILLIS else 0L)
    }

    /**
     * Once the slot has been quiet for [quietMillis] (after a run of moves) and the page drawn again where it is now (if
     * it needs it), and two display ticks have passed with the view in its window: still, the hole shows the page, and
     * a waiting handoff goes on. A later move makes this one moot. A repaint that never answers (a page whose main
     * thread is stuck) is given up after [REPAINT_MILLIS]: the page shows and takes input anyway (Minor 4).
     */
    private fun settle(serial: Int, quietMillis: Long) {
        settling?.cancel()
        if (!placed) return
        settling = scope.launch {
            if (quietMillis > 0) delay(quietMillis)
            if (serial != placement || !placed) return@launch
            if (needsRepaint) {
                withTimeoutOrNull(REPAINT_MILLIS) { request { glue.repaint(it, WebKitScripts.REPAINT_BODY) } }
                if (released || serial != placement) return@launch
            }
            if (!ticksInWindow(FRAMES_IN_PLACE) || serial != placement) return@launch
            needsRepaint = false
            still = true
            motionNeedsCover = false
            drawnHeight = bounds?.height ?: 0f
            updateInput()
            surface?.redraw()
            onSurfaceReady()
        }
    }

    /** Takes a fresh motion cover when a motion starts and the last is old: it replaces the old one when it comes. */
    private fun refreshMotionCover() {
        val slot = bounds ?: return
        val pxPerPoint = scale
        if (refreshingCover || released || pxPerPoint <= 0f) return
        refreshingCover = true
        scope.launch {
            try {
                val widthPoints = slot.width / pxPerPoint
                val shot = takeSnapshot(snapshotWidthPoints(widthPoints.toDouble(), pxPerPoint.toDouble()))
                if (shot != null && !released) {
                    keepMotionCover(shot, widthPoints)
                    if (!still) surface?.redraw()
                }
            } finally {
                refreshingCover = false
            }
        }
    }

    private fun surfaceReady(): Boolean = surface != null && window != 0L && placed && still

    /** The surface is still in a window again: a waiting replay or handoff goes on. */
    private fun onSurfaceReady() {
        repaint?.let(::drawAgain)
        if (!paintOnScreen) return
        paintOnScreen = false
        handOff(committedEpoch)
    }

    private fun updateInput() {
        if (released) return
        val pxPerPoint = if (scale > 0f) scale else 1f
        val interactive = shown && placed && !motionNeedsCover
        val visible = shown && placed
        val key = listOf(region, pxPerPoint, interactive, visible, wheelToPage)
        if (key == sentInput) return
        sentInput = key
        glue.setInput(region, pxPerPoint, interactive, visible, wheelToPage)
    }

    /** The runtime's cover is lifted: the page shows, and takes input but while it reflows. */
    private fun showPage() {
        if (shown || crashed) return
        shown = true
        updateInput()
        surface?.redraw()
    }

    /** The runtime covers the page: it takes nothing until its next first paint. */
    private fun cover() {
        if (!shown) return
        shown = false
        updateInput()
        surface?.redraw()
    }

    /**
     * Waits [count] display ticks with the view in its window and not hidden; false as soon as one isn't. A tick that
     * never comes (an occluded window, a sleeping display: nobody sees the page) counts after [TICK_MILLIS], so the page
     * doesn't stay covered for good once someone does (Minor 4).
     */
    private suspend fun ticksInWindow(count: Int): Boolean {
        repeat(count) {
            val tick = withTimeoutOrNull(TICK_MILLIS) { request { glue.tick(it) } }
            if (released) return false
            if (tick != null && (tick as? GlueEvent.Tick)?.inWindow != true) return false
        }
        return true
    }

    /** A replayed first paint, once the page is in place in its new view ([drawAgain]): now, or once it is. */
    private fun awaitRepaint(epoch: Int) {
        val wait = Repaint(epoch)
        repaint = wait
        if (surfaceReady()) drawAgain(wait)
    }

    private fun drawAgain(wait: Repaint) {
        if (wait.started || released) return
        wait.started = true
        leaseScope().launch {
            val inPlace = ticksInWindow(FRAMES_IN_PLACE) && surfaceReady()
            if (repaint !== wait || queue.epoch != wait.epoch) return@launch
            if (!inPlace) {
                wait.started = false
                return@launch
            }
            repaint = null
            showPage()
            push(wait.epoch, EngineEvent.FirstPaint)
        }
    }

    // --- The glue's events ---

    /** Every upcall of this page, on the main thread ([MacPages]). */
    internal fun onGlueEvent(event: GlueEvent) {
        if (released) return
        when (event) {
            // It changes no state: push's epoch filter is all that keeps an earlier document's start from the subscriber.
            is GlueEvent.Started -> push(event.epoch, EngineEvent.Progress(START_PROGRESS))
            is GlueEvent.Committed -> onCommitted(event)
            is GlueEvent.Finished -> onFinished(event)
            is GlueEvent.Failed -> onFailed(event)
            is GlueEvent.Policy -> decideAction(event)
            is GlueEvent.Response -> decideResponse(event)
            is GlueEvent.State -> onState(event)
            is GlueEvent.Relay -> onRelay(event)
            is GlueEvent.NewWindow -> onNewWindow(event)
            is GlueEvent.Dialog -> onDialog(event)
            is GlueEvent.Media -> askPermission(event.question, PermissionRequest(WebKitUrls.libraryOrigin(event.protocol, event.host, event.port, bundle), event.kinds))
            is GlueEvent.OpenPanel -> onOpenPanel(event)
            is GlueEvent.TaskStart -> onTaskStart(event)
            is GlueEvent.TaskStop -> tasks.remove(event.task)?.cancel()
            is GlueEvent.Result -> requests.remove(event.request)?.resume(event)
            is GlueEvent.Snapshot -> requests.remove(event.request)?.resume(event)
            is GlueEvent.Tick -> requests.remove(event.request)?.resume(event)
            GlueEvent.Crashed -> onRendererGone()
            is GlueEvent.Pointer -> onPointer(event)
            is GlueEvent.Created, GlueEvent.Memory -> Unit
        }
    }

    private fun onCommitted(committed: GlueEvent.Committed) {
        page = committed.page
        val epoch = committed.epoch
        // A commit of an earlier document: WebKit replaces it with the current one's. Dropped before it touches the
        // committed epoch or the engine's own pending navigation (D4).
        if (epoch != queue.epoch) return
        committedEpoch = epoch
        contentCommitted = true
        restoring = null
        own = null
        document = null
        documentPainted = false
        paintOnScreen = false
        commits++
        handingOff = -1
        // A new document paints for itself: a replay's wait for the old one's is moot.
        repaint = null
        // Not its commit: its content says when it can be shown ([handOff]). One that paints nothing contentful and
        // never loads is shown anyway, after a while: the cover doesn't stay up for good.
        val commit = commits
        leaseScope().launch {
            delay(CONTENT_WAIT_MILLIS)
            if (commits == commit) handOff(epoch)
        }
        val url = committed.page.url
        committedUrl = url
        val libraryUrl = url?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        if (httpError?.url != libraryUrl) httpError = null
        push(epoch, EngineEvent.UrlChanged(libraryUrl))
        reportTitle(epoch, always = true)
        push(epoch, EngineEvent.Progress(maxOf(COMMIT_PROGRESS, committed.progress)))
        pushHistory()
    }

    private fun onFinished(finished: GlueEvent.Finished) {
        page = finished.page
        val epoch = finished.epoch
        if (epoch != queue.epoch) return
        if (WebKitUrls.isFloor(finished.page.url)) floorDone?.complete(finished.floorKept)
        push(epoch, EngineEvent.Progress(null))
        if (epoch == committedEpoch) {
            reportTitle(epoch, always = true)
            onSameDocumentChange(epoch)
        }
        // A document without the relay (a PDF, an image) has no paint report: its load says it has its content.
        if (epoch == committedEpoch && document == null && !documentPainted) handOff(epoch)
    }

    private fun onFailed(failed: GlueEvent.Failed) {
        if (failed.epoch != queue.epoch) return
        floorDone?.complete(false)
        own = null
        push(failed.epoch, EngineEvent.Progress(null))
        // A navigation replaced by another, or one the engine itself turned into a download, isn't an error.
        if (WebKitErrors.isIgnorable(failed.domain, failed.code)) return
        // The URL that failed, as WebKit says it. When it doesn't (a load refused a connection), the content the engine is
        // loading, if nothing of it committed: WebKit's own URL is the document still committed then, the floor or an
        // earlier page, never the one that failed.
        val url = failed.url?.let { WebKitUrls.toLibraryUrl(it, bundle) } ?: content?.takeIf { !contentCommitted }?.let(::libraryUrlOf)
        push(failed.epoch, EngineEvent.Error(WebKitErrors.toWebError(failed.domain, failed.code, failed.description, url)))
        // The runtime lifts its cover on a main-frame error: the page then shows what WebKit has, and takes input.
        showPage()
    }

    private fun onState(state: GlueEvent.State) {
        page = state.page
        if (state.epoch != queue.epoch || state.epoch != committedEpoch) return
        reportTitle(state.epoch, always = false)
        onSameDocumentChange(state.epoch)
    }

    private fun onRendererGone() {
        if (crashed) return
        crashed = true
        floorDone?.complete(false)
        // The view is dead: no hole over it, no input into it; its last picture covers the slot until the runtime acts.
        shown = false
        updateInput()
        surface?.redraw()
        push(queue.epoch, EngineEvent.Crashed)
        // Last: a pool that parked the page releases the engine for it.
        onUnusable?.invoke()
    }

    /** The page took a press or a key (D7): it is in use, the next second's navigations are the user's, a menu closes. */
    private fun onPointer(pointer: GlueEvent.Pointer) {
        lastInput = clock()
        setFocused(queue.epoch, true)
        if (pointer.xPoints >= 0 && pointer.yPoints >= 0) surface?.dismissPopupsAt(pointer.xPoints, pointer.yPoints)
    }

    // --- Navigation ---

    private fun decideAction(action: GlueEvent.Policy) {
        val javaScript = setup?.javaScriptEnabled ?: true
        val url = action.url
        if (action.download && url != null) {
            askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), null, null, null))
            glue.answerPolicy(action.question, false, javaScript)
            return
        }
        val gesture = userGesture(spend = false)
        val verdict = decideNavigation(url, action.mainFrame, action.opensWindow, action.kind, gesture, own, bundle, htmlUrl, action.fromNetwork)
        when (verdict) {
            NavigationVerdict.Own -> {
                // Until it commits, its server redirects are the engine's own too (I6).
                own = own?.afterAllowed(action.kind)
                glue.answerPolicy(action.question, true, javaScript)
            }
            NavigationVerdict.Allow -> glue.answerPolicy(action.question, true, javaScript)
            NavigationVerdict.Refuse -> {
                glue.answerPolicy(action.question, false, javaScript)
                if (redirectOfOwn(own, action.mainFrame, action.kind, action.fromNetwork)) {
                    endRedirectedLoad(url)
                } else if (action.mainFrame && WebKitUrls.isFloor(url) && refusable(own)) {
                    // The engine's own navigation reached the floor, which only a reset may: a reload or a restore of what
                    // was the floor's. An error, never a page that sits still under its cover.
                    refusedOwn()
                }
            }
            NavigationVerdict.ReloadHtml -> {
                glue.answerPolicy(action.question, false, javaScript)
                val html = content as? WebContent.Html ?: return
                own = OwnNavigation.Load(html.origin.url)
                glue.loadHtml(queue.epoch, html.html, html.origin.url)
            }
            is NavigationVerdict.Ask -> {
                // A press of the user's makes one request of the page's to leave theirs, never every one in its second.
                if (gesture && action.mainFrame) userGesture(spend = true)
                // Synchronous, as WebKit's policy questions are answered: the runtime's client answers at once.
                val decision = try {
                    client?.onNavigation(verdict.request)
                } catch (e: Exception) {
                    null
                }
                glue.answerPolicy(action.question, decision == NavigationDecision.Allow, javaScript)
            }
        }
    }

    /**
     * The server redirected the engine's own load off the web, and the engine refused it (I6): the load ends here, with
     * an error the runtime shows, as Android's does. WebKit's own report of a refused navigation is one the engine
     * ignores, so without this the cover would stay up for good.
     */
    private fun endRedirectedLoad(url: String?) {
        own = null
        val epoch = queue.epoch
        push(epoch, EngineEvent.Progress(null))
        push(epoch, EngineEvent.Error(WebError.BlockedByPolicy(url?.let { WebKitUrls.toLibraryUrl(it, bundle) })))
        showPage()
    }

    /**
     * The engine's own navigation was refused by the rules: the content isn't shown, and the app hears so, with the
     * page's cover lifted as for any failed load. A retry loads it again, WebKit's committed document being the floor's.
     */
    private fun refusedOwn() {
        own = null
        contentCommitted = false
        val shown = content ?: return
        push(queue.epoch, EngineEvent.Error(WebError.BlockedByPolicy(libraryUrlOf(shown))))
        showPage()
    }

    /**
     * Whether [own] is a navigation of the engine's not yet allowed, whose refusal [refusedOwn] reports: not the floor's,
     * which is the rules' own, nor one allowed already and waiting for its commit ([OwnNavigation.Redirecting]), which
     * the page's own navigation meanwhile doesn't end.
     */
    private fun refusable(own: OwnNavigation?): Boolean = own != null && own != OwnNavigation.Floor && own !is OwnNavigation.Redirecting

    /** [content]'s URL in the library's terms: where the app asked it to be. */
    private fun libraryUrlOf(content: WebContent): String = when (content) {
        is WebContent.Url -> content.url
        is WebContent.Html -> content.origin.url
        is WebContent.Bundle -> WebKitUrls.toLibraryUrl(WebKitUrls.bundleUrl(content.origin, escapeUrlPath(content.entry)), content.origin)
    }

    private fun decideResponse(response: GlueEvent.Response) {
        val attachment = response.disposition?.trim()?.startsWith("attachment", ignoreCase = true) == true
        if (!response.forMainFrame || response.canShow && !attachment) {
            val status = response.status
            if (response.forMainFrame && status != null && status >= 400) {
                httpError = WebError.Http(response.url?.let { WebKitUrls.toLibraryUrl(it, bundle) }, status)
            }
            glue.answerResponse(response.question, true)
            return
        }
        // A download. The client hears of it; the engine saves nothing, either way (D13).
        val url = response.url
        if (url != null) {
            askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), response.suggestedFilename, response.mimeType, response.length))
        }
        glue.answerResponse(response.question, false)
    }

    private fun onNewWindow(request: GlueEvent.NewWindow) {
        val url = request.url
        if (url.isNullOrEmpty() || WebKitUrls.isPrivate(url) && !(bundle?.let { WebKitUrls.isBundleUrl(url, it) } ?: false)) return
        val decision = try {
            client?.onNewWindow(NewWindowRequest(WebKitUrls.toLibraryUrl(url, bundle), userGesture(spend = true)))
        } catch (e: Exception) {
            null
        }
        if (decision != NewWindowDecision.OpenHere) return
        own = OwnNavigation.Load(url)
        glue.loadUrl(queue.epoch, url, emptyMap())
    }

    // --- The relay ---

    private fun onRelay(relay: GlueEvent.Relay) {
        if (!relay.mainFrame || relay.epoch != queue.epoch) return
        val parsed = RelayMessage.parse(relay.body) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        val current = document
        if (parsed.document != current) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch)
            is RelayMessage.Focus -> setFocused(epoch, parsed.focused)
            // Key-value observation reports WebKit's own title and URL (M13): the relay's hints add nothing here.
            is RelayMessage.Title, is RelayMessage.Url -> Unit
            // The document's own token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(
                epoch,
                EngineEvent.PageMessage(WebKitUrls.libraryOrigin(relay.protocol, relay.host, relay.port, bundle), parsed.json),
                current,
            )
            // Reported only by relays built to (iOS): the glue's own input events say what the user did here.
            is RelayMessage.Input -> Unit
            is RelayMessage.Start -> Unit
        }
    }

    /**
     * The committed document has content (the relay's `paint`, a load without the relay, or [CONTENT_WAIT_MILLIS] of
     * nothing). It says [EngineEvent.FirstPaint] once the surface is placed and still and two more display ticks have
     * passed with the view in its window, under the runtime's cover (D6); out of place, it waits until the surface is
     * ([onSurfaceReady]). Once per document.
     */
    private fun handOff(epoch: Int) {
        if (released || documentPainted || epoch != committedEpoch) return
        val commit = commits
        if (handingOff == commit) return
        if (!surfaceReady()) {
            paintOnScreen = true
            return
        }
        handingOff = commit
        leaseScope().launch {
            val inPlace = ticksInWindow(FRAMES_IN_PLACE) && surfaceReady()
            if (commits != commit || documentPainted || handingOff != commit) return@launch
            if (!inPlace) {
                handingOff = -1
                paintOnScreen = true
                return@launch
            }
            documentPainted = true
            showPage()
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

    private fun reportTitle(epoch: Int, always: Boolean): Boolean {
        val title = page.title?.takeIf { it.isNotEmpty() }
        if (!always && title == reportedTitle) return false
        reportedTitle = title
        push(epoch, EngineEvent.TitleChanged(title))
        return true
    }

    /** Reports WebKit's URL if the document moved within itself (pushState, a fragment) while nothing loads. */
    private fun onSameDocumentChange(epoch: Int): Boolean {
        val url = page.url
        val moved = url != null && url != committedUrl && !page.loading && !WebKitUrls.isFloor(url)
        if (moved && url != null) {
            committedUrl = url
            push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        }
        pushHistory()
        return moved
    }

    private fun historyOf(page: WebKitPage) =
        EngineEvent.HistoryChanged(WebKitUrls.canGoBack(page.back), WebKitUrls.canGoForward(page.forward))

    private fun pushHistory() {
        val next = historyOf(page)
        if (next == history) return
        history = next
        push(queue.epoch, next)
    }

    // --- Client requests ---

    private fun onDialog(dialog: GlueEvent.Dialog) {
        val frame = dialog.frameUrl?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        val question = dialog.question
        when (dialog.kind) {
            DialogKind.Alert -> ask({ glue.answerDialog(question, true, null) }, { it.onDialog(WebDialog.Alert(dialog.message, frame)) }) {
                glue.answerDialog(question, true, null)
            }
            DialogKind.Confirm -> ask({ glue.answerDialog(question, false, null) }, { it.onDialog(WebDialog.Confirm(dialog.message, frame)) }) {
                glue.answerDialog(question, it == DialogResult.Confirmed, null)
            }
            DialogKind.Prompt -> ask(
                { glue.answerDialog(question, false, null) },
                { it.onDialog(WebDialog.Prompt(dialog.message, dialog.defaultText ?: "", frame)) },
            ) {
                when (it) {
                    is DialogResult.Text -> glue.answerDialog(question, true, it.value)
                    DialogResult.Confirmed -> glue.answerDialog(question, true, dialog.defaultText ?: "")
                    DialogResult.Dismissed -> glue.answerDialog(question, false, null)
                }
            }
        }
    }

    private fun askPermission(question: Long, request: PermissionRequest) =
        ask({ glue.answerPermission(question, false) }, { it.onPermission(request) }) { glue.answerPermission(question, it) }

    /** macOS has an open-panel hook (D13): the client's paths, or a cancel. */
    private fun onOpenPanel(panel: GlueEvent.OpenPanel) =
        ask({ glue.answerOpenPanel(panel.question, null) }, { it.onFileChooser(FileChooserRequest(emptyList(), panel.multiple)) }) {
            glue.answerOpenPanel(panel.question, it)
        }

    /**
     * Serves the lease's bundle at `webview-bundle://<host>.invalid/…` and nothing else: another host, or no bundle,
     * fails as an unknown host does. Paths go through [normalizeBundlePath] first; a path it refuses is a 404. A task
     * WebKit stopped meanwhile is never answered.
     */
    private fun onTaskStart(start: GlueEvent.TaskStart) {
        val url = start.url
        val served = content as? WebContent.Bundle
        if (url == null || served == null || !WebKitUrls.isBundleUrl(url, served.origin)) {
            glue.failTask(start.task)
            return
        }
        val path = normalizeBundlePath(WebKitUrls.rawPath(url))
        if (path == null) {
            glue.respond(start.task, 404, "text/plain", ByteArray(0), emptyMap())
            return
        }
        val file = path.ifEmpty { served.entry }
        val task = start.task
        tasks[task] = scope.launch {
            val resource = try {
                served.resources.load(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (tasks.remove(task) == null) return@launch
            if (resource == null) {
                glue.respond(task, 404, "text/plain", ByteArray(0), emptyMap())
            } else {
                glue.respond(task, 200, resource.mimeType, resource.bytes, resource.headers)
            }
        }
    }

    private fun cancelTasks() {
        for (job in tasks.values) job.cancel()
        tasks.clear()
    }

    /** Tells the client of a download, which the engine has already cancelled: nothing waits on the answer. */
    private fun askAboutDownload(request: DownloadRequest) {
        val asked = client ?: return
        leaseScope().launch {
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
     * Asks the client [question] on main and hands its answer to [answer]; with no client, a null or failed answer, or
     * a reset or release first, WebKit gets [byDefault], the safe answer. Exactly one of them runs.
     */
    private fun <T : Any> ask(byDefault: () -> Unit, question: suspend (EngineClient) -> T?, answer: (T) -> Unit) {
        val pending = Pending(byDefault)
        val asked = client
        if (asked == null || released || resetting) return pending.answerByDefault()
        questions += pending
        pending.job = leaseScope().launch {
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

    private fun leaseScope(): CoroutineScope = CoroutineScope(scope.coroutineContext + lease)

    /**
     * Queues [event], caused in [epoch]. What an earlier epoch caused is dropped here, before it is queued. The engine's
     * own events pass whatever the epoch: a crash, and focus changes, which the queue drops only at a reset.
     */
    private fun push(epoch: Int, event: EngineEvent, document: Any? = null) {
        if (epoch != queue.epoch && event != EngineEvent.Crashed && event !is EngineEvent.FocusChanged) return
        queue.offer(event, document)
    }

    /** A new document of [content] starts: nothing an earlier one caused is delivered from now on. */
    private fun startDocument(content: WebContent): Int {
        val epoch = queue.advance()
        this.content = content
        documentPainted = false
        paintOnScreen = false
        handingOff = -1
        repaint = null
        httpError = null
        document = null
        history = null
        // A press in the document before is no gesture in this one.
        lastInput = null
        return epoch
    }

    /**
     * Whether a request of the page's is the user's: they pressed a key or the page within [GESTURE_MILLIS]. A request it
     * was credited to [spend]s it: one press, one request to leave, as on iOS.
     */
    private fun userGesture(spend: Boolean): Boolean {
        val at = lastInput ?: return false
        val theirs = clock() - at <= GESTURE_MILLIS
        if (theirs && spend) lastInput = null
        return theirs
    }

    /**
     * One request to the glue, answered by the upcall that names its id; null once released. Main thread. One whose
     * waiter is cancelled (a newer motion, a timeout) is forgotten at once (Minor 4): its late answer finds nothing.
     */
    private suspend fun request(send: (Long) -> Unit): GlueEvent? {
        if (released) return null
        return suspendCancellableCoroutine { continuation ->
            val id = nextRequest++
            requests[id] = continuation
            continuation.invokeOnCancellation { requests.remove(id) }
            send(id)
        }
    }

    private suspend fun evaluateIn(pageWorld: Boolean, script: String): String? {
        val result = request { glue.evaluate(it, script, pageWorld) } as? GlueEvent.Result ?: return null
        return if (result.ok) result.json else null
    }

    /** A snapshot, and the page's background along its edges (what a cover shows past it), both made off the main thread. */
    private class Shot(val image: ImageBitmap, val background: Color)

    private suspend fun takeSnapshot(widthPoints: Double): Shot? {
        val shot = withTimeoutOrNull(SNAPSHOT_MILLIS) { request { glue.snapshot(it, widthPoints) } } as? GlueEvent.Snapshot ?: return null
        val rgba = shot.rgba ?: return null
        if (released) return null
        return withContext(Dispatchers.Default) {
            imageBitmapOf(shot.width, shot.height, rgba)?.let { Shot(it, coverBackgroundOf(it)) }
        }
    }

    companion object {
        /** How long after a press or a key a navigation counts as the user's (WebKit doesn't say). */
        private const val GESTURE_MILLIS = 1_000L
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val WARM_UP_MILLIS = 10_000L

        // The pool bounds a reset at 10 s; these two stay within it.
        private const val FLOOR_MILLIS = 4_000L
        private const val WIPE_MILLIS = 5_000L
        private const val SAVE_MILLIS = 3_000L
        private const val SNAPSHOT_MILLIS = 5_000L

        /** How long a committed document may go without content before it is shown anyway. */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** The display ticks a document, or a settled slot, is shown in its window before the next step (D2, D6). */
        private const val FRAMES_IN_PLACE = 2

        /** Placements closer together than this are one motion (D2). */
        internal const val MOTION_GAP_MILLIS = 50L

        /** How long a moving slot must be quiet before the page is drawn and shown there again (D2). */
        internal const val MOTION_SETTLE_MILLIS = 100L

        /** A motion cover older than this is taken again when a motion starts. */
        private const val COVER_STALE_MILLIS = 1_000L

        /** How long a settle waits for the page to be drawn again before it shows the page anyway (Minor 4). */
        private const val REPAINT_MILLIS = 1_500L

        /** How long a display tick may take before it counts anyway (Minor 4): at 60 Hz one comes every 17 ms. */
        private const val TICK_MILLIS = 500L

        /** False shows the live, lagging page while a slot moves: the motion checks' baseline (Task 15). */
        @Volatile internal var motionCoverEnabled: Boolean = true

        /** How many times a surface has been drawn with the motion cover in this process: for the motion checks. */
        @Volatile internal var coverDraws: Int = 0
    }
}

/** The `NSWindow` behind a Compose window or dialog (M8); 0 for anything else, and until skiko's layer is in it. */
internal fun composeWindowHandle(window: java.awt.Window?): Long = when (window) {
    is ComposeWindow -> window.windowHandle
    is ComposeDialog -> window.windowHandle
    else -> 0L
}

/** RGBA, premultiplied, top row first, as an [ImageBitmap] in full colour; null if Skia refuses it. */
internal fun imageBitmapOf(width: Int, height: Int, rgba: ByteArray): ImageBitmap? {
    if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return null
    val bitmap = Bitmap()
    if (!bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) return null
    if (!bitmap.installPixels(rgba)) return null
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
