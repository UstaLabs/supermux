package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val PRIORITY_HIDDEN = 0
internal const val PRIORITY_VISIBLE = 1
internal const val PRIORITY_VISIBLE_EAGER = 2
internal const val PRIORITY_FOCUSED = 3

/** Something that holds a live engine and can be asked to give it back. */
interface LeaseHolder {
    /** Higher wins. Read whenever the pool has to choose whom to evict. */
    val priority: Int

    /**
     * The pool is taking [engine] back: snapshot and save state now. Must not call the pool.
     * - It runs to the end even if the acquire that caused it is cancelled.
     * - Whatever it throws, the pool takes the engine back.
     */
    suspend fun onEvict(engine: WebEngine)
}

sealed interface AcquireResult {
    data class Granted(val engine: WebEngine) : AcquireResult

    /** No engine, because of [reason]. [detail] says more, for logs. */
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : AcquireResult

    /** Over budget, with nobody of lower priority to evict. Wait for a capacity listener call. */
    data object Denied : AcquireResult
}

/**
 * The live engines of one app (spec §4).
 * - At most [maxLive] engines are leased at once. While an acquire creates its engine, or waits for the holder it
 *   evicted to save itself, its slot is reserved and counts against the budget.
 * - Up to [maxIdle] spares, fresh or reset, are kept for reuse.
 * - When over budget, the pool evicts by priority first and least recent use second.
 * - No engine is ever lost: each one the pool creates or takes back ends up leased, a spare, or released, whatever
 *   throws and whoever is cancelled.
 *
 * Main thread only. The mutex only orders suspended operations. Nothing suspends while holding it: engine creation,
 * resets and [LeaseHolder.onEvict] never hold up another caller.
 */
