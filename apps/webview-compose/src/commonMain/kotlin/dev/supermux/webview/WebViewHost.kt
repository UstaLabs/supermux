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
import dev.supermux.webview.runtime.PageMemory
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.activationPriority
import dev.supermux.webview.runtime.computeCutouts
import dev.supermux.webview.runtime.decideActivation
import dev.supermux.webview.runtime.pageLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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

/** How long a snapshot or a save may take. An engine that doesn't answer in time is broken: destroyed, not reused. */
private const val ENGINE_CALL_MILLIS = 2_000L

/** How long a new view waits, before its first document, for the saves of its state or content still under way. */
private const val SAVES_WAIT_MILLIS = 1_000L

/** How long an engine changing hands waits for the composition to let go of its surface. */
private const val SURFACE_RELEASE_MILLIS = 500L

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
 * capacity listener unless the pool denied it; no snapshot state written as it scrolls; no snapshot pixels unless it is
 * on screen.
 *
 * It owns [state] once it takes it, before its first document ([WebViewState.owner]): a view that lost it, because the
 * state moved to another view while this one was still saving, touches nothing of it.
 *
 * Every lease follows the pool's holder contract:
 * - [priority] is read live, and the pool hears when it drops ([EnginePool.priorityLowered]) or the view is used
 *   ([EnginePool.touch]).
 * - It listens for capacity only while denied, subscribing right after the denial so no release is missed.
 * - It gives an engine back before asking for one of another config, and checks the config again when one arrives.
 * - [onEvict] acts only on the engine it still holds, inside its lock, and stops using it when it returns.
 *
 * Engines are not trusted to behave: every call into one is guarded, and every suspending one bounded. One that throws
 * is treated as crashed; one that stops answering is destroyed rather than handed to another view. An engine changes
 * hands only after the view that had it let go of its surface, so it is never composed in two places.
 */
