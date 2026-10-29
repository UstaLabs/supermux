package dev.supermux.webview.engine.ios

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS memory warnings reach a runtime while a web view uses it (PlatformHost), through one observer per runtime. */
class MemoryHookTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime = WebViewRuntime(
        factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
        config = RuntimeConfig(maxLive = 4, maxIdle = 0, snapshotCacheBytes = 1L shl 20),
        scope = scope,
    )

    private fun memoryWarning() {
        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, `object` = null)
        pumpUntil(0.2) { false }
        drainMainQueue()
    }

    @AfterTest fun detachAll() {
        while (MemoryHook.observers(runtime) > 0) MemoryHook.detach(runtime)
        scope.cancel()
    }

    @Test fun aMemoryWarningReachesTheRuntimeThroughOneObserverPerRuntime() {
        MemoryHook.attach(runtime)
        MemoryHook.attach(runtime)
        assertEquals(1, MemoryHook.observers(runtime))
        memoryWarning()
        assertEquals(2, runtime.pool.budget, "half the budget during the pressure window")
        MemoryHook.detach(runtime)
        assertEquals(1, MemoryHook.observers(runtime))
        MemoryHook.detach(runtime)
        assertEquals(0, MemoryHook.observers(runtime))
    }

    @Test fun withNoWebViewUsingItTheRuntimeHearsNothing() {
        MemoryHook.attach(runtime)
        MemoryHook.detach(runtime)
        memoryWarning()
        assertEquals(4, runtime.pool.budget)
    }
}
