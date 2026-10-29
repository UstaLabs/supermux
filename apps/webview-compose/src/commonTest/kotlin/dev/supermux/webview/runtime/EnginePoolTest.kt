package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EnginePoolTest {
    private val config = EngineConfig(WebProfile.Default)
    private val other = EngineConfig(WebProfile.Named("x"))
    private val factory = FakeWebEngineFactory()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val errors = mutableListOf<Throwable>()

    private class Holder(override var priority: Int) : LeaseHolder {
        val evicted = mutableListOf<WebEngine>()

        /** While set, [onEvict] waits for it, as a host does while it snapshots and saves its page. */
        var evictGate: CompletableDeferred<Unit>? = null

        /** When true, [onEvict] throws, as a host whose snapshot failed might. */
        var failEvict = false

        /** When true, the engine's renderer dies while [onEvict] runs. */
        var crashOnEvict = false

        override suspend fun onEvict(engine: WebEngine) {
            evicted += engine
            if (crashOnEvict) (engine as FakeWebEngine).emit(EngineEvent.Crashed)
            evictGate?.await()
            if (failEvict) error("snapshot failed")
        }
    }

    /** A fake that can be made to throw from [reset] or [release], to test the pool's guards. */
    private class Faulty(val fake: FakeWebEngine) : WebEngine by fake {
        var resetThrows = false
        var releaseThrows = false

        override suspend fun reset(): Boolean = if (resetThrows) error("reset failed") else fake.reset()

        override fun release() {
            fake.release()
            if (releaseThrows) error("release failed")
        }

        @Composable
        override fun Surface(modifier: Modifier) = fake.Surface(modifier)
    }

    /** A gate the test holds. [poolTest] opens it whatever happens, so a failing test fails instead of hanging. */
    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates += it }

    private fun pool(
        maxLive: Int,
        maxIdle: Int,
        factory: WebEngineFactory = this.factory,
        pressureCooldownMillis: Long = 30_000,
    ) = EnginePool(factory, maxLive, maxIdle, pressureCooldownMillis = pressureCooldownMillis).also { pool ->
        pool.onError = { errors += it }
    }

    /**
     * Runs [body], opening every gate it held, then checks that the pool reported no error the test didn't take, and
     * kept the engine contract with every engine it handled.
     */
    private fun poolTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 10.seconds) {
        try {
            body()
        } finally {
            gates.forEach { it.complete(Unit) }
        }
        assertEquals(emptyList(), errors.map { it.toString() }, "errors nobody expected")
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}") }
    }

    /** The messages of the errors reported so far, which this takes. */
    private fun takeErrors(): List<String?> = errors.map { it.message }.also { errors.clear() }

    private suspend fun EnginePool.engineFor(
        holder: Holder,
        config: EngineConfig = this@EnginePoolTest.config,
    ): FakeWebEngine = (acquire(holder, config) as AcquireResult.Granted).engine as FakeWebEngine

    /** Every engine created is leased, a spare, or released: none is lost. */
    private fun EnginePool.assertNoneLost() =
        assertEquals(factory.created.count { !it.released }, liveCount + idleCount, "engines lost")

    @Test fun grantsEnginesUpToTheBudget() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0)
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        assertEquals(2, pool.liveCount)
        assertEquals(2, factory.created.size)
    }

    @Test fun aHolderThatAlreadyHasAnEngineGetsTheSameOne() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0)
        val holder = Holder(PRIORITY_VISIBLE)
        assertSame(pool.engineFor(holder), pool.engineFor(holder))
        assertEquals(1, pool.liveCount)
    }

    @Test fun overBudgetAPeerOfEqualPriorityIsDenied() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        assertEquals(AcquireResult.Denied, pool.acquire(Holder(PRIORITY_VISIBLE), config))
    }

    @Test fun overBudgetTheLowestPriorityLeaseIsEvictedAndItsEngineReused() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
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
        val pool = pool(maxLive = 2, maxIdle = 1)
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
        val pool = pool(maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.release(a)
        assertEquals(listOf("reset"), engine.calls)
        assertEquals(1, pool.idleCount)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
        assertEquals(1, factory.created.size)
    }

    @Test fun anIdleEngineIsOnlyReusedForTheSameConfig() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        pool.acquire(Holder(PRIORITY_VISIBLE), other)
        assertEquals(2, factory.created.size)
    }

    @Test fun releasedEnginesBeyondTheIdleLimitAreDestroyed() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
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
        val pool = pool(maxLive = 2, maxIdle = 0)
        assertEquals(
            AcquireResult.Unavailable(UnavailableReason.NotImplemented, "fake"),
            pool.acquire(Holder(PRIORITY_VISIBLE), config),
        )
        assertEquals(0, pool.liveCount)
    }

    @Test fun prewarmKeepsOneSpareReadyAndActivationUsesIt() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        pool.prewarm(config)
        assertEquals(1, pool.idleCount)
        assertEquals(1, factory.created.size)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        assertEquals(0, pool.idleCount)
        assertEquals(1, factory.created.size)
    }

    @Test fun prewarmDoesNothingWithoutIdleRoom() = poolTest {
        pool(maxLive = 2, maxIdle = 0).prewarm(config)
        assertEquals(0, factory.created.size)
    }

    @Test fun capacityListenersHearAboutFreedCapacity() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        assertEquals(1, heard)
    }

    @Test fun memoryPressureDropsSparesAndEvictsDownToHalfButNeverTheFocusedView() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 1)
        // A spare of another profile, which the activations below leave alone.
        pool.prewarm(other)
        val focused = Holder(PRIORITY_FOCUSED)
        val visible = Holder(PRIORITY_VISIBLE)
        val hidden1 = Holder(PRIORITY_HIDDEN)
        val hidden2 = Holder(PRIORITY_HIDDEN)
        listOf(focused, visible, hidden1, hidden2).forEach { pool.acquire(it, config) }
        assertEquals(1, pool.idleCount)
        launch { pool.onMemoryPressure() }
        runCurrent()
        assertEquals(0, pool.idleCount)
        assertEquals(2, pool.liveCount)
        assertTrue(focused.evicted.isEmpty() && visible.evicted.isEmpty())
        assertEquals(1, hidden1.evicted.size)
        assertEquals(1, hidden2.evicted.size)
        assertEquals(3, factory.created.count { it.released })
        pool.onMemoryRecovered()
    }

    @Test fun discardDestroysACrashedEngineInsteadOfRecyclingIt() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.discard(a)
        assertTrue(engine.released)
        assertEquals(0, pool.idleCount)
        assertEquals(0, pool.liveCount)
    }

    // SPI v2: creation outside the lock, guarded and cancellation-safe; checked reset; unusable spares skipped.

    @Test fun aFactoryThatThrowsIsUnavailableAndItsSlotIsFreed() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        factory.failCreate = true
        assertEquals(
            AcquireResult.Unavailable(UnavailableReason.CreationFailed, "fake create failed"),
            pool.acquire(Holder(PRIORITY_VISIBLE), config),
        )
        assertEquals(listOf<String?>("fake create failed"), takeErrors())
        assertEquals(0, pool.liveCount)
        factory.failCreate = false
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
    }

    @Test fun anEngineWhoseResetFailsIsReleasedNotKept() = poolTest {
        factory.behavior = FakeEngineBehavior(resetSucceeds = false)
        val pool = pool(maxLive = 1, maxIdle = 1)
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
        val pool = pool(maxLive = 1, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        engine.emit(EngineEvent.Crashed)
        pool.release(a)
        assertEquals(listOf("release"), engine.calls)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aSpareThatCrashedIsNotHandedOut() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        pool.prewarm(config)
        val spare = factory.created.single()
        spare.emit(EngineEvent.Crashed)
        val engine = pool.engineFor(Holder(PRIORITY_VISIBLE))
        assertNotSame(spare, engine)
        assertTrue(spare.released)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aReleaseCompletesWhileAnotherAcquireIsCreating() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val aEngine = pool.engineFor(a)
        val creating = gate()
        factory.createGate = creating
        val b = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        assertFalse(b.isCompleted)
        val release = async { pool.release(a) }
        runCurrent()
        assertTrue(release.isCompleted)
        assertEquals(1, pool.idleCount)
        creating.complete(Unit)
        assertNotSame<WebEngine>(aEngine, (b.await() as AcquireResult.Granted).engine)
        assertEquals(1, pool.liveCount)
        assertEquals(1, pool.idleCount)
    }

    @Test fun anAcquireCancelledWhileCreatingLeavesItsEngineAsASpare() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val creating = gate()
        factory.createGate = creating
        val acquiring = launch { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        acquiring.cancel()
        runCurrent()
        creating.complete(Unit)
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        val made = factory.created.single()
        assertFalse(made.released)
        assertEquals(0, pool.liveCount)
        assertEquals(1, pool.idleCount)
        // Its slot was given back, and the engine is used by the next activation.
        factory.createGate = null
        assertSame(made, pool.engineFor(Holder(PRIORITY_VISIBLE)))
        assertEquals(1, factory.created.size)
    }

    // Races.

    @Test fun anEvictedSlotBelongsToTheEvicterWhileTheVictimSavesItself() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN)
        val saving = gate()
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
        val pool = pool(maxLive = 1, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN)
        val saving = gate()
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
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN).apply { failEvict = true }
        val engine = pool.engineFor(hidden)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_FOCUSED)))
        assertEquals(1, pool.liveCount)
        assertEquals(listOf<String?>("snapshot failed"), takeErrors())
    }

    @Test fun anAcquireDeniedForAPendingSlotHearsWhenItBecomesALease() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val creating = gate()
        factory.createGate = creating
        val visible = Holder(PRIORITY_VISIBLE)
        val pending = async { pool.acquire(visible, config) }
        runCurrent()
        val focused = Holder(PRIORITY_FOCUSED)
        assertEquals(AcquireResult.Denied, pool.acquire(focused, config))
        creating.complete(Unit)
        val visiblesEngine = (pending.await() as AcquireResult.Granted).engine
        assertEquals(1, heard)
        assertSame(visiblesEngine, pool.engineFor(focused))
        assertEquals(listOf(visiblesEngine), visible.evicted)
    }

    @Test fun prewarmingDoesNotHoldUpARelease() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        val creating = gate()
        factory.createGate = creating
        val warming = async { pool.prewarm(config) }
        runCurrent()
        val release = async { pool.release(a) }
        runCurrent()
        assertTrue(release.isCompleted)
        // The prewarm holds the only spare room, so the released engine goes at once, without a pointless reset.
        assertEquals(listOf("release"), engine.calls)
        creating.complete(Unit)
        warming.await()
        assertEquals(1, pool.idleCount)
        pool.assertNoneLost()
    }

    @Test fun concurrentPrewarmsForOneConfigCreateOneSpare() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val creating = gate()
        factory.createGate = creating
        val first = async { pool.prewarm(config) }
        val second = async { pool.prewarm(config) }
        runCurrent()
        creating.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, factory.created.size)
        assertEquals(1, pool.idleCount)
    }

    // Pool v2: a memory-pressure window.

    @Test fun memoryPressureEvictsOnceAndTheEvictedWaitForRecovery() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 1)
        val holders = List(4) { Holder(PRIORITY_VISIBLE) }
        holders.forEach { pool.acquire(it, config) }
        var heard = 0
        pool.addCapacityListener { heard++ }
        val window = launch { pool.onMemoryPressure() }
        runCurrent()
        val evicted = holders.filter { it.evicted.isNotEmpty() }
        assertEquals(2, evicted.size)
        assertEquals(2, pool.liveCount)
        // They ask straight back, as a host does after onEvict, and are told to wait; no spare is made meanwhile.
        evicted.forEach { assertEquals(AcquireResult.Denied, pool.acquire(it, config)) }
        pool.prewarm(config)
        assertEquals(0, pool.idleCount)
        assertEquals(4, factory.created.size)
        assertEquals(0, heard)
        pool.onMemoryRecovered()
        assertEquals(1, heard)
        evicted.forEach { assertIs<AcquireResult.Granted>(pool.acquire(it, config)) }
        window.join()
        assertEquals(1, heard)
        assertEquals(6, factory.created.size)
    }

    @Test fun theWindowEndsWithTheCooldownOfTheLatestPressure() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0, pressureCooldownMillis = 1_000)
        var heard = 0
        pool.addCapacityListener { heard++ }
        launch { pool.onMemoryPressure() }
        advanceTimeBy(600)
        launch { pool.onMemoryPressure() }
        advanceTimeBy(600)
        // The first call's cooldown is over, the second's isn't: the budget is still 1.
        assertEquals(0, heard)
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        advanceTimeBy(500)
        assertEquals(1, heard)
        assertIs<AcquireResult.Granted>(pool.acquire(waiting, config))
    }

    @Test fun memoryPressureCountsAcquiresStillCreating() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val holders = List(3) { Holder(PRIORITY_VISIBLE) }
        holders.forEach { pool.acquire(it, config) }
        val creating = gate()
        factory.createGate = creating
        val pending = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        launch { pool.onMemoryPressure() }
        runCurrent()
        // Three live and one on its way, down to two.
        assertEquals(2, holders.count { it.evicted.isNotEmpty() })
        creating.complete(Unit)
        assertIs<AcquireResult.Granted>(pending.await())
        assertEquals(2, pool.liveCount)
        pool.onMemoryRecovered()
    }

    // Pool v2: priorities that change get noticed.

    @Test fun aDeniedViewHearsWhenALeasesPriorityDrops() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        val visible = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(visible)
        pool.priorityLowered()
        assertEquals(0, heard, "nobody was denied, so nobody is told")
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        visible.priority = PRIORITY_HIDDEN
        pool.priorityLowered()
        assertEquals(1, heard)
        assertSame(engine, pool.engineFor(waiting))
        assertEquals(listOf<WebEngine>(engine), visible.evicted)
    }

    @Test fun touchMakesALeaseRecentlyUsed() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0)
        val a = Holder(PRIORITY_VISIBLE)
        val b = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.acquire(b, config)
        pool.touch(a)
        pool.acquire(Holder(PRIORITY_FOCUSED), config)
        assertEquals(1, b.evicted.size)
        assertTrue(a.evicted.isEmpty())
    }

    // Pool v2: the holder contract.

    @Test fun aHolderStuckInOnEvictLosesItsEngineAfterTheTimeout() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN).apply { evictGate = gate() }
        val engine = pool.engineFor(hidden)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        assertFalse(focused.isCompleted)
        advanceTimeBy(5_001)
        assertSame<WebEngine>(engine, (focused.await() as AcquireResult.Granted).engine)
        assertIs<TimeoutCancellationException>(errors.single())
        errors.clear()
    }

    // Pool v2: profile isolation.

    @Test fun aHolderLeasesOneConfigAtATime() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0)
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        assertFailsWith<IllegalStateException> { pool.acquire(a, other) }
        val b = Holder(PRIORITY_VISIBLE)
        val creating = gate()
        factory.createGate = creating
        val first = async { pool.acquire(b, config) }
        runCurrent()
        assertFailsWith<IllegalStateException> { pool.acquire(b, other) }
        creating.complete(Unit)
        assertIs<AcquireResult.Granted>(first.await())
    }

    @Test fun anEvictedEngineOfAnotherConfigIsNeverHandedToTheEvicter() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val hiddensEngine = pool.engineFor(Holder(PRIORITY_HIDDEN))
        val focused = Holder(PRIORITY_FOCUSED)
        val engine = pool.engineFor(focused, other)
        assertNotSame(hiddensEngine, engine)
        assertEquals(other, engine.config)
        // The evicted engine is a spare for its own profile.
        assertEquals(1, pool.idleCount)
        pool.discard(focused)
        assertSame(hiddensEngine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun anEvictedEngineWhoseResetFailedIsNeverHandedOver() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN)
        val hiddensEngine = pool.engineFor(hidden)
        hiddensEngine.behavior = FakeEngineBehavior(resetSucceeds = false)
        val engine = pool.engineFor(Holder(PRIORITY_FOCUSED))
        assertNotSame(hiddensEngine, engine)
        assertTrue(hiddensEngine.released)
        assertEquals(0, pool.idleCount)
    }

    @Test fun anEngineThatCrashedWhileItsHolderSavedIsNeverHandedOver() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN).apply { crashOnEvict = true }
        val hiddensEngine = pool.engineFor(hidden)
        val engine = pool.engineFor(Holder(PRIORITY_FOCUSED))
        assertNotSame(hiddensEngine, engine)
        assertTrue(hiddensEngine.released)
        assertFalse("reset" in hiddensEngine.calls)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aResetThatThrowsCountsAsFailedAndIsReported() = poolTest {
        val made = mutableListOf<Faulty>()
        val pool = pool(maxLive = 1, maxIdle = 1, factory = faultyFactory(made))
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        made.single().resetThrows = true
        pool.release(a)
        assertEquals(0, pool.idleCount)
        assertTrue(made.single().fake.released)
        assertEquals(listOf<String?>("reset failed"), takeErrors())
    }

    @Test fun aReleaseThatThrowsIsReportedAndTheEngineIsGoneAnyway() = poolTest {
        val made = mutableListOf<Faulty>()
        val pool = pool(maxLive = 1, maxIdle = 0, factory = faultyFactory(made))
        var heard = 0
        pool.addCapacityListener { heard++ }
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        made.single().releaseThrows = true
        pool.release(a)
        assertEquals(0, pool.liveCount)
        assertEquals(1, heard)
        assertTrue(made.single().fake.released)
        assertEquals(listOf<String?>("release failed"), takeErrors())
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
    }

    // Pool v2: spares that don't churn.

    @Test fun prewarmOnlyFillsFreeRoomAndNeverReplacesAnotherConfigsSpare() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        val spare = factory.created.single()
        pool.prewarm(other)
        assertEquals(1, factory.created.size)
        assertSame(spare, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun prewarmWaitsForRoomInTheBudget() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        pool.prewarm(config)
        assertEquals(1, factory.created.size)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aPrewarmYieldsToASpareThatCameBackMeanwhile() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 2)
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        val creating = gate()
        factory.createGate = creating
        val warming = async { pool.prewarm(config) }
        runCurrent()
        pool.release(a)
        creating.complete(Unit)
        warming.await()
        assertEquals(1, pool.idleCount)
        assertTrue(factory.created[1].released)
        assertSame(engine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun recyclingReservesSpareRoomBeforeItResets() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val b = Holder(PRIORITY_VISIBLE)
        val aEngine = pool.engineFor(a)
        val bEngine = pool.engineFor(b)
        val resetting = gate()
        aEngine.resetGate = resetting
        val first = async { pool.release(a) }
        runCurrent()
        // a's reset holds the only spare room, so b's engine goes at once, without a reset.
        pool.release(b)
        assertEquals(listOf("release"), bEngine.calls)
        resetting.complete(Unit)
        first.await()
        assertEquals(1, pool.idleCount)
        assertSame(aEngine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun anEvicterTakesAMatchingSpareAtOnce() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        val spare = factory.created.single()
        val hidden1 = Holder(PRIORITY_HIDDEN)
        val hidden2 = Holder(PRIORITY_HIDDEN)
        val victimsEngine = pool.engineFor(hidden1, other)
        pool.engineFor(hidden2, other)
        val saving = gate()
        hidden1.evictGate = saving
        // Granted before the victim has even started saving itself.
        assertSame(spare, pool.engineFor(Holder(PRIORITY_FOCUSED)))
        runCurrent()
        assertEquals(listOf<WebEngine>(victimsEngine), hidden1.evicted)
        assertEquals(0, pool.idleCount)
        saving.complete(Unit)
        runCurrent()
        // The victim's engine, reset, is the spare now.
        assertEquals(1, pool.idleCount)
        assertTrue("reset" in victimsEngine.calls)
        assertSame(victimsEngine, pool.engineFor(Holder(PRIORITY_FOCUSED), other))
        runCurrent()
        pool.assertNoneLost()
    }

    @Test fun scrollingSixtyViewsInPagesOfThreeCreatesAtMostTheBudgetAndTheSpares() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 3)
        scroll(pool, pages = 20, prewarmOnFirstPaint = false)
        assertTrue(factory.created.size <= 4 + 3, "created ${factory.created.size}")
        pool.assertNoneLost()
    }

    @Test fun scrollingWithPrewarmOnFirstPaintAndOneSpareDoesNotChurn() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 1)
        scroll(pool, pages = 20, prewarmOnFirstPaint = true)
        assertTrue(factory.created.size <= 4 + 1, "created ${factory.created.size}")
        pool.assertNoneLost()
    }

    /**
     * Scrolls [pages] pages of three views through [pool]: each page's views activate, then the page before, now
     * hidden, gives its engines back.
     */
    private suspend fun TestScope.scroll(pool: EnginePool, pages: Int, prewarmOnFirstPaint: Boolean) {
        var shown = emptyList<Holder>()
        repeat(pages) {
            shown.forEach { it.priority = PRIORITY_HIDDEN }
            pool.priorityLowered()
            val page = List(3) { Holder(PRIORITY_VISIBLE) }
            page.forEach {
                assertIs<AcquireResult.Granted>(pool.acquire(it, config))
                if (prewarmOnFirstPaint) pool.prewarm(config)
            }
            shown.forEach { pool.release(it) }
            shown = page
            runCurrent()
        }
    }

    // Pool v2: pending acquires and the budget.

    @Test fun aSecondAcquireForAPendingHolderWaitsAndSharesItsResult() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val creating = gate()
        factory.createGate = creating
        val holder = Holder(PRIORITY_VISIBLE)
        val first = async { pool.acquire(holder, config) }
        val second = async { pool.acquire(holder, config) }
        runCurrent()
        creating.complete(Unit)
        val engine = (first.await() as AcquireResult.Granted).engine
        assertSame(engine, (second.await() as AcquireResult.Granted).engine)
        assertEquals(1, pool.liveCount)
        assertEquals(1, factory.created.size)
        assertEquals(0, pool.idleCount)
    }

    @Test fun aSecondAcquireCarriesOnWhenTheFirstIsCancelled() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val creating = gate()
        factory.createGate = creating
        val holder = Holder(PRIORITY_VISIBLE)
        val first = launch { pool.acquire(holder, config) }
        runCurrent()
        val second = async { pool.acquire(holder, config) }
        runCurrent()
        first.cancel()
        creating.complete(Unit)
        val engine = (second.await() as AcquireResult.Granted).engine
        assertTrue(first.isCancelled)
        assertSame(factory.created.single(), engine)
        assertEquals(1, pool.liveCount)
    }

    @Test fun raisingTheBudgetTellsListeners() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        var heard = 0
        pool.addCapacityListener { heard++ }
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        pool.setMaxLive(2)
        assertEquals(2, pool.maxLive)
        assertEquals(1, heard)
        assertIs<AcquireResult.Granted>(pool.acquire(waiting, config))
    }

    @Test fun loweringTheBudgetEvictsLowestPriorityFirst() = poolTest {
        val pool = pool(maxLive = 3, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN)
        val visible = Holder(PRIORITY_VISIBLE)
        val focused = Holder(PRIORITY_FOCUSED)
        listOf(focused, visible, hidden).forEach { pool.acquire(it, config) }
        pool.setMaxLive(2)
        assertEquals(1, hidden.evicted.size)
        assertTrue(visible.evicted.isEmpty())
        pool.setMaxLive(1)
        assertEquals(1, visible.evicted.size)
        assertTrue(focused.evicted.isEmpty())
        assertEquals(1, pool.liveCount)
        assertEquals(AcquireResult.Denied, pool.acquire(visible, config))
    }

    // Pool v2: bounded creation.

    @Test fun aCreationThatTakesTooLongIsUnavailable() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        factory.createGate = gate()
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals(AcquireResult.Unavailable(UnavailableReason.CreationFailed, "timed out"), acquiring.await())
        assertEquals(1, takeErrors().size)
        // The factory honoured the cancellation, so nothing was made; the slot is free again.
        factory.createGate = null
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        assertEquals(1, factory.created.size)
    }

    @Test fun anEngineThatArrivesAfterTheTimeoutIsReleased() = poolTest {
        val slow = gate()
        val stubborn = WebEngineFactory { config ->
            withContext(NonCancellable) { slow.await() }
            factory.create(config)
        }
        val pool = pool(maxLive = 1, maxIdle = 1, factory = stubborn)
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals(AcquireResult.Unavailable(UnavailableReason.CreationFailed, "timed out"), acquiring.await())
        assertEquals(1, takeErrors().size)
        slow.complete(Unit)
        runCurrent()
        assertTrue(factory.created.single().released)
        assertEquals(0, pool.idleCount)
    }

    private fun faultyFactory(made: MutableList<Faulty>) = WebEngineFactory { config ->
        val fake = (factory.create(config) as WebEngineResult.Created).engine as FakeWebEngine
        WebEngineResult.Created(Faulty(fake).also { made += it })
    }
}
