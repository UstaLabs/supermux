package dev.supermux.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a [TerminalSession] refused a non-blocking enqueue.
 *
 * - [QUEUE_FULL]: the mailbox (or the input byte budget) is full. The event was NOT delivered; the
 *   caller decides whether to drop it or retry later. Dropping local input is always better than
 *   blocking a UI thread.
 * - [CLOSED]: [TerminalSession.close] already ran (or the owner loop failed); nothing more will be
 *   processed.
 */
enum class RejectionReason { QUEUE_FULL, CLOSED }

/**
 * One renderer's claim on a session's published frames; see [TerminalSession.attachRenderer].
 *
 * Hold it while the surface is on screen and [close] it when it is not. Closing is idempotent and
 * non-blocking, and closing one lease never stops the frames another surface is still holding open.
 *
 * Idempotence is ATOMIC, not merely a flag check: a Compose `onDispose` and a host's own cleanup
 * can race on the same lease, and a check-then-act would let both of them release it — one lease,
 * two decrements, and the session stops publishing for a SIBLING surface that is still on screen.
 * The compare-and-set makes exactly one caller the releaser; every other close is a no-op.
 */
@OptIn(ExperimentalAtomicApi::class)
class RendererLease internal constructor(private val session: TerminalSession) : AutoCloseable {
    private val open = AtomicBoolean(true)

    /** True until this lease has been released. */
    val active: Boolean get() = open.load()

    override fun close() {
        // Exactly one caller wins the transition true -> false, and only that one decrements.
        if (!open.compareAndSet(expectedValue = true, newValue = false)) return
        session.releaseRenderer()
    }
}

/** Result of a non-blocking [TerminalSession] enqueue. */
sealed interface EnqueueResult {
    data object Accepted : EnqueueResult
    data class Rejected(val reason: RejectionReason) : EnqueueResult

    val accepted: Boolean get() = this is Accepted
}

/**
 * Bounds and cadence of one [TerminalSession]. All of them are hard bounds: the session never
 * allocates an unbounded mailbox and never lets a fast producer grow memory without limit.
 *
 * - [maxPendingOutputBytes]: soft cap on the bytes accepted by [TerminalSession.receive] but not
 *   yet fed to the engine. `receive` suspends while the budget is exhausted (backpressure onto the
 *   transport), so a single chunk larger than the budget is still accepted once the queue drains —
 *   the cap is never exceeded by more than one chunk.
 * - [maxPendingInputBytes]: budget for local input (keys, mouse, paste, …). Exhausting it makes the
 *   non-blocking enqueues return [EnqueueResult.Rejected]; suspending ones ([TerminalSession.paste])
 *   wait.
 * - [mailboxCapacity]: how many commands (of any kind) may queue before `receive` suspends and the
 *   non-blocking enqueues are rejected.
 * - [maxCommandsPerBatch] / [maxBytesPerBatch]: bounded work per turn of the owner loop. Once a
 *   batch hits either limit the loop yields, which is what keeps a burst of output from freezing the
 *   browser event loop (wasmJs runs the loop on it).
 * - [holdTimeout]: how long a synchronized-output (mode 2026) frame may stay held before the owner
 *   loop reads with `breakHold = true`. The engine has NO clock — this loop is the clock.
 * - [holdPollInterval]: while a hold is active, how often the loop re-reads to notice that the
 *   program ended the hold on its own.
 */
data class TerminalSessionConfig(
    val maxPendingOutputBytes: Int = 1 shl 20,
    val maxPendingInputBytes: Int = 64 * 1024,
    val mailboxCapacity: Int = 256,
    val maxCommandsPerBatch: Int = 64,
    val maxBytesPerBatch: Int = 256 * 1024,
    val holdTimeout: Duration = 1000.milliseconds,
    val holdPollInterval: Duration = 16.milliseconds,
) {
    init {
        require(maxPendingOutputBytes > 0) { "maxPendingOutputBytes must be positive" }
        require(maxPendingInputBytes > 0) { "maxPendingInputBytes must be positive" }
        require(mailboxCapacity > 0) { "mailboxCapacity must be positive" }
        require(maxCommandsPerBatch > 0) { "maxCommandsPerBatch must be positive" }
        require(maxBytesPerBatch > 0) { "maxBytesPerBatch must be positive" }
        require(holdTimeout > Duration.ZERO) { "holdTimeout must be positive" }
        require(holdPollInterval > Duration.ZERO) { "holdPollInterval must be positive" }
    }
}

