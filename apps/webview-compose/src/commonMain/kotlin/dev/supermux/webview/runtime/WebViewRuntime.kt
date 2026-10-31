package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.monotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What a [WebViewRuntime] is made with: the pool's budget and the caches' sizes (spec §4), and the engines, the scope
 * and the clock it runs with. [platformDefault] gives this platform's sizes, and leaves the rest to the runtime; the
 * perf lab measures them. Build one with named arguments and change one with [copy]:
 * `RuntimeConfig.platformDefault().copy(maxLive = 6)`. Compare with `==`.
 *
 * A plain class, not a data class, so it can grow without breaking code built against it: a new option comes as a new
 * parameter at the end of the constructor and of [copy], each with a default, and the signatures before it stay,
 * hidden. There are no `componentN` functions: positions mean nothing. The runtime takes this one object, so a new option
 * never changes its constructor.
 *
 * @property maxLive how many engines may be live at once: at least 1.
 * @property maxIdle how many spare engines, fresh or reset, are kept for reuse: 3 by default, as on every platform but
 *   iOS. With fewer spares than a screenful of views, a page whose views give their engines back before the next page
 *   takes them destroys and rebuilds engines.
 * @property snapshotCacheBytes the snapshot cache's budget for snapshots as stored, encoded, in bytes.
 * @property decodedSnapshotBytes the snapshot cache's budget for decoded snapshots, ready to draw, in pixel bytes: 24
 *   MB by default, as on phones and tablets.
 * @property hiddenReleaseMillis how long a page off screen keeps its live engine when no visible view needs it: 10
 *   minutes. That is a hidden view's page, and a page parked by a view that left the composition, counted from when it
 *   was parked, if no view adopts it meanwhile. A hidden view ranks below every visible one, and a parked page below
 *   that, so a view that needs its slot takes it at once, and memory pressure reclaims those pages first.
 * @property pageMemoryEntries how many pages the page memory remembers.
 * @property factory creates the runtime's engines: null, the default, means this platform's
 *   ([platformWebEngineFactory]).
 * @property scope where the pool's own work runs: evictions, resets, creations and memory-pressure windows, and where web
 *   views save their pages and talk to them. Cancelling it stops that work. Null, the default, means a scope of the
 *   runtime's own on `Dispatchers.Main`; on desktop the library brings kotlinx-coroutines-swing for it.
 * @property clock milliseconds on a monotonic clock, for how long views have been hidden or still, and the pool's timing,
 *   how long pages have been parked among it. Null, the default, means the platform's monotonic clock; a test passes its
 *   own (`{ mainClock.currentTime }`).
 */
