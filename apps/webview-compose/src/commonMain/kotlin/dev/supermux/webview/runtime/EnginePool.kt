package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal const val PRIORITY_HIDDEN = 0
internal const val PRIORITY_VISIBLE = 1
internal const val PRIORITY_VISIBLE_EAGER = 2
internal const val PRIORITY_FOCUSED = 3

/** Something that holds a live engine and can be asked to give it back. */
internal interface LeaseHolder {
    /**
     * Higher wins. The pool reads it live, whenever it chooses whom to evict, so compute it from current state. When
     * it drops, call [EnginePool.priorityLowered]: a view denied meanwhile may outrank this one now.
     */
    val priority: Int

    /**
     * The pool is taking [engine] back: snapshot and save state now. Must not call the pool.
     * - Stop using [engine] once this returns or throws: the pool takes it back either way.
     * - It has the pool's evict timeout (5 s by default). Then it is cancelled, and the pool takes the engine back.
     * - It runs to the end even if the acquire that caused it is cancelled.
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
 * - At most [maxLive] engines are leased at once, half that during a memory-pressure window. While an acquire creates
 *   its engine, or waits for the holder it evicted, its slot is reserved and counts against the budget.
 * - Up to `maxIdle` spares, fresh or reset, are kept for reuse. Room for a spare is reserved before an engine is reset
 *   or created to fill it, so there are never more than `maxLive + maxIdle` engines.
 * - When over budget, the pool evicts by priority first and least recent use second.
 * - No engine is ever lost: each one the pool creates or takes back ends up leased, a spare, or released, whatever
 *   throws, times out or is cancelled.
 *
 * Main thread only. The mutex only orders suspended operations. Nothing suspends while holding it: engine creation,
 * resets and holders' evictions never hold up another caller.
 *
 * @param pressureCooldownMillis how long a memory-pressure window lasts after the latest [onMemoryPressure], unless
 *   [onMemoryRecovered] ends it first.
 * @param evictTimeoutMillis how long a holder may take to save itself before the pool takes its engine back.
 * @param createTimeoutMillis how long an engine's creation may take before the acquire is
 *   [UnavailableReason.CreationFailed]. An engine that arrives later is released.
 */
class EnginePool(
    private val factory: WebEngineFactory,
    maxLive: Int,
    maxIdle: Int,
    private val pressureCooldownMillis: Long = 30_000,
    private val evictTimeoutMillis: Long = 5_000,
    private val createTimeoutMillis: Long = 20_000,
) {
    private class Lease(val engine: WebEngine, val config: EngineConfig, var lastUsed: Long)

    /** An acquire under way. A second acquire for the same holder waits for its result: null if it was cancelled. */
    private class Pending(val config: EngineConfig) {
        val outcome = CompletableDeferred<AcquireResult?>()
    }

    /** A memory-pressure window. Every [onMemoryPressure] call counts; the latest call's cooldown ends it. */
    private class PressureWindow {
        var calls = 0
        val ended = CompletableDeferred<Unit>()
    }

    /** What an [onMemoryPressure] call takes under the lock. */
    private class PressureStart(
        val window: PressureWindow,
        val call: Int,
        val spares: List<WebEngine>,
        val victims: List<Pair<LeaseHolder, Lease>>,
    )

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
    private val idle = ArrayList<Pair<EngineConfig, WebEngine>>()
    private val pending = HashMap<LeaseHolder, Pending>()
    private val listeners = ArrayList<() -> Unit>()
    private var useCounter = 0L

    /** Slots held by acquires that are creating an engine, or waiting for the holder they evicted. */
    private var reserved = 0

    /** Spare room held for engines being reset, or created, to fill it. */
    private var spareRoomReserved = 0

    /** The configs [prewarm] is creating a spare for. */
    private val warming = HashSet<EngineConfig>()

    private var pressure: PressureWindow? = null

    /** An acquire was denied since capacity listeners were last told. */
    private var deniedSinceNotice = false

    /** An acquire was denied while slots were reserved: it may be able to evict the lease one of them becomes. */
    private var deniedWhileReserved = false

    /**
     * Sees what the pool's guards catch, for logs: failures and timeouts of [WebEngineFactory.create],
     * [WebEngine.reset], [WebEngine.release] and of holders saving themselves before an eviction.
     */
    var onError: ((Throwable) -> Unit)? = null

    val liveCount: Int get() = live.size
    val idleCount: Int get() = idle.size

    /** The budget now: [maxLive], or half of it (at least 1) during a memory-pressure window. */
    private val budget: Int get() = if (pressure == null) maxLive else (maxLive / 2).coerceAtLeast(1)

    /** How many spares may be kept now: none during a memory-pressure window. */
    private val spareLimit: Int get() = if (pressure == null) maxIdle else 0

    internal fun holds(holder: LeaseHolder): Boolean = holder in live

    internal fun addCapacityListener(listener: () -> Unit) {
        listeners += listener
    }

    internal fun removeCapacityListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * Leases an engine for [config] to [holder], or returns the one it already holds.
     * - A holder leases one config at a time: asking for another while it holds or awaits one is a bug.
     * - A second acquire while the first is under way waits for it and shares its result.
     * - Under budget, the engine is a spare for [config], or a new one.
     * - Over budget, it evicts the lowest-priority lease below [holder]'s, least recently used first. It takes a spare
     *   for [config] at once if there is one; otherwise it waits for the victim to save itself and takes its engine if
     *   that was leased for [config] and resets cleanly. With nobody to evict, it is [AcquireResult.Denied].
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
                    launchDetached { withContext(NonCancellable) { evictToSpare(step.victim) } }
                    return AcquireResult.Granted(step.spare)
                }
                is Step.Fill -> return fill(holder, config, step)
            }
        }
    }

    /**
     * Gives [holder]'s engine back, reset for reuse. Once begun it isn't cancellable. A no-op while [holder] holds no
     * lease, even if its own acquire is still under way; that acquire completes as usual.
     */
    internal suspend fun release(holder: LeaseHolder) {
        val lease = mutex.withLock { live.remove(holder) } ?: return
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
     * recently used first, and the engines it takes back may become spares.
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
        withContext(NonCancellable) {
            victims.forEach { (holder, lease) ->
                tellEvicted(holder, lease.engine)
                recycle(lease.engine, lease.config)
            }
        }
    }

    /**
     * Keeps a spare for [config] ready, so the next activation skips engine construction.
     * - Only into free spare room: it never replaces another config's spare.
     * - Only while the budget has room for the spare to be leased, and not during a memory-pressure window.
     * - The new spare is released if one for [config] came back while it was being created.
     */
    suspend fun prewarm(config: EngineConfig) {
        val start = mutex.withLock {
            dropUnusableSpares()
            val wanted = live.size + reserved < budget && config !in warming && idle.none { it.first == config }
            (wanted && reserveSpareRoom()).also { if (it) warming += config }
        }
        if (!start) return
        withContext(NonCancellable) {
            val engine = (create(config) as? WebEngineResult.Created)?.engine
            val kept = mutex.withLock {
                warming -= config
                spareRoomReserved--
                val keep = engine != null && engine.isUsable && idle.size < spareLimit &&
                    idle.none { it.first == config }
                if (keep) idle += config to engine
                keep
            }
            if (engine != null && !kept) destroy(engine)
        }
    }

    /**
     * Memory is short (spec §4). Releases every spare, and evicts and destroys the lowest-priority leases, never the
     * focused view's, until the live and pending ones fit half the budget. The victims are chosen all at once.
     *
     * A window follows. Until [onMemoryRecovered], or [pressureCooldownMillis] after the latest call, the budget stays
     * at half (at least 1), no spares are kept and [prewarm] does nothing, so evicted views can't come straight back.
     * When it ends, capacity listeners are told once.
     *
     * Suspends until the window ends: launch it.
     */
    suspend fun onMemoryPressure() {
        val start = mutex.withLock {
            val window = pressure ?: PressureWindow().also { pressure = it }
            window.calls++
            val spares = idle.map { it.second }
            idle.clear()
            PressureStart(window, window.calls, spares, takeVictims { it.priority < PRIORITY_FOCUSED })
        }
        try {
            withContext(NonCancellable) {
                start.spares.forEach(::destroy)
                start.victims.forEach { (holder, lease) ->
                    tellEvicted(holder, lease.engine)
                    destroy(lease.engine)
                }
            }
            withTimeoutOrNull(pressureCooldownMillis) { start.window.ended.await() }
        } finally {
            // The latest call's cooldown ends the window, unless onMemoryRecovered ended it first.
            if (pressure === start.window && start.window.calls == start.call) endPressure()
        }
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
        if (live.size + reserved < budget) {
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
                    live[holder] = Lease(found.engine, config, ++useCounter)
                    inHand = null
                    notify = deniedWhileReserved
                    AcquireResult.Granted(found.engine)
                }
            }
            result = outcome
            return outcome
        } finally {
            // An engine that didn't become the lease is clean (fresh, reset or a spare): it can be a spare as it is.
            val unused = inHand
            withContext(NonCancellable) {
                if (unused != null) keepAsSpare(config, unused)
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
     * An engine for [config]: the evicted [victim]'s if it was leased for [config] and resets cleanly; else a spare;
     * else a new one. Runs with cancellation held off.
     */
    private suspend fun engineFor(config: EngineConfig, victim: Pair<LeaseHolder, Lease>?): WebEngineResult {
        if (victim != null) {
            val (holder, lease) = victim
            tellEvicted(holder, lease.engine)
            when {
                // Another profile's engine is never handed over. It may still be a spare for its own.
                lease.config != config -> recycle(lease.engine, lease.config)
                resetForReuse(lease.engine) -> return WebEngineResult.Created(lease.engine)
                else -> destroy(lease.engine)
            }
        }
        mutex.withLock { takeIdle(config) }?.let { return WebEngineResult.Created(it) }
        return create(config)
    }

    /** Lets [victim]'s holder save itself, then puts its engine, reset, into the spare room reserved for it. */
    private suspend fun evictToSpare(victim: Pair<LeaseHolder, Lease>) {
        val (holder, lease) = victim
        tellEvicted(holder, lease.engine)
        fillReservedRoom(lease.config, lease.engine, reset = true)
    }

    /** Takes leases out of [live], lowest priority and least recent first, until they and reserved slots fit. */
    private fun takeVictims(eligible: (LeaseHolder) -> Boolean): List<Pair<LeaseHolder, Lease>> {
        val excess = live.size + reserved - budget
        if (excess <= 0) return emptyList()
        val victims = live.entries
            .filter { eligible(it.key) }
            .sortedWith(compareBy<Map.Entry<LeaseHolder, Lease>>({ it.key.priority }, { it.value.lastUsed }))
            .take(excess)
            .map { it.key to it.value }
        victims.forEach { live.remove(it.first) }
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
        window.ended.complete(Unit)
        notifyCapacity()
    }

    /**
     * Lets [holder] snapshot and save before its engine goes, for at most [evictTimeoutMillis]. Whatever happens, the
     * pool takes the engine back. Runs with cancellation held off, so a CancellationException is the holder's own, or
     * the timeout's.
     */
    private suspend fun tellEvicted(holder: LeaseHolder, engine: WebEngine) {
        try {
            withTimeout(evictTimeoutMillis) { holder.onEvict(engine) }
        } catch (e: Throwable) {
            report(e)
        }
    }

    /**
     * [WebEngineFactory.create], bounded by [createTimeoutMillis] and guarded: a throw or a timeout is
     * [UnavailableReason.CreationFailed]. The factory runs in a coroutine of its own, so the wait ends at the timeout
     * even for one that ignores cancellation; an engine that arrives after that is released.
     */
    private suspend fun create(config: EngineConfig): WebEngineResult {
        val creation = Creation()
        val job = launchDetached(CoroutineStart.UNDISPATCHED) {
            val result = try {
                factory.create(config)
            } catch (e: Throwable) {
                // After the timeout, a CancellationException is the pool's own doing.
                if (!creation.abandoned) report(e)
                WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.message ?: e.toString())
            }
            if (creation.abandoned) {
                (result as? WebEngineResult.Created)?.engine?.let(::destroy)
            } else {
                creation.result = result
                creation.arrived.complete(Unit)
            }
        }
        withTimeoutOrNull(createTimeoutMillis) { creation.arrived.await() }
        creation.result?.let { return it }
        creation.abandoned = true
        job.cancel()
        report(IllegalStateException("engine creation timed out after $createTimeoutMillis ms"))
        return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "timed out")
    }

    /** Keeps [engine] as a spare for [config] if there's room, reserved before its reset, and it resets cleanly. */
    private suspend fun recycle(engine: WebEngine, config: EngineConfig) {
        if (mutex.withLock { reserveSpareRoom() }) fillReservedRoom(config, engine, reset = true) else destroy(engine)
    }

    /** Keeps [engine], which is clean (fresh, reset or a spare), as a spare for [config] if there's room. */
    private suspend fun keepAsSpare(config: EngineConfig, engine: WebEngine) {
        if (mutex.withLock { reserveSpareRoom() }) fillReservedRoom(config, engine, reset = false) else destroy(engine)
    }

    private fun reserveSpareRoom(): Boolean {
        val room = idle.size + spareRoomReserved < spareLimit
        if (room) spareRoomReserved++
        return room
    }

    /**
     * Puts [engine] into spare room reserved for it, once it has reset if [reset]. Releases it instead if it isn't
     * usable or clean, or spares were stopped meanwhile.
     */
    private suspend fun fillReservedRoom(config: EngineConfig, engine: WebEngine, reset: Boolean) {
        val clean = if (reset) resetForReuse(engine) else engine.isUsable
        val kept = mutex.withLock {
            spareRoomReserved--
            val keep = clean && engine.isUsable && idle.size < spareLimit
            if (keep) idle += config to engine
            keep
        }
        if (!kept) destroy(engine)
    }

    /**
     * Readies [engine] for another lease: true only if it is usable, its reset succeeds, and it is still usable after.
     * A reset that throws failed. Runs with cancellation held off: the runtime never cancels a reset.
     */
    private suspend fun resetForReuse(engine: WebEngine): Boolean {
        if (!engine.isUsable) return false
        val reset = try {
            engine.reset()
        } catch (e: Throwable) {
            report(e)
            false
        }
        return reset && engine.isUsable
    }

    /** A spare for [config], if there's one. Spares that crashed while idle are released on the way. */
    private fun takeIdle(config: EngineConfig): WebEngine? {
        dropUnusableSpares()
        val index = idle.indexOfFirst { it.first == config }
        return if (index >= 0) idle.removeAt(index).second else null
    }

    private fun dropUnusableSpares() {
        val spares = idle.iterator()
        while (spares.hasNext()) {
            val engine = spares.next().second
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

    /**
     * Runs [block] in a coroutine of its own, on the caller's dispatcher, so it isn't cancelled with the caller and
     * can outlive it.
     */
    private suspend fun launchDetached(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend () -> Unit): Job =
        CoroutineScope(currentCoroutineContext().minusKey(Job)).launch(start = start) {
            try {
                block()
            } catch (e: Throwable) {
                if (e !is CancellationException) report(e)
            }
        }

    private fun report(e: Throwable) {
        try {
            onError?.invoke(e)
        } catch (ignored: Throwable) {
            // An error hook that fails has nowhere left to report to.
        }
    }

    private fun notifyCapacity() {
        deniedSinceNotice = false
        deniedWhileReserved = false
        listeners.toList().forEach { it() }
    }
}
