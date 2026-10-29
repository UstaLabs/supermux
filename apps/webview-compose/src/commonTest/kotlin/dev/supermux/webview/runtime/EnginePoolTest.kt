package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnginePoolTest {
    private val config = EngineConfig(WebProfile.Default)
    private val factory = FakeWebEngineFactory()

    private class Holder(override var priority: Int) : LeaseHolder {
        val evicted = mutableListOf<WebEngine>()

        /** While set, [onEvict] waits for it, as a host does while it snapshots and saves its page. */
        var evictGate: CompletableDeferred<Unit>? = null

        /** When true, [onEvict] throws, as a host whose snapshot failed might. */
        var failEvict = false

        override suspend fun onEvict(engine: WebEngine) {
            evicted += engine
            evictGate?.await()
            if (failEvict) error("snapshot failed")
        }
    }

    /** Runs [body], then checks that no engine the pool handed out saw a contract violation. */
    private fun poolTest(body: suspend TestScope.() -> Unit) = runTest {
        body()
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}") }
    }

    private suspend fun EnginePool.engineFor(holder: Holder): FakeWebEngine =
        (acquire(holder, config) as AcquireResult.Granted).engine as FakeWebEngine

    @Test fun grantsEnginesUpToTheBudget() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 0)
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        assertEquals(2, pool.liveCount)
        assertEquals(2, factory.created.size)
    }

    @Test fun aHolderThatAlreadyHasAnEngineGetsTheSameOne() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 0)
        val holder = Holder(PRIORITY_VISIBLE)
        assertSame(pool.engineFor(holder), pool.engineFor(holder))
        assertEquals(1, pool.liveCount)
    }

    @Test fun overBudgetAPeerOfEqualPriorityIsDenied() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        assertEquals(AcquireResult.Denied, pool.acquire(Holder(PRIORITY_VISIBLE), config))
    }

    @Test fun overBudgetTheLowestPriorityLeaseIsEvictedAndItsEngineReused() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN)
        val visible = Holder(PRIORITY_VISIBLE)
        val hiddensEngine = pool.engineFor(hidden)
        pool.engineFor(visible)
        val focused = pool.engineFor(Holder(PRIORITY_FOCUSED))
        assertEquals(listOf<WebEngine>(hiddensEngine), hidden.evicted)
        assertTrue(visible.evicted.isEmpty())
        assertSame(hiddensEngine, focused)
        assertTrue("reset" in hiddensEngine.calls)
    }

    @Test fun amongEqualPrioritiesTheLeastRecentlyUsedIsEvicted() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val b = Holder(PRIORITY_VISIBLE)
        pool.engineFor(a)
        pool.engineFor(b)
        pool.engineFor(a)
        pool.engineFor(Holder(PRIORITY_FOCUSED))
        assertEquals(1, b.evicted.size)
        assertTrue(a.evicted.isEmpty())
    }

    @Test fun releasingRecyclesTheEngineIntoTheIdlePool() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.release(a)
        assertEquals(listOf("reset"), engine.calls)
        assertEquals(1, pool.idleCount)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
        assertEquals(1, factory.created.size)
    }

    @Test fun anIdleEngineIsOnlyReusedForTheSameConfig() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        pool.acquire(Holder(PRIORITY_VISIBLE), EngineConfig(WebProfile.Named("x")))
        assertEquals(2, factory.created.size)
    }

    @Test fun releasedEnginesBeyondTheIdleLimitAreDestroyed() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val b = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.acquire(b, config)
        pool.release(a)
        pool.release(b)
        assertEquals(1, pool.idleCount)
        assertEquals(1, factory.created.count { it.released })
    }

    @Test fun anUnavailablePlatformIsReported() = poolTest {
        factory.unavailable = UnavailableReason.NotImplemented
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 0)
        assertEquals(
            AcquireResult.Unavailable(UnavailableReason.NotImplemented, "fake"),
            pool.acquire(Holder(PRIORITY_VISIBLE), config),
        )
        assertEquals(0, pool.liveCount)
    }

    @Test fun prewarmKeepsOneSpareReadyAndActivationUsesIt() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        pool.prewarm(config)
        assertEquals(1, pool.idleCount)
        assertEquals(1, factory.created.size)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        assertEquals(0, pool.idleCount)
        assertEquals(1, factory.created.size)
    }

    @Test fun prewarmDoesNothingWithoutIdleRoom() = poolTest {
        EnginePool(factory, maxLive = 2, maxIdle = 0).prewarm(config)
        assertEquals(0, factory.created.size)
    }

    @Test fun capacityListenersHearAboutFreedCapacity() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        assertEquals(1, heard)
    }

    @Test fun memoryPressureDropsSparesAndEvictsDownToHalfButNeverTheFocusedView() = poolTest {
        val pool = EnginePool(factory, maxLive = 4, maxIdle = 1)
        val focused = Holder(PRIORITY_FOCUSED)
        val visible = Holder(PRIORITY_VISIBLE)
        val hidden1 = Holder(PRIORITY_HIDDEN)
        val hidden2 = Holder(PRIORITY_HIDDEN)
        listOf(focused, visible, hidden1, hidden2).forEach { pool.acquire(it, config) }
        pool.prewarm(config)
        pool.onMemoryPressure()
        assertEquals(0, pool.idleCount)
        assertEquals(2, pool.liveCount)
        assertTrue(focused.evicted.isEmpty() && visible.evicted.isEmpty())
        assertEquals(1, hidden1.evicted.size)
        assertEquals(1, hidden2.evicted.size)
        assertEquals(3, factory.created.count { it.released })
    }

    @Test fun discardDestroysACrashedEngineInsteadOfRecyclingIt() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.discard(a)
        assertTrue(engine.released)
        assertEquals(0, pool.idleCount)
        assertEquals(0, pool.liveCount)
    }

    // SPI v2: creation outside the lock, guarded and cancellation-safe; checked reset; unusable spares skipped.

    @Test fun aFactoryThatThrowsIsUnavailableAndItsSlotIsFreed() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        factory.failCreate = true
        assertEquals(
            AcquireResult.Unavailable(UnavailableReason.CreationFailed, "fake create failed"),
            pool.acquire(Holder(PRIORITY_VISIBLE), config),
        )
        assertEquals(0, pool.liveCount)
        factory.failCreate = false
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
    }

    @Test fun anEngineWhoseResetFailsIsReleasedNotKept() = poolTest {
        factory.behavior = FakeEngineBehavior(resetSucceeds = false)
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.release(a)
        assertEquals(listOf("reset", "release"), engine.calls)
        assertEquals(0, pool.idleCount)
        assertEquals(1, heard)
        assertNotSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun aCrashedEngineIsReleasedWithoutAReset() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        engine.emit(EngineEvent.Crashed)
        pool.release(a)
        assertEquals(listOf("release"), engine.calls)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aSpareThatCrashedIsNotHandedOut() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        pool.prewarm(config)
        val spare = factory.created.single()
        spare.emit(EngineEvent.Crashed)
        val engine = pool.engineFor(Holder(PRIORITY_VISIBLE))
        assertNotSame(spare, engine)
        assertTrue(spare.released)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aReleaseCompletesWhileAnotherAcquireIsCreating() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val aEngine = pool.engineFor(a)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val b = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        assertFalse(b.isCompleted)
        val release = async { pool.release(a) }
        runCurrent()
        assertTrue(release.isCompleted)
        assertEquals(1, pool.idleCount)
        gate.complete(Unit)
        assertNotSame<WebEngine>(aEngine, (b.await() as AcquireResult.Granted).engine)
        assertEquals(1, pool.liveCount)
        assertEquals(1, pool.idleCount)
    }

    @Test fun anAcquireCancelledWhileCreatingLeavesItsEngineAsASpare() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val acquiring = launch { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        acquiring.cancel()
        runCurrent()
        gate.complete(Unit)
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        val made = factory.created.single()
        assertFalse(made.released)
        assertEquals(0, pool.liveCount)
        assertEquals(1, pool.idleCount)
        // Its slot was given back, and the engine is used by the next activation.
        assertSame(made, pool.engineFor(Holder(PRIORITY_VISIBLE)))
        assertEquals(1, factory.created.size)
    }

    // Races.

    @Test fun anEvictedSlotBelongsToTheEvicterWhileTheVictimSavesItself() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN)
        val saving = CompletableDeferred<Unit>()
        hidden.evictGate = saving
        val hiddensEngine = pool.engineFor(hidden)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        assertEquals(listOf<WebEngine>(hiddensEngine), hidden.evicted)
        assertFalse(focused.isCompleted)
        assertEquals(AcquireResult.Denied, pool.acquire(Holder(PRIORITY_VISIBLE), config))
        saving.complete(Unit)
        // Handed straight over, so it's reused even with no room for spares.
        assertSame<WebEngine>(hiddensEngine, (focused.await() as AcquireResult.Granted).engine)
        assertEquals(1, factory.created.size)
        assertEquals(1, pool.liveCount)
    }

    @Test fun anAcquireCancelledDuringAnEvictionLeavesTheEngineAsASpare() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN)
        val saving = CompletableDeferred<Unit>()
        hidden.evictGate = saving
        val engine = pool.engineFor(hidden)
        val acquiring = launch { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        acquiring.cancel()
        saving.complete(Unit)
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        assertEquals(0, pool.liveCount)
        assertEquals(1, pool.idleCount)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun aHolderThatFailsToSaveStillGivesItsEngineBack() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN).apply { failEvict = true }
        val engine = pool.engineFor(hidden)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_FOCUSED)))
        assertEquals(1, pool.liveCount)
    }

    @Test fun twoAcquiresForOneHolderShareOneEngineAndLoseNone() = poolTest {
        val pool = EnginePool(factory, maxLive = 2, maxIdle = 1)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val holder = Holder(PRIORITY_VISIBLE)
        val first = async { pool.acquire(holder, config) }
        val second = async { pool.acquire(holder, config) }
        runCurrent()
        gate.complete(Unit)
        val engine = (first.await() as AcquireResult.Granted).engine
        assertSame(engine, (second.await() as AcquireResult.Granted).engine)
        assertEquals(1, pool.liveCount)
        assertEquals(2, factory.created.size)
        assertEquals(1, pool.idleCount)
    }

    @Test fun anAcquireDeniedForAPendingSlotHearsWhenItBecomesALease() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val visible = Holder(PRIORITY_VISIBLE)
        val pending = async { pool.acquire(visible, config) }
        runCurrent()
        val focused = Holder(PRIORITY_FOCUSED)
        assertEquals(AcquireResult.Denied, pool.acquire(focused, config))
        gate.complete(Unit)
        val visiblesEngine = (pending.await() as AcquireResult.Granted).engine
        assertEquals(1, heard)
        assertSame(visiblesEngine, pool.engineFor(focused))
        assertEquals(listOf(visiblesEngine), visible.evicted)
    }

    @Test fun prewarmingDoesNotHoldUpAReleaseAndYieldsToASpareThatAppearedMeanwhile() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val warming = async { pool.prewarm(config) }
        runCurrent()
        val release = async { pool.release(a) }
        runCurrent()
        assertTrue(release.isCompleted)
        gate.complete(Unit)
        warming.await()
        assertEquals(1, pool.idleCount)
        assertTrue(factory.created[1].released)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun concurrentPrewarmsForOneConfigCreateOneSpare() = poolTest {
        val pool = EnginePool(factory, maxLive = 1, maxIdle = 1)
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val first = async { pool.prewarm(config) }
        val second = async { pool.prewarm(config) }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, factory.created.size)
        assertEquals(1, pool.idleCount)
    }
}