/**
 * Serialized owner of one [TerminalEngine]: a bounded mailbox, ONE coroutine and a
 * [StateFlow] of published frames.
 *
 * Every engine call happens on that single coroutine, so the engine's "externally serialized"
 * requirement is satisfied by construction and no UI draw ever holds a lock across native parsing.
 * Public methods only enqueue (or await a reply); they never touch the engine.
 *
 * **Cadence.** The loop feeds every queued chunk in order, then publishes at most ONE frame that has
 * not been acknowledged. Output keeps being parsed while a frame is in flight; the engine merges the
 * dirty rows, so the frame published after [acknowledge] carries everything that changed since the
 * previous acknowledged one. A host that never acknowledges simply stops receiving frames — it never
 * blocks the terminal.
 *
 * **Scope.** No UI, no transport, no SSH, no PTY: the session neither opens nor closes a WebSocket,
 * and it never emits an "exit"/"closed" event of its own. [close] stopping the local engine says
 * nothing about the remote program.
 *
 * **Failure.** Recoverable engine errors ([TerminalNativeException]) go to the host's
 * `onEngineError` and the loop carries on. Anything else stops the owner: the engine is closed, the
 * mailbox is closed and every queued command is failed, so suspending calls throw that error (and
 * a suspended [paste] / [selectedText] / [receive] fails instead of hanging) while the non-blocking
 * enqueues return [EnqueueResult.Rejected] with [RejectionReason.CLOSED]. [close] rethrows it. It is
 * also published on [failure], which is the only way a renderer that never awaits anything can tell
 * a dead terminal from an idle one.
 *
 * Create one with [open]; always [close] it (it owns native memory).
 */
