package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * Pool and cache sizes (spec §4). [platformDefault] gives this platform's; the perf lab measures them.
 * @property maxLive how many engines may be live at once: at least 1.
 * @property maxIdle how many spare engines, fresh or reset, are kept for reuse: 3 by default, as on every platform but
 *   iOS. With fewer spares than a screenful of views, a page whose views give their engines back before the next page
 *   takes them destroys and rebuilds engines.
 * @property snapshotCacheBytes the snapshot cache's budget, in pixel bytes.
 * @property hiddenReleaseMillis how long a view off screen keeps its live engine when no visible view needs it: 10
 *   minutes. A hidden view ranks below every visible one, so a view that needs its slot takes it at once, and memory
 *   pressure reclaims hidden pages first.
 * @property pageMemoryEntries how many pages the page memory remembers.
 */
data class RuntimeConfig(
    val maxLive: Int,
    val maxIdle: Int = 3,
    val snapshotCacheBytes: Long,
    val hiddenReleaseMillis: Long = 600_000,
    val pageMemoryEntries: Int = 512,
) {
    init {
        require(maxLive >= 1) { "maxLive must be at least 1, not $maxLive" }
        require(maxIdle >= 0 && snapshotCacheBytes >= 0 && hiddenReleaseMillis >= 0 && pageMemoryEntries >= 0) {
            "Sizes and times can't be negative: $this"
        }
    }

    companion object {
        /**
         * This platform's sizes: 4 live engines on phones, 6 on tablets (Android's smallest width of 600 dp or more,
         * iPads), 8 on desktop and 12 iframes on the web, with 3 spares (2 on iOS, where each idle WKWebView may keep its
         * own WebContent process). Snapshots: 32 MB on phones and tablets, 128 MB on desktop, 64 MB on the web.
         */
        fun platformDefault(): RuntimeConfig = platformRuntimeConfig()
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
 * @param factory creates this platform's engines.
 * @param config the pool's budget, the cache sizes, and how long hidden views keep their engines.
 * @param scope where the pool's own work runs: evictions, resets, creations and memory-pressure windows, and where web
 *   views save their pages and talk to them. Cancelling it stops that work. The default runs on `Dispatchers.Main`;
 *   on desktop the library brings kotlinx-coroutines-swing for it.
 * @param clock milliseconds on a monotonic clock, for how long views have been hidden or still, and the pool's timing.
 */
class WebViewRuntime(
    val factory: WebEngineFactory = platformEngineFactory(),
    val config: RuntimeConfig = RuntimeConfig.platformDefault(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    val clock: () -> Long = monotonicClock(),
) {
    val pool: EnginePool = EnginePool(factory, config.maxLive, config.maxIdle, scope = scope, clock = clock)
    val snapshots: SnapshotCache = SnapshotCache(config.snapshotCacheBytes)
    val pages: PageMemoryStore = PageMemoryStore(config.pageMemoryEntries)
    val overlays: OverlayRegistry = OverlayRegistry()

    /**
     * Sees what the runtime's guards catch, for logs: engines that throw or stop answering (their views treat them as
     * crashed), and what the pool's guards catch. Nothing an engine throws reaches the app otherwise.
     */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * The bytes of the snapshots that web views on screen hold now as their covers, the image a view shows until its
     * page paints. Only a view on screen holds one (off screen it keeps the key, and reads the cache again when shown),
     * so this stays within about a screenful. A cover is usually the snapshot cache's own image, counted in
     * [SnapshotCache.sizeBytes] too; it outlives its cache entry only while its view stays on screen.
     */
    var coverBytes: Long = 0
        private set

    /** How many web views on screen hold a cover now ([coverBytes]). */
    var coverCount: Int = 0
        private set

    /** The view the user touched last; it outranks every other visible view. */
    internal var focused: Any? = null

    /** Pages being saved now, which a new view of the same state or page waits for. */
    internal val saves = PendingSaves()

    /**
     * How many times [clear] ran. Snapshot state, so covers drawn from before a clear are hidden at once. A page started
     * before a clear saves nothing after it, and what a state remembers from before one isn't used.
     */
    internal var memoryEpoch: Int by mutableIntStateOf(0)
        private set

    init {
        pool.onError = ::report
        // The pool's memory-pressure window ended: on onMemoryRecovered(), or after its cooldown.
        pool.onPressureEnded = { snapshots.limitBytes = null }
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
     * Memory is short: call this when the OS says so (Android's `onTrimMemory`, an iOS memory warning, the macOS
     * memory-pressure source). It gives back half of what the snapshot cache holds, releases the spare engines and
     * evicts live ones down to half the budget, never the focused view's.
     *
     * A window follows, until [onMemoryRecovered] or a cooldown after the latest call ([EnginePool.onMemoryPressure]).
     * Meanwhile the pool stays at half its budget and keeps no spares, and the snapshot cache stays within the half it
     * kept, although the evicted views snapshot their pages on the way out and other views keep snapshotting.
     *
     * When this returns, the budget is halved and the spares are gone, so an [onMemoryRecovered] right after it ends
     * the window. The evicted views may still be saving themselves.
     */
    fun onMemoryPressure() {
        // Half of what it holds: half of its budget would free nothing from a cache that is less than half full, and
        // would let it grow back past what it held.
        snapshots.limitBytes = snapshots.sizeBytes / 2
        // Undispatched: on main the pool's lock is always free, so the window opens before this returns, never after a
        // later onMemoryRecovered().
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.onMemoryPressure() }
    }

    /** Memory is fine again: the pool goes back to its full budget and keeps spares again, and so does the cache. */
    fun onMemoryRecovered() {
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
     * - what a [dev.supermux.webview.WebViewState] remembers from before it is dropped the next time a view uses the
     *   state, so states the app still holds can't carry it over either;
     * - pages parked live for the next view of them ([EnginePool.park]) are given back unsaved: no view adopts one;
     * - pages already live keep running, but save nothing more: remove or reload the views of the last user's pages.
     *
     * Engines keep their profile's cookies and storage: give each user a [dev.supermux.webview.WebProfile.Named] of
     * their own, or clear that data through the platform.
     */
    fun clear() {
        pages.clear()
        snapshots.clear()
        memoryEpoch++
        // Undispatched: on main the pool's lock is always free, so no view adopts a parked page once this returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.unpark { true } }
    }

    /**
     * Forgets [content]'s pages, in every profile: their page memory, their snapshots, and the pages parked live for the
     * next view of them, which are given back unsaved. For content that is gone (a deleted message, an uninstalled
     * plugin). Views and states that hold its page keep what they hold; at logout, use [clear].
     */
    fun forget(content: WebContent) {
        val key = content.key
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.unpark { it.contentKey == key } }
        pages.forget(key)
        snapshots.forget(key)
    }

    companion object {
        /** The process-wide runtime, with this platform's engines and sizes, used when nothing is provided. */
        val Default: WebViewRuntime by lazy { WebViewRuntime() }
    }
}

/** The runtime web views and overlays use: [WebViewRuntime.Default] unless [ProvideWebViewRuntime] provides one. */
val LocalWebViewRuntime = staticCompositionLocalOf { WebViewRuntime.Default }

/** Provides [runtime] to the web views and overlays in [content]. */
@Composable
fun ProvideWebViewRuntime(runtime: WebViewRuntime, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWebViewRuntime provides runtime, content = content)
}
