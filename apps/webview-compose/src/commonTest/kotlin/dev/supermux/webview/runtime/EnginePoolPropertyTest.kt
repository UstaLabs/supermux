package dev.supermux.webview.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Random interleavings of everything the pool's callers do: host-like holders, with and without their own lock, that
 * activate, deactivate and crash; priority changes, prewarming, memory pressure and budget changes; and creations,
 * resets and evictions held open past their bounds. Each seed runs 300 steps. After every step, and once everything
 * has drained, no engine may be lost or held by two holders, and the budgets must hold.
 */
class EnginePoolPropertyTest {
    @Test fun seeds1To10() = check(1..10)

    @Test fun seeds11To20() = check(11..20)

    @Test fun seeds21To30() = check(21..30)

    @Test fun seeds31To40() = check(31..40)

    @Test fun seeds41To50() = check(41..50)

    private fun check(seeds: IntRange) = runTest(timeout = 60.seconds) {
        assertEquals(emptyList(), seeds.mapNotNull { Run(it).go(this, steps = 300) })
    }

    private class Run(private val seed: Int) {
        private val random = Random(seed)
        private val url = EngineConfig(WebProfile.Default)
        private val html = EngineConfig(WebProfile.Ephemeral)
        private val setup = PageSetup(
            javaScriptEnabled = true,
            inspectable = false,
            dark = false,
            transparentBackground = false,
            userAgentSuffix = null,
            bridgeOrigins = emptySet(),
        )

        /** Who holds each engine, as the hosts see it. */
        private val holders = HashMap<WebEngine, Host>()
        private val misuse = mutableListOf<String>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val factory = Factory()

        private fun gate() = CompletableDeferred<Unit>().also { gates += it }

        private fun heldBy(engine: WebEngine): Host? = holders[engine]?.takeIf { it.engine === engine }

        /** A fake that flags a reset or release of an engine a host still holds. */
        private inner class Tracked(val fake: FakeWebEngine) : WebEngine by fake {
            override suspend fun reset(): Boolean {
                heldBy(this)?.let { misuse += "reset of ${fake.id} while ${it.name} holds it" }
                return fake.reset()
            }

            override fun release() {
                heldBy(this)?.let { misuse += "release of ${fake.id} while ${it.name} holds it" }
                fake.release()
            }

            @Composable
            override fun Surface(modifier: Modifier) = fake.Surface(modifier)
        }

        /** Holds some creations open, and gives some engines a reset that is held open. */
        private inner class Factory : WebEngineFactory {
            val fakes = FakeWebEngineFactory()
            val made = mutableListOf<Tracked>()
            var holding = true

            override suspend fun create(config: EngineConfig): WebEngineResult {
                if (holding && random.nextInt(10) < 3) gate().await()
                val fake = (fakes.create(config) as WebEngineResult.Created).engine as FakeWebEngine
                if (holding && random.nextInt(10) < 3) fake.resetGate = gate()
                return WebEngineResult.Created(Tracked(fake).also { made += it })
            }

            val alive: Int get() = made.count { !it.fake.released }
        }

        /** A host-like holder. A [locked] one does everything under its lock, as the WebView's host does. */
        private inner class Host(val name: String, val config: EngineConfig, val locked: Boolean) : LeaseHolder {
            var level = PRIORITY_HIDDEN
            override val priority: Int get() = level
            private val lock = Mutex()
            var engine: WebEngine? = null
            var evictGate: CompletableDeferred<Unit>? = null
            private val content =
                if (config == html) WebContent.Html("<p>$name</p>") else WebContent.Url("https://$name.test/")

            override suspend fun onEvict(engine: WebEngine) {
                lock.withLock {
                    if (this.engine !== engine) return@withLock
                    try {
                        evictGate?.await()
                    } finally {
                        unbind()
                    }
                }
            }

            suspend fun activate(pool: EnginePool) {
                if (locked) {
                    lock.withLock { if (engine == null) take(pool.acquire(this, config)) }
                } else {
                    take(pool.acquire(this, config))
                }
            }

            suspend fun deactivate(pool: EnginePool) {
                if (locked) {
                    lock.withLock { if (engine != null) giveBack(pool) }
                } else if (engine != null) {
                    giveBack(pool)
                }
            }

            suspend fun crash(pool: EnginePool) {
                val crashed = engine as? Tracked ?: return
                crashed.fake.emit(EngineEvent.Crashed)
                lock.withLock {
                    if (engine !== crashed) return@withLock
                    unbind()
                    pool.discard(this)
                }
            }

            private suspend fun giveBack(pool: EnginePool) {
                unbind()
                pool.release(this)
            }

            /** A lockless host may be granted anew while the eviction of its old engine is still on its way. */
            private fun take(result: AcquireResult) {
                if (result !is AcquireResult.Granted || result.engine === engine) return
                unbind()
                heldBy(result.engine)?.let {
                    misuse += "engine ${(result.engine as Tracked).fake.id} held by ${it.name} and $name"
                }
                holders[result.engine] = this
                engine = result.engine
                result.engine.configure(setup)
                result.engine.load(content)
            }

            fun unbind() {
                val held = engine ?: return
                if (holders[held] === this) holders.remove(held)
                engine = null
            }
        }

