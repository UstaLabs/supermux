package dev.supermux.webview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import dev.supermux.webview.bridge.PageAnswer
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.runtime.AcquireResult
import dev.supermux.webview.runtime.ActivationInputs
import dev.supermux.webview.runtime.LeaseHolder
import dev.supermux.webview.runtime.OverlayRegistry
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.activationPriority
import dev.supermux.webview.runtime.computeCutouts
import dev.supermux.webview.runtime.decideActivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.concurrent.Volatile

/** How long a page may take to hand over its state (`$saveState`) when its engine goes. */
private const val PAGE_STATE_TIMEOUT_MILLIS = 300L

/** How long after the first paint, and after the user's last interaction, the page is snapshotted (spec §4). */
private const val SNAPSHOT_SETTLE_MILLIS = 500L

/** Waits before retrying an engine creation that failed: 2 s, then 10 s, then every 60 s. */
private val CREATION_RETRY_MILLIS = longArrayOf(2_000, 10_000, 60_000)

/**
 * One [WebView]'s lease lifecycle (spec §4). It watches where the view is and what it shows, asks [decideActivation]
 * whether it should be live, leases, binds and gives back engines, keeps the page's snapshot, and saves the page's
 * engine and page state into [state] and the runtime's page memory when its engine goes.
 *
 * Main thread only, except [clientAdapter]'s two synchronous callbacks, which engines may call on their own thread.
 *
 * What a cold view costs: no coroutine but its [run] loop, which sleeps until something it decides on changes; no
 * capacity listener unless the pool denied it; no snapshot state written as it scrolls.
 *
 * Every lease follows the pool's holder contract:
 * - [priority] is read live, and the pool hears when it drops ([EnginePool.priorityLowered]) or the view is used
 *   ([EnginePool.touch]).
 * - It listens for capacity only while denied, subscribing right after the denial so no release is missed.
 * - It gives an engine back before asking for one of another config, and checks the config again when one arrives.
 * - [onEvict] acts only on the engine it still holds, inside its lock, and stops using it when it returns.
 */