internal class WebViewHost(
    internal val state: WebViewState,
    private val runtime: WebViewRuntime,
) : LeaseHolder {
    /** What a lease loaded: the content and the load it came from, the engine config and the setup it was given. */
    private class Loaded(val content: WebContent, val generation: Int, val config: EngineConfig, val setup: PageSetup)

    /** A snapshot that covers the page while no engine has painted, and the key it is cached under. */
    class Cover(val image: ImageBitmap, val key: SnapshotKey)

    /** One composition of an engine's surface: [gone] completes when it leaves. */
    class SurfacePresence(val engine: WebEngine) {
        val gone: CompletableDeferred<Unit> = CompletableDeferred()
    }

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

    /** Whether the bound engine painted the document the view started (or failed it). Until then the cover hides it. */
    var painted: Boolean by mutableStateOf(false)
        private set

    /**
     * The page's last snapshot, which the WebView draws as the cover while it is of the content and theme shown. Held
     * only while the view is on screen: a hidden view keeps the key ([WebViewState.snapshotKey]) and reads the cache
     * again when shown, so covers hold no more than a screenful of pixels.
     */
    var cover: Cover? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    /** Whether the view has been disposed: it leaves the composition, and lets its state go once its save is done. */
    var disposed: Boolean = false
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

    /** The bound engine let a call overrun its bound: it is broken, and goes back to be destroyed, never reused. */
    private var stuck = false

    /** Where the bound engine's surface is composed, if it is. */
    private var surface: SurfacePresence? = null

    private var interacted = false
    private var listening = false
    private var creationFailures = 0
    private var retryAt: Long? = null

    /** Whether this view has waited, once, for the saves under way that concern it ([takeOver]). */
    private var waitedForSaves = false

    /** Whether [state] held nothing of a page when this view was made, and which load it showed then. */
    private val freshAtInit: Boolean = state.snapshotKey == null && state.savedEngineState == null &&
        state.pageState == null && state.contentSize == null
    private val generationAtInit: Int = state.loadGeneration

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val capacityListener: () -> Unit = { signal() }
    private val stateListener: () -> Unit = { signal() }

    init {
        // Only into a state no other view holds: one still saving may write into it yet. [takeOver] reads it after.
        if (freshAtInit && state.owner == null) runtime.pages.recall(state.content.key)?.let(state::restoreFrom)
    }

    /** Read live by the pool: focused, then visible Eager, then visible; hidden ranks lowest. */
    override val priority: Int
        get() = activationPriority(activation, visible, focused = runtime.focused === this)

    private fun owns(): Boolean = state.owner === this

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

    /**
     * Called after every layout pass that moved the view. Cheap for a cold view: some comparisons, no state writes
     * unless it came into view or left it. True if the cover changed, so the view redraws it in this frame.
     */
    fun onPositioned(coordinates: LayoutCoordinates): Boolean {
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
        val coverChanged = setVisible(coordinates.isAttached && clipped.width >= 1f && clipped.height >= 1f, now)
        if (engine != null) publishBounds()
        return coverChanged
    }

    /** The view's layout left the composition, or was moved where nothing places it yet. */
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

    /** The bound engine's surface entered the composition. */
    fun surfaceEntered(engine: WebEngine): SurfacePresence = SurfacePresence(engine).also { surface = it }

    /** That surface left the composition. */
    fun surfaceLeft(presence: SurfacePresence) {
        presence.gone.complete(Unit)
        if (surface === presence) surface = null
    }

    /** True if the cover changed. */
    private fun setVisible(isVisible: Boolean, now: Long): Boolean {
        if (isVisible == visible) return false
        visible = isVisible
        hiddenSince = if (isVisible) null else now
        // Only views on screen hold a cover's pixels: a hidden one keeps the key, and reads the cache again when shown.
        cover = if (isVisible) state.snapshotKey?.let(::cachedCover) else null
        if (engine != null) {
            if (isVisible) runtime.pool.touch(this) else runtime.pool.priorityLowered()
        }
        signal()
        return true
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
            if (!owns()) {
                // The state moved to another view: this one gives its engine back and leaves the state alone.
                letGo()
                return now
            }
            if (engineVisible != visible) {
                engineVisible = visible
                if (!safely { current.setVisible(visible) }) {
                    crashNow(current)
                    return now
                }
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

    /**
     * Makes this view the owner of [state], before its first document. False while another view on screen holds it.
     * - Saves of this state or this content still under way (the view it moved from, the item that scrolled away)
     *   finish first, for [SAVES_WAIT_MILLIS] at most.
     * - Then a state nothing has been loaded into since this view was made takes what the page memory has now, so a
     *   lazy item scrolled back within its old save keeps what that save kept.
     */
    private suspend fun takeOver(): Boolean {
        if (owns()) return true
        if (heldElsewhere()) return false
        if (!waitedForSaves) {
            waitedForSaves = true
            awaitSaves()
            if (heldElsewhere()) return false
            state.owner = this
            if (freshAtInit && state.loadGeneration == generationAtInit) {
                runtime.pages.recall(state.content.key)?.let(state::restoreFrom)
                if (visible) cover = state.snapshotKey?.let(::cachedCover)
            }
            return true
        }
        state.owner = this
        return true
    }

    /** Whether another view still on screen owns [state]. A disposed one only finishes its save. */
    private fun heldElsewhere(): Boolean {
        val owner = state.owner as? WebViewHost ?: return false
        return owner !== this && !owner.disposed
    }

    private suspend fun awaitSaves() {
        val saves = runtime.saves.concerning(state, state.content.key)
        if (saves.isEmpty()) return
        val deadline = runtime.clock() + SAVES_WAIT_MILLIS
        for (save in saves) {
            val left = deadline - runtime.clock()
            if (left <= 0 || !awaitWithin(left, save)) return
        }
    }

    /** Leases an engine and binds it. Returns when to decide again, like [step]. */
    private suspend fun activate(): Long? {
        if (!takeOver()) return null
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

    /** Gives back a lease that was never bound, even from a cancelled coroutine. */
    private suspend fun giveBack() {
        withContext(NonCancellable) { runtime.pool.release(this@WebViewHost) }
    }

    /** Binds [engine], under the lock: an engine that is dead already, or throws, is treated as crashed. */
    private suspend fun bind(engine: WebEngine, config: EngineConfig) {
        val scope = CoroutineScope(runtime.scope.coroutineContext + SupervisorJob(runtime.scope.coroutineContext[Job]))
        leaseScope = scope
        this.engine = engine
        state.engine = engine
        stuck = false
        prewarmed = false
        publishBounds()
        if (!safely { engine.client = clientAdapter }) {
            crash(engine)
            return
        }
        // Only while live: the cut-outs follow the view's bounds and the window's overlays, once a frame at most, and
        // reach the engine only when they change in the page's own terms: a page that only moves needs nothing new.
        scope.launch {
            snapshotFlow { computeCutouts(pageBounds, visibleBounds, overlays.shapes, overlays.scrims) }
                .map { it to it.pageLocal() }
                .distinctUntilChangedBy { it.second }
                .collect { (cutouts, _) -> if (!safely { engine.applyOverlays(cutouts) }) onCrashed(engine) }
        }
        if (!startDocument(engine, config)) crash(engine)
    }

    /**
     * Starts the document [state] asks for in the bound [engine]: a fresh event subscription and bridge connection, so
     * nothing of the previous document carries over; the cover until it paints; the setup, then a restore of the saved
     * engine state or a load. False if the engine is dead or threw.
     */
    private fun startDocument(engine: WebEngine, config: EngineConfig): Boolean {
        val scope = leaseScope ?: return false
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
        if (visible) cover = state.snapshotKey?.let(::cachedCover)
        eventsJob?.cancel()
        state.bridge.disconnect()
        // Subscribed before the load, as the engine contract asks: undispatched, the collector subscribes right here.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { onEvent(engine, it) }
        }
        // A renderer that died before anything listened has had its Crashed event already.
        val usable = try {
            engine.isUsable
        } catch (e: Exception) {
            runtime.report(e)
            false
        }
        if (!usable) return false
        state.bridge.connect(engine::postMessage, ::originAllowed, scope)
        return safely {
            engine.configure(setup)
            val saved = state.savedEngineState
            if (saved == null || !engine.restoreState(saved, content)) engine.load(content)
        }
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
        if (event == EngineEvent.Crashed) {
            onCrashed(source)
            return
        }
        // A view that lost the state hears nothing more of its page.
        if (!owns()) return
        state.onEngineEvent(event)
        when (event) {
            EngineEvent.FirstPaint -> onFirstPaint()
            is EngineEvent.Error -> liftCover()
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

    /** The bound [crashed] engine's renderer died, or the engine broke: it is destroyed, and the view shows Crashed. */
    private fun onCrashed(crashed: WebEngine) {
        runtime.scope.launch {
            lock.withLock { crash(crashed) }
            signal()
        }
    }

    private suspend fun crashNow(crashed: WebEngine) = withContext(NonCancellable) {
        lock.withLock { crash(crashed) }
    }

    /** Under the lock: unbinds [crashed] if it is still bound, and destroys it once its surface has left. */
    private suspend fun crash(crashed: WebEngine) {
        if (engine !== crashed) return
        unbind()
        if (owns()) state.phase = WebViewPhase.Crashed
        withContext(NonCancellable) {
            awaitSurfaceGone(crashed)
            runtime.pool.discard(this@WebViewHost)
        }
    }

    /**
     * The state asks for something the lease doesn't show:
     * - other content, or the same loaded again: with the same engine config, the same engine starts the new
     *   document; otherwise the engine goes back. The old page is remembered under its own content, never in the
     *   state, which shows other content now;
     * - the same page with another engine config: the page is saved, its engine goes back, and a new lease restores it;
     * - another setup only (theme, settings): the engine is configured again, mid-lease.
     */
    private suspend fun reconcile(lease: Loaded) {
        withContext(NonCancellable) {
            lock.withLock {
                val current = engine ?: return@withLock
                if (loaded !== lease || !owns()) return@withLock
                val content = state.content
                val config = configFor(content)
                val switched = lease.generation != state.loadGeneration || lease.content != content
                if (switched && config == lease.config && current.isUsable) {
                    if (!startDocument(current, config)) crash(current)
                } else if (switched) {
                    try {
                        rememberOldPage(current, lease)
                    } finally {
                        handBack(current)
                    }
                } else if (config != lease.config) {
                    try {
                        capture(current, lease, snapshot = true)
                    } finally {
                        handBack(current)
                    }
                } else {
                    reconfigure(current, lease, content)
                }
            }
        }
    }

    /**
     * Applies a new setup to the page mid-lease. An origin taken off the bridge's list gets nothing more: if it is the
     * current document's, the bridge starts over, and that document can't announce itself again.
     */
    private suspend fun reconfigure(engine: WebEngine, lease: Loaded, content: WebContent) {
        val setup = setupFor(content)
        if (!safely { engine.configure(setup) }) {
            crash(engine)
            return
        }
        allowedOrigins = setup.bridgeOrigins
        loaded = Loaded(lease.content, lease.generation, lease.config, setup)
        val origin = state.bridge.documentOrigin
        val scope = leaseScope
        if (origin != null && origin !in allowedOrigins && scope != null) {
            state.bridge.disconnect()
            state.bridge.connect(engine::postMessage, ::originAllowed, scope)
        }
    }

    /** No longer wanted live: the page is saved, then its engine goes back. Not cancelled when the view goes away. */
    private suspend fun deactivate() = withContext(NonCancellable) {
        lock.withLock {
            val current = engine ?: return@withLock
            try {
                loaded?.let { capture(current, it, snapshot = true) }
            } finally {
                interacted = false
                handBack(current)
            }
        }
    }

    /** The state moved to another view: the engine goes back, with nothing of it written into the state. */
    private suspend fun letGo() = withContext(NonCancellable) {
        lock.withLock {
            val current = engine ?: return@withLock
            handBack(current)
        }
    }

    /**
     * Unbinds [engine] and gives it back once the composition has let go of its surface, so it never shows in two
     * places: destroyed if it stopped answering, recycled otherwise.
     */
    private suspend fun handBack(engine: WebEngine) {
        val healthy = !stuck
        unbind()
        if (owns()) state.phase = WebViewPhase.Cold
        withContext(NonCancellable) {
            awaitSurfaceGone(engine)
            if (healthy) runtime.pool.release(this@WebViewHost) else runtime.pool.discard(this@WebViewHost)
        }
    }

    /**
     * The pool is taking [engine] back, maybe late: only the engine this view still holds is saved and unbound. The
     * save stays cancellable, since the pool bounds it; the engine is unbound either way, and never touched after.
     * - An engine that stopped answering must go to no one: the view then waits out the pool's bound, after which the
     *   pool destroys it instead of handing it on.
     * - Otherwise it returns once the composition has let go of the engine's surface, for the next view to compose it.
     */
    override suspend fun onEvict(engine: WebEngine) {
        var broken = false
        lock.withLock {
            if (this.engine !== engine) return
            try {
                loaded?.let { capture(engine, it, snapshot = true) }
            } finally {
                broken = stuck
                unbind()
                if (owns()) state.phase = WebViewPhase.Cold
                interacted = false
            }
        }
        signal()
        if (broken) awaitCancellation()
        awaitSurfaceGone(engine)
    }

    /**
     * The view left the composition. Its surface goes with it, so no snapshot is taken: the last good one stays. The
     * engine and page state are still saved, in bounded time, then the engine goes back and the state is let go.
     */
    fun dispose() {
        disposed = true
        stopListening()
        if (state.onNeedsEvaluation === stateListener) state.onNeedsEvaluation = null
        if (runtime.focused === this) runtime.focused = null
        val current = engine
        if (current == null) {
            releaseState()
            return
        }
        // Registered now, before a new view of the state or content can look: it waits for this save.
        val save = runtime.saves.start(state, (loaded?.content ?: state.content).key)
        val job = runtime.scope.launch {
            lock.withLock {
                if (engine !== current) return@withLock
                try {
                    loaded?.let { capture(current, it, snapshot = false) }
                } finally {
                    handBack(current)
                }
            }
        }
        job.invokeOnCompletion {
            runtime.saves.finish(save)
            releaseState()
        }
    }

    /** A disposed view lets its state go: a view kept waiting for it can take it now. */
    private fun releaseState() {
        if (!owns()) return
        state.owner = null
        state.onNeedsEvaluation?.invoke()
    }

    /** Forgets the bound engine: nothing reaches it from this view after this. The pool decides what becomes of it. */
    private fun unbind() {
        val current = engine ?: return
        eventsJob?.cancel()
        eventsJob = null
        // Cancels the engine calls still running, too: none of them touches the engine once it is gone.
        leaseScope?.cancel()
        leaseScope = null
        if (owns()) {
            state.bridge.disconnect()
            if (state.engine === current) state.engine = null
        }
        safely { current.client = null }
        engine = null
        painted = false
        loaded = null
        pendingRestore = null
        snapshotDueAt = null
        engineVisible = null
    }

    /**
     * Saves the page [lease] shows into [state] and the runtime's page memory, keyed by the content the lease loaded:
     * the page's own state first, then its snapshot (if [snapshot]) and its engine state.
     * - The page's state comes first: it needs the state's bridge, which only the owner may use, and a new view of the
     *   state or content may be waiting for it. It is written, and remembered, as soon as it comes.
     * - Each engine call is bounded. What the engine has no answer for keeps its last good value: a snapshot before the
     *   first paint, say.
     * - Restored page state that never reached a document is kept unless the page has new state of its own.
     * - Nothing is written once this view lost the state, or the state moved on to other content.
     */
    private suspend fun capture(engine: WebEngine, lease: Loaded, snapshot: Boolean) {
        val save = runtime.saves.start(state, lease.content.key)
        try {
            if (owns() && shows(lease) && state.pageSavesState) {
                val answer = guarded {
                    state.bridge.callForState("\$saveState", timeoutMillis = PAGE_STATE_TIMEOUT_MILLIS)
                }
                if (answer != null && owns() && shows(lease)) {
                    keepPageState(answer)
                    runtime.pages.remember(lease.content.key, state.memory())
                }
            }
            val image = if (snapshot) engineCall { engine.snapshot() } else null
            val engineState = engineCall { engine.saveState() }
            if (!owns() || !shows(lease)) return
            if (image != null) keepSnapshot(image, lease)
            if (engineState != null) state.savedEngineState = engineState
            runtime.pages.remember(lease.content.key, state.memory())
        } finally {
            runtime.saves.finish(save)
        }
    }

    private fun keepPageState(answer: PageAnswer) {
        val result = answer.result
        if (result !is JsonNull) {
            state.pageState = SavedPageState(result.toString(), answer.origin)
        } else if (pendingRestore == null) {
            // The page had what was restored, and keeps nothing now.
            state.pageState = null
        }
    }

    /**
     * The page a config-changing switch leaves, remembered under its own content: its snapshot and engine state, taken
     * while the engine is still bound, over what was remembered for it before. Never into the state, which shows other
     * content now.
     */
    private suspend fun rememberOldPage(engine: WebEngine, lease: Loaded) {
        val save = runtime.saves.start(state, lease.content.key)
        try {
            val image = engineCall { engine.snapshot() }
            val engineState = engineCall { engine.saveState() }
            val previous = runtime.pages.recall(lease.content.key)
            var key = previous?.snapshotKey
            if (image != null) {
                val fresh = SnapshotKey(lease.content.key, image.width, image.height, lease.setup.dark, density)
                runtime.snapshots.put(fresh, image)
                if (key != null && key != fresh) runtime.snapshots.remove(key)
                key = fresh
            }
            if (key == null && engineState == null && previous == null) return
            runtime.pages.remember(
                lease.content.key,
                PageMemory(
                    snapshotKey = key,
                    contentSize = previous?.contentSize,
                    engineState = engineState ?: previous?.engineState,
                    pageState = previous?.pageState,
                    pageSavesState = previous?.pageSavesState ?: false,
                    lastSizeReport = previous?.lastSizeReport,
                    growsWithViewport = previous?.growsWithViewport ?: false,
                ),
            )
        } finally {
            runtime.saves.finish(save)
        }
    }

    /**
     * Refreshes the snapshot of the page [expected] shows, if it is still bound and painted. The lock isn't held across
     * the snapshot, so an eviction or a deactivation never waits for it; unbinding cancels it.
     */
    private suspend fun refreshSnapshot(expected: WebEngine) {
        if (engine !== expected || !painted || !owns()) return
        val lease = loaded ?: return
        val image = engineCall { expected.snapshot() } ?: return
        if (engine !== expected || loaded !== lease || !owns() || !shows(lease)) return
        keepSnapshot(image, lease)
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
        // Only a view on screen holds the pixels; a hidden one reads them back from the cache when shown.
        cover = if (visible) Cover(image, key) else null
    }

    private fun cachedCover(key: SnapshotKey): Cover? = runtime.snapshots[key]?.let { Cover(it, key) }

    /**
     * A suspending call into the bound engine, bounded at [ENGINE_CALL_MILLIS]: its result, or null if it failed or
     * overran. It runs in the lease's scope, so unbinding cancels it; one that overruns is cancelled, and marks the
     * engine [stuck].
     */
    private suspend fun <T> engineCall(block: suspend () -> T?): T? {
        val scope = leaseScope ?: return null
        val call = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runtime.report(e)
                null
            }
        }
        if (!awaitWithin(ENGINE_CALL_MILLIS, call)) {
            call.cancel()
            stuck = true
            runtime.report(IllegalStateException("an engine call took longer than $ENGINE_CALL_MILLIS ms"))
            return null
        }
        return if (call.isCancelled) null else call.await()
    }

    /** Runs [block], a call into the engine: false, and reported, if it threw. Engines must not throw. */
    private inline fun safely(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runtime.report(e)
        false
    }

    /** Waits until the composition has let go of [engine]'s surface, for [SURFACE_RELEASE_MILLIS] at most. */
    private suspend fun awaitSurfaceGone(engine: WebEngine) {
        val presence = surface?.takeIf { it.engine === engine } ?: return
        awaitWithin(SURFACE_RELEASE_MILLIS, presence.gone)
    }

    /** The engine and page requests, answered with this view's client. */
    private val clientAdapter = object : EngineClient {
        // These two may run on the engine's own thread: they read only what is published for it, and change nothing
        // but what they hand to main.
        override fun onNavigation(request: NavigationRequest): NavigationDecision {
            val decision = client.onNavigation(request, engineContent)
            if (decision == NavigationDecision.OpenExternally) openExternally(request.url)
            return decision
        }

        /** "Open here" loads the URL here where the policy allows it here, and opens it outside where it says to. */
        override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
            val client = client
            return when (val decision = client.onNewWindow(request)) {
                NewWindowDecision.OpenExternally -> {
                    openExternally(request.url)
                    decision
                }
                NewWindowDecision.OpenHere -> {
                    val here = NavigationRequest(request.url, isMainFrame = true, isUserGesture = request.isUserGesture)
                    when (client.onNavigation(here, engineContent)) {
                        NavigationDecision.Allow -> decision
                        NavigationDecision.OpenExternally -> {
                            openExternally(request.url)
                            NewWindowDecision.OpenExternally
                        }
                        NavigationDecision.Block -> NewWindowDecision.Block
                    }
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

/**
 * Waits until [job] completes or [millis] pass, kept with `delay` for the reason [WebViewHost]'s sleep gives. True if
 * it completed. [job] is not cancelled.
 */
private suspend fun awaitWithin(millis: Long, job: Job): Boolean {
    if (job.isCompleted) return true
    return coroutineScope {
        val timer = launch { delay(millis) }
        try {
            select {
                job.onJoin { true }
                timer.onJoin { false }
            }
        } finally {
            timer.cancel()
        }
    }
}

/** The earlier of two deadlines, either of which may be absent. */
private fun earliest(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)
