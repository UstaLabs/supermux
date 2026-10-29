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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** What Html content runs in. */
    private val html = EngineConfig(WebProfile.Ephemeral)
    private val factory = FakeWebEngineFactory()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val errors = mutableListOf<Throwable>()

    private class Holder(override var priority: Int) : LeaseHolder {
        val evicted = mutableListOf<WebEngine>()

        /** While set, [onEvict] waits for it, as a host does while it snapshots and saves its page. */
        var evictGate: CompletableDeferred<Unit>? = null

        /** While set, [onEvict] waits for it even when cancelled, as a holder that ignores cancellation would. */
        var stubbornGate: CompletableDeferred<Unit>? = null

        /** When true, [onEvict] throws, as a host whose snapshot failed might. */
        var failEvict = false

        /** When true, the engine's renderer dies while [onEvict] runs. */
        var crashOnEvict = false

        override suspend fun onEvict(engine: WebEngine) {
            evicted += engine
            if (crashOnEvict) (engine as FakeWebEngine).emit(EngineEvent.Crashed)
            stubbornGate?.let { withContext(NonCancellable) { it.await() } }
            evictGate?.await()
            if (failEvict) error("snapshot failed")
        }
    }

    /**
     * A holder that behaves like the WebView's host: it holds at most one engine, under its own lock, and acts on an
     * eviction only while it still holds that engine.
     */
    private class Host(override var priority: Int, val config: EngineConfig) : LeaseHolder {
        private val lock = Mutex()
        var engine: WebEngine? = null
            private set
        var lateEvictions = 0
            private set

        override suspend fun onEvict(engine: WebEngine) {
            lock.withLock {
                if (this.engine === engine) this.engine = null else lateEvictions++
            }
        }

        suspend fun activate(pool: EnginePool): AcquireResult = lock.withLock {
            engine?.let { return@withLock AcquireResult.Granted(it) }
            pool.acquire(this, config).also { if (it is AcquireResult.Granted) engine = it.engine }
        }

        suspend fun deactivate(pool: EnginePool) {
            lock.withLock {
                if (engine == null) return@withLock
                engine = null
                pool.release(this)
            }
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

    private class Scroll(val creates: Int, val destroys: Int, val createsByConfig: Map<EngineConfig, Int>)

    /** A gate the test holds. [poolTest] opens it whatever happens, so a failing test fails instead of hanging. */
    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates += it }

    /** A pool whose work runs in the test's background scope, on its virtual clock. */
    private fun TestScope.pool(
        maxLive: Int,
        maxIdle: Int,
        factory: WebEngineFactory = this@EnginePoolTest.factory,
        pressureCooldownMillis: Long = 30_000,
        scope: CoroutineScope = backgroundScope,
    ) = EnginePool(
        factory,
        maxLive,
        maxIdle,
        scope,
        clock = { testScheduler.currentTime },
        pressureCooldownMillis = pressureCooldownMillis,
    ).also { pool -> pool.onError = { errors += it } }

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
    private fun EnginePool.assertNoneLost(made: FakeWebEngineFactory = factory) =
        assertEquals(made.created.count { !it.released }, liveCount + idleCount, "engines lost")

    /** Counts capacity notices, heard as a listener of [priority]. */
    private fun EnginePool.notices(priority: Int = PRIORITY_HIDDEN): () -> Int {
        var heard = 0
        addCapacityListener(Holder(priority)) { heard++ }
        return { heard }
    }

    private fun faultyFactory(made: MutableList<Faulty>) = WebEngineFactory { config ->
        val fake = (factory.create(config) as WebEngineResult.Created).engine as FakeWebEngine
        WebEngineResult.Created(Faulty(fake).also { made += it })
    }

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
        val heard = pool.notices()
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        assertEquals(1, heard())
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
        pool.onMemoryPressure()
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
        val heard = pool.notices()
        val a = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(a)
        pool.release(a)
        assertEquals(listOf("reset", "release"), engine.calls)
        assertEquals(0, pool.idleCount)
        assertEquals(1, heard())
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
        val heard = pool.notices()
        val creating = gate()
        factory.createGate = creating
        val visible = Holder(PRIORITY_VISIBLE)
        val pending = async { pool.acquire(visible, config) }
        runCurrent()
        val focused = Holder(PRIORITY_FOCUSED)
        assertEquals(AcquireResult.Denied, pool.acquire(focused, config))
        creating.complete(Unit)
        val visiblesEngine = (pending.await() as AcquireResult.Granted).engine
        assertEquals(1, heard())
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
        val heard = pool.notices()
        pool.onMemoryPressure()
        val evicted = holders.filter { it.evicted.isNotEmpty() }
        assertEquals(2, evicted.size)
        assertEquals(2, pool.liveCount)
        // They ask straight back, as a host does after onEvict, and are told to wait; no spare is made meanwhile.
        evicted.forEach { assertEquals(AcquireResult.Denied, pool.acquire(it, config)) }
        pool.prewarm(config)
        assertEquals(0, pool.idleCount)
        assertEquals(4, factory.created.size)
        assertEquals(0, heard())
        pool.onMemoryRecovered()
        assertEquals(1, heard())
        evicted.forEach { assertIs<AcquireResult.Granted>(pool.acquire(it, config)) }
        assertEquals(1, heard())
        assertEquals(6, factory.created.size)
    }

    @Test fun theWindowEndsWithTheCooldownOfTheLatestPressure() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 0, pressureCooldownMillis = 1_000)
        val heard = pool.notices()
        pool.onMemoryPressure()
        advanceTimeBy(600)
        pool.onMemoryPressure()
        advanceTimeBy(600)
        // The first call's cooldown is over, the second's isn't: the budget is still 1.
        assertEquals(0, heard())
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        advanceTimeBy(500)
        assertEquals(1, heard())
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
        pool.onMemoryPressure()
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
        val heard = pool.notices()
        val visible = Holder(PRIORITY_VISIBLE)
        val engine = pool.engineFor(visible)
        pool.priorityLowered()
        assertEquals(0, heard(), "nobody was denied, so nobody is told")
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        visible.priority = PRIORITY_HIDDEN
        pool.priorityLowered()
        assertEquals(1, heard())
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
        val heard = pool.notices()
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        made.single().releaseThrows = true
        pool.release(a)
        assertEquals(0, pool.liveCount)
        assertEquals(1, heard())
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
        pool.release(a)
        // a's reset holds the only spare room, so b's engine goes at once, without a reset.
        pool.release(b)
        assertEquals(listOf("release"), bEngine.calls)
        resetting.complete(Unit)
        runCurrent()
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
        val heard = pool.notices()
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        val waiting = Holder(PRIORITY_VISIBLE)
        assertEquals(AcquireResult.Denied, pool.acquire(waiting, config))
        pool.setMaxLive(2)
        assertEquals(2, pool.maxLive)
        assertEquals(1, heard())
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

    // Pool v3: failure paths that can't strand capacity or share an engine.

    @Test fun aTimedOutEvictionNeverHandsOverTheVictimsEngine() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN).apply { evictGate = gate() }
        val engine = pool.engineFor(hidden)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        assertFalse(focused.isCompleted)
        advanceTimeBy(5_001)
        assertNotSame<WebEngine>(engine, (focused.await() as AcquireResult.Granted).engine)
        runCurrent()
        // The holder's save was cancelled; once it ended, its engine was released, never reset for someone else.
        assertTrue(engine.released)
        assertFalse("reset" in engine.calls)
        assertEquals(1, takeErrors().size)
        pool.assertNoneLost()
    }

    @Test fun aHolderThatIgnoresCancellationInOnEvictStrandsNoCapacity() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hung = gate()
        val hidden = Holder(PRIORITY_HIDDEN).apply { stubbornGate = hung }
        val engine = pool.engineFor(hidden)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        advanceTimeBy(5_001)
        assertIs<AcquireResult.Granted>(focused.await())
        assertEquals(1, pool.liveCount)
        assertEquals(1, pool.overdueCount)
        // Its holder may still be using it, so it goes only once that call returns.
        assertFalse(engine.released)
        hung.complete(Unit)
        runCurrent()
        assertTrue(engine.released)
        assertEquals(0, pool.overdueCount)
        assertEquals(1, takeErrors().size)
    }

    @Test fun aResetThatHangsHoldsUpNeitherReleaseNorSpareRoom() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        val aEngine = pool.engineFor(a)
        val hung = gate()
        aEngine.resetGate = hung
        val release = async { pool.release(a) }
        runCurrent()
        assertTrue(release.isCompleted, "release waits for no reset")
        advanceTimeBy(10_001)
        assertEquals(1, takeErrors().size)
        // The spare room is free again: the next engine given back is kept.
        val b = Holder(PRIORITY_VISIBLE)
        val bEngine = pool.engineFor(b)
        pool.release(b)
        assertEquals(1, pool.idleCount)
        assertSame(bEngine, pool.engineFor(Holder(PRIORITY_VISIBLE)))
        // The hung engine goes once its reset returns.
        assertFalse(aEngine.released)
        hung.complete(Unit)
        runCurrent()
        assertTrue(aEngine.released)
        pool.assertNoneLost()
    }

    @Test fun anEvicterWhoseVictimsResetHangsGetsAnotherEngine() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hiddensEngine = pool.engineFor(Holder(PRIORITY_HIDDEN))
        val hung = gate()
        hiddensEngine.resetGate = hung
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        advanceTimeBy(10_001)
        assertNotSame<WebEngine>(hiddensEngine, (focused.await() as AcquireResult.Granted).engine)
        assertEquals(1, takeErrors().size)
        hung.complete(Unit)
        runCurrent()
        assertTrue(hiddensEngine.released)
        pool.assertNoneLost()
    }

    @Test fun aLateOnEvictFindsTheVictimHoldingAnotherEngineAndChangesNothing() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        val v1 = Host(PRIORITY_HIDDEN, other)
        val v2 = Host(PRIORITY_HIDDEN, other)
        v1.activate(pool)
        v2.activate(pool)
        val old = v1.engine!!
        // A focused view takes the spare at once; v1's eviction is on its way.
        Host(PRIORITY_FOCUSED, config).activate(pool)
        // Before it arrives, v1 scrolls away and back: it evicts v2 and takes its engine.
        v1.deactivate(pool)
        v1.priority = PRIORITY_VISIBLE
        v1.activate(pool)
        val now = v1.engine!!
        runCurrent()
        assertNotSame(old, now)
        assertSame(now, v1.engine)
        assertEquals(1, v1.lateEvictions)
        assertTrue(pool.holds(v1))
        // The old engine, reset, is the spare.
        assertEquals(1, pool.idleCount)
        assertTrue("reset" in (old as FakeWebEngine).calls)
        pool.assertNoneLost()
    }

    @Test fun memoryPressureReturnsAtOnceAndEvictsItsVictimsTogether() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val holders = List(4) { Holder(PRIORITY_VISIBLE).apply { evictGate = gate() } }
        holders.forEach { pool.acquire(it, config) }
        pool.onMemoryPressure()
        // Both victims save at once, and the call didn't wait for them.
        assertEquals(2, holders.count { it.evicted.isNotEmpty() })
        holders.forEach { it.evictGate!!.complete(Unit) }
        runCurrent()
        assertEquals(2, factory.created.count { it.released })
        pool.onMemoryRecovered()
    }

    @Test fun recoveryWhileVictimsStillSaveKeepsTheBound() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val holders = List(4) { Holder(PRIORITY_VISIBLE).apply { evictGate = gate() } }
        holders.forEach { pool.acquire(it, config) }
        val heard = pool.notices()
        pool.onMemoryPressure()
        pool.onMemoryRecovered()
        assertEquals(1, heard())
        // The victims still save: their engines count against the budget until they're gone.
        val newcomers = List(2) { Holder(PRIORITY_VISIBLE) }
        newcomers.forEach { assertEquals(AcquireResult.Denied, pool.acquire(it, config)) }
        assertEquals(4, factory.created.count { !it.released })
        holders.forEach { it.evictGate!!.complete(Unit) }
        runCurrent()
        assertEquals(2, heard(), "told once the victims' engines are gone")
        newcomers.forEach { assertIs<AcquireResult.Granted>(pool.acquire(it, config)) }
        assertEquals(4, factory.created.count { !it.released })
    }

    @Test fun creationsThatLandOverBudgetDuringPressureAreDenied() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val creating = gate()
        factory.createGate = creating
        val acquires = List(4) { async { pool.acquire(Holder(PRIORITY_VISIBLE), config) } }
        runCurrent()
        pool.onMemoryPressure()
        val heard = pool.notices()
        creating.complete(Unit)
        val results = acquires.awaitAll()
        assertEquals(2, results.count { it is AcquireResult.Granted })
        assertEquals(2, results.count { it == AcquireResult.Denied })
        assertEquals(2, pool.liveCount)
        // No spares during the window: the denied ones' engines were released.
        assertEquals(2, factory.created.count { !it.released })
        pool.onMemoryRecovered()
        assertEquals(1, heard(), "the denied hear when the window ends")
    }

    @Test fun cancellingThePoolsScopeStopsItsWork() = poolTest {
        val poolScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val pool = pool(maxLive = 1, maxIdle = 1, scope = poolScope)
        factory.createGate = gate()
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        poolScope.cancel()
        assertEquals(UnavailableReason.CreationFailed, (acquiring.await() as AcquireResult.Unavailable).reason)
        assertTrue(factory.created.isEmpty())
    }

    @Test fun listenersHearInPriorityOrderHighestFirst() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val order = mutableListOf<Int>()
        for (priority in listOf(PRIORITY_VISIBLE, PRIORITY_FOCUSED, PRIORITY_HIDDEN, PRIORITY_VISIBLE_EAGER)) {
            pool.addCapacityListener(Holder(priority)) { order += priority }
        }
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        assertEquals(listOf(PRIORITY_FOCUSED, PRIORITY_VISIBLE_EAGER, PRIORITY_VISIBLE, PRIORITY_HIDDEN), order)
    }

    @Test fun aThrowingListenerNeitherBreaksAGrantNorSilencesTheOthers() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        pool.addCapacityListener(Holder(PRIORITY_FOCUSED)) { error("listener failed") }
        val heard = pool.notices()
        val creating = gate()
        factory.createGate = creating
        val pending = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        runCurrent()
        assertEquals(AcquireResult.Denied, pool.acquire(Holder(PRIORITY_FOCUSED), config))
        creating.complete(Unit)
        // The grant tells the listeners; the one that throws doesn't turn the grant into an exception.
        assertIs<AcquireResult.Granted>(pending.await())
        assertEquals(1, heard())
        assertEquals(listOf<String?>("listener failed"), takeErrors())
    }

    @Test fun aCreationThatTakesTooLongIsUnavailable() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val creating = gate()
        factory.createGate = creating
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        advanceTimeBy(20_001)
        assertEquals(AcquireResult.Unavailable(UnavailableReason.CreationFailed, "timed out"), acquiring.await())
        assertEquals(1, takeErrors().size)
        // The slot is free again for a retry.
        factory.createGate = null
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_VISIBLE), config))
        // The first creation still finishes; with no room for a spare, its engine is released.
        creating.complete(Unit)
        runCurrent()
        assertEquals(2, factory.created.size)
        assertTrue(factory.created[1].released)
        pool.assertNoneLost()
    }

    @Test fun anEngineThatArrivesAfterTheTimeoutBecomesASpare() = poolTest {
        val slow = gate()
        val stubborn = WebEngineFactory { config ->
            withContext(NonCancellable) { slow.await() }
            factory.create(config)
        }
        val pool = pool(maxLive = 1, maxIdle = 1, factory = stubborn)
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        advanceTimeBy(20_001)
        assertEquals(AcquireResult.Unavailable(UnavailableReason.CreationFailed, "timed out"), acquiring.await())
        assertEquals(1, takeErrors().size)
        slow.complete(Unit)
        runCurrent()
        // A backoff retry on a slow-starting platform finds it ready.
        assertEquals(1, pool.idleCount)
        assertSame(factory.created.single(), pool.engineFor(Holder(PRIORITY_VISIBLE)))
    }

    @Test fun anEngineThatArrivesAfterTheTimeoutWithNoRoomIsReleased() = poolTest {
        val slow = gate()
        val stubborn = WebEngineFactory { config ->
            withContext(NonCancellable) { slow.await() }
            factory.create(config)
        }
        val pool = pool(maxLive = 1, maxIdle = 0, factory = stubborn)
        val acquiring = async { pool.acquire(Holder(PRIORITY_VISIBLE), config) }
        advanceTimeBy(20_001)
        assertIs<AcquireResult.Unavailable>(acquiring.await())
        assertEquals(1, takeErrors().size)
        slow.complete(Unit)
        runCurrent()
        assertTrue(factory.created.single().released)
        assertEquals(0, pool.idleCount)
    }

    // Pool v3: prewarm only at cold start; spares that rebalance.

    @Test fun prewarmActsOnlyAtColdStart() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val a = Holder(PRIORITY_VISIBLE)
        pool.acquire(a, config)
        pool.release(a)
        pool.acquire(Holder(PRIORITY_VISIBLE), config)
        // An engine of this profile came back moments ago: recycling supplies its spares.
        pool.prewarm(config)
        assertEquals(1, factory.created.size)
        assertEquals(0, pool.idleCount)
        // Quiet for 10 s: that's a cold start again.
        advanceTimeBy(10_001)
        pool.prewarm(config)
        assertEquals(2, factory.created.size)
        assertEquals(1, pool.idleCount)
    }

    @Test fun scrollingWithReleasesFirstReusesEnginesWithOrWithoutPrewarm() = poolTest {
        val without = scroll(maxIdle = 3, prewarm = false, releaseFirst = true) { _, _ -> config }
        assertEquals(3, without.creates)
        assertEquals(0, without.destroys)
        val with = scroll(maxIdle = 3, prewarm = true, releaseFirst = true) { _, _ -> config }
        assertTrue(with.creates <= 4 && with.destroys <= 1, "${with.creates} creates, ${with.destroys} destroys")
    }

    @Test fun prewarmAddsAtMostOneSparePerProfileWhenScrolling() = poolTest {
        val modes = listOf<Triple<String, Int, (Int, Int) -> EngineConfig>>(
            Triple("one profile", 1) { _, _ -> config },
            Triple("profiles mixed per view", 2) { page, view -> if ((page * 3 + view) % 2 == 0) config else other },
            Triple("profiles mixed per page", 2) { page, _ -> if (page % 2 == 0) config else other },
        )
        for ((mode, profiles, configOf) in modes) {
            for (releaseFirst in listOf(true, false)) {
                for (maxIdle in listOf(1, 3)) {
                    val case = "$mode, maxIdle $maxIdle, ${if (releaseFirst) "releases" else "activations"} first"
                    val without = scroll(maxIdle, prewarm = false, releaseFirst, configOf)
                    val with = scroll(maxIdle, prewarm = true, releaseFirst, configOf)
                    assertTrue(
                        with.creates <= without.creates + profiles && with.destroys <= without.destroys + profiles,
                        "$case: with prewarm ${with.creates} creates and ${with.destroys} destroys, " +
                            "without ${without.creates} and ${without.destroys}",
                    )
                }
            }
        }
    }

    @Test fun staleSparesOfAnotherProfileGiveWayAfterSpareStaleMillis() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 2)
        val demos = List(2) { Holder(PRIORITY_VISIBLE) }
        demos.forEach { pool.acquire(it, html) }
        demos.forEach { pool.release(it) }
        // Two Ephemeral spares that nothing uses again.
        val ephemeral = factory.created.toList()
        val page = List(3) { Holder(PRIORITY_VISIBLE) }.onEach { pool.acquire(it, config) }
        page.forEach { pool.release(it) }
        // While they're young they stay, and the Url engines given back are released instead.
        assertTrue(ephemeral.none { it.released })
        assertEquals(3, factory.created.count { it.released })
        advanceTimeBy(10_000)
        // Idle for 10 s, they give way to the profile in use: 3 + 9 creations for the next 30 activations, not 30.
        val before = factory.created.size
        var shown = emptyList<Holder>()
        repeat(10) {
            shown.forEach { pool.release(it) }
            shown = List(3) { Holder(PRIORITY_VISIBLE) }.onEach { pool.acquire(it, config) }
            runCurrent()
        }
        assertTrue(ephemeral.all { it.released })
        assertEquals(3 + 9, factory.created.size - before)
        pool.assertNoneLost()
    }

    @Test fun amongStaleSparesTheOneIdleLongestGivesWayFirst() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 2)
        val a = Holder(PRIORITY_VISIBLE)
        val older = pool.engineFor(a, html)
        pool.release(a)
        advanceTimeBy(5_000)
        val b = Holder(PRIORITY_VISIBLE)
        val newer = pool.engineFor(b, other)
        pool.release(b)
        // Both spares are stale now; an engine of a third profile comes back to a full spare room.
        advanceTimeBy(10_000)
        val c = Holder(PRIORITY_VISIBLE)
        pool.engineFor(c)
        pool.release(c)
        assertTrue(older.released)
        assertFalse(newer.released)
        assertEquals(2, pool.idleCount)
    }

    @Test fun alternatingPagesWithReleasesFirstReuseOneProfileInsteadOfThrashing() = poolTest {
        val scroll = scroll(maxIdle = 3, prewarm = false, releaseFirst = true) { page, _ ->
            if (page % 2 == 0) html else config
        }
        // The Html pages' spares are young when the Url pages give their engines back, so they stay and are reused:
        // only the first Html page creates. Replacing them would create on every page, 60 for 60 activations.
        assertEquals(3, scroll.createsByConfig[html])
        assertTrue(scroll.creates <= 60 / 2 + 3, "${scroll.creates} creates for 60 activations")
    }

    /**
     * Scrolls [pages] pages of three host-like views through a fresh pool with a budget of 4: each page's views
     * activate, prewarming on first paint if [prewarm], and the page before, now hidden, gives its engines back,
     * before the activations if [releaseFirst].
     */
    private suspend fun TestScope.scroll(
        maxIdle: Int,
        prewarm: Boolean,
        releaseFirst: Boolean,
        configOf: (page: Int, view: Int) -> EngineConfig,
    ): Scroll {
        val made = FakeWebEngineFactory()
        val pool = pool(maxLive = 4, maxIdle = maxIdle, factory = made)
        var shown = emptyList<Host>()
        repeat(20) { page ->
            shown.forEach { it.priority = PRIORITY_HIDDEN }
            pool.priorityLowered()
            if (releaseFirst) shown.forEach { it.deactivate(pool) }
            val views = List(3) { Host(PRIORITY_VISIBLE, configOf(page, it)) }
            views.forEach { view ->
                assertIs<AcquireResult.Granted>(view.activate(pool))
                if (prewarm) pool.prewarm(view.config)
            }
            if (!releaseFirst) shown.forEach { it.deactivate(pool) }
            runCurrent()
            assertTrue(made.created.count { !it.released } <= 4 + maxIdle, "over the bound on page $page")
            shown = views
        }
        pool.assertNoneLost(made)
        made.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}") }
        val byConfig = made.created.groupingBy { it.config }.eachCount()
        return Scroll(made.created.size, made.created.count { it.released }, byConfig)
    }

    // Pool v3: the reviewer's surviving mutants.

    @Test fun pressureNeverEvictsTheFocusedViewEvenWhenOnlyItIsLive() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val focused = Holder(PRIORITY_FOCUSED)
        pool.acquire(focused, config)
        factory.createGate = gate()
        repeat(3) { launch { pool.acquire(Holder(PRIORITY_VISIBLE), config) } }
        runCurrent()
        pool.onMemoryPressure()
        assertTrue(focused.evicted.isEmpty(), "focused evicted")
        assertTrue(pool.holds(focused))
        pool.onMemoryRecovered()
    }

    @Test fun aReleaseDuringAPressureWindowKeepsNoSpare() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 2)
        val focused = Holder(PRIORITY_FOCUSED)
        val visible = Holder(PRIORITY_VISIBLE)
        pool.acquire(focused, config)
        val engine = pool.engineFor(visible)
        pool.onMemoryPressure()
        pool.release(visible)
        assertEquals(0, pool.idleCount)
        assertTrue(engine.released)
        assertFalse("reset" in engine.calls, "reset for nothing")
        pool.onMemoryRecovered()
    }

    @Test fun aVictimsEngineHoldsTheSpareRoomTheEvicterEmptied() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        pool.prewarm(config)
        val hidden1 = Holder(PRIORITY_HIDDEN).apply { evictGate = gate() }
        val hidden2 = Holder(PRIORITY_HIDDEN)
        pool.acquire(hidden1, other)
        val hidden2sEngine = pool.engineFor(hidden2, other)
        assertIs<AcquireResult.Granted>(pool.acquire(Holder(PRIORITY_FOCUSED), config))
        runCurrent()
        // hidden1 is saving itself; the room the evicter emptied is its engine's, so hidden2's goes unreset.
        pool.release(hidden2)
        assertEquals(listOf("release"), hidden2sEngine.calls, "no room, so no reset")
        hidden1.evictGate!!.complete(Unit)
        runCurrent()
        pool.assertNoneLost()
    }

    @Test fun loweringTheBudgetLosesNoEngine() = poolTest {
        val pool = pool(maxLive = 3, maxIdle = 1)
        List(3) { Holder(PRIORITY_VISIBLE) }.forEach { pool.acquire(it, config) }
        pool.setMaxLive(1)
        runCurrent()
        assertEquals(1, pool.liveCount)
        // One victim's engine is a spare, the other's was released.
        assertEquals(1, pool.idleCount)
        pool.assertNoneLost()
    }

    @Test fun aSpareThatComesBackWhileAMismatchedVictimSavesIsUsed() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN).apply { evictGate = gate() }
        val visible = Holder(PRIORITY_VISIBLE)
        pool.acquire(hidden, other)
        val visiblesEngine = pool.engineFor(visible)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        // The victim's engine is of another profile; while it saves, an engine of the evicter's comes back.
        pool.release(visible)
        hidden.evictGate!!.complete(Unit)
        assertSame<WebEngine>(visiblesEngine, (focused.await() as AcquireResult.Granted).engine)
        assertEquals(2, factory.created.size)
        runCurrent()
        pool.assertNoneLost()
    }

    @Test fun anEvicterFallsBackToASpareThatCameBackMeanwhile() = poolTest {
        val pool = pool(maxLive = 2, maxIdle = 1)
        val hidden = Holder(PRIORITY_HIDDEN).apply { evictGate = gate() }
        val hiddensEngine = pool.engineFor(hidden)
        hiddensEngine.behavior = FakeEngineBehavior(resetSucceeds = false)
        val visible = Holder(PRIORITY_VISIBLE)
        val visiblesEngine = pool.engineFor(visible)
        val focused = async { pool.acquire(Holder(PRIORITY_FOCUSED), config) }
        runCurrent()
        // While the victim saves, an engine of the same profile comes back; then the victim's fails its reset.
        pool.release(visible)
        hidden.evictGate!!.complete(Unit)
        assertSame<WebEngine>(visiblesEngine, (focused.await() as AcquireResult.Granted).engine)
        assertEquals(2, factory.created.size)
        assertTrue(hiddensEngine.released)
    }

    @Test fun aCancelledCallerEvictsNoOne() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 0)
        val hidden = Holder(PRIORITY_HIDDEN)
        pool.acquire(hidden, config)
        val acquiring = launch {
            cancel()
            pool.acquire(Holder(PRIORITY_FOCUSED), config)
        }
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        assertTrue(hidden.evicted.isEmpty())
        assertTrue(pool.holds(hidden))
    }

    @Test fun pressureEvictsTheLeastRecentlyUsedFirst() = poolTest {
        val pool = pool(maxLive = 4, maxIdle = 0)
        val holders = List(4) { Holder(PRIORITY_VISIBLE) }
        holders.forEach { pool.acquire(it, config) }
        pool.touch(holders[0])
        pool.onMemoryPressure()
        assertEquals(listOf(false, true, true, false), holders.map { it.evicted.isNotEmpty() })
        pool.onMemoryRecovered()
    }

    @Test fun aWaiterChecksAgainOnceTheFirstAcquireIsDone() = poolTest {
        val pool = pool(maxLive = 1, maxIdle = 1)
        val creating = gate()
        factory.createGate = creating
        val holder = Holder(PRIORITY_VISIBLE)
        val first = launch {
            pool.acquire(holder, config)
            // Granted, and given back at once.
            pool.release(holder)
        }
        val second = async { pool.acquire(holder, config) }
        runCurrent()
        creating.complete(Unit)
        first.join()
        assertIs<AcquireResult.Granted>(second.await())
        // The second didn't keep the first's stale grant: it holds a lease of its own.
        assertTrue(pool.holds(holder))
        assertEquals(0, pool.idleCount)
        assertEquals(1, factory.created.size)
    }
}
