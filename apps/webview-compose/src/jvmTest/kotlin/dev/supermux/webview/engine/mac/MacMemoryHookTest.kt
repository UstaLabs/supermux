package dev.supermux.webview.engine.mac

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.desktop.MemoryHooks
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** macOS memory pressure reaches the runtimes web views use (spec §4), through one source, started once. */
class MacMemoryHookTest {
    @AfterTest fun quiet() {
        MacPages.onMemoryPressure = {}
    }

    @Test fun pressureHalvesTheBudgetOfEachRuntimeInUse() = runTest {
        var started = 0
        val hooks = MemoryHooks(load = { null }, start = { started++ }, register = { MacPages.onMemoryPressure = it })
        val runtime = WebViewRuntime(
            RuntimeConfig(
                maxLive = 4,
                maxIdle = 0,
                snapshotCacheBytes = 1L shl 20,
                factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
                scope = backgroundScope,
            ),
        )
        hooks.attach(runtime)
        hooks.attach(runtime)
        assertEquals(1, hooks.observers(runtime))
        assertEquals(1, started, "one source for the process")
        MacPages.onMemoryPressure() // what a MEMORY upcall calls
        runCurrent()
        assertEquals(2, runtime.pool.budget, "half the budget during the pressure window")
        hooks.detach(runtime)
        assertEquals(1, hooks.observers(runtime))
        hooks.detach(runtime)
        assertEquals(0, hooks.observers(runtime))
    }

    @Test fun withoutTheGlueNothingIsHooked() = runTest {
        val hooks = MemoryHooks(load = { "no glue here" }, start = { error("never started") }, register = { MacPages.onMemoryPressure = it })
        val runtime = WebViewRuntime(
            RuntimeConfig(
                maxLive = 4,
                maxIdle = 0,
                snapshotCacheBytes = 1L shl 20,
                factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
                scope = backgroundScope,
            ),
        )
        hooks.attach(runtime)
        assertEquals(0, hooks.observers(runtime))
    }
}