class EnginePool(
    private val factory: WebEngineFactory,
    maxLive: Int,
    maxIdle: Int,
) {
    private class Lease(val engine: WebEngine, val config: EngineConfig, var lastUsed: Long)

    var maxLive: Int = maxLive.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val maxIdle = maxIdle.coerceAtLeast(0)
    private val mutex = Mutex()
    private val live = LinkedHashMap<LeaseHolder, Lease>()
    private val idle = ArrayList<Pair<EngineConfig, WebEngine>>()
    private val listeners = ArrayList<() -> Unit>()
    private var useCounter = 0L

    /** Slots held by acquires that are creating an engine, or waiting for an evicted holder, outside the lock. */
    private var reserved = 0

    /** An acquire was denied while slots were reserved. It may be able to evict the lease one of them becomes. */
    private var deniedWhileReserved = false

    /** The configs [prewarm] is creating a spare for, so concurrent calls create one spare, not one each. */
    private val warming = HashSet<EngineConfig>()

    val liveCount: Int get() = live.size
    val idleCount: Int get() = idle.size

    fun holds(holder: LeaseHolder): Boolean = holder in live

    fun addCapacityListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeCapacityListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * Leases an engine for [config] to [holder], or returns the one it already holds.
     * - Under budget, the engine is a spare for [config], or a new one.
     * - Over budget, it evicts the lowest-priority lease below [holder]'s (least recently used first) and takes over
     *   its slot, and its engine if it was leased for [config]. With nobody to evict, it is [AcquireResult.Denied].
     * - Cancelled meanwhile, the engine it was getting becomes a spare or is released, and the slot is freed.
     */
    suspend fun acquire(holder: LeaseHolder, config: EngineConfig): AcquireResult {
        // An uncontended lock doesn't check for cancellation, and a cancelled caller mustn't evict anyone.
        currentCoroutineContext().ensureActive()
        val victim: Pair<LeaseHolder, Lease>? = mutex.withLock {
            live[holder]?.let { lease ->
                lease.lastUsed = ++useCounter
                return AcquireResult.Granted(lease.engine)
            }
            var candidate: Pair<LeaseHolder, Lease>? = null
            if (live.size + reserved >= maxLive) {
                candidate = lowestPriority { it.priority < holder.priority }
                if (candidate == null) {
                    if (reserved > 0) deniedWhileReserved = true
                    return AcquireResult.Denied
                }
                live.remove(candidate.first)
            }
            reserved++
            candidate
        }
        // A slot is reserved: it becomes [holder]'s lease or is given back, and an engine found for it that doesn't
        // become the lease is kept as a spare or released.
        var inHand: WebEngine? = null
        var slotOpen = true
        var capacityFreed = false
        try {
            // Cancellation waits until there's an engine, so one created or taken back meanwhile is never dropped.
            val found = withContext(NonCancellable) {
                engineFor(config, victim).also { if (it is WebEngineResult.Created) inHand = it.engine }
            }
            currentCoroutineContext().ensureActive()
            val engine = when (found) {
                is WebEngineResult.Created -> found.engine
                is WebEngineResult.Unavailable -> return AcquireResult.Unavailable(found.reason, found.detail)
            }
            return mutex.withLock {
                reserved--
                slotOpen = false
                val existing = live[holder]
                if (existing == null) {
                    live[holder] = Lease(engine, config, ++useCounter)
                    inHand = null
                    capacityFreed = deniedWhileReserved
                    AcquireResult.Granted(engine)
                } else {
                    // Another acquire for [holder] finished first. It keeps that lease; this engine becomes a spare.
                    existing.lastUsed = ++useCounter
                    capacityFreed = true
                    AcquireResult.Granted(existing.engine)
                }
            }
        } finally {
            val unused = inHand
            if (unused != null || slotOpen || capacityFreed) {
                withContext(NonCancellable) {
                    if (unused != null) stash(config, unused)
                    if (slotOpen) mutex.withLock { reserved-- }
                    notifyCapacity()
                }
            }
        }
    }

    /**
     * Gives [holder]'s engine back, reset for reuse. Once begun it isn't cancellable. A no-op while [holder] holds no
     * lease, even if its own acquire is still under way; that acquire completes as usual.
     */
    suspend fun release(holder: LeaseHolder) {
        val lease = mutex.withLock { live.remove(holder) } ?: return
        withContext(NonCancellable) {
            recycle(lease.engine, lease.config)
            notifyCapacity()
        }
    }

    /** Destroys [holder]'s engine instead of recycling it, e.g. after its renderer crashed. */
    suspend fun discard(holder: LeaseHolder) {
        val lease = mutex.withLock { live.remove(holder) } ?: return
        destroy(lease.engine)
        notifyCapacity()
    }

    /**
     * Keeps a spare for [config] ready, so the next activation skips engine construction. The spare replaces the
     * oldest one when there's no room; it is released if a spare for [config] came back while it was being created,
     * or the room was taken.
     */
    suspend fun prewarm(config: EngineConfig) {
        if (maxIdle == 0) return
        val start = mutex.withLock {
            dropUnusableSpares()
            if (config in warming || idle.any { it.first == config }) return@withLock false
            while (idle.size >= maxIdle) destroy(idle.removeAt(0).second)
            warming += config
            true
        }
        if (!start) return
        withContext(NonCancellable) {
            val engine = (create(config) as? WebEngineResult.Created)?.engine
            val kept = mutex.withLock {
                warming -= config
                val keep = engine != null && engine.isUsable && idle.size < maxIdle && idle.none { it.first == config }
                if (keep) idle += config to engine
                keep
            }
            if (engine != null && !kept) destroy(engine)
        }
    }

    /** Drops every spare, then evicts (and destroys) down to half the budget. Never evicts the focused view. */
    suspend fun onMemoryPressure() {
        val spares = mutex.withLock { idle.map { it.second }.also { idle.clear() } }
        spares.forEach(::destroy)
        val target = maxLive / 2
        while (true) {
            val victim = mutex.withLock {
                if (live.size <= target) return@withLock null
                lowestPriority { it.priority < PRIORITY_FOCUSED }?.also { live.remove(it.first) }
            } ?: break
            withContext(NonCancellable) {
                tellEvicted(victim.first, victim.second.engine)
                destroy(victim.second.engine)
                notifyCapacity()
            }
        }
    }

    private fun lowestPriority(eligible: (LeaseHolder) -> Boolean): Pair<LeaseHolder, Lease>? =
        live.entries
            .filter { eligible(it.key) }
            .minWithOrNull(compareBy<Map.Entry<LeaseHolder, Lease>>({ it.key.priority }, { it.value.lastUsed }))
            ?.let { it.key to it.value }

    /**
     * An engine for [config]: the evicted [victim]'s, reset, if it was leased for [config]; else a spare; else a new
     * one. Runs with cancellation held off.
     */
    private suspend fun engineFor(config: EngineConfig, victim: Pair<LeaseHolder, Lease>?): WebEngineResult {
        val reused = victim?.let { (holder, lease) -> reclaim(holder, lease, config) }
            ?: mutex.withLock { takeIdle(config) }
        return if (reused != null) WebEngineResult.Created(reused) else create(config)
    }

    /**
     * Takes [lease]'s engine back from [holder], once it has saved itself. Returns the engine, reset, if it was
     * leased for [config]; otherwise it is recycled. Runs with cancellation held off.
     */
    private suspend fun reclaim(holder: LeaseHolder, lease: Lease, config: EngineConfig): WebEngine? {
        tellEvicted(holder, lease.engine)
        if (lease.config != config) {
            recycle(lease.engine, lease.config)
            return null
        }
        if (resetForReuse(lease.engine)) return lease.engine
        destroy(lease.engine)
        return null
    }

    /** Lets [holder] snapshot and save before its engine goes. A holder that fails still gives the engine back. */
    private suspend fun tellEvicted(holder: LeaseHolder, engine: WebEngine) {
        try {
            holder.onEvict(engine)
        } catch (e: Throwable) {
            // Its snapshot or state is lost, not the engine. With cancellation held off, even a
            // CancellationException is the holder's own.
        }
    }

    /**
     * [WebEngineFactory.create], which never throws, guarded anyway: a throw becomes
     * [UnavailableReason.CreationFailed]. Runs with cancellation held off, so a CancellationException is the
     * factory's own failure.
     */
    private suspend fun create(config: EngineConfig): WebEngineResult = try {
        factory.create(config)
    } catch (e: Throwable) {
        WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.message ?: e.toString())
    }

    /** Keeps [engine] as a spare for [config] if there's room and it resets cleanly; otherwise releases it. */
    private suspend fun recycle(engine: WebEngine, config: EngineConfig) {
        val room = mutex.withLock { idle.size < maxIdle }
        if (room && resetForReuse(engine)) stash(config, engine) else destroy(engine)
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
            false
        }
        return reset && engine.isUsable
    }

    /** Keeps [engine], fresh or reset, as a spare for [config] if it's usable and there's room; else releases it. */
    private suspend fun stash(config: EngineConfig, engine: WebEngine) {
        val kept = mutex.withLock {
            val keep = engine.isUsable && idle.size < maxIdle
            if (keep) idle += config to engine
            keep
        }
        if (!kept) destroy(engine)
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
            // Released as far as the pool is concerned.
        }
    }

    private fun notifyCapacity() {
        deniedWhileReserved = false
        listeners.toList().forEach { it() }
    }
}
