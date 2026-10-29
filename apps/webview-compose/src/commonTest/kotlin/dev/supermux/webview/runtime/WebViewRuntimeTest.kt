package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmapConfig
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebViewRuntimeTest {
    private val factory = FakeWebEngineFactory()
    private val engineConfig = EngineConfig(WebProfile.Default)

    private class Holder : LeaseHolder {
        override val priority: Int get() = PRIORITY_VISIBLE

        override suspend fun onEvict(engine: WebEngine) = Unit
    }

    /** A runtime whose pool works in the test's background scope, on [clock] (the test's virtual time by default). */
    private fun TestScope.runtime(
        config: RuntimeConfig,
        clock: () -> Long = { testScheduler.currentTime },
    ) = WebViewRuntime(factory, config, scope = backgroundScope, clock = clock)

    private suspend fun WebViewRuntime.lease(holder: LeaseHolder = Holder()): AcquireResult = pool.acquire(holder, engineConfig)

    @Test fun thePoolTheSnapshotsAndThePageMemoryAreSizedByTheConfig() = runTest {
        val runtime = runtime(RuntimeConfig(maxLive = 2, maxIdle = 1, snapshotCacheBytes = 4_000, pageMemoryEntries = 2))
        val a = Holder()
        val b = Holder()
        assertIs<AcquireResult.Granted>(runtime.lease(a))
        assertIs<AcquireResult.Granted>(runtime.lease(b))
        assertEquals(AcquireResult.Denied, runtime.lease())
        runtime.pool.release(a)
        runtime.pool.release(b)
        assertEquals(1, runtime.pool.idleCount)
        assertEquals(4_000, runtime.snapshots.maxBytes)
        repeat(3) { runtime.pages.remember("page$it", PageMemory(null, null, null, null, false, null, false)) }
        assertNull(runtime.pages.recall("page0"))
        assertNotNull(runtime.pages.recall("page2"))
    }

    @Test fun thePoolTellsTimeByTheRuntimesClock() = runTest {
        var now = 0L
        val runtime = runtime(RuntimeConfig(maxLive = 2, maxIdle = 1, snapshotCacheBytes = 0), clock = { now })
        val holder = Holder()
        runtime.lease(holder)
        runtime.pool.release(holder)
        // The engine given back is the spare, and the next lease takes it.
        runtime.lease(holder)
        assertEquals(1, factory.created.size)
        // An engine came back 9 s ago, by the runtime's clock: recycling still supplies the spares.
        now = 9_000
        runtime.pool.prewarm(engineConfig)
        assertEquals(1, factory.created.size)
        now = 10_000
        runtime.pool.prewarm(engineConfig)
        assertEquals(2, factory.created.size)
    }

    @Test fun memoryPressureHalvesTheSnapshotCacheAndThePoolBeforeItReturns() = runTest {
        val runtime = runtime(RuntimeConfig(maxLive = 4, maxIdle = 1, snapshotCacheBytes = 4_000))
        repeat(10) { runtime.snapshots.put(SnapshotKey("page$it", 10, 10, false, 1f), StubImage(10, 10, ImageBitmapConfig.Argb8888)) }
        assertEquals(4_000, runtime.snapshots.sizeBytes)
        repeat(4) { runtime.lease() }
        runtime.onMemoryPressure()
        assertEquals(2_000, runtime.snapshots.sizeBytes)
        assertEquals(2, runtime.pool.liveCount)
        assertEquals(AcquireResult.Denied, runtime.lease())
        runtime.onMemoryRecovered()
    }

    /** A cache a quarter full gives back half of what it holds, not everything below half its budget. */
    @Test fun memoryPressureHalvesWhatTheSnapshotCacheHoldsNotItsBudget() = runTest {
        val runtime = runtime(RuntimeConfig(maxLive = 4, maxIdle = 1, snapshotCacheBytes = 4_000))
        repeat(3) { runtime.snapshots.put(SnapshotKey("page$it", 10, 10, false, 1f), StubImage(10, 10, ImageBitmapConfig.Argb8888)) }
        assertEquals(1_200, runtime.snapshots.sizeBytes)
        runtime.onMemoryPressure()
        assertEquals(400, runtime.snapshots.sizeBytes)
        assertEquals(1, runtime.snapshots.count)
        runtime.onMemoryRecovered()
    }

    @Test fun aConfigKeepsThreeSparesUnlessToldOtherwise() {
        assertEquals(3, RuntimeConfig(maxLive = 4, snapshotCacheBytes = 0).maxIdle)
    }

    /** Spec §4: 4 live engines on phones, 6 on tablets, with the platform's spares and 32 MB of snapshots. */
    @Test fun tabletsKeepSixLiveEnginesAndPhonesFour() {
        assertEquals(RuntimeConfig(maxLive = 6, maxIdle = 3, snapshotCacheBytes = 32L shl 20), mobileRuntimeConfig(tablet = true, maxIdle = 3))
        assertEquals(RuntimeConfig(maxLive = 4, maxIdle = 2, snapshotCacheBytes = 32L shl 20), mobileRuntimeConfig(tablet = false, maxIdle = 2))
    }

    @Test fun recoveryRightAfterPressureEndsIt() = runTest {
        val runtime = runtime(RuntimeConfig(maxLive = 2, maxIdle = 0, snapshotCacheBytes = 0))
        runtime.onMemoryPressure()
        runtime.onMemoryRecovered()
        runCurrent()
        assertIs<AcquireResult.Granted>(runtime.lease())
        assertIs<AcquireResult.Granted>(runtime.lease())
    }

    @Test fun thePressureWindowRunsOutInTheRuntimesScope() = runTest {
        val runtime = runtime(RuntimeConfig(maxLive = 2, maxIdle = 0, snapshotCacheBytes = 0))
        runtime.onMemoryPressure()
        assertIs<AcquireResult.Granted>(runtime.lease())
        val waiting = Holder()
        assertEquals(AcquireResult.Denied, runtime.lease(waiting))
        // The pool's cooldown (30 s) is a timer in the runtime's scope.
        advanceTimeBy(30_000)
        runCurrent()
        assertIs<AcquireResult.Granted>(runtime.lease(waiting))
    }

    @Test fun aConfigRefusesSizesThatCantWork() {
        assertFailsWith<IllegalArgumentException> { RuntimeConfig(maxLive = 0, snapshotCacheBytes = 0) }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig(maxLive = 1, maxIdle = -1, snapshotCacheBytes = 0) }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig(maxLive = 1, snapshotCacheBytes = -1) }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig(maxLive = 1, snapshotCacheBytes = 0, graceMillis = -1) }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig(maxLive = 1, snapshotCacheBytes = 0, hiddenReleaseMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig(maxLive = 1, snapshotCacheBytes = 0, pageMemoryEntries = -1) }
        // The smallest sizes that work: one live engine, no spare, no snapshots, no page memory, no grace.
        RuntimeConfig(maxLive = 1, maxIdle = 0, snapshotCacheBytes = 0, graceMillis = 0, hiddenReleaseMillis = 0, pageMemoryEntries = 0)
    }
}