class TerminalSession private constructor(
    private val config: TerminalSessionConfig,
    private val effects: (TerminalEffect) -> Unit,
    private val onEngineError: (Throwable) -> Unit,
    private val timeSource: TimeSource,
) {
    // ------------------------------------------------------------------ mailbox / budgets ----

    private val mailbox = Channel<Command>(config.mailboxCapacity)

    /** Conflated wake-up for the owner loop: every producer pokes it after enqueuing. */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    /** Newest acknowledged generation. Conflated on purpose: only the newest ack can matter. */
    private val acks = Channel<Long>(Channel.CONFLATED)

    /** Newest requested rendering state (see [setRenderingEnabled]). */
    private val renderingRequests = Channel<Boolean>(Channel.CONFLATED)

    /**
     * Renderer attach (`true`) / release (`false`) deltas; see [attachRenderer].
     *
     * UNLIMITED, not conflated: a count cannot be rebuilt from the newest delta. It is closed by
     * [terminate], so a lease released after the session died simply fails to enqueue.
     */
    private val leaseRequests = Channel<Boolean>(Channel.UNLIMITED)

    /** Pending "make the next frame full" request (see [requestFullFrame]). */
    private val fullFrameRequests = Channel<Unit>(Channel.CONFLATED)

    private val outputBudget = ByteBudget(config.maxPendingOutputBytes)
    private val inputBudget = ByteBudget(config.maxPendingInputBytes)

    private lateinit var scope: CoroutineScope
    private var owner: Job? = null

    @Volatile
    private var state: MutableStateFlow<TerminalViewport>? = null

    /**
     * The frames this session publishes. The value is always the newest PUBLISHED frame; the next
     * one appears only after [acknowledge] of the current [TerminalViewport.generation].
     *
     * Frames after the first are usually partial ([TerminalViewport.full] = false): they carry only
     * the rows that changed since the last acknowledged frame, so a renderer keeps its own row
     * model and patches it.
     */
    val viewports: StateFlow<TerminalViewport>
        get() = checkNotNull(state) { "TerminalSession is not open" }

    private val failureState = MutableStateFlow<Throwable?>(null)

    /**
     * The error that stopped the owner loop, or null while the session is healthy.
     *
     * Observable on purpose: a renderer that only uses the NON-BLOCKING calls (`key`, `mouse`,
     * `acknowledge`, …) never awaits anything, so without this flow a fatal engine failure would
     * only show up as a screen that silently stopped updating. Collect it and surface the error;
     * [close] still rethrows it and every suspending call still throws it.
     *
     * It is set exactly once, on the owner coroutine, before the mailbox is drained — so a
     * collector that sees it non-null also knows every queued reply has been (or is about to be)
     * failed. An ordinary [close] leaves it null.
     */
    val failure: StateFlow<Throwable?> get() = failureState

    /** Internal alias of [failureState]'s value; the loop reads/writes the failure through it. */
    private var fatal: Throwable?
        get() = failureState.value
        set(value) { failureState.value = value }

    @Volatile
    private var closedByHost = false

    // ------------------------------------------------------------------ public API ----

    /**
     * Server (pty) output. Suspends while the pending-output budget
     * ([TerminalSessionConfig.maxPendingOutputBytes]) is exhausted — that is the backpressure the
     * transport is expected to honour; do not wrap it in a fire-and-forget launch.
     *
     * Bytes are fed to the engine in submission order, interleaved with [reset] / [resize] exactly
     * as submitted.
     */
    suspend fun receive(bytes: ByteArray, origin: OutputOrigin = OutputOrigin.LIVE) {
        if (bytes.isEmpty()) return
        val permits = outputBudget.acquire(bytes.size)
        try {
            send(Command.Feed(bytes, origin, permits))
        } catch (t: Throwable) {
            outputBudget.release(permits)
            throw t
        }
    }

    /** Full reset (RIS). Ordered with [receive]: output submitted before it is parsed before it. */
    suspend fun reset() = send(Command.Reset)

    /** Resize the grid. Ordered with [receive]; the next frame is full. */
    suspend fun resize(size: TerminalSize) = send(Command.Resize(size))

    /** Default colours + palette. Ordered with [receive]; the next frame is full. */
    suspend fun colors(colors: TerminalColors) = send(Command.Colors(colors))

    /**
     * Paste per the terminal's modes. Suspends until the owner loop ran it and returns what
     * [TerminalEngine.paste] returned: false means nothing was sent because the text could inject
     * commands — confirm with the user, then call again with [allowUnsafe] = true.
     */
    suspend fun paste(text: String, allowUnsafe: Boolean = false): Boolean {
        val permits = inputBudget.acquire(text.length)
        val reply = CompletableDeferred<Boolean>()
        try {
            send(Command.Paste(text, allowUnsafe, permits, reply))
        } catch (t: Throwable) {
            inputBudget.release(permits)
            throw t
        }
        return reply.await()
    }

    /** Plain text of the active selection ("" if none). Runs on the owner coroutine. */
    suspend fun selectedText(): String {
        val reply = CompletableDeferred<String>()
        send(Command.SelectedText(reply))
        return reply.await()
    }

    /** Non-blocking: a key event. Safe to call from a UI thread. */
    fun key(key: TerminalKey): EnqueueResult = offerInput(key.text.length + EVENT_OVERHEAD_BYTES) {
        Command.Key(key, it)
    }

    /** Non-blocking: a mouse event in viewport cell coordinates. Safe to call from a UI thread. */
    fun mouse(mouse: TerminalMouse): EnqueueResult = offerInput(EVENT_OVERHEAD_BYTES) { Command.Mouse(mouse, it) }

    /** Non-blocking: focus in/out (the terminal may report it to the program). */
    fun focus(focused: Boolean): EnqueueResult = offerInput(EVENT_OVERHEAD_BYTES) { Command.Focus(focused, it) }

    /** Non-blocking: scroll so absolute row [row] is at the top (clamped by the engine). */
    fun scrollTo(row: Long): EnqueueResult = offerInput(EVENT_OVERHEAD_BYTES) { Command.ScrollTo(row, it) }

    /** Non-blocking: set (or clear with null) the active selection. */
    fun select(selection: TerminalSelection?): EnqueueResult =
        offerInput(EVENT_OVERHEAD_BYTES) { Command.Select(selection, it) }

    /**
     * The frame with [generation] has been drawn; the session may publish the next one, carrying the
     * rows that changed since. Non-blocking and safe from a draw/UI thread — it never rides the
     * mailbox, so a saturated mailbox can never stall rendering.
     *
     * Acknowledging anything but the currently published generation is ignored.
     */
    fun acknowledge(generation: Long) {
        acks.trySend(generation)
        wakeup.trySend(Unit)
    }

    /**
     * The session's master rendering switch: `false` stops PUBLISHING frames (and stops reading
     * them out of the engine) while output keeps being parsed and effects keep being delivered.
     * `true` publishes a fresh FULL frame, because the session cannot know what a hidden renderer
     * still has on screen.
     *
     * It is a HOST-level switch (a whole window that went off-screen), not a per-surface one: a
     * surface that goes away takes a [RendererLease] and closes it instead, which is ref-counted and
     * therefore safe when several surfaces share one session. Both must allow publication:
     * `renderingEnabled = thisFlag && (no lease was ever taken || at least one is open)`.
     *
     * Non-blocking; applied by the owner loop.
     */
    fun setRenderingEnabled(enabled: Boolean) {
        renderingRequests.trySend(enabled)
        wakeup.trySend(Unit)
    }

    /**
     * Attach a renderer and keep frames flowing while the returned lease is open.
     *
     * Rendering is a REFERENCE-COUNTED property of the session, because a session may legitimately
     * have more than one surface on it (a split view, a picture-in-picture preview, a screen that is
     * animating out while its replacement animates in). The first lease starts publication and asks
     * for a FULL frame — an attaching renderer has no rows to patch — and only the LAST one to close
     * stops it. A surface that goes off-screen therefore closes its own lease without freezing
     * anybody else's; a session-wide flag would (that is what [setRenderingEnabled] is for, and it
     * is the host's to flip).
     *
     * Before the first lease is ever taken the session publishes as it always did, so a host that
     * never attaches one — a headless session, a test — is unaffected. After the last lease closes,
     * publication stops until a new one is taken: no renderer, no frames.
     *
     * Non-blocking, applied by the owner loop, and safe from a UI thread. Closing the lease is
     * idempotent; a lease on a closed session is inert.
     */
    fun attachRenderer(): RendererLease {
        leaseRequests.trySend(true)
        wakeup.trySend(Unit)
        return RendererLease(this)
    }

    /** Called by [RendererLease.close]; never by anything else. */
    internal fun releaseRenderer() {
        leaseRequests.trySend(false)
        wakeup.trySend(Unit)
    }

    /**
     * Ask for the next published frame to be FULL.
     *
     * A renderer keeps its own row model and patches it from partial frames, so a renderer that
     * attaches to an ALREADY RUNNING session (a re-mounted view, a second surface) has no rows to
     * patch: the newest published frame it sees may be partial. This is how it recovers without
     * touching the engine or closing anything. Non-blocking, conflated and idempotent; applied by
     * the owner loop.
     */
    fun requestFullFrame() {
        fullFrameRequests.trySend(Unit)
        wakeup.trySend(Unit)
    }

    /**
     * Stop the session and close the engine. Idempotent; queued commands are drained first so
     * nothing already accepted is silently lost. Rethrows the error that stopped the owner loop, if
     * any.
     *
     * Closing is a LOCAL decision: it produces no effect and no "exit" event.
     */
    suspend fun close() {
        closedByHost = true
        mailbox.close()
        wakeup.trySend(Unit)
        withContext(NonCancellable) { owner?.join() }
        fatal?.let { throw it }
    }

    /** Cancel everything at once (cancelled [open]); the engine is still closed by the owner. */
    private fun abort() {
        closedByHost = true
        mailbox.close()
        wakeup.trySend(Unit)
        scope.cancel()
    }

    // ------------------------------------------------------------------ enqueuing ----

    private suspend fun send(command: Command) {
        fatal?.let { throw it }
        try {
            mailbox.send(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw fatal ?: IllegalStateException("TerminalSession is closed", e)
        }
        wakeup.trySend(Unit)
    }

    private inline fun offerInput(bytes: Int, command: (permits: Int) -> Command): EnqueueResult {
        if (closedByHost || fatal != null) return EnqueueResult.Rejected(RejectionReason.CLOSED)
        val permits = inputBudget.tryAcquire(bytes) ?: return EnqueueResult.Rejected(RejectionReason.QUEUE_FULL)
        val result = mailbox.trySend(command(permits))
        if (!result.isSuccess) {
            inputBudget.release(permits)
            return EnqueueResult.Rejected(
                if (result.isClosed) RejectionReason.CLOSED else RejectionReason.QUEUE_FULL,
            )
        }
        wakeup.trySend(Unit)
        return EnqueueResult.Accepted
    }

    // ------------------------------------------------------------------ owner loop ----

    /** Frames read but not yet acknowledged: at most one, ever. */
    private var pendingGeneration: Long? = null
    private var publishedGeneration: Long = Long.MIN_VALUE

    /** Something mutated the engine since the last viewport read. */
    private var dirty = false

    /** The next read must be full (first frame, a re-shown view). */
    private var forceFull = false

    /** The effective switch: [hostRendering] and the leases together; see [attachRenderer]. */
    private var renderingEnabled = true

    /** The host's own switch ([setRenderingEnabled]). */
    private var hostRendering = true

    /** Open [RendererLease]s, and whether one was ever taken at all. */
    private var leases = 0
    private var leasesUsed = false

    /** When the currently held (mode 2026) frame was first seen; null when no hold is active. */
    private var heldSince: TimeMark? = null
    private var lastReadAt: TimeMark? = null

    private suspend fun start(
        size: TerminalSize,
        limits: TerminalLimits,
        initialColors: TerminalColors?,
        context: CoroutineContext,
        engineFactory: (TerminalSize, TerminalLimits) -> TerminalEngine,
    ) {
        scope = CoroutineScope(context + SupervisorJob(context[Job]))
        val ready = CompletableDeferred<MutableStateFlow<TerminalViewport>>()
        owner = scope.launch {
            val engine = try {
                engineFactory(size, limits)
            } catch (t: Throwable) {
                if (t !is CancellationException) fatal = t
                terminate()
                ready.completeExceptionally(t)
                return@launch
            }
            try {
                val first = try {
                    initialColors?.let { engine.colors(it) }
                    engine.viewport(forceFull = true)
                } catch (t: Throwable) {
                    ready.completeExceptionally(t)
                    throw t
                }
                val flow = MutableStateFlow(first)
                publishedGeneration = first.generation
                pendingGeneration = first.generation
                lastReadAt = timeSource.markNow()
                heldSince = if (first.held) lastReadAt else null
                if (!ready.complete(flow)) return@launch
                drainEngineEffects(engine)
                run(engine, flow)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Recorded, NOT rethrown: the failure reaches the host through close() and through
                // every suspending call, whereas rethrowing here would only reach the platform's
                // unhandled-coroutine-exception hook — which on Kotlin/Native terminates the process.
                fatal = t
            } finally {
                // The engine's native memory is released exactly once, on the owner coroutine,
                // whatever stopped the loop (close, cancellation, engine failure) — and then the
                // mailbox is shut down so no producer is left waiting on a loop that is gone.
                try {
                    engine.close()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (fatal == null) fatal = t
                } finally {
                    terminate()
                }
            }
        }
        try {
            state = ready.await()
        } catch (t: Throwable) {
            abort()
            throw t
        }
    }

    private suspend fun run(engine: TerminalEngine, flow: MutableStateFlow<TerminalViewport>) {
        while (true) {
            val drained = drainMailbox(engine)
            // Acknowledgements first: one that arrived before a hide still cleans the engine's
            // render state, so hiding does not silently force the next frame full.
            applyAcknowledgements(engine)
            applyRenderingRequests()
            applyLeaseRequests()
            applyRenderingState()
            applyFullFrameRequests()
            val wait = renderStep(engine, flow)
            when (drained) {
                Drained.CLOSED -> return
                // The batch limit was hit: give the dispatcher (on wasmJs: the browser event loop)
                // a turn before parsing the rest.
                Drained.MORE -> yield()
                Drained.IDLE -> if (wait == null) wakeup.receiveCatching() else withTimeoutOrNull(wait) {
                    wakeup.receiveCatching()
                }
            }
        }
    }

    private enum class Drained { IDLE, MORE, CLOSED }

    private suspend fun drainMailbox(engine: TerminalEngine): Drained {
        var commands = 0
        var bytes = 0
        while (commands < config.maxCommandsPerBatch && bytes < config.maxBytesPerBatch) {
            val received = mailbox.tryReceive()
            val command = received.getOrNull()
                ?: return if (received.isClosed) Drained.CLOSED else Drained.IDLE
            bytes += execute(engine, command)
            commands++
        }
        return Drained.MORE
    }

    /** Run one command; returns the bytes it fed (batch accounting). */
    private fun execute(engine: TerminalEngine, command: Command): Int {
        var fed = 0
        try {
            when (command) {
                is Command.Feed -> {
                    engine.feed(command.bytes, command.origin)
                    fed = command.bytes.size
                    dirty = true
                }
                Command.Reset -> {
                    engine.reset()
                    dirty = true
                    heldSince = null
                }
                is Command.Resize -> {
                    engine.resize(command.size)
                    dirty = true
                    heldSince = null
                }
                is Command.Colors -> {
                    engine.colors(command.colors)
                    dirty = true
                }
                is Command.ScrollTo -> {
                    engine.scrollTo(command.row)
                    dirty = true
                }
                is Command.Key -> {
                    engine.key(command.key)
                    dirty = true
                }
                is Command.Mouse -> {
                    engine.mouse(command.mouse)
                    dirty = true
                }
                is Command.Focus -> {
                    engine.focus(command.focused)
                    dirty = true
                }
                is Command.Select -> {
                    engine.select(command.selection)
                    dirty = true
                }
                is Command.Paste -> {
                    val sent = runCatching { engine.paste(command.text, command.allowUnsafe) }
                    dirty = true
                    sent.fold(command.reply::complete, command.reply::completeExceptionally)
                    sent.getOrThrow()
                }
                is Command.SelectedText -> {
                    val text = runCatching { engine.selectedText() }
                    text.fold(command.reply::complete, command.reply::completeExceptionally)
                    text.getOrThrow()
                }
            }
        } catch (e: TerminalNativeException) {
            // Recoverable: an effect or an envelope hit its limit, or an allocation failed. The
            // screen was updated regardless, so the session keeps running.
            reportError(e)
        } finally {
            releasePermits(command)
        }
        drainEngineEffects(engine)
        return fed
    }

    private fun drainEngineEffects(engine: TerminalEngine) {
        val drained = try {
            engine.drainEffects()
        } catch (e: TerminalNativeException) {
            reportError(e)
            return
        }
        for (effect in drained) {
            try {
                effects(effect)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A consumer that throws must not cost the remaining effects.
                reportError(t)
            }
        }
    }

    private fun applyRenderingRequests() {
        while (true) {
            val enabled = renderingRequests.tryReceive().getOrNull() ?: return
            hostRendering = enabled
        }
    }

    private fun applyLeaseRequests() {
        while (true) {
            val attach = leaseRequests.tryReceive().getOrNull() ?: return
            if (attach) {
                leases++
                leasesUsed = true
                // Every attaching renderer starts from nothing, not only the first one: a second
                // surface joining a live session has no rows a partial frame could patch.
                forceFull = true
                dirty = true
            } else if (leases > 0) {
                leases--
            }
        }
    }

    /** Fold [hostRendering] and the leases into [renderingEnabled] and apply the transition. */
    private fun applyRenderingState() {
        val enabled = hostRendering && (!leasesUsed || leases > 0)
        if (enabled == renderingEnabled) return
        renderingEnabled = enabled
        if (enabled) {
            // The hidden (or newly attached) renderer may have dropped anything, and an attaching
            // one has no rows to patch at all; start it over from a full frame.
            forceFull = true
            dirty = true
        } else {
            // A hidden renderer will not acknowledge; do not wedge the loop on it. The frame
            // stays unacknowledged in the engine, which is exactly what makes the next one full.
            pendingGeneration = null
        }
    }

    private fun applyFullFrameRequests() {
        if (fullFrameRequests.tryReceive().isSuccess) {
            forceFull = true
            dirty = true
        }
    }

    private fun applyAcknowledgements(engine: TerminalEngine) {
        while (true) {
            val generation = acks.tryReceive().getOrNull() ?: return
            if (generation != pendingGeneration) continue
            try {
                engine.acknowledge(generation)
            } catch (e: TerminalNativeException) {
                reportError(e)
            }
            pendingGeneration = null
        }
    }

    /**
     * Publish at most one unacknowledged frame; returns how long the loop may sleep before it has
     * to look at a synchronized-output hold again (null = nothing pending).
     */
    private fun renderStep(engine: TerminalEngine, flow: MutableStateFlow<TerminalViewport>): Duration? {
        if (!renderingEnabled || pendingGeneration != null) return null
        val held = heldSince
        if (held != null) {
            val heldFor = held.elapsedNow()
            if (heldFor >= config.holdTimeout) {
                // The engine has no clock: this loop is the ~1 s timeout of mode 2026.
                read(engine, flow, breakHold = true)
            } else {
                val sinceRead = lastReadAt?.elapsedNow()
                if (sinceRead == null || sinceRead >= config.holdPollInterval) read(engine, flow, breakHold = false)
            }
        } else if (dirty || forceFull) {
            read(engine, flow, breakHold = false)
        }
        val stillHeld = heldSince ?: return null
        if (pendingGeneration != null) return null
        val untilBreak = config.holdTimeout - stillHeld.elapsedNow()
        val untilPoll = config.holdPollInterval - (lastReadAt?.elapsedNow() ?: Duration.ZERO)
        return maxOf(minOf(untilBreak, untilPoll), MIN_WAIT)
    }

    private fun read(engine: TerminalEngine, flow: MutableStateFlow<TerminalViewport>, breakHold: Boolean) {
        val wantedFull = forceFull
        val viewport = try {
            engine.viewport(forceFull = forceFull, breakHold = breakHold)
        } catch (e: TerminalNativeException) {
            // A frame that could not be serialized (ST_ERR_LIMIT / OUT_OF_MEMORY) leaves `dirty` and
            // `forceFull` SET on purpose: the screen still changed, so the next wake-up retries and
            // the change is not lost. Nothing is published, so `pendingGeneration` stays null and
            // the loop parks on `wakeup` instead of spinning on a failing engine.
            reportError(e)
            return
        }
        forceFull = false
        dirty = false
        val now = timeSource.markNow()
        lastReadAt = now
        heldSince = if (viewport.held) heldSince ?: now else null
        if (viewport.generation == publishedGeneration) {
            // A FULL read that nothing else made dirty ([requestFullFrame] from a renderer that
            // attached mid-stream): the generation is the one already on screen, but the frame now
            // carries every row, so publish it — unless it is identical to what the flow already
            // holds, in which case the attaching renderer has the full rows anyway and publishing
            // nothing keeps `pendingGeneration` from waiting for an ack that will never come.
            if (wantedFull && viewport != flow.value) {
                pendingGeneration = viewport.generation
                flow.value = viewport
                return
            }
            // A hold poll re-serialized the frame the renderer already drew: acknowledge it again so
            // the engine's render state stays clean (an unacknowledged frame forces the next full).
            try {
                engine.acknowledge(viewport.generation)
            } catch (e: TerminalNativeException) {
                reportError(e)
            }
            return
        }
        publishedGeneration = viewport.generation
        pendingGeneration = viewport.generation
        flow.value = viewport
    }

    // ------------------------------------------------------------------ teardown ----

    /**
     * Shut the mailbox down from the owner coroutine, whatever stopped it.
     *
     * Closing alone is not enough: a command already in the buffer would keep its
     * [CompletableDeferred] reply forever (so [paste] / [selectedText] would hang) and would never
     * give its byte-budget permits back (so a [receive] waiting for budget would hang too). Every
     * leftover command is therefore failed with [failure] — or with a plain "closed" error on the
     * ordinary [close] path, where the loop has already drained everything and this is a no-op.
     */
    private fun terminate() {
        mailbox.close()
        leaseRequests.close()
        while (true) {
            val command = mailbox.tryReceive().getOrNull() ?: return
            abandon(command)
        }
    }

    private fun abandon(command: Command) {
        val error = fatal ?: IllegalStateException("TerminalSession is closed")
        when (command) {
            is Command.Paste -> command.reply.completeExceptionally(error)
            is Command.SelectedText -> command.reply.completeExceptionally(error)
            else -> Unit
        }
        releasePermits(command)
    }

    private fun releasePermits(command: Command) {
        command.permits?.let { (budget, count) ->
            when (budget) {
                Budget.OUTPUT -> outputBudget.release(count)
                Budget.INPUT -> inputBudget.release(count)
            }
        }
    }

    /**
     * Hand a recoverable error to the host. The callback is documented as "must not throw"; if it
     * does anyway, that is a host bug and must not take the terminal down with it (there is nowhere
     * else to report it, so it is dropped).
     */
    private fun reportError(error: Throwable) {
        try {
            onEngineError(error)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    // ------------------------------------------------------------------ commands ----

    private enum class Budget { OUTPUT, INPUT }

    private sealed interface Command {
        /** Budget permits to give back once the command ran. */
        val permits: Pair<Budget, Int>? get() = null

        class Feed(val bytes: ByteArray, val origin: OutputOrigin, granted: Int) : Command {
            override val permits = Budget.OUTPUT to granted
        }

        data object Reset : Command
        class Resize(val size: TerminalSize) : Command
        class Colors(val colors: TerminalColors) : Command

        class ScrollTo(val row: Long, granted: Int) : Command {
            override val permits = Budget.INPUT to granted
        }

        class Key(val key: TerminalKey, granted: Int) : Command {
            override val permits = Budget.INPUT to granted
        }

        class Mouse(val mouse: TerminalMouse, granted: Int) : Command {
            override val permits = Budget.INPUT to granted
        }

        class Focus(val focused: Boolean, granted: Int) : Command {
            override val permits = Budget.INPUT to granted
        }

        class Select(val selection: TerminalSelection?, granted: Int) : Command {
            override val permits = Budget.INPUT to granted
        }

        class Paste(
            val text: String,
            val allowUnsafe: Boolean,
            granted: Int,
            val reply: CompletableDeferred<Boolean>,
        ) : Command {
            override val permits = Budget.INPUT to granted
        }

        class SelectedText(val reply: CompletableDeferred<String>) : Command
    }

    companion object {
        /** Per-event accounting overhead of a local input event against the input budget. */
        private const val EVENT_OVERHEAD_BYTES = 16

        /** Never sleep for less than this when a hold is active (keeps a busy loop impossible). */
        private val MIN_WAIT = 1.milliseconds

        /**
         * Start a session: create the engine, apply [colors], read the first (full) frame and
         * publish it, then return once [viewports] holds it.
         *
         * @param size initial grid size.
         * @param limits scrollback limits.
         * @param colors default colours + palette, applied before the first frame.
         * @param config bounds and cadence; see [TerminalSessionConfig].
         * @param context where the owner coroutine runs. [Dispatchers.Default] keeps parsing off the
         *   UI thread on Android/JVM/iOS; on wasmJs it IS the browser event loop, which is why the
         *   loop yields every [TerminalSessionConfig.maxCommandsPerBatch] commands /
         *   [TerminalSessionConfig.maxBytesPerBatch] bytes. A dedicated web worker is a later,
         *   measured optimisation, not a dependency. If the context carries a [Job], the session is
         *   its child and dies with it (the engine is still closed).
         * @param effects consumer of the engine's typed effects, invoked ON the owner coroutine in
         *   queue order. It must not block; [TerminalEffect.Response] and [TerminalEffect.Input]
         *   bytes are what the host writes to its transport (the session owns no transport).
         * @param onEngineError recoverable engine errors (a dropped effect, an allocation failure)
         *   and [effects] consumer exceptions. The session keeps running. It MUST NOT throw; an
         *   exception from it is a host bug, and the session drops it rather than dying with it.
         * @param engineFactory engine constructor; the default is [createTerminalEngine]. Injectable
         *   for tests and for hosts that pre-create engines.
         *
         * Throws whatever [createTerminalEngine] throws (typically
         * [TerminalEngineUnavailableException]). Cancelling the caller closes the engine again.
         */
        suspend fun open(
            size: TerminalSize,
            limits: TerminalLimits = TerminalLimits(),
            colors: TerminalColors? = null,
            config: TerminalSessionConfig = TerminalSessionConfig(),
            context: CoroutineContext = Dispatchers.Default,
            effects: (TerminalEffect) -> Unit = {},
            onEngineError: (Throwable) -> Unit = {},
            engineFactory: (TerminalSize, TerminalLimits) -> TerminalEngine = ::createTerminalEngine,
        ): TerminalSession = open(size, limits, colors, config, context, effects, onEngineError, engineFactory, TimeSource.Monotonic)

        /** [open] with an injectable clock (hold timeout); tests only. */
        internal suspend fun open(
            size: TerminalSize,
            limits: TerminalLimits,
            colors: TerminalColors?,
            config: TerminalSessionConfig,
            context: CoroutineContext,
            effects: (TerminalEffect) -> Unit,
            onEngineError: (Throwable) -> Unit,
            engineFactory: (TerminalSize, TerminalLimits) -> TerminalEngine,
            timeSource: TimeSource,
        ): TerminalSession {
            val session = TerminalSession(config, effects, onEngineError, timeSource)
            session.start(size, limits, colors, context, engineFactory)
            return session
        }
    }
}

/**
 * A byte budget backed by a counting [Semaphore]: permits are granules of the budget, so acquiring
 * is O(permits) and never needs a lock. Acquisition is all-or-nothing (no partial hold), so
 * concurrent producers cannot deadlock each other, and a request larger than the whole budget takes
 * every permit instead of waiting forever.
 */
private class ByteBudget(capacityBytes: Int) {
    private val granule: Int = maxOf(1, capacityBytes / GRANULES)
    private val total: Int = maxOf(1, (capacityBytes + granule - 1) / granule)
    private val semaphore = Semaphore(total)
    private val released = Channel<Unit>(Channel.CONFLATED)

    private fun permitsFor(bytes: Int): Int = minOf(total, maxOf(1, (bytes + granule - 1) / granule))

    /** Permits taken, or null when the budget cannot cover [bytes] right now. */
    fun tryAcquire(bytes: Int): Int? {
        val wanted = permitsFor(bytes)
        var taken = 0
        while (taken < wanted && semaphore.tryAcquire()) taken++
        if (taken < wanted) {
            repeat(taken) { semaphore.release() }
            return null
        }
        return wanted
    }

    /** Suspends until [bytes] fit. */
    suspend fun acquire(bytes: Int): Int {
        while (true) {
            tryAcquire(bytes)?.let { return it }
            released.receive()
        }
    }

    fun release(permits: Int) {
        repeat(permits) { semaphore.release() }
        released.trySend(Unit)
    }

    private companion object {
        /** Target number of permits per budget: fine enough to be fair, small enough to be cheap. */
        const val GRANULES = 1024
    }
}