        /** What's wrong with the pool after a run of [steps] random steps, or null. */
        suspend fun go(test: TestScope, steps: Int): String? = with(test) {
            val poolScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val maxLive = 1 + random.nextInt(4)
            val maxIdle = random.nextInt(4)
            val pool = EnginePool(
                factory,
                maxLive,
                maxIdle,
                poolScope,
                clock = { testScheduler.currentTime },
                pressureCooldownMillis = 3_000,
            )
            // The bounds run out often here; what the guards catch is expected.
            pool.onError = {}
            val hosts = List(8) { Host("h$it", if (it % 3 == 0) html else url, locked = it < 6) }
            val levels = listOf(PRIORITY_HIDDEN, PRIORITY_VISIBLE, PRIORITY_VISIBLE_EAGER, PRIORITY_FOCUSED)
            val jobs = mutableListOf<Job>()
            var maxLiveEver = maxLive
            try {
                for (step in 0 until steps) {
                    when (random.nextInt(14)) {
                        0, 1 -> {
                            val host = hosts.random(random)
                            host.level = levels.random(random)
                            jobs += launch { host.activate(pool) }
                        }
                        2 -> {
                            val host = hosts.random(random)
                            jobs += launch { host.deactivate(pool) }
                        }
                        3 -> {
                            val host = hosts.random(random)
                            val before = host.level
                            host.level = levels.random(random)
                            if (host.level < before) pool.priorityLowered()
                        }
                        4 -> pool.touch(hosts.random(random))
                        5 -> jobs += launch { pool.prewarm(if (random.nextBoolean()) url else html) }
                        6 -> gates.filter { !it.isCompleted }.randomOrNull(random)?.complete(Unit)
                        7 -> advanceTimeBy(random.nextLong(0, 3_000))
                        8 -> hosts.random(random).evictGate = if (random.nextBoolean()) gate() else null
                        9 -> hosts.filter { it.engine != null && it.locked }.randomOrNull(random)?.let { host ->
                            jobs += launch { host.crash(pool) }
                        }
                        10 -> repeat(random.nextInt(3)) {
                            gates.filter { !it.isCompleted }.randomOrNull(random)?.complete(Unit)
                        }
                        11 -> pool.onMemoryPressure()
                        12 -> pool.onMemoryRecovered()
                        13 -> {
                            val budget = 1 + random.nextInt(4)
                            maxLiveEver = maxOf(maxLiveEver, budget)
                            jobs += launch { pool.setMaxLive(budget) }
                        }
                    }
                    runCurrent()
                    problem(pool, maxIdle, maxLiveEver)?.let { return "seed $seed, step $step: $it" }
                }
                // Drain: open every gate, and let every bound run out.
                factory.holding = false
                repeat(10) {
                    hosts.forEach { it.evictGate = null }
                    gates.forEach { it.complete(Unit) }
                    runCurrent()
                    advanceTimeBy(60_000)
                    runCurrent()
                }
                pool.onMemoryRecovered()
                runCurrent()
                val stuck = jobs.count { it.isActive }
                if (stuck > 0) return "seed $seed: $stuck jobs stuck"
                if (factory.alive != pool.liveCount + pool.idleCount) {
                    val counts = "${factory.alive} alive, ${pool.liveCount} leased, ${pool.idleCount} spare"
                    return "seed $seed: engines lost: $counts"
                }
                hosts.firstOrNull { (it.engine != null) != pool.holds(it) }?.let {
                    return "seed $seed: ${it.name} holds an engine: ${it.engine != null}; the pool: ${pool.holds(it)}"
                }
                problem(pool, maxIdle, maxLiveEver)?.let { return "seed $seed, drained: $it" }
                null
            } finally {
                gates.forEach { it.complete(Unit) }
                jobs.forEach { it.cancel() }
                poolScope.cancel()
                runCurrent()
            }
        }

        /**
         * What breaks the pool's promises now, or null: an engine held twice or reset or released while held, a
         * broken engine contract, more leases than the budget or more spares than room, or more engines than both,
         * beyond those whose eviction or reset overran its bound.
         */
        private fun problem(pool: EnginePool, maxIdle: Int, maxLiveEver: Int): String? {
            if (misuse.isNotEmpty()) return misuse.toString()
            if (pool.liveCount > pool.maxLive) return "${pool.liveCount} leased, over the budget of ${pool.maxLive}"
            if (pool.idleCount > maxIdle) return "${pool.idleCount} spares, over the room for $maxIdle"
            val bound = maxLiveEver + maxIdle + pool.overdueCount
            if (factory.alive > bound) return "${factory.alive} engines, over $bound"
            val violations = factory.made.flatMap { engine -> engine.fake.violations.map { "${engine.fake.id}: $it" } }
            return if (violations.isEmpty()) null else "violations: $violations"
        }
    }
}
