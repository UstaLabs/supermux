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
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.runtime.AcquireResult
import dev.supermux.webview.runtime.ActivationInputs
import dev.supermux.webview.runtime.LeaseHolder
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.OverlayRegistry
import dev.supermux.webview.runtime.PageKey
import dev.supermux.webview.runtime.PageHolder
import dev.supermux.webview.runtime.PageMemory
import dev.supermux.webview.runtime.ParkedPage
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.activationPriority
import dev.supermux.webview.runtime.keptSnapshot
import dev.supermux.webview.runtime.computeCutouts
import dev.supermux.webview.runtime.decideActivation
import dev.supermux.webview.runtime.pageLocal
import dev.supermux.webview.runtime.pixelBytes
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

/** How long after its crash a crashed view on screen goes live again by itself (a shared renderer died, spec §4). */
private const val AUTO_RECOVERY_DELAY_MILLIS = 1_000L

/**
 * A view goes live again by itself at most once in this window: a page that kills its renderer whenever it loads stays
 * Crashed after one retry, until the app reloads it.
 */
private const val AUTO_RECOVERY_WINDOW_MILLIS = 60_000L

/**
 * One [WebView]'s lease lifecycle (spec §4). It watches where the view is and what it shows, asks [decideActivation]
 * whether it should be live, leases, binds and gives back engines, keeps the page's snapshot, and saves the page's
 * engine and page state into [state] and the runtime's page memory when its engine goes.
 *
 * A view that leaves the screen keeps its engine, at the lowest priority and told it is hidden
 * ([WebEngine.setVisible]): scrolled back, its page is as it was. It gives the engine up only to a visible view that
 * needs it (an eviction), to memory pressure, or after [dev.supermux.webview.runtime.RuntimeConfig.hiddenReleaseMillis].
 * A lazy list's item that scrolled out but stays composed, unplaced, is hidden as well ([onUnplaced]).
 *
 * A view that leaves the composition with its page live (a lazy list's item that scrolled away) saves the page as
 * before, then parks it in the pool instead of giving its engine back ([EnginePool.park]): the next view of the same
 * page adopts it as it is, unreloaded, with its in-page state ([adoptDocument]). The pool reclaims it first when a view
 * needs the slot, or once it has been parked as long as a hidden view keeps its engine with no view adopting it, and
 * the page saves what it has then ([ParkedView.onReclaim]).
 *
 * Main thread only, except [clientAdapter]'s two synchronous callbacks, which engines may call on their own thread.
 *
 * What a cold view costs: no coroutine but its [run] loop, which sleeps until something it decides on changes; no
 * capacity listener unless the pool denied it; no snapshot state written as it scrolls; no snapshot pixels unless it is
 * on screen.
 *
 * It owns [state] once it takes it, before its first document ([WebViewState.owner]): a view that lost it, because the
 * state moved to another view while this one was still saving, touches nothing of it. Only the owner's engine is
 * reachable through the state ([WebViewState.engine], the navigator).
 *
 * What it saves and shows is per page: the content in the resolved profile ([PageKey]). A page shown in another profile
 * starts fresh, with no restore and no cover, and nothing from before a [WebViewRuntime.clear] is kept or shown.
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
) : LeaseHolder, StateOwner, PageHolder {
    /**
     * What a lease loaded: the content and the load it came from, the engine config and the setup it was given, and
     * the runtime's [WebViewRuntime.memoryEpoch] when it started: a page started before a clear saves nothing after it.
     * [forgetMark] is the runtime's [WebViewRuntime.forgets] since when its content wasn't forgotten.
     */
    private class Loaded(
        val content: WebContent,
        val generation: Int,
        val config: EngineConfig,
        val setup: PageSetup,
        val epoch: Int,
        var forgetMark: Int,
    ) {
        val page: PageKey get() = PageKey(config.profile, content.key)
    }

    /**
     * A snapshot that covers the page while no engine has painted, the key it is cached under, and the runtime's
     * [WebViewRuntime.memoryEpoch] it belongs to.
     */
    class Cover(val image: ImageBitmap, val key: SnapshotKey, val epoch: Int)

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
    override var engine: WebEngine? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    /** Whether the bound engine painted the document the view started (or failed it). Until then the cover hides it. */
    var painted: Boolean by mutableStateOf(false)
        private set

    private var coverState: Cover? by mutableStateOf(null, referentialEqualityPolicy())

    /**
     * The engine whose painted page the composition shows now: its surface composed, and no placeholder. The cover is
     * drawn until then, even once [painted]: an engine can paint before the composition has caught up (an adopted page
     * replays its first paint at once), and a frame drawn from the composition before would show the placeholder, or
     * nothing, where the page should be.
     */
    private var composedPaint: WebEngine? by mutableStateOf(null, referentialEqualityPolicy())

    /** What [cover] counts for in the runtime's [WebViewRuntime.coverBytes]; null while there is none. */
    private var coverCost: Long? = null

    /**
     * The page's last snapshot, which the WebView draws as the cover while it is of the content and theme shown. Held
     * only while the view is on screen: a hidden view keeps the key ([WebViewState.snapshotKey]) and asks the cache
     * again when shown, so covers hold no more than a screenful of pixels. The runtime counts what they hold
     * ([WebViewRuntime.coverBytes]).
     */
    var cover: Cover?
        get() = coverState
        private set(value) {
            val cost = value?.let { pixelBytes(it.image) }
            runtime.coverChanged(coverCost, cost)
            coverCost = cost
            coverState = value
        }

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

    /**
     * The view's bounds in the window, mirrored into snapshot state only while bound to an engine that needs cut-outs
     * ([cutoutsWanted]), for them.
     */
    private var pageBounds by mutableStateOf(Rect.Zero)
    private var visibleBounds by mutableStateOf(Rect.Zero)

    // The lease.
    private var loaded: Loaded? = null

    /** Whether the bound engine's page sits above Compose, and is told its cut-outs ([WebEngine.needsCutouts]). */
    private var cutoutsWanted = false
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

    /**
     * The view adopted a page that announced itself to a connection before this one: the engine's replayed commit tells
     * the bridge where the page is, and the page is asked to announce itself then.
     */
    private var announceOnCommit = false

    /** When to refresh the snapshot: after the first paint settles, or after the user's last interaction. */
    private var snapshotDueAt: Long? = null

    /**
     * Whether the cached snapshot shows the page as it is: taken since the page last painted, committed, reported a size
     * or was touched, and since the view was laid out at another size, drawn at another density or configured again
     * (theme, settings). Going cold then needs no second snapshot (Plan 1's deferred "one snapshot per page start"), as
     * long as the cache still holds this one.
     */
    private var snapshotFresh = false

    /** The view's size when its snapshot was kept: laid out at another size, the page has reflowed since. */
    private var snapshotViewSize = IntSize.Zero
    private var prewarmed = false

    /** The bound engine let a call overrun its bound: it is broken, and goes back to be destroyed, never reused. */
    private var stuck = false

    /** Where the bound engine's surface is composed, if it is. */
    private var surface: SurfacePresence? = null

    private var interacted = false
    private var listening = false
    private var creationFailures = 0
    private var retryAt: Long? = null

    /** When the view's engine last crashed under it, and when the view last went live again by itself. */
    private var crashedAt: Long? = null
    private var lastAutoRecovery: Long? = null

    /** Whether this view has waited, once, for the saves under way that concern it ([takeOver]). */
    private var waitedForSaves = false

    /**
     * Whether [state] held nothing of a page when this view was made, nor ever had one shown in it, and which load it
     * showed then.
     */
    private val freshAtInit: Boolean = !state.pageShown && state.snapshotKey == null && state.savedEngineState == null &&
        state.pageState == null && state.contentSize == null
    private val generationAtInit: Int = state.loadGeneration

    /** Whether [update] has run: the settings, and with them the page's profile, are known from then on. */
    private var updated = false

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val capacityListener: () -> Unit = { signal() }
    private val stateListener: () -> Unit = { signal() }

    /** Read live by the pool: focused, then visible Eager, then visible; hidden ranks lowest. */
    override val priority: Int
        get() = activationPriority(activation, visible, focused = runtime.focused === this)

    private fun owns(): Boolean = state.owner === this

    /** The profile [state]'s content is shown in, with the settings the view has now. */
    private fun profileNow(): WebProfile = settings.profileFor(state.content)

    /** The page [state] shows now, as page memory and snapshots know it. */
    private fun pageNow(): PageKey = PageKey(profileNow(), state.content.key)

    /**
     * Takes what the WebView was composed with. Cheap: it wakes the host only when something it decides on changed.
     * The first call, made before the view's first layout, gives a state nothing has been shown in yet what page
     * memory has for its page, so the view shows the page's snapshot at its height from its first frame.
     */
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
        // Drawn at another density, the page no longer looks like its snapshot.
        if (density != this.density) snapshotFresh = false
        this.density = density
        if (this.overlays !== overlays) this.overlays = overlays
        val first = !updated
        val changed = settings != this.settings || activation != this.activation || dark != this.dark
        if (!changed && !first) return
        val before = priority
        val profileBefore = profileNow()
        this.settings = settings
        this.activation = activation
        this.dark = dark
        if (first) {
            updated = true
            // Only into a state no other view holds: one still saving may write into it yet. [takeOver] reads it after.
            if (freshAtInit && state.owner == null && state.loadGeneration == generationAtInit) recallPage()
            prefetchCover()
        } else if (profileNow() != profileBefore && visible && engine == null) {
            // A page shown in another profile starts fresh: the old profile's snapshot doesn't cover it.
            cover = coverFor(state.snapshotKey)
        }
        if (engine != null && priority < before) runtime.pool.priorityLowered()
        signal()
    }

    /**
     * Starts decoding the page's snapshot as the view is composed, before it is seen: a lazy list composes an item just
     * before it scrolls in, so its cover is usually ready by the frame it shows.
     */
    private fun prefetchCover() {
        val key = state.snapshotKey ?: return
        if (coverable(key)) runtime.snapshots.request(key)
    }

    /** Takes what page memory has for the page [state] shows, if anything, as the state's memory of it. */
    private fun recallPage() {
        val page = pageNow()
        val memory = runtime.pages.recall(page) ?: return
        state.restoreFrom(memory)
        state.memoryProfile = page.profile
        state.memoryEpoch = runtime.memoryEpoch
    }

    /**
     * Makes what [state] remembers of its page [profile]'s, as of the runtime's last [WebViewRuntime.clear]: what it
     * holds of another profile's page, or from before a clear, goes. A page shown in another profile starts fresh,
     * with no restore and no cover: profiles are a boundary.
     */
    private fun claimMemory(profile: WebProfile) {
        val epoch = runtime.memoryEpoch
        val held = state.memoryProfile
        if (held != null && (held != profile || state.memoryEpoch != epoch)) state.forgetPage()
        state.memoryProfile = profile
        state.memoryEpoch = epoch
    }

    /**
     * The navigator's way into the engine: [command] runs on the bound engine, guarded. An engine that throws is treated
     * as crashed, and reported; nothing reaches the app, whose click handler called this.
     */
    override fun onEngine(command: (WebEngine) -> Unit): Boolean {
        val current = engine ?: return false
        if (!owns()) return false
        try {
            command(current)
        } catch (e: Exception) {
            runtime.report(e)
            onCrashed(current)
        }
        return true
    }

    fun signal() {
        wake.trySend(Unit)
    }

    /**
     * The runtime forgot pages: the view looks whether its lease shows one of them ([matches]). A lease whose page isn't
     * among them counts from now, so a page that stays live long never takes old forgets of others for its own.
     */
    override fun onPagesForgotten() {
        loaded?.let { if (!forgotten(it)) it.forgetMark = runtime.forgets }
        signal()
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
            // Laid out at another size, the page reflows: its snapshot no longer shows it. Collapsed to nothing, it is
            // only hidden.
            if (size != snapshotViewSize && size.width > 0 && size.height > 0) snapshotFresh = false
        }
        val clipped = coordinates.boundsInWindow()
        lastVisibleBounds = clipped
        val coverChanged = setVisible(coordinates.isAttached && clipped.width >= 1f && clipped.height >= 1f, now)
        if (cutoutsWanted && engine != null) publishBounds()
        return coverChanged
    }

    /**
     * The view's layout is no longer placed, so nothing of it shows: it left the composition, was moved where nothing
     * places it yet, or is a lazy list's item that scrolled out and that the list keeps composed without placing it
     * (one it measured on its way to the new first item, until it measures without it). It counts as hidden until
     * placed again: a lazy list's retained item hears nothing of where it is meanwhile, and would otherwise hold its
     * engine as if on screen.
     */
    fun onUnplaced() {
        setVisible(false, runtime.clock())
    }

    /** The user pressed on the view. */
    fun onInteraction() {
        interacted = true
        takeFocus()
        noteUse()
        signal()
    }

    /** A composition was applied that shows [engine]'s page painted ([painted]), or not: the cover may go once it does. */
    fun composed(painted: Boolean, engine: WebEngine?) {
        val showing = if (painted) engine else null
        if (composedPaint !== showing) composedPaint = showing
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
        cover = if (isVisible) coverFor(state.snapshotKey) else null
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
        snapshotFresh = false
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
                // Not while the view moves (its list scrolls or flings): an engine's snapshot may take the main thread
                // for longer than a frame. Once it has been still for the settle time.
                val still = lastMovedAt + SNAPSHOT_SETTLE_MILLIS
                if (now < still) {
                    snapshotDueAt = still
                    return earliest(decision.reevaluateAt, still)
                }
                snapshotDueAt = null
                refreshSnapshot(current)
                return now
            }
            return earliest(decision.reevaluateAt, due)
        }
        if (!decision.wantLive) {
            stopListening()
            dropParkedBridge()
            return decision.reevaluateAt
        }
        when (val phase = state.phase) {
            WebViewPhase.Crashed -> {
                stopListening()
                dropParkedBridge()
                val at = autoRecoveryAt() ?: return null
                if (now < at) return at
                // Back by itself: the crash was most likely the shared renderer's, not this page's.
                lastAutoRecovery = now
                crashedAt = null
                state.phase = WebViewPhase.Cold
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

    /**
     * Whether the lease still shows what the state asks for, with the engine config and setup it asks for, and a page
     * the runtime still keeps: not one started before its last clear, or whose content it forgot since.
     */
    private fun matches(lease: Loaded): Boolean {
        val content = state.content
        return lease.generation == state.loadGeneration && lease.content == content &&
            lease.config == configFor(content) && lease.setup == setupFor(content) && !forgotten(lease)
    }

    /**
     * Whether the runtime forgot [lease]'s page since it started: a clear since (it may be the last user's), or a
     * forget of its content (gone). Such a page is given back unsaved, and nothing of it is kept.
     */
    private fun forgotten(lease: Loaded): Boolean =
        lease.epoch != runtime.memoryEpoch || runtime.forgottenSince(lease.forgetMark, lease.content.key)

    /**
     * When this crashed view goes live again by itself: only while on screen, [AUTO_RECOVERY_DELAY_MILLIS] after the
     * crash, and only if it hasn't done so in the [AUTO_RECOVERY_WINDOW_MILLIS] before the crash. Null: it waits for the
     * app (`navigator.reload()`), or to be shown.
     */
    private fun autoRecoveryAt(): Long? {
        val crashed = crashedAt ?: return null
        if (!visible) return null
        val last = lastAutoRecovery
        if (last != null && crashed - last < AUTO_RECOVERY_WINDOW_MILLIS) return null
        return crashed + AUTO_RECOVERY_DELAY_MILLIS
    }

    private fun configFor(content: WebContent): EngineConfig =
        EngineConfig(settings.profileFor(content), settings.mediaPlaybackRequiresGesture)

    /** The lease's setup: the bridge goes to the listed origins and to the content's own local origin. */
    private fun setupFor(content: WebContent): PageSetup {
        val local = content.localOrigin?.origin ?: return settings.pageSetup(dark, settings.bridgeOrigins)
        return settings.pageSetup(dark, settings.bridgeOrigins + local)
    }

    /**
     * Makes this view the owner of [state], before its first document. Null while another view on screen holds it;
     * otherwise whether it had to wait for saves first, during which the view may have changed.
     * - Saves of this state or this page still under way (the view it moved from, the item that scrolled away) finish
     *   first, for [SAVES_WAIT_MILLIS] at most.
     * - Then a state nothing has been loaded into since this view was made takes what the page memory has now, so a
     *   lazy item scrolled back within its old save keeps what that save kept.
     * - Nothing of the view it takes the state from stays reachable ([becomeOwner]), and nothing of another profile's
     *   page, or from before a clear, stays remembered ([claimMemory]).
     */
    private suspend fun takeOver(): Boolean? {
        if (owns()) return false
        if (heldElsewhere()) return null
        var waited = false
        if (!waitedForSaves) {
            waitedForSaves = true
            waited = awaitSaves()
            if (heldElsewhere()) return null
            becomeOwner()
            if (freshAtInit && state.loadGeneration == generationAtInit) recallPage()
        } else {
            becomeOwner()
        }
        claimMemory(profileNow())
        if (visible) cover = coverFor(state.snapshotKey)
        return waited
    }

    /**
     * Takes [state] from whichever view had it. That view may still hold its engine while it saves, but the state no
     * longer leads there: its engine is only ever the owner's ([WebViewState.engine]), the bridge's connection to it
     * ends, and its phase, which was that engine's, goes back to Cold.
     */
    private fun becomeOwner() {
        state.owner = this
        state.bridge.disconnect()
        if (state.phase == WebViewPhase.Live || state.phase == WebViewPhase.Activating) state.phase = WebViewPhase.Cold
    }

    /** Whether another view still on screen owns [state]. A disposed one only finishes its save. */
    private fun heldElsewhere(): Boolean {
        val owner = state.owner as? WebViewHost ?: return false
        return owner !== this && !owner.disposed
    }

    /** Waits, for [SAVES_WAIT_MILLIS] at most, for the saves that concern this view. True if there were any. */
    private suspend fun awaitSaves(): Boolean {
        val saves = runtime.saves.concerning(state, pageNow())
        if (saves.isEmpty()) return false
        val deadline = runtime.clock() + SAVES_WAIT_MILLIS
        for (save in saves) {
            val left = deadline - runtime.clock()
            if (left <= 0 || !awaitWithin(left, save)) break
        }
        return true
    }

    /**
     * Leases an engine and binds it. Returns when to decide again, like [step]. A view that had to wait for saves
     * before taking its state decides again first: it may have left the screen in that second.
     */
    private suspend fun activate(): Long? {
        if (!owns()) {
            val waited = takeOver() ?: return null
            if (waited) return runtime.clock()
        }
        lock.withLock {
            if (disposed) return null
            if (engine != null) return runtime.clock()
            val config = configFor(state.content)
            when (val result = runtime.pool.acquire(this, config, pageNow(), ::mayAdopt)) {
                is AcquireResult.Granted -> {
                    // Only pages a WebView parked are ever adopted ([mayAdopt]).
                    val adopted = result.adopted as? ParkedView
                    if (disposed || !currentCoroutineContext().isActive) {
                        giveBack(adopted)
                        return null
                    }
                    // A load() may have changed the content while the engine was on its way.
                    if (configFor(state.content) != config || (adopted != null && !mayAdopt(adopted))) {
                        giveBack(adopted)
                        return runtime.clock()
                    }
                    stopListening()
                    creationFailures = 0
                    retryAt = null
                    bind(result.engine, config, adopted)
                    return runtime.clock()
                }
                is AcquireResult.Unavailable -> {
                    stopListening()
                    dropParkedBridge()
                    state.phase = WebViewPhase.Unavailable(result.reason, result.detail)
                    if (result.reason != UnavailableReason.CreationFailed) return null
                    val wait = CREATION_RETRY_MILLIS[creationFailures.coerceAtMost(CREATION_RETRY_MILLIS.lastIndex)]
                    creationFailures++
                    return (runtime.clock() + wait).also { retryAt = it }
                }
                AcquireResult.Denied -> {
                    // Synchronously after the denial: a release in between can't be missed.
                    startListening()
                    dropParkedBridge()
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

    /**
     * The view is without an engine, and isn't getting one now: a bridge it kept waiting for the new content's document
     * across a switch ([reconcile]) waits no more, and what was sent to it fails, as for any view without an engine.
     */
    private fun dropParkedBridge() {
        if (owns() && state.bridge.isParked) state.bridge.disconnect()
    }

    /**
     * Gives back a lease that was never bound, even from a cancelled coroutine. A page it [adopted] goes back parked, as
     * it was: nothing touched it.
     */
    private suspend fun giveBack(adopted: ParkedView? = null) {
        withContext(NonCancellable) {
            if (adopted != null) runtime.pool.park(this@WebViewHost, adopted) else runtime.pool.release(this@WebViewHost)
        }
    }

    /**
     * Whether this view may take over [parked] as it is: a page of what the state shows, in its profile, that the runtime
     * hasn't forgotten (a clear, a forget of its content), and nothing loaded into the state since. A load navigates,
     * even to the content shown: the state it parked from adopts it only from that load, and another state only if it
     * has had no load since this view was made, as page memory is recalled only into such a state.
     */
    private fun mayAdopt(parked: ParkedPage): Boolean {
        val page = parked as? ParkedView ?: return false
        if (page.forgotten() || page.key != pageNow()) return false
        return if (page.state === state) state.loadGeneration == page.generation else state.loadGeneration == generationAtInit
    }

    /**
     * Binds [engine], under the lock: an engine that is dead already, or throws, is treated as crashed. A page it
     * [adopted] is taken over as it is ([adoptDocument]); otherwise the state's document starts ([startDocument]).
     */
    private suspend fun bind(engine: WebEngine, config: EngineConfig, adopted: ParkedView? = null) {
        val scope = CoroutineScope(runtime.scope.coroutineContext + SupervisorJob(runtime.scope.coroutineContext[Job]))
        leaseScope = scope
        this.engine = engine
        stuck = false
        prewarmed = false
        var needsCutouts = false
        if (!safely {
                engine.client = clientAdapter
                needsCutouts = engine.needsCutouts
            }
        ) {
            crash(engine)
            return
        }
        cutoutsWanted = needsCutouts
        // Only while live, and only for a page above Compose: the cut-outs follow the view's bounds and the window's
        // overlays, once a frame at most, and reach the engine only when they change in the page's own terms. A page
        // that only moves needs nothing new; one that Compose draws over needs none at all.
        if (needsCutouts) {
            publishBounds()
            scope.launch {
                snapshotFlow { computeCutouts(pageBounds, visibleBounds, overlays.shapes, overlays.scrims) }
                    .map { it to it.pageLocal() }
                    .distinctUntilChangedBy { it.second }
                    .collect { (cutouts, _) -> if (!safely { engine.applyOverlays(cutouts) }) onCrashed(engine) }
            }
        }
        val started = if (adopted != null) adoptDocument(engine, config, adopted) else startDocument(engine, config)
        if (!started) crash(engine)
    }

    /**
     * Starts the document [state] asks for in the bound [engine]: a fresh event subscription and bridge connection, so
     * nothing of the previous document carries over; the cover until it paints; the setup, then a restore of the saved
     * engine state or a load. False if the engine is dead or threw.
     *
     * What the state remembers is used only if it is this profile's page, from since the last clear ([claimMemory]).
     */
    private fun startDocument(engine: WebEngine, config: EngineConfig): Boolean {
        val scope = leaseScope ?: return false
        val content = state.content
        val setup = setupFor(content)
        claimMemory(config.profile)
        loaded = Loaded(content, state.loadGeneration, config, setup, runtime.memoryEpoch, runtime.forgets)
        state.pageShown = true
        engineContent = content
        allowedOrigins = setup.bridgeOrigins
        painted = false
        state.phase = WebViewPhase.Activating
        snapshotDueAt = null
        snapshotFresh = false
        // The document announces its own save provider; what was saved goes back to it, if it is of that origin.
        state.pageSavesState = false
        pendingRestore = state.pageState
        announceOnCommit = false
        if (visible) cover = coverFor(state.snapshotKey)
        eventsJob?.cancel()
        // Subscribed before the load, as the engine contract asks: undispatched, the collector subscribes right here.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { onEvent(engine, it) }
        }
        // A renderer that died before anything listened has had its Crashed event already.
        if (!usable(engine)) return false
        // A fresh connection, for this document only. After a load() the bridge already waits for it, parked, with what
        // the app sent since: the connection takes that over, and delivers it once this document announces itself.
        state.bridge.connect(engine::postMessage, ::originAllowed, scope)
        return safely {
            engine.configure(setup)
            val saved = state.savedEngineState
            if (saved == null || !engine.restoreState(saved, content)) engine.load(content)
        }
    }

    /**
     * Takes over the page [parked] left live in [engine], as it is: no [WebEngine.load] or [WebEngine.restoreState], and
     * no [WebEngine.configure] unless the setup changed meanwhile, which configures it mid-lease. The live page is newer
     * than anything saved of it, so nothing is restored into it. A fresh event subscription and bridge connection, as for
     * any document: the engine replays its page ([WebEngine.replayState]), whose first paint lifts the cover at once, and
     * the page is asked to announce itself to the new connection once the replayed commit says where it is. False if the
     * engine is dead or threw.
     */
    private fun adoptDocument(engine: WebEngine, config: EngineConfig, parked: ParkedView): Boolean {
        val scope = leaseScope ?: return false
        val content = state.content
        val setup = setupFor(content)
        claimMemory(config.profile)
        loaded = Loaded(content, state.loadGeneration, config, setup, runtime.memoryEpoch, runtime.forgets)
        state.pageShown = true
        engineContent = content
        allowedOrigins = setup.bridgeOrigins
        painted = false
        state.phase = WebViewPhase.Activating
        snapshotDueAt = null
        snapshotFresh = false
        // The page's save provider is the one it announced before it was parked.
        state.pageSavesState = parked.pageSavesState
        pendingRestore = null
        announceOnCommit = true
        if (visible) cover = coverFor(state.snapshotKey)
        eventsJob?.cancel()
        // Connected first: the engine kept what the page did while parked, and the collector, undispatched, hears it
        // right here. A commit among it asks the page to announce itself, which needs the connection.
        state.bridge.connect(engine::postMessage, ::originAllowed, scope)
        // Subscribed before the replay.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { onEvent(engine, it) }
        }
        if (!usable(engine)) return false
        return safely {
            if (setup != parked.setup) engine.configure(setup)
            engine.replayState()
        }
    }

    /** [engine]'s [WebEngine.isUsable]: false, and reported, if it throws. */
    private fun usable(engine: WebEngine): Boolean = try {
        engine.isUsable
    } catch (e: Exception) {
        runtime.report(e)
        false
    }

    private fun originAllowed(origin: String): Boolean = origin in allowedOrigins

    /** What is over the page now: its window's overlays and scrims, against where the view is and what of it shows. */
    private fun cutoutsNow(): OverlayCutouts {
        val position = lastPosition
        val page = if (position.isSpecified) Rect(position, lastSize.toSize()) else Rect.Zero
        return computeCutouts(page, lastVisibleBounds, overlays.shapes, overlays.scrims)
    }

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
        // A view that lost the state hears nothing more of its page. Nor does the state hear the page it no longer shows:
        // after a load(), until this view starts the new content's document, the old one's title, size and messages
        // would land in the new content's fields.
        if (!owns() || loaded?.generation != state.loadGeneration) return
        state.onEngineEvent(event)
        if (event is EngineEvent.UrlChanged && announceOnCommit) {
            // An adopted page announced itself to an earlier connection: the replayed commit says where it is now.
            announceOnCommit = false
            state.bridge.requestAnnouncement()
        }
        if (event is EngineEvent.UrlChanged || event is EngineEvent.ContentSize) snapshotFresh = false
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
        snapshotFresh = false
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
        if (owns()) {
            state.phase = WebViewPhase.Crashed
            crashedAt = runtime.clock()
        }
        withContext(NonCancellable) {
            awaitSurfaceGone(crashed)
            runtime.pool.discard(this@WebViewHost)
        }
    }

    /**
     * The state asks for something the lease doesn't show:
     * - other content, or the same loaded again: from one network page to another with the same engine config, the
     *   same engine starts the new document, and the page keeps its history. Otherwise the engine goes back, and the
     *   view takes a fresh lease: history never crosses local content ([WebContent.Html], [WebContent.Bundle]), which
     *   the pool's reset guarantees. The old page is remembered under its own page, never in the state, which shows
     *   other content now. The bridge, which [WebViewState.load] parked, keeps what the app sent since for the new
     *   document, even across the change of engine;
     * - the same page with another engine config: the page is saved, its engine goes back, and a new lease restores it,
     *   unless the profile changed: a page shown in another profile starts fresh;
     * - another setup only (theme, settings): the engine is configured again, mid-lease;
     * - a page the runtime forgot ([forgotten]): its engine goes back unsaved, to be reset, and the view starts afresh.
     */
    private suspend fun reconcile(lease: Loaded) {
        withContext(NonCancellable) {
            lock.withLock {
                val current = engine ?: return@withLock
                if (loaded !== lease || !owns()) return@withLock
                if (forgotten(lease)) {
                    // Nothing the state remembers of it stays either: the view loads the content afresh.
                    state.forgetPage()
                    cover = null
                    handBack(current)
                    return@withLock
                }
                val content = state.content
                val config = configFor(content)
                val switched = lease.generation != state.loadGeneration || lease.content != content
                val local = lease.content.localOrigin != null || content.localOrigin != null
                if (switched && !local && config == lease.config && current.isUsable) {
                    if (!startDocument(current, config)) crash(current)
                } else if (switched) {
                    try {
                        rememberOldPage(current, lease)
                    } finally {
                        handBack(current, keepParkedBridge = true)
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
        // Another theme or other settings: the page may look different, and its snapshot no longer shows it.
        snapshotFresh = false
        allowedOrigins = setup.bridgeOrigins
        loaded = Loaded(lease.content, lease.generation, lease.config, setup, lease.epoch, lease.forgetMark)
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
     * places: destroyed if it stopped answering, recycled otherwise. With [keepParkedBridge], a bridge waiting for the
     * new content's document ([WebViewState.load]) keeps waiting, for the engine the view leases next.
     */
    private suspend fun handBack(engine: WebEngine, keepParkedBridge: Boolean = false) {
        val healthy = !stuck
        unbind(keepParkedBridge)
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
     * engine and page state are still saved, in bounded time, then the state is let go. A page that is still live and
     * healthy is parked for the next view of it ([park]); otherwise the engine goes back.
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
        // Registered now, before a new view of the state or page can look: it waits for this save.
        val save = runtime.saves.start(state, loaded?.page ?: pageNow())
        val job = runtime.scope.launch {
            lock.withLock {
                if (engine !== current) return@withLock
                val lease = loaded
                try {
                    lease?.let { capture(current, it, snapshot = false) }
                } finally {
                    if (lease != null && parkable(current, lease)) park(current, lease) else handBack(current)
                }
            }
        }
        job.invokeOnCompletion {
            runtime.saves.finish(save)
            releaseState()
        }
    }

    /**
     * Whether the page [lease] shows may stay live for the next view of it: the engine is still this view's and healthy,
     * and shows what the state shows, without an error, a page the runtime hasn't forgotten ([forgotten]).
     */
    private fun parkable(current: WebEngine, lease: Loaded): Boolean =
        engine === current && loaded === lease && !stuck && owns() && shows(lease) && state.error == null &&
            !forgotten(lease) && usable(current)

    /**
     * The view left the composition with its page live: the engine goes to the pool parked, unreset, for the next view
     * of the page to adopt ([EnginePool.park]). It is unbound and told it is hidden first, and parked only once the
     * composition has let go of its surface, so it is never composed in two places. One that throws is destroyed. One
     * the runtime forgot meanwhile ([forgotten]) goes back unsaved instead: right before it would be parked, as nothing
     * else gives it back once it is.
     */
    private suspend fun park(current: WebEngine, lease: Loaded) {
        val page = ParkedView(
            key = lease.page,
            setup = lease.setup,
            epoch = lease.epoch,
            forgetMark = lease.forgetMark,
            state = state,
            generation = lease.generation,
            pageSavesState = state.pageSavesState,
            runtime = runtime,
        )
        unbind()
        if (owns()) state.phase = WebViewPhase.Cold
        withContext(NonCancellable) {
            val hidden = safely { current.setVisible(false) }
            awaitSurfaceGone(current)
            when {
                !hidden -> runtime.pool.discard(this@WebViewHost)
                forgotten(lease) -> runtime.pool.release(this@WebViewHost)
                else -> runtime.pool.park(this@WebViewHost, page)
            }
        }
    }

    /**
     * A disposed view lets its state go: a view kept waiting for it can take it now. It has no engine, so the state's
     * bridge has none either, even one parked for a content switch the view never finished.
     */
    private fun releaseState() {
        if (!owns()) return
        state.bridge.disconnect()
        state.owner = null
        state.onNeedsEvaluation?.invoke()
    }

    /**
     * Forgets the bound engine: nothing reaches it from this view after this, and the state no longer leads to it
     * ([WebViewState.engine] is the owner's bound engine). The pool decides what becomes of it. The state's bridge
     * ends with it, unless [keepParkedBridge] and the bridge waits for the new content's document.
     */
    private fun unbind(keepParkedBridge: Boolean = false) {
        val current = engine ?: return
        eventsJob?.cancel()
        eventsJob = null
        // Cancels the engine calls still running, too: none of them touches the engine once it is gone.
        leaseScope?.cancel()
        leaseScope = null
        if (owns() && !(keepParkedBridge && state.bridge.isParked)) state.bridge.disconnect()
        safely { current.client = null }
        engine = null
        painted = false
        loaded = null
        pendingRestore = null
        announceOnCommit = false
        snapshotDueAt = null
        snapshotFresh = false
        engineVisible = null
        cutoutsWanted = false
    }

    /**
     * Saves the page [lease] shows into [state] and the runtime's page memory, keyed by the page the lease loaded (its
     * content in its profile): the page's own state first, then its snapshot (if [snapshot]) and its engine state.
     * - The page's state comes first: it needs the state's bridge, which only the owner may use, and a new view of the
     *   state or page may be waiting for it. It is written, and remembered, as soon as it comes.
     * - Each engine call is bounded. What the engine has no answer for keeps its last good value: a snapshot before the
     *   first paint, say.
     * - Restored page state that never reached a document is kept unless the page has new state of its own.
     * - Nothing is written once this view lost the state, or the state moved on to other content; nor at all for a page
     *   the runtime forgot since it started ([forgotten]): the last user's, or content that is gone.
     */
    private suspend fun capture(engine: WebEngine, lease: Loaded, snapshot: Boolean) {
        if (forgotten(lease)) return
        val save = runtime.saves.start(state, lease.page)
        try {
            if (owns() && shows(lease) && state.pageSavesState) {
                val answer = guarded {
                    state.bridge.callForState("\$saveState", timeoutMillis = PAGE_STATE_TIMEOUT_MILLIS)
                }
                if (answer != null && owns() && shows(lease) && !forgotten(lease)) {
                    keepPageState(answer)
                    runtime.pages.remember(lease.page, state.memory())
                }
            }
            // A snapshot that still shows the page as it is, and that the cache still holds, stays: one PixelCopy per page
            // state, not one per departure.
            val fresh = snapshotFresh && state.snapshotKey?.let { it in runtime.snapshots } == true
            val image = if (snapshot && !fresh) snapshotOf(engine, lease) else null
            // Encoded off the main thread; the previous snapshot stays until this one is stored.
            val stored = image?.let { encode(it, lease) }
            val engineState = engineCall { engine.saveState() }
            if (!owns() || !shows(lease) || forgotten(lease)) return
            if (image != null && stored != null) keepSnapshot(image, stored, lease)
            if (engineState != null) state.savedEngineState = engineState
            runtime.pages.remember(lease.page, state.memory())
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
     * The page a switch to new content on another engine leaves, remembered under its own page: its snapshot and engine
     * state, taken while the engine is still bound, over what was remembered for it before. Never into the state, which
     * shows other content now; and not at all for a page the runtime forgot ([forgotten]).
     */
    private suspend fun rememberOldPage(engine: WebEngine, lease: Loaded) {
        if (forgotten(lease)) return
        val page = lease.page
        val save = runtime.saves.start(state, page)
        try {
            val image = snapshotOf(engine, lease)
            val stored = image?.let { encode(it, lease) }
            val engineState = engineCall { engine.saveState() }
            if (forgotten(lease)) return
            val previous = runtime.pages.recall(page)
            var key = previous?.snapshotKey
            if (image != null && stored != null) {
                val fresh = SnapshotKey(page.contentKey, page.profile, image.width, image.height, lease.setup.dark, density)
                runtime.snapshots.put(fresh, stored, image)
                if (key != null && key != fresh) runtime.snapshots.remove(key)
                key = fresh
            }
            if (key == null && engineState == null && previous == null) return
            runtime.pages.remember(
                page,
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
        if (forgotten(lease)) return
        val image = snapshotOf(expected, lease) ?: return
        val stored = encode(image, lease) ?: return
        if (engine !== expected || loaded !== lease || !owns() || !shows(lease) || forgotten(lease)) return
        keepSnapshot(image, stored, lease)
    }

    /**
     * A snapshot of the page [lease] shows in [engine], as the runtime keeps it ([keptSnapshot]): the one place every
     * engine's snapshot comes through, at the screen's own density up to 3 pixels a dp of the view, in full colour.
     * Null if the engine has none now, or failed.
     */
    private suspend fun snapshotOf(engine: WebEngine, lease: Loaded): ImageBitmap? {
        // An engine Compose draws over isn't told its cut-outs: it hears, for this snapshot, what is over the page.
        if (!cutoutsWanted && !safely { engine.overlaysAtSnapshot(cutoutsNow()) }) {
            onCrashed(engine)
            return null
        }
        val image = engineCall { engine.snapshot() } ?: return null
        val viewWidthDp = if (density > 0f) lastSize.width / density else 0f
        return keptSnapshot(image, viewWidthDp)
    }

    /**
     * [image] as the cache stores it, encoded off the main thread: losslessly only for a page with a transparent
     * background, which shows what is under the view through it. Null if it can't be encoded.
     */
    private suspend fun encode(image: ImageBitmap, lease: Loaded): ByteArray? =
        runtime.snapshots.codec.encode(image, opaque = !lease.setup.transparentBackground)

    /** Whether [state] still shows what [lease] loaded. */
    private fun shows(lease: Loaded): Boolean =
        state.loadGeneration == lease.generation && state.content == lease.content

    /**
     * Caches [image], encoded as [stored], as [lease]'s snapshot in place of the view's previous one, and makes it the
     * cover. The image itself stays ready to draw, as the cache's decoded copy.
     */
    private fun keepSnapshot(image: ImageBitmap, stored: ByteArray, lease: Loaded) {
        val key = SnapshotKey(lease.content.key, lease.config.profile, image.width, image.height, lease.setup.dark, density)
        runtime.snapshots.put(key, stored, image)
        val previous = state.snapshotKey
        if (previous != null && previous != key) runtime.snapshots.remove(previous)
        state.snapshotKey = key
        snapshotFresh = true
        snapshotViewSize = lastSize
        // Only a view on screen holds the pixels; a hidden one asks the cache for them again when shown.
        cover = if (visible) Cover(image, key, lease.epoch) else null
    }

    /**
     * Whether [key] is a snapshot of the content shown, in the profile it is shown in, that the state remembers from
     * since the runtime's last clear: one that may cover the page.
     */
    private fun coverable(key: SnapshotKey): Boolean =
        key.contentKey == state.content.key && key.profile == profileNow() && state.memoryEpoch == runtime.memoryEpoch

    /**
     * The cover for [key], from the cache, if it may cover the page ([coverable]): the one the view holds already, or
     * the cache's decoded copy. Null while the cache decodes it: [coverDecoded] makes it the cover once it has, and the
     * view draws it in the next frame.
     */
    private fun coverFor(key: SnapshotKey?): Cover? {
        if (key == null || !coverable(key)) return null
        val epoch = runtime.memoryEpoch
        val held = cover
        if (held != null && held.key == key && held.epoch == epoch) return held.takeIf { runtime.snapshots.touch(key) }
        return runtime.snapshots.request(key, coverDecoded)?.let { Cover(it, key, epoch) }
    }

    /** A decode the view waited for finished: [image] is its cover if the view, on screen, still shows that page. */
    private val coverDecoded: (SnapshotKey, ImageBitmap) -> Unit = { key, image ->
        if (visible && !disposed && key == state.snapshotKey && coverable(key) && cover?.key != key) {
            cover = Cover(image, key, runtime.memoryEpoch)
        }
    }

    /**
     * What the WebView draws over the page now, in [dark]: the [cover], while no engine has painted, as long as it is a
     * snapshot of the content shown, in the profile and theme shown, and from since the runtime's last clear. Another
     * page's snapshot, another user's, or a light one in a dark app, would be a wrong frame. The WebView composes no
     * placeholder under it.
     */
    fun coverToDraw(dark: Boolean): Cover? {
        if (painted && engine != null && composedPaint === engine) return null
        val cover = cover ?: return null
        if (cover.image.width <= 0 || cover.image.height <= 0) return null
        val key = cover.key
        if (key.contentKey != state.content.key || key.dark != dark || key.profile != profileNow()) return null
        return cover.takeIf { it.epoch == runtime.memoryEpoch }
    }

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

/**
 * A page a view left live in the pool when it left the composition ([WebViewHost.park]): what the view that adopts it
 * needs, and the save the pool asks for when it reclaims the engine instead.
 * @property setup what the engine was last configured with: a view that adopts it with another configures it again.
 * @property epoch the runtime's [WebViewRuntime.memoryEpoch] when the page started: it is adopted, or saves, only then.
 * @property forgetMark the runtime's [WebViewRuntime.forgets] since when its content wasn't forgotten ([forgotten]).
 * @property state the state it was shown in, with the load it showed ([generation]): that state adopts it only if
 *   nothing was loaded into it since.
 * @property pageSavesState whether the page announced a save provider: only then is it asked for its state.
 */
private class ParkedView(
    override val key: PageKey,
    val setup: PageSetup,
    val epoch: Int,
    val forgetMark: Int,
    val state: WebViewState,
    val generation: Int,
    val pageSavesState: Boolean,
    private val runtime: WebViewRuntime,
) : ParkedPage {
    /** Whether the runtime forgot the page since it started: a clear, or a forget of its content. */
    fun forgotten(): Boolean = epoch != runtime.memoryEpoch || runtime.forgottenSince(forgetMark, key.contentKey)

    /**
     * The pool takes the engine back: what the page has now goes into page memory, over what its view saved when it
     * left. Its own state first, if it has a save provider, then the engine's; each bounded, and nothing for a page the
     * runtime forgot ([forgotten]), even meanwhile. Only page memory: the state belongs to whichever view shows it now. A
     * view of the page that activates meanwhile waits for this save, as it does for a view's.
     */
    override suspend fun onReclaim(engine: WebEngine) {
        if (forgotten()) return
        val save = runtime.saves.start(state, key)
        try {
            val pageState = if (pageSavesState) askPageState(engine) else null
            // Outside this call's job, as a view's engine calls are: one that ignores cancellation can't hold it open.
            val call = runtime.scope.async(start = CoroutineStart.UNDISPATCHED) { guarded { engine.saveState() } }
            val stuck = !awaitWithin(ENGINE_CALL_MILLIS, call)
            if (stuck) call.cancel()
            val engineState = if (stuck || call.isCancelled) null else call.await()
            if (!forgotten() && (pageState != null || engineState != null)) {
                val previous = runtime.pages.recall(key)
                runtime.pages.remember(
                    key,
                    PageMemory(
                        snapshotKey = previous?.snapshotKey,
                        contentSize = previous?.contentSize,
                        engineState = engineState ?: previous?.engineState,
                        pageState = pageState ?: previous?.pageState,
                        pageSavesState = pageSavesState,
                        lastSizeReport = previous?.lastSizeReport,
                        growsWithViewport = previous?.growsWithViewport ?: false,
                    ),
                )
            }
            if (stuck) {
                runtime.report(IllegalStateException("an engine call took longer than $ENGINE_CALL_MILLIS ms"))
                // An engine that stopped answering must go to no one: the pool's bound runs out, and it is released.
                awaitCancellation()
            }
        } finally {
            runtime.saves.finish(save)
        }
    }

    /**
     * Asks the page for its state (`$saveState`) through a bridge of its own, for this one call: connected, subscribed,
     * brought up to date ([WebEngine.replayState]) and asked to announce itself, as an adopting view's would be. Null if
     * the page doesn't answer in time, or keeps nothing. The answer is pinned to the origin of the document that gave it.
     */
    private suspend fun askPageState(engine: WebEngine): SavedPageState? = coroutineScope {
        val bridge = WebBridge(onReservedEvent = { _, _ -> })
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val committed = CompletableDeferred<Unit>()
        try {
            // Connected first, as an adopting view's: what the page did while parked is heard right here, undispatched,
            // and a commit among it tells the connected bridge which origin to ask.
            bridge.connect(engine::postMessage, { it in setup.bridgeOrigins }, scope)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { event ->
                    when (event) {
                        is EngineEvent.UrlChanged -> {
                            bridge.documentCommitted(event.url)
                            committed.complete(Unit)
                        }
                        is EngineEvent.PageMessage -> bridge.receive(event.origin, event.json)
                        else -> Unit
                    }
                }
            }
            try {
                engine.replayState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runtime.report(e)
                return@coroutineScope null
            }
            // The replayed commit tells the bridge which origin to ask; the call then asks it to announce itself.
            if (!awaitWithin(PAGE_STATE_TIMEOUT_MILLIS, committed)) return@coroutineScope null
            val answer = guarded { bridge.callForState("\$saveState", timeoutMillis = PAGE_STATE_TIMEOUT_MILLIS) }
            answer?.result?.takeIf { it !is JsonNull }?.let { SavedPageState(it.toString(), answer.origin) }
        } finally {
            bridge.disconnect()
            scope.cancel()
        }
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