internal class WebViewHost(
    internal val state: WebViewState,
    private val runtime: WebViewRuntime,
) : LeaseHolder {
    /** What a lease loaded: the content and the load it came from, the engine config and the setup it was given. */
    private class Loaded(val content: WebContent, val generation: Int, val config: EngineConfig, val setup: PageSetup)

    /** A snapshot that covers the page while no engine has painted, and the key it is cached under. */
    class Cover(val image: ImageBitmap, val key: SnapshotKey)

    // What the WebView passes, at every recomposition (update).
    private var settings: WebSettings = WebSettings.Default
    private var activation: Activation = Activation.Eager
    private var uriHandler: UriHandler? = null
    private var dark = false
    private var density = 1f

    /** Read by [clientAdapter], which engines may call off main. */
    @Volatile
    private var client: WebClient = WebClient.Default

    /** The window's overlays. Snapshot state: the cut-outs follow it. */
    private var overlays: OverlayRegistry by mutableStateOf(runtime.overlays, referentialEqualityPolicy())

    /** The leased engine, while bound. The WebView composes its surface. */
    var engine: WebEngine? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    /** Whether the bound engine's current document painted (or failed). Until then the cover hides the surface. */
    var painted: Boolean by mutableStateOf(false)
        private set

    /** The page's last snapshot, which the WebView shows as the cover while it is for the content shown. */
    var cover: Cover? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    // Where the view is. Plain fields: a cold view writes no snapshot state as it scrolls.
    private var visible = false
    private var hiddenSince: Long? = runtime.clock()
    private var lastMovedAt = runtime.clock()
    private var lastPosition = Offset.Unspecified
    private var lastSize = IntSize.Zero
    private var lastVisibleBounds = Rect.Zero

    /** The view's bounds in the window, mirrored into snapshot state only while bound, for the cut-outs. */
    private var pageBounds by mutableStateOf(Rect.Zero)
    private var visibleBounds by mutableStateOf(Rect.Zero)

    // The lease.
    private var loaded: Loaded? = null
    private var leaseScope: CoroutineScope? = null
    private var eventsJob: Job? = null
    private var allowedOrigins: Set<String> = emptySet()

    /** What the engine shows, for [clientAdapter]'s navigation decisions off main. */
    @Volatile
    private var engineContent: WebContent = state.content

    /** What the engine was last told about its visibility. */
    private var engineVisible: Boolean? = null

    /** Restored page state not yet delivered to a document of its origin. */
    private var pendingRestore: SavedPageState? = null

    /** When to refresh the snapshot: after the first paint settles, or after the user's last interaction. */
    private var snapshotDueAt: Long? = null
    private var prewarmed = false

    private var interacted = false
    private var disposed = false
    private var listening = false
    private var creationFailures = 0
    private var retryAt: Long? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val capacityListener: () -> Unit = { signal() }
    private val stateListener: () -> Unit = { signal() }

    init {
        val fresh = state.snapshotKey == null && state.savedEngineState == null && state.pageState == null &&
            state.contentSize == null
        if (fresh) runtime.pages.recall(state.content.key)?.let(state::restoreFrom)
        cover = state.snapshotKey?.let(::cachedCover)
    }

    /** Read live by the pool: focused, then visible Eager, then visible; hidden ranks lowest. */
    override val priority: Int
        get() = activationPriority(activation, visible, focused = runtime.focused === this)

    /** Takes what the WebView was composed with. Cheap: it wakes the host only when something it decides on changed. */
    fun update(
        settings: WebSettings,
        client: WebClient,
        activation: Activation,
        uriHandler: UriHandler,
        dark: Boolean,
        density: Float,
        overlays: OverlayRegistry,
    ) {
        this.client = client
        this.uriHandler = uriHandler
        this.density = density
        if (this.overlays !== overlays) this.overlays = overlays
        val changed = settings != this.settings || activation != this.activation || dark != this.dark
        if (!changed) return
        val before = priority
        this.settings = settings
        this.activation = activation
        this.dark = dark
        if (engine != null && priority < before) runtime.pool.priorityLowered()
        signal()
    }

    fun signal() {
        wake.trySend(Unit)
    }

    /** Called after every layout pass that moved the view. Cheap for a cold view: some comparisons, no state writes. */
    fun onPositioned(coordinates: LayoutCoordinates) {
        val now = runtime.clock()
        val position = coordinates.positionInWindow()
        val size = coordinates.size
        if (position != lastPosition || size != lastSize) {
            lastPosition = position
            lastSize = size
            lastMovedAt = now
        }
        val clipped = coordinates.boundsInWindow()
        lastVisibleBounds = clipped
        setVisible(coordinates.isAttached && clipped.width >= 1f && clipped.height >= 1f, now)
        if (engine != null) publishBounds()
    }

    /** The view's layout left the composition. */
    fun onDetached() {
        setVisible(false, runtime.clock())
    }

    /** The user pressed on the view. */
    fun onInteraction() {
        interacted = true
        takeFocus()
        noteUse()
        signal()
    }

    private fun setVisible(isVisible: Boolean, now: Long) {
        if (isVisible == visible) return
        visible = isVisible
        hiddenSince = if (isVisible) null else now
        if (engine != null) {
            if (isVisible) runtime.pool.touch(this) else runtime.pool.priorityLowered()
        }
        signal()
    }

    private fun takeFocus() {
        val previous = runtime.focused
        if (previous === this) return
        runtime.focused = this
        // The view that had focus ranks lower now: a view denied meanwhile may outrank it.
        if (previous != null) runtime.pool.priorityLowered()
    }

    private fun loseFocus() {
        if (runtime.focused !== this) return
        runtime.focused = null
        if (engine != null) runtime.pool.priorityLowered()
    }

    /** The user used the page: the pool counts it as recent, and a snapshot follows once the user lets go. */
    private fun noteUse() {
        if (engine == null) return
        runtime.pool.touch(this)
        if (painted) snapshotDueAt = runtime.clock() + SNAPSHOT_SETTLE_MILLIS
    }

    /**
     * The view's life, while it is composed: decides, acts, and sleeps until something changes or a deadline. Returns
     * only by cancellation; [dispose] gives back what the view still holds.
     */
    suspend fun run() {
        state.onNeedsEvaluation = stateListener
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                sleepUntil(step())
            }
        } finally {
            stopListening()
            if (state.onNeedsEvaluation === stateListener) state.onNeedsEvaluation = null
        }
    }

    /**
     * Sleeps until [deadline] on the runtime clock, or until [signal]; with no deadline, until [signal] only.
     *
     * The deadline is kept with `delay`, not `withTimeout`: Compose's effect dispatcher runs `delay` on the frame
     * clock's time, which tests drive, but leaves timeouts to the wall clock.
     */
    private suspend fun sleepUntil(deadline: Long?) {
        if (deadline == null) {
            wake.receive()
            return
        }
        val wait = deadline - runtime.clock()
        if (wait <= 0) return
        coroutineScope {
            val timer = launch {
                delay(wait)
                signal()
            }
            try {
                wake.receive()
            } finally {
                timer.cancel()
            }
        }
    }

    /** One decision and what it calls for. Returns when to decide again: null when something changes, now to go on. */
    private suspend fun step(): Long? {
        val now = runtime.clock()
        val current = engine
        val decision = decideActivation(
            ActivationInputs(
                activation = activation,
                visible = visible,
                focused = runtime.focused === this,
                interacted = interacted,
                live = current != null,
                now = now,
                lastMovedAt = lastMovedAt,
                hiddenSince = hiddenSince,
                graceMillis = runtime.config.graceMillis,
                hiddenReleaseMillis = runtime.config.hiddenReleaseMillis,
            ),
        )
        if (current != null) {
            if (engineVisible != visible) {
                engineVisible = visible
                current.setVisible(visible)
            }
            if (!decision.wantLive) {
                deactivate()
                return now
            }
            val lease = loaded
            if (lease != null && !matches(lease)) {
                reconcile(lease)
                return now
            }
            val due = snapshotDueAt
            if (due != null && now >= due) {
                snapshotDueAt = null
                refreshSnapshot(current)
                return now
            }
            return earliest(decision.reevaluateAt, due)
        }
        if (!decision.wantLive) {
            stopListening()
            return decision.reevaluateAt
        }
        when (val phase = state.phase) {
            WebViewPhase.Crashed -> {
                stopListening()
                return null
            }
            is WebViewPhase.Unavailable -> {
                stopListening()
                // Only a failed creation is retried; a missing engine stays missing until the app retries.
                if (phase.reason != UnavailableReason.CreationFailed) return null
                val at = retryAt ?: (now + CREATION_RETRY_MILLIS[0]).also { retryAt = it }
                if (now < at) return at
            }
            else -> retryAt = null
        }
        return activate()
    }

    /** Whether the lease still shows what the state asks for, with the engine config and setup it asks for. */
    private fun matches(lease: Loaded): Boolean {
        val content = state.content
        return lease.generation == state.loadGeneration && lease.content == content &&
            lease.config == configFor(content) && lease.setup == setupFor(content)
    }

    private fun configFor(content: WebContent): EngineConfig =
        EngineConfig(settings.profileFor(content), settings.mediaPlaybackRequiresGesture)

    /** The lease's setup: the bridge goes to the listed origins and to the content's own local origin. */
    private fun setupFor(content: WebContent): PageSetup {
        val local = content.localOrigin?.origin ?: return settings.pageSetup(dark, settings.bridgeOrigins)
        return settings.pageSetup(dark, settings.bridgeOrigins + local)
    }

    /** Leases an engine and binds it. Returns when to decide again, like [step]. */
    private suspend fun activate(): Long? {
        lock.withLock {
            if (disposed) return null
            if (engine != null) return runtime.clock()
            val config = configFor(state.content)
            when (val result = runtime.pool.acquire(this, config)) {
                is AcquireResult.Granted -> {
                    if (disposed || !currentCoroutineContext().isActive) {
                        giveBack()
                        return null
                    }
                    // A load() may have changed the content while the engine was on its way.
                    if (configFor(state.content) != config) {
                        giveBack()
                        return runtime.clock()
                    }
                    stopListening()
                    creationFailures = 0
                    retryAt = null
                    bind(result.engine, config)
                    return runtime.clock()
                }
                is AcquireResult.Unavailable -> {
                    stopListening()
                    state.phase = WebViewPhase.Unavailable(result.reason, result.detail)
                    if (result.reason != UnavailableReason.CreationFailed) return null
                    val wait = CREATION_RETRY_MILLIS[creationFailures.coerceAtMost(CREATION_RETRY_MILLIS.lastIndex)]
                    creationFailures++
                    return (runtime.clock() + wait).also { retryAt = it }
                }
                AcquireResult.Denied -> {
                    // Synchronously after the denial: a release in between can't be missed.
                    startListening()
                    if (state.phase is WebViewPhase.Unavailable) state.phase = WebViewPhase.Cold
                    return null
                }
            }
        }
    }

    private fun startListening() {
        if (listening) return
        listening = true
        runtime.pool.addCapacityListener(this, capacityListener)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runtime.pool.removeCapacityListener(this)
    }

    /** Gives the lease back, even from a cancelled coroutine. */
    private suspend fun giveBack() {
        withContext(NonCancellable) { runtime.pool.release(this@WebViewHost) }
    }

    private fun bind(engine: WebEngine, config: EngineConfig) {
        val scope = CoroutineScope(runtime.scope.coroutineContext + SupervisorJob(runtime.scope.coroutineContext[Job]))
        leaseScope = scope
        this.engine = engine
        state.engine = engine
        engine.client = clientAdapter
        prewarmed = false
        publishBounds()
        // Only while live: the cut-outs follow the view's bounds and the window's overlays, once a frame at most.
        scope.launch {
            snapshotFlow { computeCutouts(pageBounds, visibleBounds, overlays.shapes, overlays.scrims) }
                .distinctUntilChanged()
                .collect { engine.applyOverlays(it) }
        }
        startDocument(engine, config)
    }

    /**
     * Starts the document [state] asks for in the bound [engine]: a fresh event subscription and bridge connection, so
     * nothing of the previous document carries over; the cover until it paints; the setup, then a restore of the saved
     * engine state or a load.
     */
    private fun startDocument(engine: WebEngine, config: EngineConfig) {
        val scope = leaseScope ?: return
        val content = state.content
        val setup = setupFor(content)
        loaded = Loaded(content, state.loadGeneration, config, setup)
        engineContent = content
        allowedOrigins = setup.bridgeOrigins
        painted = false
        state.phase = WebViewPhase.Activating
        snapshotDueAt = null
        // The document announces its own save provider; what was saved goes back to it, if it is of that origin.
        state.pageSavesState = false
        pendingRestore = state.pageState
        cover = state.snapshotKey?.let(::cachedCover)
        eventsJob?.cancel()
        state.bridge.disconnect()
        // Subscribed before the load, as the engine contract asks: undispatched, the collector subscribes right here.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { onEvent(engine, it) }
        }
        state.bridge.connect(engine::postMessage, ::originAllowed, scope)
        engine.configure(setup)
        val saved = state.savedEngineState
        if (saved == null || !engine.restoreState(saved, content)) engine.load(content)
    }

    private fun originAllowed(origin: String): Boolean = origin in allowedOrigins

    /** Mirrors the view's bounds into the snapshot state the cut-outs read. */
    private fun publishBounds() {
        val position = lastPosition
        pageBounds = if (position.isSpecified) Rect(position, lastSize.toSize()) else Rect.Zero
        visibleBounds = lastVisibleBounds
    }

    private fun onEvent(source: WebEngine, event: EngineEvent) {
        if (engine !== source) return
        state.onEngineEvent(event)
        when (event) {
            EngineEvent.FirstPaint -> onFirstPaint()
            is EngineEvent.Error -> liftCover()
            EngineEvent.Crashed -> onCrashed(source)
            is EngineEvent.FocusChanged -> if (event.focused) {
                // Compose never sees pointers over a native view: focus is how the page says it is being used.
                takeFocus()
                noteUse()
                signal()
            } else {
                loseFocus()
            }
            is EngineEvent.PageMessage -> deliverRestore()
            else -> Unit
        }
    }

    private fun onFirstPaint() {
        liftCover()
        snapshotDueAt = runtime.clock() + SNAPSHOT_SETTLE_MILLIS
        signal()
        if (!prewarmed) {
            prewarmed = true
            // A spare for the next view, at cold start only (the pool decides), once this page is on screen.
            val config = loaded?.config ?: return
            runtime.scope.launch { runtime.pool.prewarm(config) }
        }
    }

    private fun liftCover() {
        if (painted) return
        painted = true
        state.phase = WebViewPhase.Live
    }

    /**
     * Hands restored page state to the current document, once one of its origin is current; never to another origin.
     * A document that never comes leaves it undelivered, and it is kept.
     */
    private fun deliverRestore() {
        val saved = pendingRestore ?: return
        val bridge = state.bridge
        if (!bridge.isReady || bridge.documentOrigin != saved.origin) return
        val data = try {
            Json.parseToJsonElement(saved.json)
        } catch (e: IllegalArgumentException) {
            pendingRestore = null
            return
        }
        if (bridge.postPinned("\$restoreState", data, saved.origin)) pendingRestore = null
    }

    private fun onCrashed(crashed: WebEngine) {
        runtime.scope.launch {
            lock.withLock {
                if (engine !== crashed) return@withLock
                unbind()
                state.phase = WebViewPhase.Crashed
                withContext(NonCancellable) { runtime.pool.discard(this@WebViewHost) }
            }
            signal()
        }
    }

    /**
     * The state asks for something the lease doesn't show:
     * - other content, or the same loaded again: with the same engine config, the same engine starts the new
     *   document; otherwise the engine goes back, with nothing of the old page saved into the state, which shows
     *   other content now;
     * - the same page with another engine config: the page is saved, its engine goes back, and a new lease restores it;
     * - another setup only (theme, settings): the engine is configured again, mid-lease.
     */
    private suspend fun reconcile(lease: Loaded) = withContext(NonCancellable) {
        lock.withLock {
            val current = engine ?: return@withLock
            if (loaded !== lease) return@withLock
            val content = state.content
            val config = configFor(content)
            val switched = lease.generation != state.loadGeneration || lease.content != content
            when {
                switched && config == lease.config && current.isUsable -> startDocument(current, config)
                switched -> {
                    unbind()
                    state.phase = WebViewPhase.Cold
                    runtime.pool.release(this@WebViewHost)
                }
                config != lease.config -> try {
                    capture(current, lease, snapshot = true)
                } finally {
                    unbind()
                    state.phase = WebViewPhase.Cold
                    runtime.pool.release(this@WebViewHost)
                }
                else -> {
                    val setup = setupFor(content)
                    current.configure(setup)
                    allowedOrigins = setup.bridgeOrigins
                    loaded = Loaded(lease.content, lease.generation, lease.config, setup)
                }
            }
        }
    }

    /** No longer wanted live: the page is saved, then its engine goes back. Not cancelled when the view goes away. */
    private suspend fun deactivate() = withContext(NonCancellable) {
        lock.withLock {
            val current = engine ?: return@withLock
            try {
                loaded?.let { capture(current, it, snapshot = true) }
            } finally {
                unbind()
                state.phase = WebViewPhase.Cold
                interacted = false
                runtime.pool.release(this@WebViewHost)
            }
        }
    }

    /**
     * The pool is taking [engine] back, maybe late: only the engine this view still holds is saved and unbound. The
     * save stays cancellable, since the pool bounds it; the engine is unbound either way, and never touched after.
     */
    override suspend fun onEvict(engine: WebEngine) {
        lock.withLock {
            if (this.engine !== engine) return
            try {
                loaded?.let { capture(engine, it, snapshot = true) }
            } finally {
                unbind()
                state.phase = WebViewPhase.Cold
                interacted = false
            }
        }
        signal()
    }

    /**
     * The view left the composition. Its surface goes with it, so no snapshot is taken: the last good one stays. The
     * engine and page state are still saved, then the engine goes back.
     */
    fun dispose() {
        disposed = true
        stopListening()
        if (state.onNeedsEvaluation === stateListener) state.onNeedsEvaluation = null
        if (runtime.focused === this) runtime.focused = null
        val current = engine ?: return
        runtime.scope.launch {
            lock.withLock {
                if (engine !== current) return@withLock
                try {
                    loaded?.let { capture(current, it, snapshot = false) }
                } finally {
                    unbind()
                    state.phase = WebViewPhase.Cold
                    withContext(NonCancellable) { runtime.pool.release(this@WebViewHost) }
                }
            }
        }
    }

    /** Forgets the bound engine: nothing reaches it from this view after this. The pool decides what becomes of it. */
    private fun unbind() {
        val current = engine ?: return
        eventsJob?.cancel()
        eventsJob = null
        leaseScope?.cancel()
        leaseScope = null
        state.bridge.disconnect()
        current.client = null
        if (state.engine === current) state.engine = null
        engine = null
        painted = false
        loaded = null
        pendingRestore = null
        snapshotDueAt = null
        engineVisible = null
    }

    /**
     * Saves the page [lease] shows: its snapshot (if [snapshot]), its engine state and the state the page keeps, into
     * [state] and the runtime's page memory, keyed by the content the lease loaded.
     * - What the engine has no answer for keeps its last good value: a snapshot before the first paint, say.
     * - Restored page state that never reached a document is kept unless the page has new state of its own.
     * - If [state] moved on to other content meanwhile, nothing of this page is written into it.
     */
    private suspend fun capture(engine: WebEngine, lease: Loaded, snapshot: Boolean) {
        val image = if (snapshot) guarded { engine.snapshot() } else null
        val engineState = guarded { engine.saveState() }
        val answer: PageAnswer? = if (state.pageSavesState && shows(lease)) {
            guarded { state.bridge.callForState("\$saveState", timeoutMillis = PAGE_STATE_TIMEOUT_MILLIS) }
        } else {
            null
        }
        if (!shows(lease)) return
        if (image != null) keepSnapshot(image, lease)
        if (engineState != null) state.savedEngineState = engineState
        if (answer != null) {
            val result = answer.result
            if (result !is JsonNull) {
                state.pageState = SavedPageState(result.toString(), answer.origin)
            } else if (pendingRestore == null) {
                // The page had what was restored, and keeps nothing now.
                state.pageState = null
            }
        }
        runtime.pages.remember(lease.content.key, state.memory())
    }

    /** Refreshes the snapshot of the page [expected] shows, if it is still bound and painted. */
    private suspend fun refreshSnapshot(expected: WebEngine) {
        lock.withLock {
            if (engine !== expected || !painted) return
            val lease = loaded ?: return
            val image = guarded { expected.snapshot() } ?: return
            if (engine !== expected || loaded !== lease || !shows(lease)) return
            keepSnapshot(image, lease)
        }
    }

    /** Whether [state] still shows what [lease] loaded. */
    private fun shows(lease: Loaded): Boolean =
        state.loadGeneration == lease.generation && state.content == lease.content

    /** Caches [image] as [lease]'s snapshot, in place of the view's previous one, and makes it the cover. */
    private fun keepSnapshot(image: ImageBitmap, lease: Loaded) {
        val key = SnapshotKey(lease.content.key, image.width, image.height, lease.setup.dark, density)
        runtime.snapshots.put(key, image)
        val previous = state.snapshotKey
        if (previous != null && previous != key) runtime.snapshots.remove(previous)
        state.snapshotKey = key
        cover = Cover(image, key)
    }

    private fun cachedCover(key: SnapshotKey): Cover? = runtime.snapshots[key]?.let { Cover(it, key) }

    /** The engine and page requests, answered with this view's client. */
    private val clientAdapter = object : EngineClient {
        // These two may run on the engine's own thread: they read only what is published for it, and change nothing
        // but what they hand to main.
        override fun onNavigation(request: NavigationRequest): NavigationDecision {
            val decision = client.onNavigation(request, engineContent)
            if (decision == NavigationDecision.OpenExternally) openExternally(request.url)
            return decision
        }

        override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
            val client = client
            return when (val decision = client.onNewWindow(request)) {
                NewWindowDecision.OpenExternally -> {
                    openExternally(request.url)
                    decision
                }
                NewWindowDecision.OpenHere -> {
                    val here = NavigationRequest(request.url, isMainFrame = true, isUserGesture = request.isUserGesture)
                    val allowed = client.onNavigation(here, engineContent) == NavigationDecision.Allow
                    if (allowed) decision else NewWindowDecision.Block
                }
                NewWindowDecision.Block -> decision
            }
        }

        override suspend fun onDialog(dialog: WebDialog): DialogResult = client.onDialog(dialog)

        override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = client.onFileChooser(request)

        override suspend fun onPermission(request: PermissionRequest): Boolean = client.onPermission(request)

        override suspend fun onDownload(request: DownloadRequest): Boolean = client.onDownload(request)
    }

    /**
     * Hands [url] to the OS, on main. Only http(s), mailto, tel and sms are ever handed over, whatever a custom client
     * decided: other schemes reach OS protocol handlers (file:, intent:, ms-msdt:…). Safe to call from any thread.
     */
    private fun openExternally(url: String) {
        val scheme = schemeOf(url)
        if (scheme != "http" && scheme != "https" && scheme !in EXTERNAL_SCHEMES) return
        val target = normalizeUrl(url)
        // An OS with no app for it throws (Android's ActivityNotFoundException): nothing opens; the app hears nothing.
        runtime.scope.launch { runCatching { uriHandler?.openUri(target) } }
    }
}

/** [block]'s result, or null if it failed. Cancellation still cancels. */
private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** The earlier of two deadlines, either of which may be absent. */
private fun earliest(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)
