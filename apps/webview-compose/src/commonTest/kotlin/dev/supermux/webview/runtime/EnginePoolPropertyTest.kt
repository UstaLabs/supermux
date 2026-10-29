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
 * activate, deactivate, park their page, adopt a parked one and crash; priority changes, prewarming, memory pressure
 * and budget changes; engines that crash while parked; and creations, resets, evictions and parked pages' saves held
 * open past their bounds. Each seed runs 300 steps. After every step, and once everything has drained, no engine may be
 * lost, held by two holders, or reset or handed on while parked but by adoption, and the budgets must hold, parked
 * engines counted.
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

        /** The engines parked, by the page they were parked as, until the pool reclaims them or a host adopts them. */
        private val parked = HashMap<WebEngine, Spot>()
        private val misuse = mutableListOf<String>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val factory = Factory()

        private fun gate() = CompletableDeferred<Unit>().also { gates += it }

        private fun heldBy(engine: WebEngine): Host? = holders[engine]?.takeIf { it.engine === engine }

        /** A fake that flags a reset or release of an engine a host still holds, or that is parked. */
        private inner class Tracked(val fake: FakeWebEngine) : WebEngine by fake {
            override suspend fun reset(): Boolean {
                heldBy(this)?.let { misuse += "reset of ${fake.id} while ${it.name} holds it" }
                if (this in parked) misuse += "reset of ${fake.id} while parked"
                return fake.reset()
            }

            override fun release() {
                heldBy(this)?.let { misuse += "release of ${fake.id} while ${it.name} holds it" }
                // A parked engine that crashed is released unsaved; a live one only once it was reclaimed.
                if (this in parked && fake.isUsable) misuse += "release of ${fake.id} while parked"
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

        /**
         * A parked page. Some take a while to save when reclaimed: past the pool's bound, their engine must go to no one.
         */
        private inner class Spot(override val key: PageKey) : ParkedPage {
            var reclaimGate: CompletableDeferred<Unit>? = null

            override suspend fun onReclaim(engine: WebEngine) {
                val id = (engine as Tracked).fake.id
                heldBy(engine)?.let { misuse += "reclaim of $id while ${it.name} holds it" }
                if (parked.remove(engine) !== this) misuse += "reclaim of $id, which isn't parked as ${key.contentKey}"
                reclaimGate?.await()
            }
        }

        /**
         * A host-like holder. A [locked] one does everything under its lock, as the WebView's host does. Hosts share pages
         * ([page]) with others of their config, so one may adopt what another parked.
         */
        private inner class Host(
            val name: String,
            val config: EngineConfig,
            val locked: Boolean,
            val page: PageKey,
        ) : LeaseHolder {
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

            suspend fun activate(pool: EnginePool, adopt: Boolean) {
                val key = if (adopt) page else null
                if (locked) {
                    lock.withLock { if (engine == null) take(pool.acquire(this, config, key)) }
                } else {
                    take(pool.acquire(this, config, key))
                }
            }

            /** The view leaves the composition, its page live: parked, unless the pool gives the engine back instead. */
            suspend fun park(pool: EnginePool, slow: Boolean) {
                if (locked) {
                    lock.withLock { if (engine != null) parkIt(pool, slow) }
                } else if (engine != null) {
                    parkIt(pool, slow)
                }
            }

            private suspend fun parkIt(pool: EnginePool, slow: Boolean) {
                val held = engine ?: return
                unbind()
                val spot = Spot(page).also { if (slow) it.reclaimGate = gate() }
                if (pool.park(this, spot)) parked[held] = spot
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

            /**
             * A lockless host may be granted anew while the eviction of its old engine is still on its way. An adopted
             * engine shows its page already: it is neither configured nor loaded again.
             */
            private fun take(result: AcquireResult) {
                if (result !is AcquireResult.Granted || result.engine === engine) return
                unbind()
                val granted = result.engine as Tracked
                heldBy(granted)?.let { misuse += "engine ${granted.fake.id} held by ${it.name} and $name" }
                val spot = parked.remove(granted)
                val adopted = result.adopted
                when {
                    adopted == null && spot != null -> misuse += "$name got ${granted.fake.id} while it was parked"
                    adopted != null && adopted !== spot -> misuse += "$name adopted ${granted.fake.id} as a page it wasn't parked as"
                    adopted != null && (adopted.key != page || granted.fake.config != config) ->
                        misuse += "$name adopted ${granted.fake.id}, another page's or config's"
                }
                holders[granted] = this
                engine = granted
                if (adopted == null) {
                    granted.configure(setup)
                    granted.load(content)
                }
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
            val hosts = List(8) {
                val config = if (it % 3 == 0) html else url
                Host("h$it", config, locked = it < 6, page = PageKey(config.profile, "p${it % 2}"))
            }
            val levels = listOf(PRIORITY_HIDDEN, PRIORITY_VISIBLE, PRIORITY_VISIBLE_EAGER, PRIORITY_FOCUSED)
            val jobs = mutableListOf<Job>()
            var maxLiveEver = maxLive
            try {
                for (step in 0 until steps) {
                    when (random.nextInt(17)) {
                        0, 1 -> {
                            val host = hosts.random(random)
                            host.level = levels.random(random)
                            val adopt = random.nextBoolean()
                            jobs += launch { host.activate(pool, adopt) }
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
                        14, 15 -> {
                            val host = hosts.random(random)
                            val slow = random.nextInt(4) == 0
                            jobs += launch { host.park(pool, slow) }
                        }
                        16 -> (parked.keys.randomOrNull(random) as Tracked?)?.fake?.emit(EngineEvent.Crashed)
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
                if (factory.alive != pool.liveCount + pool.idleCount + pool.parkedCount) {
                    val counts = "${factory.alive} alive, ${pool.liveCount} leased, ${pool.idleCount} spare, ${pool.parkedCount} parked"
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
         * What breaks the pool's promises now, or null: an engine held twice, reset or released while held or parked, or
         * handed on while parked but by adoption; a broken engine contract; more leases and parked pages than the budget
         * or more spares than room, or more engines than both, beyond those whose eviction or reset overran its bound.
         */
        private fun problem(pool: EnginePool, maxIdle: Int, maxLiveEver: Int): String? {
            if (misuse.isNotEmpty()) return misuse.toString()
            if (pool.liveCount + pool.parkedCount > pool.maxLive) {
                return "${pool.liveCount} leased and ${pool.parkedCount} parked, over the budget of ${pool.maxLive}"
            }
            if (pool.idleCount > maxIdle) return "${pool.idleCount} spares, over the room for $maxIdle"
            val bound = maxLiveEver + maxIdle + pool.overdueCount
            if (factory.alive > bound) return "${factory.alive} engines, over $bound"
            val violations = factory.made.flatMap { engine -> engine.fake.violations.map { "${engine.fake.id}: $it" } }
            return if (violations.isEmpty()) null else "violations: $violations"
        }
    }
}
