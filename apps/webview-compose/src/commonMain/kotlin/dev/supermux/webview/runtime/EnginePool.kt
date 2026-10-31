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

/**
 * A live page whose view left the composition, parked in the pool with its engine ([EnginePool.park]): no holder has
 * it, and its engine isn't reset, so the next view of the page can adopt it as it is ([EnginePool.acquire]).
 */
internal interface ParkedPage {
    /**
     * The page: its content in its resolved profile. Only a view of this page, with an equal engine config, adopts
     * it.
     */
    val key: PageKey

    /**
     * The pool is taking [engine] back, to reset it for another view or release it: save what the page has now. As
     * [LeaseHolder.onEvict] does, it must not call the pool, it has the pool's evict timeout, after which it is
     * cancelled and [engine] is released once it returns, never handed on, and it runs in the pool's scope.
     */
    suspend fun onReclaim(engine: WebEngine)
}

internal sealed interface AcquireResult {
    /**
     * [engine] is the holder's now. [adopted] is the parked page it already shows, on the grant that adopted it: the
     * holder takes the page over as it is. Otherwise the engine is fresh, a spare or reset, and shows nothing.
     */
    data class Granted(val engine: WebEngine, val adopted: ParkedPage? = null) : AcquireResult

    /** No engine, because of [reason]. [detail] says more, for logs. */
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : AcquireResult

    /** Over budget, with nobody of lower priority to evict. Wait for a capacity listener call. */
    data object Denied : AcquireResult
}

/**
 * The live engines of one app (spec §4).
 * - At most [maxLive] engines are leased or parked at once, half that during a memory-pressure window. A slot whose
 *   engine is being created, or being taken back from its holder or parked page, counts against the budget until
 *   that's done.
 * - Up to `maxIdle` spares, fresh or reset, are kept for reuse. Room for a spare is reserved before an engine is reset
 *   or created to fill it. So there are never more than `maxLive + maxIdle` engines, besides those whose eviction or
 *   reset overran its bound, which are released once that call returns.
 * - When the spare room is full, an engine given back replaces another config's spare only once that spare has sat
 *   unused for `spareStaleMillis`, the one idle longest first. Younger spares stay, so configs that take turns, like
 *   alternating pages of Html and Url content, reuse their engines instead of trading spares.
 * - When over budget, the pool evicts by priority first and least recent use second.
 * - A holder whose view leaves may park its engine instead of giving it back ([park]): unreset, keeping the page it
 *   shows, for the next view of that page to adopt ([acquire]). A parked engine holds its slot at the lowest priority,
 *   below every lease, hidden ones included: it is reclaimed first, the one parked longest first, whenever a view needs
 *   a slot, the budget shrinks or memory runs short, and by itself once it has been parked `parkedReleaseMillis` with
 *   no view adopting it. Reclaiming lets the page save itself ([ParkedPage.onReclaim]), bounded as an eviction is,
 *   then resets the engine as a released one is. A parked engine that dies (it says so through
 *   [WebEngine.onUnusable]) is released at once, with nothing left to save.
 * - No engine is ever lost or shared: each one the pool creates or takes back ends up leased, a spare, parked or
 *   released, whatever throws, hangs or is cancelled; a holder's engine goes to no one else before its eviction
 *   returns, and a parked one to no one before its page saved itself, but the one view that adopts it.
 *
 * Main thread only. Holders' evictions, resets and creations run in [scope], bounded in time; tearing the scope
 * down cancels them. The mutex only orders suspended operations: nothing suspends while holding it.
 *
 * Apps read it ([liveCount], [idleCount], [parkedCount], [budget], [maxLive]) and control it through the runtime:
 * memory pressure and errors go through [WebViewRuntime.onMemoryPressure], [WebViewRuntime.onMemoryRecovered] and
 * [WebViewRuntime.onError], which also halve the snapshot cache and forward the pool's errors, a cached process's light
 * trims through [WebViewRuntime.onBackgroundTrim] ([trim]), and the budget is [RuntimeConfig.maxLive].
 *
 * @param scope where the pool's own work runs. The runtime passes its scope.
 * @param clock milliseconds, for [prewarm]'s quiet period, the age of spares and how long pages have been parked.
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
 * @param parkedReleaseMillis how long a parked page keeps its engine while no view adopts it, from when it was parked:
 *   then it is reclaimed, as a view needing its slot would reclaim it. 10 minutes, as long as a hidden view keeps its
 *   engine ([RuntimeConfig.hiddenReleaseMillis], which the runtime passes).
 */
