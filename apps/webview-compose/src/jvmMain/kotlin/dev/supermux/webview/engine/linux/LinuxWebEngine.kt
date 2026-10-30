package dev.supermux.webview.engine.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DialogResult
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import java.io.File
import java.net.URI
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebResource
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.EngineEventQueue
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.webkit.RelayMessage
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.engine.webkit.escapeUrlPath
import dev.supermux.webview.normalizeBundlePath
import dev.supermux.webview.originOf
import dev.supermux.webview.runtime.OverlayCutouts
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The script world the relay runs in and holds the message handler in (F16): the page's own world can't reach it. */
internal object WebEngineWorld {
    const val WORLD: String = "webview"
}

/**
 * The Linux engine: one WPE WebKit web view on the glue's own WPEPlatform display, its frames drawn by Compose (spec §7
 * "Linux: frames"), following the [WebEngine] contract. It is the iOS engine's logic on WPE: the same WebKit scripts,
 * relay, URLs, saved state and navigation rules (`engine/webkit`), adapted where the feasibility test found WPE
 * differs: a navigation's frame is known at its response ([WpeNavigation], W4), and dark mode is the process's (W9).
 * Main thread only: the glue's events reach [onEvent] there through [WpeHost], in the order WebKit made them.
 *
 * - **Events** go through an [EngineEventQueue], with an epoch at every load, reload, restore and reset.
 *   - Until the new load starts, WebKit's load events are the last document's, and are dropped.
 *   - Title and URL changes count only for the document committed in the current epoch.
 *   - The relay's messages count only for the current document's token.
 * - **The bridge:** [WebKitScripts]. The relay runs in [WebEngineWorld.WORLD] and holds the message handler; the shim
 *   runs in the page's world.
 * - **Local content:**
 *   - `Html` loads with `load_html` on its `https://<hash>.invalid` origin;
 *   - `Bundle` is served on `webview-bundle://<host>.invalid` ([onScheme]);
 *   - the floor is served on `webview-floor://reset/`.
 */
