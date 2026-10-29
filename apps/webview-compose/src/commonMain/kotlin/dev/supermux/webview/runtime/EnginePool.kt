package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.monotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val PRIORITY_HIDDEN = 0
internal const val PRIORITY_VISIBLE = 1
internal const val PRIORITY_VISIBLE_EAGER = 2
internal const val PRIORITY_FOCUSED = 3

/** Something that holds a live engine and can be asked to give it back. */
internal interface LeaseHolder {
    /**
     * Higher wins. The pool reads it live, whenever it chooses whom to evict or whom to tell about capacity first, so
     * compute it from current state. When it drops, call [EnginePool.priorityLowered]: a view denied meanwhile may
     * outrank this one now.
     */
    val priority: Int

    /**
     * The pool is taking [engine] back: snapshot and save state now. Must not call the pool.
     * - It may arrive late: after the holder released [engine], or leased another. Act only while still holding
     *   [engine], and check that inside the holder's own lock.
     * - Stop using [engine] once this returns or throws: the pool takes it back either way.
     * - It has the pool's evict timeout (5 s by default). Then it is cancelled, and [engine] is released once it
     *   returns; it never goes to anyone else.
     * - It runs in the pool's scope, so cancelling the acquire that caused it doesn't cancel it.
     */
    suspend fun onEvict(engine: WebEngine)
}

internal sealed interface AcquireResult {
    data class Granted(val engine: WebEngine) : AcquireResult

    /** No engine, because of [reason]. [detail] says more, for logs. */
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : AcquireResult

    /** Over budget, with nobody of lower priority to evict. Wait for a capacity listener call. */
    data object Denied : AcquireResult
}

/**
 * The live engines of one app (spec §4).
 * - At most [maxLive] engines are leased at once, half that during a memory-pressure window. A slot whose engine is
 *   being created, or whose holder is being evicted, counts against the budget until that's done.
 * - Up to `maxIdle` spares, fresh or reset, are kept for reuse. Room for a spare is reserved before an engine is reset
 *   or created to fill it. So there are never more than `maxLive + maxIdle` engines, besides those whose eviction or
 *   reset overran its bound, which are released once that call returns.
 * - When the spare room is full, an engine given back replaces another config's spare only once that spare has sat
 *   unused for `spareStaleMillis`, the one idle longest first. Younger spares stay, so configs that take turns, like
 *   alternating pages of Html and Url content, reuse their engines instead of trading spares.
 * - When over budget, the pool evicts by priority first and least recent use second.
 * - No engine is ever lost or shared: each one the pool creates or takes back ends up leased, a spare, or released,
 *   whatever throws, hangs or is cancelled; and a holder's engine goes to no one else before its eviction returns.
 *
 * Main thread only. Holders' evictions, resets and creations run in [scope], bounded in time; tearing the scope
 * down cancels them. The mutex only orders suspended operations: nothing suspends while holding it.
 *
 * @param scope where the pool's own work runs. The runtime passes its scope.
 * @param clock milliseconds, for [prewarm]'s quiet period and the age of spares.
 * @param pressureCooldownMillis how long a memory-pressure window lasts after the latest [onMemoryPressure], unless
 *   [onMemoryRecovered] ends it first.
 * @param evictTimeoutMillis how long a holder may take to save itself before the pool gives up on its engine.
 * @param resetTimeoutMillis how long a reset may take before the pool gives up on the engine.
 * @param createTimeoutMillis how long a creation may take before the acquire is [UnavailableReason.CreationFailed].
 *   An engine that arrives later becomes a spare if there's room.
 * @param prewarmQuietMillis how long after an engine of a config was given back [prewarm] still leaves that config
 *   to recycling.
 * @param spareStaleMillis how long another config's spare must sit unused before an engine given back to a full spare
 *   room may replace it.
 */