public class EnginePool(
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
    private val parkedReleaseMillis: Long = 600_000,
) {
    private class Lease(val engine: WebEngine, val config: EngineConfig, var lastUsed: Long)

    /** A spare, and when it became one, on [clock]: how long it has sat unused decides whether it may be replaced. */
    private class Spare(val config: EngineConfig, val engine: WebEngine, val keptAt: Long)

    /**
     * A parked page and its unreset engine, and when it was parked, on [clock]: it expires [parkedReleaseMillis] later.
     */
    private class Parked(val page: ParkedPage, val config: EngineConfig, val engine: WebEngine, val parkedAt: Long)

    /**
     * An engine the pool takes back before it hands it on or lets it go: an evicted lease's, whose holder saves itself
     * ([LeaseHolder.onEvict]), or a reclaimed parked page's, which saves itself ([ParkedPage.onReclaim]). [overrun]
     * says what [save] failed to do in time, for reports.
     */
    private class Victim(
        val engine: WebEngine,
        val config: EngineConfig,
        val overrun: (timeoutMillis: Long) -> String,
        val save: suspend (WebEngine) -> Unit,
    )

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
        class TookSpare(val victim: Victim, val spare: WebEngine) : Step

        /** A slot is reserved: fill it, with the victim's engine or a spare or a new one. */
        class Fill(val victim: Victim?, val pending: Pending) : Step
    }

    /** The budget without memory pressure. Change it with [setMaxLive]. */
    public var maxLive: Int = maxLive.coerceAtLeast(1)
        private set

    private val maxIdle = maxIdle.coerceAtLeast(0)
    private val mutex = Mutex()
    private val live = LinkedHashMap<LeaseHolder, Lease>()
    private val idle = ArrayList<Spare>()

    /** Parked pages, the one parked longest first: so the first is always the next to expire. */
    private val parked = ArrayList<Parked>()

    /** Sleeps until the page parked longest expires ([expireParked]); null while nothing is parked. */
    private var expiry: Job? = null
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
     * listeners that throw. The runtime forwards it to [WebViewRuntime.onError], which apps set.
     */
    internal var onError: ((Throwable) -> Unit)? = null

    /**
     * Called when a memory-pressure window ends, on [onMemoryRecovered] or at the end of its cooldown, before capacity
     * listeners are told. The runtime gives its snapshot cache the whole budget back then.
     */
    internal var onPressureEnded: (() -> Unit)? = null

    public val liveCount: Int get() = live.size
    public val idleCount: Int get() = idle.size

    /** Live pages whose views left, parked for the next view of the page ([park]). Each holds a slot of [budget]. */
    public val parkedCount: Int get() = parked.size

    /** Engines given up on after their eviction or reset overran its bound, not yet released. */
    internal val overdueCount: Int get() = overdue

    /** The budget now: [maxLive], or half of it (at least 1) during a memory-pressure window ([onMemoryPressure]). */
    public val budget: Int get() = if (pressure == null) maxLive else (maxLive / 2).coerceAtLeast(1)

    /** How many spares may be kept now: none during a memory-pressure window. */
    private val spareLimit: Int get() = if (pressure == null) maxIdle else 0

    /** Slots counted against the budget: leases, parked pages, slots being filled, and engines being taken back. */
    private val slotsTaken: Int get() = live.size + parked.size + reserved + evicting

    internal fun holds(holder: LeaseHolder): Boolean = holder in live

    /** Who holds a lease now: the views the runtime tells when it forgets pages, and tests. */
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
     * - With a [page], it first adopts a parked page ([park]) of that page and of [config], one [accept] takes (called
     *   under the pool's lock: it must not call the pool), the one parked last first: [AcquireResult.Granted.adopted]
     *   says so. The engine is the holder's as it was left, in the slot it held, whatever the budget. Only one acquire
     *   adopts a given page.
     * - Under budget, the engine is a spare for [config], or a new one.
     * - Over budget, it reclaims the page parked longest, if any; otherwise it evicts the lowest-priority lease below
     *   [holder]'s, least recently used first. It takes a spare for [config] at once if there is one; otherwise it
     *   waits for the victim to save itself and takes its engine if that was for [config] and resets cleanly. With
     *   nobody to evict, it is [AcquireResult.Denied].
     * - If the budget shrank while it was getting an engine, it is [AcquireResult.Denied] too.
     * - Cancelled meanwhile, the engine it was getting becomes a spare or is released, and the slot is freed.
     */
    internal suspend fun acquire(
        holder: LeaseHolder,
        config: EngineConfig,
        page: PageKey? = null,
        accept: (ParkedPage) -> Boolean = { true },
    ): AcquireResult {
        while (true) {
            // An uncontended lock doesn't check for cancellation, and a cancelled caller mustn't evict anyone.
            currentCoroutineContext().ensureActive()
            when (val step = mutex.withLock { plan(holder, config, page, accept) }) {
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

    /**
     * [holder]'s view leaves with its page live: its engine stays as it is, parked as [page], for the next view of that
     * page to adopt ([acquire]). True if it was parked. Capacity listeners are told: a parked page gives its slot to
     * any view that needs one.
     * - It holds its slot, at the lowest priority, and is reclaimed first ([ParkedPage.onReclaim], then a reset).
     * - No view adopting it within `parkedReleaseMillis`, it is reclaimed then, as for a view that needs its slot.
     * - Its engine dying meanwhile ([WebEngine.onUnusable], which the pool sets while it is parked), it is released at
     *   once.
     * - During a memory-pressure window nothing is parked, and an engine that isn't usable isn't either: it is given
     *   back as by [release] instead, and this is false. So it is when [holder] holds no lease (one taken back
     *   meanwhile).
     */
    internal suspend fun park(holder: LeaseHolder, page: ParkedPage): Boolean {
        var kept = false
        val given = mutex.withLock {
            val lease = live.remove(holder) ?: return false
            if (pressure == null && lease.engine.isUsable) {
                parked += Parked(page, lease.config, lease.engine, clock())
                watch(lease.engine)
                if (expiry == null) expiry = scope.launch { expireParked() }
                kept = true
                null
            } else {
                lastGivenBack[lease.config] = clock()
                lease
            }
        }
        withContext(NonCancellable) {
            if (given != null) recycle(given.engine, given.config)
            notifyCapacity()
        }
        return kept
    }

    /**
     * Gives back the parked pages [which] picks, unsaved: their engines are reset for reuse, or released. For pages
     * that must not come back: a logout ([WebViewRuntime.clear]), content that is gone ([WebViewRuntime.forget]).
     */
    internal suspend fun unpark(which: (PageKey) -> Boolean) {
        val gone = mutex.withLock {
            val picked = parked.filter { which(it.page.key) }
            parked.removeAll { it in picked }
            picked.forEach {
                unwatch(it.engine)
                lastGivenBack[it.config] = clock()
            }
            picked
        }
        if (gone.isEmpty()) return
        withContext(NonCancellable) {
            gone.forEach { recycle(it.engine, it.config) }
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
    internal suspend fun setMaxLive(value: Int) {
        val victims = mutex.withLock {
            val before = budget
            maxLive = value.coerceAtLeast(1)
            if (budget > before) null else takeVictims(allParked = false) { true }
        }
        if (victims == null) {
            notifyCapacity()
            return
        }
        victims.forEach { evictInBackground(it, keep = true) }
    }

    /**
     * Keeps a spare for [config] ready at cold start, so the first activations skip engine construction. It acts only
     * when the pool has no spare for [config] and got no engine of [config] back in the last `prewarmQuietMillis`:
     * after that, recycling supplies the spares.
     * - Only into free spare room: it never replaces another config's spare.
     * - Only while the budget has room for the spare to be leased, and not during a memory-pressure window.
     * - The new spare is released if one for [config] came back while it was being created.
     */
    public suspend fun prewarm(config: EngineConfig) {
        val start = mutex.withLock {
            dropUnusableSpares()
            dropUnusableParked()
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
     * Memory is short (spec §4). Releases every spare, reclaims every parked page, and evicts the lowest-priority
     * leases, hidden ones first and never the focused view's, until the live and pending ones fit half the budget. The
     * victims are chosen all at once and taken back together, in the pool's scope; their engines are destroyed. This
     * returns at once.
     *
     * A window follows. Until [onMemoryRecovered], or `pressureCooldownMillis` after the latest call, the budget stays
     * at half (at least 1), no spares are kept, nothing is parked and [prewarm] does nothing, so evicted views can't
     * come straight back; an engine created over that budget is denied. When the window ends, capacity listeners are
     * told once.
     */
    internal suspend fun onMemoryPressure() {
        val (spares, victims) = mutex.withLock {
            val window = pressure ?: PressureWindow().also { pressure = it }
            window.timer?.cancel()
            window.timer = scope.launch {
                delay(pressureCooldownMillis)
                if (pressure === window) endPressure()
            }
            val spares = idle.map { it.engine }
            idle.clear()
            spares to takeVictims(allParked = true) { it.priority < PRIORITY_FOCUSED }
        }
        spares.forEach(::destroy)
        victims.forEach { evictInBackground(it, keep = false) }
    }

    /**
     * A light trim, for when memory isn't short yet but the app's process may be cached (Android's background trims, at
     * an app switch): releases the spares, and reclaims every parked page, which saves itself as it would for a view
     * needing its slot (bounded as an eviction is) before its engine is released, not reset into a spare. Every lease
     * stays, hidden ones included, and no window follows: unlike [onMemoryPressure], the budget is whole, spares are kept
     * and pages are parked again as soon as views give engines back. This returns at once; the pages save themselves in
     * the pool's scope.
     */
    internal suspend fun trim() {
        val (spares, victims) = mutex.withLock {
            val spares = idle.map { it.engine }
            idle.clear()
            val victims = ArrayList<Victim>(parked.size)
            while (parked.isNotEmpty()) victims += takeOldestParked() ?: break
            evicting += victims.size
            spares to victims
        }
        spares.forEach(::destroy)
        victims.forEach { evictInBackground(it, keep = false) }
    }

    /** Memory is fine again: ends a memory-pressure window now. */
    internal fun onMemoryRecovered() {
        endPressure()
    }

    /** Decides, under the lock, what [acquire] does for [holder], and takes what that needs. */
    private fun plan(holder: LeaseHolder, config: EngineConfig, page: PageKey?, accept: (ParkedPage) -> Boolean): Step {
        live[holder]?.let { lease ->
            checkOneConfig(lease.config, config)
            lease.lastUsed = ++useCounter
            return Step.Done(AcquireResult.Granted(lease.engine))
        }
        pending[holder]?.let { first ->
            checkOneConfig(first.config, config)
            return Step.Join(first)
        }
        dropUnusableParked()
        if (page != null) {
            val index = parked.indexOfLast { it.page.key == page && it.config == config && accept(it.page) }
            if (index >= 0) {
                val found = parked.removeAt(index)
                unwatch(found.engine)
                live[holder] = Lease(found.engine, config, ++useCounter)
                return Step.Done(AcquireResult.Granted(found.engine, adopted = found.page))
            }
        }
        if (slotsTaken < budget) {
            takeIdle(config)?.let { spare ->
                live[holder] = Lease(spare, config, ++useCounter)
                return Step.Done(AcquireResult.Granted(spare))
            }
            return reserve(holder, config, victim = null)
        }
        val victim = takeOldestParked() ?: lowestPriority { it.priority < holder.priority }?.let { (owner, lease) ->
            live.remove(owner)
            lastGivenBack[lease.config] = clock()
            evicted(owner, lease)
        }
        if (victim == null) {
            deniedSinceNotice = true
            if (reserved > 0) deniedWhileReserved = true
            return Step.Done(AcquireResult.Denied)
        }
        takeIdle(config)?.let { spare ->
            // The room the spare leaves is the victim's engine's, once its holder has saved and it has reset.
            live[holder] = Lease(spare, config, ++useCounter)
            spareRoomReserved++
            return Step.TookSpare(victim, spare)
        }
        return reserve(holder, config, victim)
    }

    private fun reserve(holder: LeaseHolder, config: EngineConfig, victim: Victim?): Step.Fill {
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
                    if (live.size + parked.size + reserved + 1 > budget) {
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
     * An engine for [config]: the [victim]'s if it was for [config], gave it back in time and resets cleanly; else a
     * spare; else a new one. Runs with cancellation held off.
     */
    private suspend fun engineFor(config: EngineConfig, victim: Victim?): WebEngineResult {
        if (victim != null) {
            // One that wasn't given back in time is never handed over: its holder, or its page, may still be using it.
            if (tellEvicted(victim)) {
                if (victim.config == config) {
                    if (resetOrRelease(victim.engine)) return WebEngineResult.Created(victim.engine)
                } else {
                    // Another profile's engine is never handed over, but may be a spare for its own. A spare for
                    // this one, if one came back meanwhile, is taken first.
                    val spare = mutex.withLock { takeIdle(config) }
                    recycle(victim.engine, victim.config)
                    if (spare != null) return WebEngineResult.Created(spare)
                }
            }
        }
        mutex.withLock { takeIdle(config) }?.let { return WebEngineResult.Created(it) }
        return create(config)
    }

    /** Takes [victim]'s engine back into the spare room reserved for it: reset and kept, or released. */
    private suspend fun evictIntoSpareRoom(victim: Victim) {
        if (tellEvicted(victim)) {
            fillReservedRoom(victim.config, victim.engine, reset = true)
        } else {
            mutex.withLock { spareRoomReserved-- }
        }
    }

    /**
     * Takes [victim]'s engine back in the pool's scope. Its slot counts against the budget, in [evicting], until the
     * engine is a spare (if [keep] and there's room) or is gone.
     */
    private fun evictInBackground(victim: Victim, keep: Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                val gaveBack = tellEvicted(victim)
                val room = mutex.withLock {
                    evicting--
                    gaveBack && keep && reserveSpareRoom(victim.config, replace = true)
                }
                when {
                    room -> fillReservedRoom(victim.config, victim.engine, reset = true)
                    gaveBack -> destroy(victim.engine)
                }
                capacityMayHaveFreed()
            }
        }
    }

    /**
     * Takes engines back until they and the other slots fit the budget: parked pages first, the one parked longest
     * first, all of them if [allParked]; then leases out of [live], lowest priority and least recently used first, of
     * those [eligible]. They count in [evicting] until their engines are gone.
     */
    private fun takeVictims(allParked: Boolean, eligible: (LeaseHolder) -> Boolean): List<Victim> {
        var excess = slotsTaken - budget
        val victims = ArrayList<Victim>()
        while (parked.isNotEmpty() && (allParked || excess > 0)) {
            victims += takeOldestParked() ?: break
            excess--
        }
        if (excess > 0) {
            val leases = live.entries
                .filter { eligible(it.key) }
                .sortedWith(compareBy<Map.Entry<LeaseHolder, Lease>>({ it.key.priority }, { it.value.lastUsed }))
                .take(excess)
                .map { it.key to it.value }
            for ((holder, lease) in leases) {
                live.remove(holder)
                lastGivenBack[lease.config] = clock()
                victims += evicted(holder, lease)
            }
        }
        evicting += victims.size
        return victims
    }

    /** The page parked longest, taken out of [parked] to be reclaimed, or null when none is. */
    private fun takeOldestParked(): Victim? {
        if (parked.isEmpty()) return null
        val oldest = parked.removeAt(0)
        unwatch(oldest.engine)
        lastGivenBack[oldest.config] = clock()
        val page = oldest.page
        // A page whose renderer died has nothing to save.
        val overrun = { millis: Long -> "a parked page took longer than $millis ms to save itself" }
        return Victim(oldest.engine, oldest.config, overrun) { engine -> if (engine.isUsable) page.onReclaim(engine) }
    }

    /**
     * Reclaims each parked page once it has been parked [parkedReleaseMillis] with no view adopting it, the one parked
     * longest first, as a view needing its slot would: it saves itself, bounded, then its engine is reset into spare
     * room, or released ([evictInBackground]). Runs in [scope] while anything is parked, sleeping until the next page
     * is due by [clock]: a page adopted or reclaimed meanwhile only makes it look again.
     */
    private suspend fun expireParked() {
        while (true) {
            val (due, wait) = mutex.withLock {
                val now = clock()
                val due = ArrayList<Victim>()
                while (parked.isNotEmpty() && now - parked[0].parkedAt >= parkedReleaseMillis) {
                    due += takeOldestParked() ?: break
                }
                evicting += due.size
                val next = parked.firstOrNull()
                // Under the lock: a page parked after this starts the next timer.
                if (next == null) expiry = null
                due to next?.let { parkedReleaseMillis - (now - it.parkedAt) }
            }
            due.forEach { evictInBackground(it, keep = true) }
            delay(wait ?: return)
        }
    }

    /** [lease], taken from [holder], as a victim that [holder] gives back. */
    private fun evicted(holder: LeaseHolder, lease: Lease): Victim {
        val overrun = { millis: Long -> "a holder took longer than $millis ms to give its engine back" }
        return Victim(lease.engine, lease.config, overrun, holder::onEvict)
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
        onPressureEnded?.invoke()
        notifyCapacity()
    }

    /**
     * Asks [victim] to save itself and give its engine back (its holder's [LeaseHolder.onEvict], or its parked page's
     * [ParkedPage.onReclaim]), in the pool's scope, and waits up to [evictTimeoutMillis]. True if it returned or threw
     * in time. Otherwise the call is cancelled and the engine is overdue: never reused, and released once the call
     * returns, since its holder or page may still be using it; this returns false.
     */
    private suspend fun tellEvicted(victim: Victim): Boolean {
        val engine = victim.engine
        var gaveUp = false
        val call = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                victim.save(engine)
            } catch (e: Throwable) {
                // Once the pool gave up, it cancelled the call itself.
                if (!gaveUp && scope.isActive) report(e)
            }
        }
        if (withTimeoutOrNull(evictTimeoutMillis) { call.join() } != null) return true
        gaveUp = true
        call.cancel()
        releaseWhenDone(engine, call)
        report(IllegalStateException(victim.overrun(evictTimeoutMillis)))
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

    /** Releases parked engines whose renderer died: there's no page left to adopt or save. */
    private fun dropUnusableParked() {
        val pages = parked.iterator()
        while (pages.hasNext()) {
            val engine = pages.next().engine
            if (!engine.isUsable) {
                pages.remove()
                unwatch(engine)
                destroy(engine)
            }
        }
    }

    /**
     * Listens for parked [engine] dying ([WebEngine.onUnusable]): it is released then, at once, rather than at the next
     * acquire or when its park expires. [unwatch] stops listening as it leaves [parked].
     */
    private fun watch(engine: WebEngine) = tell(engine) {
        // The engine calls this from inside its own crash path: it is released once that has returned.
        scope.launch {
            mutex.withLock { dropUnusableParked() }
            capacityMayHaveFreed()
        }
    }

    private fun unwatch(engine: WebEngine) = tell(engine, null)

    /** Sets [engine]'s [WebEngine.onUnusable] to [listener]. An engine that throws keeps what it had, and is reported. */
    private fun tell(engine: WebEngine, listener: (() -> Unit)?) {
        try {
            engine.onUnusable = listener
        } catch (e: Throwable) {
            report(e)
        }
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