internal class LinuxWebEngine(
    val config: EngineConfig,
    internal val view: WpeView,
    private val scripts: WebKitScripts,
    private val scope: CoroutineScope,
) : WebEngine {
    private class Pending(val byDefault: () -> Unit, val holdsPage: Boolean) {
        var job: Job? = null
        private var answered = false

        fun answer(block: () -> Unit) {
            if (answered) return
            answered = true
            block()
        }

        fun answerByDefault() = answer(byDefault)
    }

    /** A page that floods its bridge while parked goes the way of a crash: discarded, never adopted with less said. */
    private val queue = EngineEventQueue(onOverflow = ::onCrashed)
    private val navigation = WpeNavigation()
    private val questions = mutableListOf<Pending>()
    private val evals = HashMap<Long, CompletableDeferred<String?>>()
    private var nextRequest = 1L
    private var lease: Job = SupervisorJob(scope.coroutineContext[Job])
    private var released = false
    private var crashed = false
    private var resetting = false
    private var resetFailed = false
    private var setup: PageSetup? = null
    private var content: WebContent? = null
    private var own: OwnNavigation? = null

    /** A load of ours was asked for and hasn't started: WebKit's load events until then are the last document's. */
    private var awaitingStart = false

    /** Between a load's start and its commit or failure: a URL change then is that load's, not the document's. */
    private var provisional = false
    private var committedEpoch = -1
    private var committedUrl: String? = null
    private var commits = 0
    private var reportedTitle: String? = null
    private var document: String? = null
    private var documentPainted = false
    private var httpError: WebError? = null
    private var focused: Boolean? = null
    private var history: EngineEvent.HistoryChanged? = null
    private var backList: List<String> = emptyList()
    private var forwardList: List<String> = emptyList()
    private var floorState: ByteArray? = null
    private var floorDone: CompletableDeferred<Boolean>? = null
    private var baseUserAgent: String? = null
    private var dark: Boolean? = null

    /** Data wipes under way ([wipeData]), by request. */
    private val clears = HashMap<Long, CompletableDeferred<Boolean>>()

    /** Bundle requests the provider hasn't answered: a reset or a release answers them 404, so the glue lets them go. */
    private val schemeRequests = mutableSetOf<Long>()

    /** Whether the page is shown: WebKit's view visible, its sound on ([setVisible]). */
    private var visible = true

    override val mode: EmbeddingMode get() = EmbeddingMode.Frames
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
        WpeHost.listen(view.id, ::onEvent)
        // Pulled at the surface's frames and acknowledged then (F10); damage as S5 found it (WpeDefaults).
        view.frameMode(WpeDefaults.DAMAGE_SCALE)
    }

    /** Loads the floor and keeps its state for [reset], and learns WebKit's own user agent. False if the engine can't. */
    internal suspend fun warmUp(): Boolean {
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        awaitingStart = true
        view.loadUri(WebKitUrls.FLOOR_URL, emptyMap())
        val loaded = withTimeoutOrNull(WARM_UP_MILLIS) { done.await() } ?: false
        floorDone = null
        if (!loaded || released || crashed) return false
        floorState = view.saveState()
        baseUserAgent = evaluateIn(WebEngineWorld.WORLD, "navigator.userAgent")
            ?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull }
        // Nothing from before the first lease reaches it: as after a reset.
        queue.advance(reset = true)
        return floorState != null
    }

    // --- The lease ---

    override fun configure(setup: PageSetup) {
        if (!leaseCallAllowed()) return
        this.setup = setup
        val origins = WebKitUrls.pageOrigins(setup.bridgeOrigins)
        val background = when {
            setup.transparentBackground -> 0x00000000
            setup.dark -> 0xFF000000.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val agent = setup.userAgentSuffix?.let { suffix -> listOfNotNull(baseUserAgent, suffix).joinToString(" ") }
        view.configure(
            scripts.relay(origins), if (setup.javaScriptEnabled) scripts.shim(origins) else null, WebEngineWorld.WORLD,
            scripts.handlerName, setup.javaScriptEnabled, setup.inspectable, background, agent,
        )
        // WPE's dark mode is the process's (W9): the last lease to set its theme sets every page's.
        if (dark != setup.dark) {
            dark = setup.dark
            view.setDark(setup.dark)
        }
    }

    override fun load(content: WebContent) {
        if (!leaseCallAllowed()) return
        startDocument(content)
        when (content) {
            is WebContent.Url -> {
                own = OwnNavigation.Load(content.url)
                view.loadUri(content.url, content.headers)
            }
            is WebContent.Html -> {
                // WebKit keeps load_html's document out of session history, so back from a page it navigates to skips
                // it. load_alternate_html would add the entry, but going back to it refetches the origin, which doesn't
                // resolve (measured on WPE 2.54.2, 2026-10-30): no entry is the clean choice.
                own = OwnNavigation.Load(content.origin.url)
                view.loadHtml(content.html, content.origin.url)
            }
            is WebContent.Bundle -> {
                val url = WebKitUrls.bundleUrl(content.origin, escapeUrlPath(content.entry))
                own = OwnNavigation.Load(url)
                view.loadUri(url, emptyMap())
            }
        }
    }

    override fun reload() {
        if (released || resetting) return
        val shown = content ?: return
        // WebKit would fetch in-memory HTML from its origin, which doesn't resolve: load it again instead.
        if (shown is WebContent.Html) {
            load(shown)
            return
        }
        startDocument(shown)
        own = OwnNavigation.Reload
        view.navigate(WpeNative.GO_RELOAD)
    }

    override fun goBack() {
        if (released || resetting || !WebKitUrls.canGoBack(backList)) return
        own = OwnNavigation.History
        view.navigate(WpeNative.GO_BACK)
    }

    override fun goForward() {
        if (released || resetting || !WebKitUrls.canGoForward(forwardList)) return
        own = OwnNavigation.History
        view.navigate(WpeNative.GO_FORWARD)
    }

    override fun stop() {
        if (!released) view.navigate(WpeNative.GO_STOP)
    }

    override suspend fun evaluate(script: String): String? = if (released) null else evaluateIn(null, script)

    /**
     * Delivers [json] to the main frame's shim, if its document's origin is [targetOrigin] when it arrives: the delivery
     * body runs in the engine's script world, where `self.origin` is the document's and the page can't redefine it (B3).
     * A bundle's origin is given as its pages see it, `webview-bundle://<host>.invalid`. Nothing waits for an answer.
     */
    override fun postMessage(json: String, targetOrigin: String) {
        if (!leaseCallAllowed()) return
        val arguments = mapOf("json" to json, "target" to WebKitUrls.pageOrigin(targetOrigin, bundle), "name" to scripts.inEvent)
        view.callAsync(NO_ANSWER, scripts.deliveryBody, WebEngineWorld.WORLD, arguments)
    }

    /**
     * The page's last frame, copied: at the screen's pixels, in full colour, its alpha kept for a transparent page, and
     * never what the app draws over it. Null before the document's first paint, and with no surface composed at a size.
     */
    override suspend fun snapshot(): ImageBitmap? {
        if (released || !documentPainted || surfaces == 0 || size == null) return null
        return frames.copy()
    }

    override suspend fun saveState(): ByteArray? {
        if (released) return null
        val shown = content ?: return null
        // A document of in-memory HTML can't be restored: WebKit would fetch it from its origin, which doesn't resolve.
        if (shown is WebContent.Html) return null
        val state = view.saveState() ?: return null
        return SavedWebKitState.encode(shown.key, state)
    }

    override fun restoreState(state: ByteArray, content: WebContent): Boolean {
        if (!leaseCallAllowed() || content is WebContent.Html) return false
        val saved = SavedWebKitState.decode(state, content.key) ?: return false
        startDocument(content)
        own = OwnNavigation.Restore
        if (!view.restoreState(saved)) {
            own = null
            awaitingStart = false
            return false
        }
        return true
    }

    /**
     * A hidden page (off screen, or parked for the next view of it) is hidden from WebKit, which throttles its timers
     * and stops drawing it, and its sound is muted: WPE has no API to suspend a page's media as iOS does. Its document
     * stays as it is, and the engine keeps its last frame for the next surface.
     */
    override fun setVisible(visible: Boolean) {
        if (released || crashed || this.visible == visible) return
        this.visible = visible
        view.setVisible(visible)
    }

    /**
     * Tells a new subscriber what the page is now ([WebEngine.replayState]):
     * - its URL, a same-document change included;
     * - its history and its title;
     * - if the document had painted, its first paint again, once a surface has drawn it in its new place
     *   ([waitForDrawing]). The surface's first draw is the frame the page left, so that is at once.
     *
     * The size is the shim's to tell. Nothing starts: no epoch, so the relay's token still names the document. Nothing
     * without a committed document of the current epoch: a load under way reports its own commit.
     */
    override fun replayState() {
        if (!leaseCallAllowed() || crashed || content == null || committedEpoch != queue.epoch) return
        val url = committedUrl ?: return
        if (WebKitUrls.isFloor(url)) return
        val epoch = queue.epoch
        push(epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(url, bundle)))
        history = null
        pushHistory()
        push(epoch, EngineEvent.TitleChanged(reportedTitle))
        focused = null
        if (documentPainted) waitForDrawing(Handoff(epoch, commits, view.frameSeq().coerceAtLeast(0), replay = true))
    }

    /** Moves Compose's focus into the composed surface, and so WebKit's; with none composed, tells WebKit alone. */
    override fun requestFocus() {
        if (released) return
        val requester = focusRequester
        if (requester == null || runCatching { requester.requestFocus() }.isFailure) view.setFocus(true)
    }

    override fun applyOverlays(cutouts: OverlayCutouts) = Unit

    /** The page's frames, drawn and fed input by [FrameSurface]. */
    @Composable
    override fun Surface(modifier: Modifier) {
        if (released) return
        FrameSurface(this, modifier)
    }

    /**
     * Readies the engine for another lease, with the contract's postconditions:
     * - the floor state goes back, so the back-forward list is the floor alone, which nothing but a reset reaches (W5);
     * - what the client was asked and hasn't answered gets its safe default, and the bundle's open requests a 404;
     * - the lease's scripts go: the next lease's [configure] installs its own;
     * - an ephemeral engine's network session is wiped of every kind of data: its own, no other engine's (F20);
     * - nothing from before or during it is delivered after it.
     * False if any of it failed or didn't happen in time; the pool then destroys the engine.
     */
    override suspend fun reset(): Boolean {
        if (released || crashed || resetting) return false
        resetting = true
        resetFailed = true
        // The last lease's frame never shows again: not under the next lease's cover, nor in its snapshot.
        frames.hideUntil(Long.MAX_VALUE)
        // A reset's epochs drop the last lease's queued focus changes too: the next lease's view would take them.
        queue.advance(reset = true)
        client = null
        setup = null
        content = null
        handoff = null
        answerAllByDefault()
        finishSchemeRequests()
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        view.navigate(WpeNative.GO_STOP)
        // A page that doesn't answer is hung: the floor would load in another process and leave its own spinning, out
        // of the view's reach (desktop, 2026-10-30). It is ended, and the engine with it.
        if (!pageAnswers(ANSWER_MILLIS)) {
            endHungPage()
            resetting = false
            return false
        }
        view.setFocus(false)
        view.configure(null, null, WebEngineWorld.WORLD, scripts.handlerName, false, false, FLOOR_BACKGROUND, null)
        val floorShown = showFloor()
        val wiped = config.profile != WebProfile.Ephemeral || wipeData()
        resetting = false
        if (released || crashed || !floorShown || !wiped) return false
        // The floor's own events and timers, and anything else from before or during the reset, stay behind.
        queue.advance(reset = true)
        lease.cancel()
        lease = SupervisorJob(scope.coroutineContext[Job])
        own = null
        document = null
        committedUrl = null
        committedEpoch = -1
        reportedTitle = null
        documentPainted = false
        handoff = null
        httpError = null
        focused = null
        history = null
        navigation.clear()
        resetFailed = false
        return true
    }

    /**
     * Puts the floor state back (W5, F19): the back-forward list becomes the floor alone, and the floor loads. True
     * once it has, committed, with nothing in the list before it and nothing but the floor after it.
     */
    private suspend fun showFloor(): Boolean {
        val state = floorState ?: return false
        val done = CompletableDeferred<Boolean>()
        floorDone = done
        own = OwnNavigation.Floor
        awaitingStart = true
        // What WebKit reports of the list from now on is the floor's; until it does, the floor's list is empty (W5).
        backList = emptyList()
        forwardList = emptyList()
        val onFloor = committedUrl == null || WebKitUrls.isFloor(committedUrl)
        if (!view.restoreState(state)) {
            floorDone = null
            own = null
            awaitingStart = false
            return false
        }
        // The restore goes to the list's current item, the floor; a view on the floor already loads nothing then: a lease
        // whose page never committed (an unreachable URL, say) left the floor shown. That floor is reloaded instead, or
        // the reset would wait for a load that never comes (the sample's perf lab on WPE, offline: 6 resets in 150).
        if (onFloor) view.navigate(WpeNative.GO_RELOAD)
        val shown = withTimeoutOrNull(FLOOR_MILLIS) { done.await() } ?: false
        floorDone = null
        return shown && WebKitUrls.isFloor(committedUrl) && backList.isEmpty() && forwardList.all { WebKitUrls.isFloor(it) }
    }

    /** Wipes this engine's own network session of every kind of website data (F20). True once WebKit says it did. */
    private suspend fun wipeData(): Boolean {
        val request = nextRequest++
        val done = CompletableDeferred<Boolean>()
        clears[request] = done
        view.clearData(request)
        return try {
            withTimeoutOrNull(WIPE_MILLIS) { done.await() } ?: false
        } finally {
            clears.remove(request)
        }
    }

    /** Answers the bundle's open requests with a 404: the lease whose provider they wait on is over. */
    private fun finishSchemeRequests() {
        for (request in schemeRequests) view.finishScheme(request, 404, "text/plain", null)
        schemeRequests.clear()
    }

    override fun release() {
        if (released) return
        answerAllByDefault()
        released = true
        client = null
        evals.values.forEach { it.complete(null) }
        evals.clear()
        clears.values.forEach { it.complete(false) }
        clears.clear()
        floorDone?.complete(false)
        finishSchemeRequests()
        scope.cancel()
        WpeHost.forget(view.id)
        // Its process first: dropped alone, the view waits for its page to close, which a page in a loop never does,
        // and its process would spin on until the app exits. The glue cancels the view's script calls with it.
        view.terminate()
        view.destroy()
        frames.close()
        focusRequester = null
    }

    // --- The surface (FrameSurface) ---

    /**
     * The page's frames: what every surface of this engine draws, and what a snapshot copies. Nothing shows until a
     * document of a lease commits, and from a reset or a load on, nothing of the document before: under a transparent
     * page's cover, another lease's page or the floor's white would show through ([showFramesOf]).
     */
    internal val frames = FrameStore(view).apply { hideUntil(Long.MAX_VALUE) }

    /** WebKit delivered a frame: the surface pulls it at its next Compose frame. */
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    /** Surfaces in the composition now. */
    private var surfaces = 0

    /** What WebKit was last told of the view's size: logical pixels, and the scale. */
    private var size: Triple<Int, Int, Double>? = null

    /** The size when the document being loaded started loading: its commit compares ([sizeAgain]). */
    private var loadSize: Triple<Int, Int, Double>? = null

    /** The page's cursor over the surface. */
    internal var cursor: PointerIcon by mutableStateOf(PointerIcon.Default)
        private set

    /** The composed surface's focus requester: [requestFocus] moves Compose's focus there. */
    internal var focusRequester: FocusRequester? = null

    internal fun onSurfaceAttached(attached: Boolean) {
        val before = surfaces
        surfaces = (surfaces + if (attached) 1 else -1).coerceAtLeast(0)
        // A surface that comes has drawn nothing yet; the last one to go takes what it drew, and a handoff's count, along.
        if (before == 0 && surfaces > 0) frames.forgetDrawn()
        if (surfaces == 0) handoff?.framesInPlace = -1
        if (surfaces > 0) startHeartbeat() else stopHeartbeat()
    }

    /** At the start of a Compose frame: the newest frame, whole if [full]; then the handoff's count. */
    internal fun onSurfaceFrame(full: Boolean) {
        if (released) return
        frames.pull(full)
        advanceHandoff()
    }

    /**
     * A surface drew the frame pulled last: if that is the handoff's frame or a newer one, at the surface's size, its
     * count starts.
     */
    internal fun onSurfaceDrawn() {
        frames.onDrawn()
        val waiting = handoff ?: return
        if (waiting.framesInPlace < 0 && frames.drawnSeq >= waiting.target && drawnAtSurfaceSize()) {
            waiting.framesInPlace = 0
            frameSignal.trySend(Unit)
        }
    }

    /**
     * Whether the frame drawn last is at the size WebKit was last told, in pixels: drawn in its place, not a frame of the
     * view's earlier size scaled into the surface. A new view's first page can paint at the size the view was created
     * with, before the surface's reaches WebKit; that frame is no first paint, and a snapshot of it is the wrong size.
     */
    private fun drawnAtSurfaceSize(): Boolean {
        val (width, height, scale) = size ?: return true
        return abs(frames.width - (width * scale).roundToInt()) <= 1 && abs(frames.height - (height * scale).roundToInt()) <= 1
    }

    /** Suspends until the surface has work at its next Compose frame: a frame from WebKit, or a handoff's next tick. */
    internal suspend fun awaitSurfaceWork() {
        frameSignal.receive()
    }

    /**
     * The surface is [widthPx] × [heightPx] at [density]: WebKit's view is in logical pixels, at that scale, enough of
     * them that their device pixels cover the surface (a fractional scale rounded down would leave a strip of it bare,
     * as the frame is drawn as it is); the surface cuts off the rest, less than a pixel of the page's.
     */
    internal fun onSurfaceSize(widthPx: Int, heightPx: Int, density: Float) {
        if (released || widthPx <= 0 || heightPx <= 0 || density <= 0f) return
        val scale = density.toDouble()
        val next = Triple(logicalPixels(widthPx, scale), logicalPixels(heightPx, scale), scale)
        if (next == size) return
        size = next
        view.setSize(next.first, next.second, next.third)
    }

    /**
     * Tells WebKit the view's size again, once a document that started loading at another size has committed. A page of
     * another site loads in a web process of its own, which takes the view's size when the load starts: a view's first
     * page starts loading before the view is laid out. WebKit hears a size only when it changes, so it changes twice.
     */
    private fun sizeAgain() {
        val (width, height, scale) = size ?: return
        view.setSize(width + 1, height, scale)
        view.setSize(width, height, scale)
    }

    internal fun onSurfaceFocus(focused: Boolean) {
        if (!released && !crashed) view.setFocus(focused)
    }

    internal fun pointer(event: PointerEvent, density: Float) {
        if (!released && !crashed) forwardPointer(view, event, density.toDouble(), WpeDefaults.SCROLL_SIGN)
    }

    internal fun key(event: KeyEvent): Boolean = !released && !crashed && forwardKey(view, event)

    // --- The glue's events ---

    internal fun onEvent(event: WpeEvent) {
        if (released) return
        when (event) {
            is WpeEvent.Load -> onLoad(event)
            is WpeEvent.LoadFailed -> onFailed(event)
            is WpeEvent.Title -> if (!awaitingStart && committedEpoch == queue.epoch) reportTitle(event.title)
            is WpeEvent.Uri -> onUri(event.uri)
            is WpeEvent.Progress -> if (!awaitingStart && provisional) push(queue.epoch, EngineEvent.Progress(event.progress.coerceIn(START_PROGRESS, 0.99f)))
            is WpeEvent.History -> {
                backList = event.back
                forwardList = event.forward
                pushHistory()
            }
            is WpeEvent.Policy -> onPolicy(event)
            is WpeEvent.Response -> onResponse(event)
            is WpeEvent.Relay -> onRelay(event.body)
            is WpeEvent.Eval -> evals.remove(event.request)?.complete(if (event.ok) event.json else null)
            is WpeEvent.Crashed -> onCrashed()
            is WpeEvent.Scheme -> onScheme(event)
            is WpeEvent.Frame -> frameSignal.trySend(Unit)
            is WpeEvent.Cursor -> cursor = PointerIcon(Cursor.getPredefinedCursor(cursorTypeFor(event.name)))
            // IME is deferred (F22): Compose's text input can't drive an editing state that lives in WebKit yet.
            is WpeEvent.ImeFocus, is WpeEvent.ImeCursor -> Unit
            is WpeEvent.DataCleared -> clears.remove(event.request)?.complete(event.ok)
            is WpeEvent.Dialog -> onDialog(event)
            is WpeEvent.Permission -> onPermission(event)
            is WpeEvent.FileChooser -> onFileChooser(event)
            is WpeEvent.NewWindow -> onNewWindow(event)
            is WpeEvent.Responsive -> onResponsive(event.responsive)
        }
    }

    /** The grace a page WebKit found unresponsive has to answer again; then it is ended, as Android's ([onResponsive]). */
    private var unresponsive: Job? = null

    /**
     * WebKit finds a page unresponsive when it leaves input or a message unanswered for 3 s. One still unresponsive
     * [UNRESPONSIVE_GRACE_MILLIS] later (about Android's 5 s) is hung: its process is ended and the engine reports
     * [EngineEvent.Crashed], so the runtime gives its view a new engine, as on Android. A process left spinning would
     * burn a CPU until the app exits.
     */
    private fun onResponsive(responsive: Boolean) {
        if (responsive) {
            unresponsive?.cancel()
            unresponsive = null
            return
        }
        if (released || crashed || unresponsive != null) return
        unresponsive = scope.launch {
            delay(UNRESPONSIVE_GRACE_MILLIS)
            unresponsive = null
            endHungPage()
        }
    }

    /** The heartbeat while a surface shows the page ([startHeartbeat]); null otherwise. */
    private var heartbeat: Job? = null

    /**
     * Asks the page shown on a surface every [HEARTBEAT_MILLIS] whether it answers, and ends it once it has been silent
     * for [HUNG_MILLIS] (about Android's 5 s). WebKit's own check needs input it waits on, and on WPE it gets none from
     * a mouse: a move, which it sends unchecked and a hung page never acknowledges, holds every click behind it
     * (WebPageProxy::processNextQueuedMouseEvent). Not while a script dialog holds the page, as WebKit stops its own then.
     */
    private fun startHeartbeat() {
        if (heartbeat != null || released || crashed) return
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_MILLIS)
                if (!visible || questions.any { it.holdsPage }) continue
                if (pageAnswers(HUNG_MILLIS) || !visible || questions.any { it.holdsPage }) continue
                heartbeat = null
                endHungPage()
                return@launch
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    /** Ends the page's web process, which no longer answers, and with it the engine. */
    private fun endHungPage() {
        if (released || crashed) return
        view.terminate()
        onCrashed()
    }

    /** Whether the page's process answers a script within [millis], with a result or an error. */
    private suspend fun pageAnswers(millis: Long): Boolean {
        if (released) return false
        val request = nextRequest++
        val done = CompletableDeferred<String?>()
        evals[request] = done
        view.evaluate(request, "1", WebEngineWorld.WORLD)
        val answered = try {
            withTimeoutOrNull(millis) { done.await(); true } ?: false
        } finally {
            evals.remove(request)
        }
        return answered && !released
    }

    private fun onLoad(event: WpeEvent.Load) {
        when (event.event) {
            WpeNative.LOAD_STARTED -> {
                awaitingStart = false
                provisional = true
                loadSize = size
                push(queue.epoch, EngineEvent.Progress(START_PROGRESS))
            }
            WpeNative.LOAD_COMMITTED -> {
                // A main-frame commit, whoever's: the last document's frames, and what they announced, are gone.
                navigation.clear()
                if (!awaitingStart) onCommitted(event.uri, event.frameSeq)
            }
            WpeNative.LOAD_FINISHED -> if (!awaitingStart) onFinished(event.uri)
        }
    }

    private fun onCommitted(uri: String?, frameSeq: Long) {
        val epoch = queue.epoch
        provisional = false
        showFramesOf(uri, frameSeq)
        if (loadSize != size) sizeAgain()
        committedEpoch = epoch
        own = null
        document = null
        documentPainted = false
        committedUrl = uri
        val commit = ++commits
        val url = uri?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        if (httpError?.url != url) httpError = null
        push(epoch, EngineEvent.UrlChanged(url))
        reportedTitle = null
        push(epoch, EngineEvent.TitleChanged(null))
        push(epoch, EngineEvent.Progress(COMMIT_PROGRESS))
        pushHistory()
        // Not its commit: its content says when it can be shown. One that never paints anything is shown anyway, later.
        CoroutineScope(scope.coroutineContext + lease).launch {
            delay(CONTENT_WAIT_MILLIS)
            if (commits == commit) handOff(epoch)
        }
    }

    /**
     * The document of [uri] committed when WebKit had delivered frame [frameSeq]: if the engine hides frames for a
     * document it started (a lease's first, or a load), the frames from the next one on are that document's. A page's
     * own navigation keeps showing the last document until the next one paints (paint holding), and the floor never shows.
     */
    private fun showFramesOf(uri: String?, frameSeq: Long) {
        if (frames.hidesAll && !WebKitUrls.isFloor(uri)) frames.hideUntil(frameSeq + 1)
    }

    private fun onFinished(uri: String?) {
        provisional = false
        val epoch = queue.epoch
        push(epoch, EngineEvent.Progress(null))
        // A document without the relay (a PDF, an image) has no paint report: its load says it has its content.
        if (epoch == committedEpoch && document == null && !documentPainted) handOff(epoch)
        // Last: a reset waiting for the floor goes on from here, and nothing of the floor's load may follow it.
        if (WebKitUrls.isFloor(uri)) floorDone?.complete(true)
    }

    private fun onFailed(event: WpeEvent.LoadFailed) {
        own = null
        awaitingStart = false
        provisional = false
        push(queue.epoch, EngineEvent.Progress(null))
        // A floor that fails fails the warm-up or the reset waiting for it.
        floorDone?.complete(false)
        // A navigation replaced by another, or one refused or turned into a download, isn't an error.
        if (event.cancelled || event.policy) return
        val url = event.uri?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        val error = when {
            event.tls -> WebError.Certificate(url, event.message ?: "TLS certificate error")
            event.cannotShow -> WebError.UnsupportedOnPlatform(url)
            else -> WebError.Network(url, event.message ?: "load failed (${event.code})")
        }
        push(queue.epoch, EngineEvent.Error(error))
    }

    /** A URL change outside a load: the document moved within itself (pushState, a fragment). WebKit tells it (W8). */
    private fun onUri(uri: String?) {
        if (awaitingStart || provisional || committedEpoch != queue.epoch || uri == null) return
        if (uri == committedUrl || WebKitUrls.isFloor(uri)) return
        committedUrl = uri
        push(queue.epoch, EngineEvent.UrlChanged(WebKitUrls.toLibraryUrl(uri, bundle)))
    }

    private fun onCrashed() {
        if (crashed) return
        crashed = true
        stopHeartbeat()
        floorDone?.complete(false)
        push(queue.epoch, EngineEvent.Crashed)
    }

    // --- Navigation ---

    private fun onPolicy(event: WpeEvent.Policy) {
        when (val verdict = navigation.decideAction(event, own, bundle, htmlUrl)) {
            WpeVerdict.Own -> {
                own = null
                view.decide(event.decision, WpeNative.USE)
            }
            WpeVerdict.Use, WpeVerdict.AtResponse -> view.decide(event.decision, WpeNative.USE)
            WpeVerdict.Ignore -> view.decide(event.decision, WpeNative.IGNORE)
            WpeVerdict.ReloadHtml -> {
                view.decide(event.decision, WpeNative.IGNORE)
                val html = content as? WebContent.Html ?: return
                own = OwnNavigation.Load(html.origin.url)
                view.loadHtml(html.html, html.origin.url)
            }
            is WpeVerdict.Ask -> {
                // Synchronous, as WebKit's policy questions are answered here: the runtime's client answers at once.
                val allowed = askNavigation(verdict.request)
                if (allowed) event.url?.let(navigation::allowedAtAction)
                view.decide(event.decision, if (allowed) WpeNative.USE else WpeNative.IGNORE)
            }
        }
    }

    /** The client's answer to [request]: allowed only if it says so; no client, or one that throws, refuses. */
    private fun askNavigation(request: NavigationRequest): Boolean = try {
        client?.onNavigation(request) == NavigationDecision.Allow
    } catch (e: Exception) {
        false
    }

    private fun onResponse(event: WpeEvent.Response) {
        val url = event.url
        val verdict = navigation.atResponse(event, bundle)
        if (event.mainFrame && (event.attachment || !event.canShow)) {
            // A download. The client hears of it; the engine saves nothing, either way (as Android's DownloadListener).
            view.decide(event.decision, WpeNative.IGNORE)
            if (url != null) askAboutDownload(DownloadRequest(WebKitUrls.toLibraryUrl(url, bundle), event.filename, event.mime, null))
            return
        }
        val allowed = when (verdict) {
            WpeResponseVerdict.Use -> true
            WpeResponseVerdict.Ignore -> false
            is WpeResponseVerdict.Ask -> askNavigation(verdict.request)
        }
        if (!allowed) {
            view.decide(event.decision, WpeNative.IGNORE)
            return
        }
        if (event.mainFrame && event.status >= 400 && url != null) httpError = WebError.Http(WebKitUrls.toLibraryUrl(url, bundle), event.status)
        view.decide(event.decision, WpeNative.USE)
    }

    // --- The relay ---

    private fun onRelay(body: String) {
        val parsed = RelayMessage.parse(body) ?: return
        if (parsed is RelayMessage.Start) {
            document = parsed.document
            return
        }
        if (parsed.document != document) return
        val epoch = committedEpoch
        when (parsed) {
            is RelayMessage.Paint -> handOff(epoch)
            is RelayMessage.Focus -> setFocused(epoch, parsed.focused)
            // WebKit reports title and same-document URL changes itself on WPE (W8): the relay's hints aren't needed.
            is RelayMessage.Title, is RelayMessage.Url -> Unit
            // The document's own token instance: the queue collapses its repeated $ready by identity.
            is RelayMessage.Page -> push(epoch, EngineEvent.PageMessage(documentOrigin(), parsed.json), document)
            is RelayMessage.Start -> Unit
        }
    }

    /**
     * The origin of the document the relay speaks for, in the library's terms. WPE's script messages carry no origin
     * (F16), so it is the committed document's URL's: the relay runs only in the main frame, forwards only from a
     * document whose own origin (`self.origin`) is a bridge origin, and its token ties each message to that commit. A
     * bundle's `webview-bundle://` URL reports as its `https://<host>.invalid`; a URL without a host is `"null"`.
     */
    private fun documentOrigin(): String = committedUrl?.let { originOf(WebKitUrls.toLibraryUrl(it, bundle)) } ?: "null"

    /**
     * A first paint waiting for the page to be drawn in its place: the document of [epoch], at commit [commit], has its
     * content in WebKit's frame [target] or an earlier one. [replay] is a parked page's first paint again ([replayState]).
     */
    private class Handoff(val epoch: Int, val commit: Int, val target: Long, val replay: Boolean) {
        /** Compose frames since a surface drew [target] or a newer frame; -1 until one has. */
        var framesInPlace = -1
    }

    /** The first paint under way, or null. */
    private var handoff: Handoff? = null

    /**
     * The committed document has content to show:
     * - the relay's `paint` (its first contentful paint or its load, then two animation frames);
     * - a document without the relay's load;
     * - or [CONTENT_WAIT_MILLIS] of nothing since its commit.
     *
     * [EngineEvent.FirstPaint] then waits until a surface has drawn WebKit's newest frame of that moment, or a newer
     * one, and [FRAMES_IN_PLACE] Compose frames have passed since ([advanceHandoff]). The runtime's cover lifts then, so
     * the first frame without it is one the page drew in its place. With no surface composed it waits for one. Once per
     * document; never for the floor.
     */
    private fun handOff(epoch: Int) {
        if (released || documentPainted || epoch != committedEpoch || WebKitUrls.isFloor(committedUrl)) return
        if (handoff?.let { it.commit == commits && !it.replay } == true) return
        waitForDrawing(Handoff(epoch, commits, view.frameSeq().coerceAtLeast(0), replay = false))
    }

    /** Starts [next]: drawn already if a surface drew its frame, else at the first draw that does. */
    private fun waitForDrawing(next: Handoff) {
        if (surfaces > 0 && frames.drawnSeq >= next.target && drawnAtSurfaceSize()) next.framesInPlace = 0
        handoff = next
        frameSignal.trySend(Unit)
    }

    /** At each Compose frame of a surface: the handoff's count, and its first paint once it is drawn in place. */
    private fun advanceHandoff() {
        val waiting = handoff ?: return
        if (waiting.framesInPlace < 0) return
        // A frame of another size pulled meanwhile (WebKit still catching up with the surface's size) starts it over: the
        // next draw at the surface's size counts again.
        if (!drawnAtSurfaceSize()) {
            waiting.framesInPlace = -1
            return
        }
        waiting.framesInPlace++
        if (waiting.framesInPlace < FRAMES_IN_PLACE) {
            frameSignal.trySend(Unit)
            return
        }
        handoff = null
        if (waiting.epoch != queue.epoch || waiting.commit != commits) return
        if (waiting.replay) push(waiting.epoch, EngineEvent.FirstPaint) else markPainted(waiting.epoch)
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

    private fun reportTitle(title: String?) {
        val shown = title?.takeIf { it.isNotEmpty() }
        if (shown == reportedTitle) return
        reportedTitle = shown
        push(queue.epoch, EngineEvent.TitleChanged(shown))
    }

    private fun pushHistory() {
        val next = EngineEvent.HistoryChanged(WebKitUrls.canGoBack(backList), WebKitUrls.canGoForward(forwardList))
        if (next == history) return
        history = next
        push(queue.epoch, next)
    }

    // --- Local content ---

    /**
     * Serves the floor, and the lease's bundle and nothing else, as iOS's scheme handlers do:
     * - another bundle's URL, or any URL between leases, fails as an unknown host does, so no document of another
     *   bundle can exist;
     * - a path [normalizeBundlePath] refuses, or a file the provider hasn't, is an HTTP 404;
     * - a file is a 200 with the provider's type and its own headers.
     */
    private fun onScheme(event: WpeEvent.Scheme) {
        val uri = event.uri
        if (WebKitUrls.isFloor(uri)) {
            view.finishScheme(event.request, 200, "text/html; charset=utf-8", WebKitUrls.FLOOR_HTML.encodeToByteArray())
            return
        }
        val shown = content as? WebContent.Bundle
        if (shown == null || !WebKitUrls.isBundleUrl(uri, shown.origin)) {
            view.finishScheme(event.request, SCHEME_FAILED, null, null)
            return
        }
        val path = normalizeBundlePath(WebKitUrls.rawPath(uri))
        if (path == null) {
            view.finishScheme(event.request, 404, "text/plain", null)
            return
        }
        val file = path.ifEmpty { shown.entry }
        val request = event.request
        schemeRequests += request
        CoroutineScope(scope.coroutineContext + lease).launch {
            val resource = try {
                shown.resources.load(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A reset or a release may have answered it meanwhile.
            if (!schemeRequests.remove(request)) return@launch
            if (resource == null) view.finishScheme(request, 404, "text/plain", null)
            else view.finishScheme(request, 200, typeAndHeaders(resource), resource.bytes)
        }
    }

    // --- Client requests ---

    /** Tells the client of a download, which the engine has already refused: nothing waits on the answer. */
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
     * A script dialog: the client's answer, or the safe default (dismissed; a beforeunload leaves the page). Its URL is
     * the page's: WPE doesn't say which frame raised it.
     */
    private fun onDialog(event: WpeEvent.Dialog) {
        val request = event.request
        val message = event.message.orEmpty()
        val url = committedUrl?.let { WebKitUrls.toLibraryUrl(it, bundle) }
        val dismiss = { view.answer(request, false, null, null) }
        when (event.type) {
            WpeNative.DIALOG_ALERT -> ask(dismiss, { it.onDialog(WebDialog.Alert(message, url)) }, holdsPage = true) { dismiss() }
            WpeNative.DIALOG_CONFIRM -> ask(dismiss, { it.onDialog(WebDialog.Confirm(message, url)) }, holdsPage = true) {
                view.answer(request, it == DialogResult.Confirmed, null, null)
            }
            WpeNative.DIALOG_PROMPT -> {
                val default = event.defaultText.orEmpty()
                ask(dismiss, { it.onDialog(WebDialog.Prompt(message, default, url)) }, holdsPage = true) {
                    when (it) {
                        is DialogResult.Text -> view.answer(request, true, it.value, null)
                        DialogResult.Confirmed -> view.answer(request, true, default, null)
                        DialogResult.Dismissed -> dismiss()
                    }
                }
            }
            // beforeunload: "stay" only if the client says so; by default the page can't trap the user.
            else -> ask({ view.answer(request, true, null, null) }, { it.onDialog(WebDialog.BeforeUnload(message, url)) }, holdsPage = true) {
                view.answer(request, it != DialogResult.Dismissed, null, null)
            }
        }
    }

    /**
     * A permission: the client's answer, or deny. Its origin is the committed document's: WPE doesn't say which frame
     * asks. Nothing is kept: the next request asks again.
     */
    private fun onPermission(event: WpeEvent.Permission) {
        val kinds = buildSet {
            if ((event.kinds and WpeNative.PERM_CAMERA) != 0L) add(PermissionKind.Camera)
            if ((event.kinds and WpeNative.PERM_MICROPHONE) != 0L) add(PermissionKind.Microphone)
            if ((event.kinds and WpeNative.PERM_GEOLOCATION) != 0L) add(PermissionKind.Geolocation)
            if ((event.kinds and WpeNative.PERM_NOTIFICATIONS) != 0L) add(PermissionKind.Notifications)
            if ((event.kinds and WpeNative.PERM_OTHER) != 0L) add(PermissionKind.Other)
        }.ifEmpty { setOf(PermissionKind.Other) }
        val request = PermissionRequest(documentOrigin(), kinds)
        ask({ view.answer(event.request, false, null, null) }, { it.onPermission(request) }) { granted ->
            view.answer(event.request, granted, null, null)
        }
    }

    /** A file chooser: the client's files as WebKit takes them (local paths), one unless several are allowed; or cancel. */
    private fun onFileChooser(event: WpeEvent.FileChooser) {
        val cancel = { view.answer(event.request, false, null, null) }
        val question = FileChooserRequest(event.mimeTypes, event.multiple)
        ask(cancel, { it.onFileChooser(question) ?: emptyList() }) { chosen ->
            val paths = chosen.mapNotNull(::localPath).let { if (event.multiple) it else it.take(1) }
            if (paths.isEmpty()) cancel() else view.answer(event.request, true, null, paths)
        }
    }

    /**
     * The page asked for a window, and WebKit got none: the client decides what becomes of its URL. `OpenHere` loads
     * it in this view as the engine's own load (iOS's rule). A private scheme, but for the lease's bundle, is never
     * asked about.
     */
    private fun onNewWindow(event: WpeEvent.NewWindow) {
        val url = event.url
        if (url.isNullOrEmpty() || WebKitUrls.isPrivate(url) && !(bundle?.let { WebKitUrls.isBundleUrl(url, it) } ?: false)) return
        val decision = try {
            client?.onNewWindow(NewWindowRequest(WebKitUrls.toLibraryUrl(url, bundle), event.gesture))
        } catch (e: Exception) {
            null
        }
        if (decision != NewWindowDecision.OpenHere) return
        own = OwnNavigation.Load(url)
        view.loadUri(url, emptyMap())
    }

    /**
     * Asks the client [question] on main, and hands its answer to [answer]. With no client, or one that fails, or when a
     * reset or release comes first, WebKit gets [byDefault], the safe answer. Exactly one of them runs.
     */
    private fun <T : Any> ask(
        byDefault: () -> Unit,
        question: suspend (EngineClient) -> T,
        holdsPage: Boolean = false,
        answer: (T) -> Unit,
    ) {
        val pending = Pending(byDefault, holdsPage)
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
            questions -= pending
            if (result == null) pending.answerByDefault() else pending.answer { answer(result) }
        }
    }

    private fun answerAllByDefault() {
        for (pending in questions.toList()) {
            pending.job?.cancel()
            pending.answerByDefault()
        }
        questions.clear()
    }

    // --- Helpers ---

    private fun leaseCallAllowed(): Boolean = !released && !resetting && !resetFailed

    /**
     * Queues [event], caused in [epoch]. What an earlier epoch caused is dropped here: the queue only knows the epoch it
     * is in. A crash and focus changes pass whatever the epoch: they are the engine's and the view's.
     */
    private fun push(epoch: Int, event: EngineEvent, document: Any? = null) {
        if (epoch != queue.epoch && event != EngineEvent.Crashed && event !is EngineEvent.FocusChanged) return
        queue.offer(event, document)
    }

    /** A new document of [content] starts: nothing an earlier one caused is delivered from now on. */
    private fun startDocument(content: WebContent): Int {
        val epoch = queue.advance()
        this.content = content
        frames.hideUntil(Long.MAX_VALUE)
        documentPainted = false
        handoff = null
        httpError = null
        document = null
        history = null
        awaitingStart = true
        provisional = false
        navigation.clear()
        return epoch
    }

    private suspend fun evaluateIn(world: String?, script: String): String? {
        if (released) return null
        val request = nextRequest++
        val done = CompletableDeferred<String?>()
        evals[request] = done
        view.evaluate(request, script, world)
        return try {
            withTimeoutOrNull(EVAL_MILLIS) { done.await() }
        } finally {
            evals.remove(request)
        }
    }

    companion object {
        private const val START_PROGRESS = 0.1f
        private const val COMMIT_PROGRESS = 0.5f
        private const val WARM_UP_MILLIS = 10_000L
        private const val EVAL_MILLIS = 10_000L
        private const val FLOOR_MILLIS = 10_000L
        private const val WIPE_MILLIS = 10_000L

        /** How long a page WebKit found unresponsive (3 s) has to answer again before it is ended: Android's 5 s in all. */
        private const val UNRESPONSIVE_GRACE_MILLIS = 2_000L

        /** How long a reset waits for the page to answer a script before it takes the page for hung. */
        private const val ANSWER_MILLIS = 1_500L

        /** How often a page shown on a surface is asked whether it answers ([startHeartbeat]). */
        private const val HEARTBEAT_MILLIS = 3_000L

        /** How long a page shown on a surface may leave the heartbeat's question unanswered before it is ended. */
        private const val HUNG_MILLIS = 5_000L

        /** The floor's background, until the next lease's configure: opaque white (0xAARRGGBB). */
        private const val FLOOR_BACKGROUND = -1

        /** The glue's request id for a script whose result no one waits for. */
        private const val NO_ANSWER = 0L

        /** The glue's answer to a scheme request that fails as an unknown host does: not an HTTP response. */
        private const val SCHEME_FAILED = 0

        /**
         * [resource]'s type, then its own headers as `Name: value` lines: the glue's format. The type and the length
         * are the engine's to set, as on iOS; a header that can't be one HTTP line is left out.
         */
        fun typeAndHeaders(resource: WebResource): String = buildString {
            append(resource.mimeType)
            for ((name, value) in resource.headers) {
                if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
                if (name.isEmpty() || name.any { it == ':' || it == '\n' || it == '\r' } || value.any { it == '\n' || it == '\r' }) continue
                append('\n').append(name).append(": ").append(value)
            }
        }

        /** A chosen file as WebKit takes it, a local path: a `file:` URI's, or an absolute path. Null for anything else. */
        fun localPath(uri: String): String? = when {
            uri.startsWith("file:") -> runCatching { File(URI(uri)).path }.getOrNull()
            uri.startsWith("/") -> uri
            else -> null
        }

        /** How long a committed document may show nothing contentful before it is shown anyway (the iOS engine's 3 s). */
        private const val CONTENT_WAIT_MILLIS = 3_000L

        /** The logical pixels whose device pixels at [scale] cover [px]: rounded up, past a float's error. */
        fun logicalPixels(px: Int, scale: Double): Int = kotlin.math.ceil(px / scale - 1e-6).toInt().coerceAtLeast(1)

        /** Compose frames a first paint waits after a surface drew the page: the iOS engine's two display ticks. */
        private const val FRAMES_IN_PLACE = 2
    }
}
