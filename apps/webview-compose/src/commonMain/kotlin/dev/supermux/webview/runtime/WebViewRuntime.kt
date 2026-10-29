package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
 * @property maxIdle how many spare engines, fresh or reset, are kept for reuse.
 * @property snapshotCacheBytes the snapshot cache's budget, in pixel bytes.
 * @property graceMillis how long an off-screen embed keeps its engine.
 * @property hiddenReleaseMillis how long a hidden Eager pane keeps its engine.
 * @property pageMemoryEntries how many pages the page memory remembers.
 */
data class RuntimeConfig(
    val maxLive: Int,
    val maxIdle: Int = 1,
    val snapshotCacheBytes: Long,
    val graceMillis: Long = 2_000,
    val hiddenReleaseMillis: Long = 600_000,
    val pageMemoryEntries: Int = 512,
) {
    init {
        require(maxLive >= 1) { "maxLive must be at least 1, not $maxLive" }
        require(
            maxIdle >= 0 && snapshotCacheBytes >= 0 && graceMillis >= 0 && hiddenReleaseMillis >= 0 &&
                pageMemoryEntries >= 0,
        ) { "Sizes and times can't be negative: $this" }
    }

    companion object {
        /**
         * This platform's sizes: 4 live engines on phones, 8 on desktop and 12 iframes on the web, with 3 spares (2 on
         * iOS, where each idle WKWebView may keep its own WebContent process).
         */
        fun platformDefault(): RuntimeConfig = platformRuntimeConfig()
    }
}

/**
 * What all web views in one app share (spec §4, §6): the engine pool, the snapshot cache, page memory and the overlay
 * registry. One runtime serves the whole app. [Default] is used wherever [ProvideWebViewRuntime] provides none.
 *
 * Main thread only: the thread of [scope]'s dispatcher.
 *
 * @param factory creates this platform's engines.
 * @param config the pool's budget, the cache sizes and the grace periods.
 * @param scope where the pool's own work runs: evictions, resets, creations and memory-pressure windows. Cancelling it
 *   stops that work. The default runs on `Dispatchers.Main`, which a desktop app gets from kotlinx-coroutines-swing.
 * @param clock milliseconds on a monotonic clock, for grace periods and the pool's timing.
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

    /** The view the user touched last; it outranks every other visible view. */
    internal var focused: Any? = null

    /**
     * Memory is short: call this when the OS says so (Android's `onTrimMemory`, an iOS memory warning, the macOS
     * memory-pressure source). It halves the snapshot cache, releases the spare engines and evicts live ones down to
     * half the budget, never the focused view's. The pool stays at half until [onMemoryRecovered], or until a cooldown
     * after the latest call ([EnginePool.onMemoryPressure]).
     *
     * When this returns, the budget is halved and the spares are gone, so an [onMemoryRecovered] right after it ends
     * the window. The evicted views may still be saving themselves.
     */
    fun onMemoryPressure() {
        snapshots.trimTo(snapshots.maxBytes / 2)
        // Undispatched: on main the pool's lock is always free, so the window opens before this returns, never after a
        // later onMemoryRecovered().
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.onMemoryPressure() }
    }

    /** Memory is fine again: the pool goes back to its full budget, and keeps spares again. */
    fun onMemoryRecovered() {
        pool.onMemoryRecovered()
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