class EnginePool(
    private val factory: WebEngineFactory,
    maxLive: Int,
    maxIdle: Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = monotonicClock(),
    private val pressureCooldownMillis: Long = 30_000,
    private val evictTimeoutMillis: Long = 5_000,
    private val resetTimeoutMillis: Long = 10_000,
    private val createTimeoutMillis: Long = 20_000,
    private val prewarmQuietMillis: Long = 10_000,
    private val spareStaleMillis: Long = 10_000,
) {
    private class Lease(val engine: WebEngine, val config: EngineConfig, var lastUsed: Long)

    /** A spare, and when it became one, on [clock]: how long it has sat unused decides whether it may be replaced. */
    private class Spare(val config: EngineConfig, val engine: WebEngine, val keptAt: Long)

    /** An acquire under way. A second acquire for the same holder waits for its result: null if it was cancelled. */
    private class Pending(val config: EngineConfig) {
        val outcome = CompletableDeferred<AcquireResult?>()
    }

    /** A memory-pressure window, which ends when its latest timer does. */
    private class PressureWindow {
        var timer: Job? = null
    }

    /** One engine creation, which its acquire stops waiting for at the timeout. */
    private class Creation {
        var result: WebEngineResult? = null
        var abandoned = false
        val arrived = CompletableDeferred<Unit>()
    }

    /** What an acquire does, decided under the lock. */
    private sealed interface Step {
        class Done(val result: AcquireResult) : Step

        /** Wait for another acquire for the same holder. */
        class Join(val first: Pending) : Step

        /** Over budget, a spare was taken at once. The victim saves itself afterwards; its engine is the new spare. */
        class TookSpare(val victim: Pair<LeaseHolder, Lease>, val spare: WebEngine) : Step

        /** A slot is reserved: fill it, with the victim's engine or a spare or a new one. */
        class Fill(val victim: Pair<LeaseHolder, Lease>?, val pending: Pending) : Step
    }

    /** The budget without memory pressure. Change it with [setMaxLive]. */
    var maxLive: Int = maxLive.coerceAtLeast(1)
        private set

    private val maxIdle = maxIdle.coerceAtLeast(0)
    private val mutex = Mutex()
    private val live = LinkedHashMap<LeaseHolder, Lease>()
    private val idle = ArrayList<Spare>()
    private val pending = HashMap<LeaseHolder, Pending>()
    private val listeners = LinkedHashMap<LeaseHolder, () -> Unit>()
    private val lastGivenBack = HashMap<EngineConfig, Long>()
    private val warming = HashSet<EngineConfig>()
    private var useCounter = 0L

    /** Slots held by acquires that are creating an engine, or waiting for the holder they evicted. */
    private var reserved = 0

    /** Slots of leases being evicted for memory pressure or a lower budget, until their engines are gone. */
    private var evicting = 0

    /** Spare room held for engines being reset, or created, to fill it. */
    private var spareRoomReserved = 0

    /** Engines whose holder's eviction or whose reset overran its bound. Each is released once that call returns. */
    private var overdue = 0

    private var pressure: PressureWindow? = null

    /** An acquire was denied since capacity listeners were last told. */
    private var deniedSinceNotice = false

    /** An acquire was denied while slots were reserved: it may be able to evict the lease one of them becomes. */
    private var deniedWhileReserved = false

    /**
     * Sees what the pool's guards catch, for logs: failures and timeouts of [WebEngineFactory.create],
     * [WebEngine.reset], [WebEngine.release] and of holders saving themselves before an eviction, and capacity
     * listeners that throw.
     */
    var onError: ((Throwable) -> Unit)? = null

    val liveCount: Int get() = live.size
    val idleCount: Int get() = idle.size

    /** Engines given up on after their eviction or reset overran its bound, not yet released. */
    internal val overdueCount: Int get() = overdue

    /** The budget now: [maxLive], or half of it (at least 1) during a memory-pressure window. */
    private val budget: Int get() = if (pressure == null) maxLive else (maxLive / 2).coerceAtLeast(1)

    /** How many spares may be kept now: none during a memory-pressure window. */
    private val spareLimit: Int get() = if (pressure == null) maxIdle else 0

    /** Slots counted against the budget: leases, slots being filled, and leases being evicted. */
    private val slotsTaken: Int get() = live.size + reserved + evicting

    internal fun holds(holder: LeaseHolder): Boolean = holder in live

    /** Who holds a lease now. For tests. */
    internal val leaseHolders: List<LeaseHolder> get() = live.keys.toList()

    /** How many capacity listeners are registered. Views listen only while denied, so this stays small. For tests. */
    internal val capacityListenerCount: Int get() = listeners.size

    /**
     * Calls [listener] when capacity may have become available, for example after a denied acquire. Listeners are
     * called highest priority first, by their holders' priorities, so the view that should win tries first.
     */
    internal fun addCapacityListener(holder: LeaseHolder, listener: () -> Unit) {
        listeners[holder] = listener
    }

    internal fun removeCapacityListener(holder: LeaseHolder) {
        listeners.remove(holder)
    }

    /**
     * Leases an engine for [config] to [holder], or returns the one it already holds.
     * - A holder leases one config at a time: asking for another while it holds or awaits one is a bug.
     * - A second acquire while the first is under way waits for it, then checks again.
     * - Under budget, the engine is a spare for [config], or a new one.
     * - Over budget, it evicts the lowest-priority lease below [holder]'s, least recently used first. It takes a spare
     *   for [config] at once if there is one; otherwise it waits for the victim to save itself and takes its engine if
     *   that was leased for [config] and resets cleanly. With nobody to evict, it is [AcquireResult.Denied].
     * - If the budget shrank while it was getting an engine, it is [AcquireResult.Denied] too.
     * - Cancelled meanwhile, the engine it was getting becomes a spare or is released, and the slot is freed.
     */
    internal suspend fun acquire(holder: LeaseHolder, config: EngineConfig): AcquireResult {
        while (true) {
            // An uncontended lock doesn't check for cancellation, and a cancelled caller mustn't evict anyone.
            currentCoroutineContext().ensureActive()
            when (val step = mutex.withLock { plan(holder, config) }) {
                is Step.Done -> return step.result
                is Step.Join -> {
                    // A grant is looked up again, in case that lease ended meanwhile. Null: the first was cancelled.
                    val first = step.first.outcome.await()
                    if (first is AcquireResult.Unavailable) return first
                }
                is Step.TookSpare -> {
                    scope.launch { withContext(NonCancellable) { evictIntoSpareRoom(step.victim) } }
                    return AcquireResult.Granted(step.spare)
                }
                is Step.Fill -> return fill(holder, config, step)
            }
        }
    }

    /**
     * Gives [holder]'s engine back. It is reset for reuse in the pool's scope; this doesn't wait for that. A no-op
     * while [holder] holds no lease, even if its own acquire is still under way; that acquire completes as usual.
     */
    internal suspend fun release(holder: LeaseHolder) {
        val lease = mutex.withLock { live.remove(holder)?.also { lastGivenBack[it.config] = clock() } } ?: return
        withContext(NonCancellable) {
            recycle(lease.engine, lease.config)
            notifyCapacity()
        }
    }

    /** Destroys [holder]'s engine instead of recycling it, e.g. after its renderer crashed. */
    internal suspend fun discard(holder: LeaseHolder) {
        val lease = mutex.withLock { live.remove(holder) } ?: return
        destroy(lease.engine)
        notifyCapacity()
    }

    /** [holder] was used: the user interacted with it, or it came into view. Its peers are evicted before it. */
    internal fun touch(holder: LeaseHolder) {
        live[holder]?.let { it.lastUsed = ++useCounter }
    }

    /**
     * A holder's priority dropped. If an acquire was denied since capacity listeners were last told, they are told
     * now: the denied view may outrank that holder.
     */
    internal fun priorityLowered() {
        if (deniedSinceNotice) notifyCapacity()
    }

    /**
     * Changes the budget. Raising it tells capacity listeners. Lowering it evicts down to it, lowest priority and least
     * recently used first, in the pool's scope; the engines it takes back may become spares.
     */
    suspend fun setMaxLive(value: Int) {
        val victims = mutex.withLock {
            val before = budget
            maxLive = value.coerceAtLeast(1)
            if (budget > before) null else takeVictims { true }
        }
        if (victims == null) {
            notifyCapacity()
            return
        }
        victims.forEach { (holder, lease) -> evictInBackground(holder, lease, keep = true) }
    }

    /**
     * Keeps a spare for [config] ready at cold start, so the first activations skip engine construction. It acts only
     * when the pool has no spare for [config] and got no engine of [config] back in the last `prewarmQuietMillis`:
     * after that, recycling supplies the spares.
     * - Only into free spare room: it never replaces another config's spare.
     * - Only while the budget has room for the spare to be leased, and not during a memory-pressure window.
     * - The new spare is released if one for [config] came back while it was being created.
     */
    suspend fun prewarm(config: EngineConfig) {
        val start = mutex.withLock {
            dropUnusableSpares()
            val quiet = lastGivenBack[config]?.let { clock() - it >= prewarmQuietMillis } ?: true
            val wanted = quiet && slotsTaken < budget && config !in warming && idle.none { it.config == config }
            (wanted && reserveSpareRoom(config, replace = false)).also { if (it) warming += config }
        }
        if (!start) return
        withContext(NonCancellable) {
            val engine = (create(config) as? WebEngineResult.Created)?.engine
            val kept = mutex.withLock {
                warming -= config
                spareRoomReserved--
                val keep = engine != null && engine.isUsable && idle.size < spareLimit &&
                    idle.none { it.config == config }
                if (keep) idle += Spare(config, engine, clock())
                keep
            }
            if (engine != null && !kept) destroy(engine)
        }
    }

    /**
     * Memory is short (spec §4). Releases every spare, and evicts the lowest-priority leases, never the focused
     * view's, until the live and pending ones fit half the budget. The victims are chosen all at once and evicted
     * together, in the pool's scope; their engines are destroyed. This returns at once.
     *
     * A window follows. Until [onMemoryRecovered], or `pressureCooldownMillis` after the latest call, the budget stays
     * at half (at least 1), no spares are kept and [prewarm] does nothing, so evicted views can't come straight back;
     * an engine created over that budget is denied. When the window ends, capacity listeners are told once.
     */
    suspend fun onMemoryPressure() {
        val (spares, victims) = mutex.withLock {
            val window = pressure ?: PressureWindow().also { pressure = it }
            window.timer?.cancel()
            window.timer = scope.launch {
                delay(pressureCooldownMillis)
                if (pressure === window) endPressure()
            }
            val spares = idle.map { it.engine }
            idle.clear()
            spares to takeVictims { it.priority < PRIORITY_FOCUSED }
        }
        spares.forEach(::destroy)
        victims.forEach { (holder, lease) -> evictInBackground(holder, lease, keep = false) }
    }

    /** Memory is fine again: ends a memory-pressure window now. */
    fun onMemoryRecovered() {
        endPressure()
    }

    /** Decides, under the lock, what [acquire] does for [holder], and takes what that needs. */
    private fun plan(holder: LeaseHolder, config: EngineConfig): Step {
        live[holder]?.let { lease ->
            checkOneConfig(lease.config, config)
            lease.lastUsed = ++useCounter
            return Step.Done(AcquireResult.Granted(lease.engine))
        }
        pending[holder]?.let { first ->
            checkOneConfig(first.config, config)
            return Step.Join(first)
        }
        if (slotsTaken < budget) {
            takeIdle(config)?.let { spare ->
                live[holder] = Lease(spare, config, ++useCounter)
                return Step.Done(AcquireResult.Granted(spare))
            }
            return reserve(holder, config, victim = null)
        }
        val victim = lowestPriority { it.priority < holder.priority }
        if (victim == null) {
            deniedSinceNotice = true
            if (reserved > 0) deniedWhileReserved = true
            return Step.Done(AcquireResult.Denied)
        }
        live.remove(victim.first)
        lastGivenBack[victim.second.config] = clock()
        takeIdle(config)?.let { spare ->
            // The room the spare leaves is the victim's engine's, once its holder has saved and it has reset.
            live[holder] = Lease(spare, config, ++useCounter)
            spareRoomReserved++
            return Step.TookSpare(victim, spare)
        }
        return reserve(holder, config, victim)
    }

    private fun reserve(holder: LeaseHolder, config: EngineConfig, victim: Pair<LeaseHolder, Lease>?): Step.Fill {
        reserved++
        val first = Pending(config)
        pending[holder] = first
        return Step.Fill(victim, first)
    }

    private fun checkOneConfig(held: EngineConfig, wanted: EngineConfig) = check(held == wanted) {
        "A holder leases one engine config at a time: it holds or awaits one for $held. Release it before acquiring " +
            "one for $wanted."
    }

    /** Fills the slot [step] reserved for [holder], or gives it back if that fails or the caller is cancelled. */
    private suspend fun fill(holder: LeaseHolder, config: EngineConfig, step: Step.Fill): AcquireResult {
        var inHand: WebEngine? = null
        var slotOpen = true
        var notify = false
        var result: AcquireResult? = null
        try {
            // Cancellation waits until there's an engine, so one created or taken back meanwhile is never dropped.
            val found = withContext(NonCancellable) {
                engineFor(config, step.victim).also { if (it is WebEngineResult.Created) inHand = it.engine }
            }
            currentCoroutineContext().ensureActive()
            val outcome = when (found) {
                is WebEngineResult.Unavailable -> AcquireResult.Unavailable(found.reason, found.detail)
                is WebEngineResult.Created -> mutex.withLock {
                    reserved--
                    slotOpen = false
                    if (live.size + reserved + 1 > budget) {
                        // The budget shrank meanwhile: the engine becomes a spare or goes, and the caller hears when
                        // there's room again.
                        deniedSinceNotice = true
                        AcquireResult.Denied
                    } else {
                        live[holder] = Lease(found.engine, config, ++useCounter)
                        inHand = null
                        notify = deniedWhileReserved
                        AcquireResult.Granted(found.engine)
                    }
                }
            }
            result = outcome
            return outcome
        } finally {
            // An engine that didn't become the lease is clean (fresh, reset or a spare): it can be a spare as it is.
            val unused = inHand
            withContext(NonCancellable) {
                if (unused != null) keepAsSpare(config, unused, replace = true)
                mutex.withLock {
                    if (slotOpen) {
                        reserved--
                        notify = true
                    }
                    if (pending[holder] === step.pending) pending.remove(holder)
                }
                step.pending.outcome.complete(result)
                if (notify) notifyCapacity()
            }
        }
    }

    /**
     * An engine for [config]: the evicted [victim]'s if it was leased for [config], gave it back in time and resets
     * cleanly; else a spare; else a new one. Runs with cancellation held off.
     */
    private suspend fun engineFor(config: EngineConfig, victim: Pair<LeaseHolder, Lease>?): WebEngineResult {
        if (victim != null) {
            val (holder, lease) = victim
            // One that wasn't given back in time is never handed over: its holder may still be using it.
            if (tellEvicted(holder, lease.engine)) {
                if (lease.config == config) {
                    if (resetOrRelease(lease.engine)) return WebEngineResult.Created(lease.engine)
                } else {
                    // Another profile's engine is never handed over, but may be a spare for its own. A spare for
                    // this one, if one came back meanwhile, is taken first.
                    val spare = mutex.withLock { takeIdle(config) }
                    recycle(lease.engine, lease.config)
                    if (spare != null) return WebEngineResult.Created(spare)
                }
            }
        }
        mutex.withLock { takeIdle(config) }?.let { return WebEngineResult.Created(it) }
        return create(config)
    }

    /** Evicts [victim] into the spare room reserved for its engine: reset and kept, or released. */
    private suspend fun evictIntoSpareRoom(victim: Pair<LeaseHolder, Lease>) {
        val (holder, lease) = victim
        if (tellEvicted(holder, lease.engine)) {
            fillReservedRoom(lease.config, lease.engine, reset = true)
        } else {
            mutex.withLock { spareRoomReserved-- }
        }
    }

    /**
     * Evicts [lease] from [holder] in the pool's scope. Its slot counts against the budget, in [evicting], until its
     * engine is a spare (if [keep] and there's room) or is gone.
     */
    private fun evictInBackground(holder: LeaseHolder, lease: Lease, keep: Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                val gaveBack = tellEvicted(holder, lease.engine)
                val room = mutex.withLock {
                    evicting--
                    gaveBack && keep && reserveSpareRoom(lease.config, replace = true)
                }
                when {
                    room -> fillReservedRoom(lease.config, lease.engine, reset = true)
                    gaveBack -> destroy(lease.engine)
                }
                capacityMayHaveFreed()
            }
        }
    }

    /**
     * Takes leases out of [live], lowest priority and least recently used first, until they and the other slots fit
     * the budget. They count in [evicting] until their engines are gone.
     */
    private fun takeVictims(eligible: (LeaseHolder) -> Boolean): List<Pair<LeaseHolder, Lease>> {
        val excess = slotsTaken - budget
        if (excess <= 0) return emptyList()
        val victims = live.entries
            .filter { eligible(it.key) }
            .sortedWith(compareBy<Map.Entry<LeaseHolder, Lease>>({ it.key.priority }, { it.value.lastUsed }))
            .take(excess)
            .map { it.key to it.value }
        for ((holder, lease) in victims) {
            live.remove(holder)
            lastGivenBack[lease.config] = clock()
        }
        evicting += victims.size
        return victims
    }

    private fun lowestPriority(eligible: (LeaseHolder) -> Boolean): Pair<LeaseHolder, Lease>? =
        live.entries
            .filter { eligible(it.key) }
            .minWithOrNull(compareBy<Map.Entry<LeaseHolder, Lease>>({ it.key.priority }, { it.value.lastUsed }))
            ?.let { it.key to it.value }

    private fun endPressure() {
        val window = pressure ?: return
        pressure = null
        window.timer?.cancel()
        notifyCapacity()
    }

    /**
     * Asks [holder] to give [engine] back, in the pool's scope, and waits up to [evictTimeoutMillis]. True if it
     * returned or threw in time. Otherwise the call is cancelled and [engine] is overdue: never reused, and released
     * once the call returns, since its holder may still be using it; this returns false.
     */
    private suspend fun tellEvicted(holder: LeaseHolder, engine: WebEngine): Boolean {
        var gaveUp = false
        val call = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                holder.onEvict(engine)
            } catch (e: Throwable) {
                // Once the pool gave up, it cancelled the call itself.
                if (!gaveUp && scope.isActive) report(e)
            }
        }
        if (withTimeoutOrNull(evictTimeoutMillis) { call.join() } != null) return true
        gaveUp = true
        call.cancel()
        releaseWhenDone(engine, call)
        report(IllegalStateException("a holder took longer than $evictTimeoutMillis ms to give its engine back"))
        return false
    }

    /**
     * Resets [engine] for another lease, in the pool's scope, and waits up to [resetTimeoutMillis]. True only if it is
     * usable, the reset succeeds in time, and it is still usable after. Otherwise [engine] is released: now, or, after
     * an overrun, once the reset returns, since the runtime never cancels a reset.
     */
    private suspend fun resetOrRelease(engine: WebEngine): Boolean {
        if (!engine.isUsable) {
            destroy(engine)
            return false
        }
        var succeeded = false
        val call = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            succeeded = try {
                withContext(NonCancellable) { engine.reset() }
            } catch (e: Throwable) {
                report(e)
                false
            }
        }
        if (withTimeoutOrNull(resetTimeoutMillis) { call.join() } == null) {
            releaseWhenDone(engine, call)
            report(IllegalStateException("a reset took longer than $resetTimeoutMillis ms"))
            return false
        }
        if (succeeded && engine.isUsable) return true
        destroy(engine)
        return false
    }

    /** [engine] overran its bound in [call]: it no longer counts anywhere, and is released once [call] completes. */
    private fun releaseWhenDone(engine: WebEngine, call: Job) {
        overdue++
        call.invokeOnCompletion {
            overdue--
            destroy(engine)
        }
    }

    /**
     * [WebEngineFactory.create], in the pool's scope, bounded by [createTimeoutMillis] and guarded: a throw or a
     * timeout is [UnavailableReason.CreationFailed]. The wait ends at the timeout even for a factory that ignores
     * cancellation; an engine that arrives after that becomes a spare if there's room, so a retry after a slow start
     * finds it ready.
     */
    private suspend fun create(config: EngineConfig): WebEngineResult {
        val creation = Creation()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                factory.create(config)
            } catch (e: Throwable) {
                if (!creation.abandoned && scope.isActive) report(e)
                WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.message ?: e.toString())
            }
            if (!creation.abandoned) {
                creation.result = result
                creation.arrived.complete(Unit)
            } else if (result is WebEngineResult.Created) {
                withContext(NonCancellable) { keepAsSpare(config, result.engine, replace = false) }
            }
        }
        withTimeoutOrNull(createTimeoutMillis) { creation.arrived.await() }
        creation.result?.let { return it }
        creation.abandoned = true
        report(IllegalStateException("an engine took longer than $createTimeoutMillis ms to create"))
        return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "timed out")
    }

    /**
     * Keeps [engine] as a spare for [config], reset in the pool's scope, if there's room; otherwise releases it now,
     * unreset. This doesn't wait for the reset.
     */
    private suspend fun recycle(engine: WebEngine, config: EngineConfig) {
        if (!mutex.withLock { reserveSpareRoom(config, replace = true) }) {
            destroy(engine)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { fillReservedRoom(config, engine, reset = true) }
        }
    }

    /** Keeps [engine], which is clean (fresh, reset or a spare), as a spare for [config] if there's room. */
    private suspend fun keepAsSpare(config: EngineConfig, engine: WebEngine, replace: Boolean) {
        if (mutex.withLock { reserveSpareRoom(config, replace) }) {
            fillReservedRoom(config, engine, reset = false)
        } else {
            destroy(engine)
        }
    }

    /**
     * Reserves room for a spare of [config]: free room or, if [replace], the room of another config's spare that has
     * sat unused for [spareStaleMillis], the one idle longest, which is released. False if there's neither: a younger
     * spare stays, so configs that take turns keep reusing their own engines.
     */
    private fun reserveSpareRoom(config: EngineConfig, replace: Boolean): Boolean {
        if (idle.size + spareRoomReserved < spareLimit) {
            spareRoomReserved++
            return true
        }
        if (!replace) return false
        val now = clock()
        val stale = idle.filter { it.config != config && now - it.keptAt >= spareStaleMillis }
            .minByOrNull { it.keptAt } ?: return false
        idle.remove(stale)
        destroy(stale.engine)
        spareRoomReserved++
        return true
    }

    /**
     * Puts [engine] into spare room reserved for it, once it has reset if [reset]. Releases it instead if it isn't
     * usable or clean, or spares were stopped meanwhile.
     */
    private suspend fun fillReservedRoom(config: EngineConfig, engine: WebEngine, reset: Boolean) {
        val clean = if (reset) {
            resetOrRelease(engine)
        } else {
            engine.isUsable.also { if (!it) destroy(engine) }
        }
        val kept = mutex.withLock {
            spareRoomReserved--
            val keep = clean && engine.isUsable && idle.size < spareLimit
            if (keep) idle += Spare(config, engine, clock())
            keep
        }
        if (clean && !kept) destroy(engine)
    }

    /** A spare for [config], if there's one. Spares that crashed while idle are released on the way. */
    private fun takeIdle(config: EngineConfig): WebEngine? {
        dropUnusableSpares()
        val index = idle.indexOfFirst { it.config == config }
        return if (index >= 0) idle.removeAt(index).engine else null
    }

    private fun dropUnusableSpares() {
        val spares = idle.iterator()
        while (spares.hasNext()) {
            val engine = spares.next().engine
            if (!engine.isUsable) {
                spares.remove()
                destroy(engine)
            }
        }
    }

    /** Releases [engine]. If that throws, there's nothing more to do with it: it's out of the pool either way. */
    private fun destroy(engine: WebEngine) {
        try {
            engine.release()
        } catch (e: Throwable) {
            report(e)
        }
    }

    private fun report(e: Throwable) {
        try {
            onError?.invoke(e)
        } catch (ignored: Throwable) {
            // An error hook that fails has nowhere left to report to.
        }
    }

    /** A slot came free: if someone was denied since the last notice, tell them. */
    private fun capacityMayHaveFreed() {
        if (deniedSinceNotice && slotsTaken < budget) notifyCapacity()
    }

    /** Tells every capacity listener, highest priority first. One that throws doesn't stop the others. */
    private fun notifyCapacity() {
        deniedSinceNotice = false
        deniedWhileReserved = false
        val inOrder = listeners.entries.sortedByDescending { it.key.priority }.map { it.value }
        for (listener in inOrder) {
            try {
                listener()
            } catch (e: Throwable) {
                report(e)
            }
        }
    }
}