public class RuntimeConfig(
    public val maxLive: Int,
    public val maxIdle: Int = 3,
    public val snapshotCacheBytes: Long,
    public val decodedSnapshotBytes: Long = 24L shl 20,
    public val hiddenReleaseMillis: Long = 600_000,
    public val pageMemoryEntries: Int = 512,
    public val factory: WebEngineFactory? = null,
    public val scope: CoroutineScope? = null,
    public val clock: (() -> Long)? = null,
) {
    init {
        require(maxLive >= 1) { "maxLive must be at least 1, not $maxLive" }
        require(
            maxIdle >= 0 && snapshotCacheBytes >= 0 && decodedSnapshotBytes >= 0 && hiddenReleaseMillis >= 0 &&
                pageMemoryEntries >= 0,
        ) {
            "Sizes and times can't be negative: $this"
        }
    }

    /** This config, with the options named changed. */
    public fun copy(
        maxLive: Int = this.maxLive,
        maxIdle: Int = this.maxIdle,
        snapshotCacheBytes: Long = this.snapshotCacheBytes,
        decodedSnapshotBytes: Long = this.decodedSnapshotBytes,
        hiddenReleaseMillis: Long = this.hiddenReleaseMillis,
        pageMemoryEntries: Int = this.pageMemoryEntries,
        factory: WebEngineFactory? = this.factory,
        scope: CoroutineScope? = this.scope,
        clock: (() -> Long)? = this.clock,
    ): RuntimeConfig = RuntimeConfig(
        maxLive = maxLive,
        maxIdle = maxIdle,
        snapshotCacheBytes = snapshotCacheBytes,
        decodedSnapshotBytes = decodedSnapshotBytes,
        hiddenReleaseMillis = hiddenReleaseMillis,
        pageMemoryEntries = pageMemoryEntries,
        factory = factory,
        scope = scope,
        clock = clock,
    )

    override fun equals(other: Any?): Boolean = this === other || other is RuntimeConfig &&
        maxLive == other.maxLive &&
        maxIdle == other.maxIdle &&
        snapshotCacheBytes == other.snapshotCacheBytes &&
        decodedSnapshotBytes == other.decodedSnapshotBytes &&
        hiddenReleaseMillis == other.hiddenReleaseMillis &&
        pageMemoryEntries == other.pageMemoryEntries &&
        factory == other.factory &&
        scope == other.scope &&
        clock == other.clock

    override fun hashCode(): Int {
        var result = maxLive
        result = 31 * result + maxIdle
        result = 31 * result + snapshotCacheBytes.hashCode()
        result = 31 * result + decodedSnapshotBytes.hashCode()
        result = 31 * result + hiddenReleaseMillis.hashCode()
        result = 31 * result + pageMemoryEntries
        result = 31 * result + (factory?.hashCode() ?: 0)
        result = 31 * result + (scope?.hashCode() ?: 0)
        result = 31 * result + (clock?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RuntimeConfig(maxLive=$maxLive, maxIdle=$maxIdle, snapshotCacheBytes=$snapshotCacheBytes, " +
            "decodedSnapshotBytes=$decodedSnapshotBytes, hiddenReleaseMillis=$hiddenReleaseMillis, " +
            "pageMemoryEntries=$pageMemoryEntries, factory=$factory, scope=$scope, clock=$clock)"

    public companion object {
        /**
         * This platform's sizes: 4 live engines on phones, 6 on tablets (Android's smallest width of 600 dp or more,
         * iPads), 8 on desktop and 12 iframes on the web, with 3 spares (2 on iOS, where each idle WKWebView may keep its
         * own WebContent process). Snapshots, stored: 32 MB on phones and tablets, 128 MB on desktop, 64 MB on the web;
         * decoded, ready to draw: 24 MB, and 64 MB on desktop.
         */
        public fun platformDefault(): RuntimeConfig = platformRuntimeConfig()
    }
}

/**
 * What all web views in one app share (spec §4, §6): the engine pool, the snapshot cache, page memory and the overlay
 * registry. One runtime serves the whole app. [Default] is used wherever [ProvideWebViewRuntime] provides none.
 *
 * [overlays] serves a single-window app. An app with several windows gives each window its own registry with
 * [ProvideWebViewOverlays], so an overlay in one window never cuts holes in another window's pages.
 *
 * Main thread only: the thread of [scope]'s dispatcher.
 *
 * @param config what the runtime is made with: the pool's budget, the cache sizes, how long hidden views and parked
 *   pages keep their engines, and the engines, the scope and the clock it runs with, each the platform's unless the
 *   config names one. One object, so an option added to it later changes no constructor here.
 */
public class WebViewRuntime(public val config: RuntimeConfig = RuntimeConfig.platformDefault()) {
    /** Creates the runtime's engines: [RuntimeConfig.factory], or this platform's ([platformWebEngineFactory]). */
    public val factory: WebEngineFactory = config.factory ?: platformEngineFactory()

    /** Where the runtime's work runs: [RuntimeConfig.scope], or a scope of its own on `Dispatchers.Main`. */
    public val scope: CoroutineScope = config.scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The runtime's monotonic clock, in milliseconds: [RuntimeConfig.clock], or the platform's. */
    public val clock: () -> Long = config.clock ?: monotonicClock()

    public val pool: EnginePool = EnginePool(
        factory,
        config.maxLive,
        config.maxIdle,
        scope = scope,
        clock = clock,
        parkedReleaseMillis = config.hiddenReleaseMillis,
    )
    public val snapshots: SnapshotCache = SnapshotCache(config.snapshotCacheBytes, config.decodedSnapshotBytes, scope)
    public val pages: PageMemoryStore = PageMemoryStore(config.pageMemoryEntries)
    public val overlays: OverlayRegistry = OverlayRegistry()

    /**
     * Sees what the runtime's guards catch, for logs: engines that throw or stop answering (their views treat them as
     * crashed), and what the pool's guards catch. Nothing an engine throws reaches the app otherwise.
     */
    public var onError: ((Throwable) -> Unit)? = null

    /**
     * The bytes of the snapshots that web views on screen hold now as their covers, the image a view shows until its
     * page paints. Only a view on screen holds one (off screen it keeps the key, and asks the cache again when shown),
     * so this stays within about a screenful. A cover is usually the snapshot cache's own decoded image, counted in
     * [SnapshotCache.decodedBytes] too; it outlives the cache's copy only while its view stays on screen.
     */
    public var coverBytes: Long = 0
        private set

    /** How many web views on screen hold a cover now ([coverBytes]). */
    public var coverCount: Int = 0
        private set

    /** The view the user touched last; it outranks every other visible view. */
    internal var focused: Any? = null

    /** Pages being saved now, which a new view of the same state or page waits for. */
    internal val saves = PendingSaves()

    /** The web views composed now, live or not: a [clear] reaches every one of them. */
    internal val views: MutableSet<PageHolder> = LinkedHashSet()

    /**
     * How many times [clear] ran. Snapshot state, so covers drawn from before a clear are hidden at once. A page started
     * before a clear saves nothing after it, and what a state remembers from before one isn't used.
     */
    internal var memoryEpoch: Int by mutableIntStateOf(0)
        private set

    /** How many times [forget] ran: a page records it as it starts, to tell later whether it was forgotten since. */
    internal var forgets: Int = 0
        private set

    /** The content keys of the latest [forget]s, the latest last: [FORGETS_KEPT] at most. */
    private val forgotten = ArrayDeque<String>()

    init {
        pool.onError = ::report
        // The pool's memory-pressure window ended: on onMemoryRecovered(), or after its cooldown.
        pool.onPressureEnded = { snapshots.onPressureEnded() }
    }

    /** A view's cover went from one of [before] bytes to one of [after] bytes; null is none. */
    internal fun coverChanged(before: Long?, after: Long?) {
        if (before != null) {
            coverBytes -= before
            coverCount--
        }
        if (after != null) {
            coverBytes += after
            coverCount++
        }
    }

    internal fun report(error: Throwable) {
        try {
            onError?.invoke(error)
        } catch (ignored: Throwable) {
            // An error hook that fails has nowhere left to report to.
        }
    }

    /**
     * Memory is short: call this when the OS says so (Android's `onTrimMemory` at its running levels or `onLowMemory`,
     * an iOS memory warning, the macOS memory-pressure source); a cached process's light trims are [onBackgroundTrim].
     * The snapshot cache lets go of every decoded snapshot first, and gives back half of what
     * it stores; the pool releases the spare engines and evicts live ones down to half the budget, never the focused
     * view's.
     *
     * A window follows, until [onMemoryRecovered] or a cooldown after the latest call ([EnginePool.onMemoryPressure]).
     * Meanwhile the pool stays at half its budget and keeps no spares, and the snapshot cache keeps nothing decoded and
     * stores within the half it kept, although the evicted views snapshot their pages on the way out and other views
     * keep snapshotting. Views on screen still get their covers decoded, and hold them themselves.
     *
     * When this returns, the budget is halved and the spares are gone, so an [onMemoryRecovered] right after it ends
     * the window. The evicted views may still be saving themselves.
     */
    public fun onMemoryPressure() {
        snapshots.onMemoryPressure()
        // Undispatched: on main the pool's lock is always free, so the window opens before this returns, never after a
        // later onMemoryRecovered().
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.onMemoryPressure() }
    }

    /**
     * The app's process may be cached, and memory isn't short yet: call this for the OS's light trims (Android's
     * `onTrimMemory` at TRIM_MEMORY_BACKGROUND and the levels past it, which come at every app switch); on Android the
     * library does, while any of the runtime's web views is shown. What is quickest to make again goes, and nothing the
     * user comes back to: the snapshot cache lets go of its decoded snapshots (what it stores decodes again, so every
     * cover comes back), and the pool of its spares and parked pages ([EnginePool.trim]: each page saves itself first,
     * and a view of it restores it). Live views keep their pages, hidden ones too, and no window follows: unlike
     * [onMemoryPressure], the budget stays whole and spares are kept again from the next engine given back.
     */
    public fun onBackgroundTrim() {
        snapshots.trimDecodedTo(0)
        // Undispatched, as onMemoryPressure: on main the pool's lock is always free, so the spares are gone on return.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.trim() }
    }

    /**
     * Memory is fine again: the pool goes back to its full budget and keeps spares again, and the cache to its budgets.
     */
    public fun onMemoryRecovered() {
        pool.onMemoryRecovered()
    }

    /**
     * Forgets every page: all page memory and all snapshots. Call it when the user logs out.
     *
     * Pages are kept per profile and content. Two users who share a profile (a login by cookie in
     * [dev.supermux.webview.WebProfile.Default]) share those keys, and a plugin's [dev.supermux.webview.WebContent.Bundle]
     * has the same key for every user: without a clear, the next user's views would show the last one's snapshots and
     * get their drafts back. After it:
     * - no view shows a snapshot taken before it, not even one on screen now;
     * - a [dev.supermux.webview.WebViewState] a view shows without a live page drops the last user's page at once: what
     *   it showed (its URL, title, error and history) and what it remembered. One no view shows drops it as a view is
     *   made for it, before that view's first frame, so states the app still holds can't carry it over either; until
     *   then they still hold it, for whatever else the app shows of them;
     * - pages parked live for the next view of them ([EnginePool.park]) are given back unsaved: no view adopts one;
     * - pages live in a view, on screen or hidden, are given back unsaved too, their engines reset: a view on screen
     *   loads its content afresh, and a hidden one once it shows again.
     *
     * Engines keep their profile's cookies and storage: give each user a [dev.supermux.webview.WebProfile.Named] of
     * their own, or clear that data through the platform.
     */
    public fun clear() {
        pages.clear()
        snapshots.clear()
        memoryEpoch++
        // Undispatched: on main the pool's lock is always free, so no view adopts a parked page once this returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.unpark { true } }
        pagesForgotten(everyView = true)
    }

    /**
     * Tells the views that hold an engine that pages were forgotten: each looks whether its page is one of them. With
     * [everyView], every composed view too, with an engine or not: after a clear, none shows the last user's page.
     */
    private fun pagesForgotten(everyView: Boolean = false) {
        val told = HashSet<PageHolder>()
        for (holder in pool.leaseHolders) (holder as? PageHolder)?.let { if (told.add(it)) it.onPagesForgotten() }
        if (everyView) for (view in views.toList()) if (told.add(view)) view.onPagesForgotten()
    }

    /**
     * Forgets [content]'s pages, in every profile: their page memory, their snapshots, the pages parked live for the next
     * view of them, and those live in a view, which are given back unsaved: such a view loads the content afresh. A save
     * of the content under way when it is forgotten keeps nothing. For content that is gone (a deleted message, an
     * uninstalled plugin). States that no view shows now keep what they hold; at logout, use [clear].
     */
    public fun forget(content: WebContent) {
        val key = content.key
        forgets++
        forgotten.addLast(key)
        if (forgotten.size > FORGETS_KEPT) forgotten.removeFirst()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.unpark { it.contentKey == key } }
        pages.forget(key)
        snapshots.forget(key)
        pagesForgotten()
    }

    /**
     * Whether [forget] forgot content [key] since [mark], a count of [forgets]. More forgets since than the runtime keeps
     * count as one: a page that old starts afresh rather than risk being one that is gone.
     */
    internal fun forgottenSince(mark: Int, key: String): Boolean {
        val since = forgets - mark
        if (since <= 0) return false
        if (since > forgotten.size) return true
        for (i in forgotten.size - since until forgotten.size) {
            if (forgotten[i] == key) return true
        }
        return false
    }

    public companion object {
        /** The process-wide runtime, with this platform's engines and sizes, used when nothing is provided. */
        public val Default: WebViewRuntime by lazy { WebViewRuntime() }

        /** How many [forget]s the runtime remembers the keys of ([forgottenSince]). */
        private const val FORGETS_KEPT = 1_024
    }
}

/**
 * A view of a page: told when the runtime forgets pages, if it holds an engine ([WebViewRuntime.clear],
 * [WebViewRuntime.forget]), so that one showing a page it forgot gives its engine back unsaved; and at a clear even
 * without one, so that it shows nothing of the last user's page. Main thread; it must not call the pool.
 */
internal interface PageHolder {
    fun onPagesForgotten()
}

/** The runtime web views and overlays use: [WebViewRuntime.Default] unless [ProvideWebViewRuntime] provides one. */
public val LocalWebViewRuntime: ProvidableCompositionLocal<WebViewRuntime> =
    staticCompositionLocalOf { WebViewRuntime.Default }

/** Provides [runtime] to the web views and overlays in [content]. */
@Composable
public fun ProvideWebViewRuntime(runtime: WebViewRuntime, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWebViewRuntime provides runtime, content = content)
}
