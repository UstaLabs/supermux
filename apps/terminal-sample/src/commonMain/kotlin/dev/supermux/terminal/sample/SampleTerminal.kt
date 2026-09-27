package dev.supermux.terminal.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSessionConfig
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.compose.TerminalEffectRelay
import dev.supermux.terminal.compose.TerminalTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * One terminal of the sample: a [TerminalSession], the coroutine that feeds it a [SampleFixture],
 * and the lifecycle controls the task asks for (reset / hide / show / dispose).
 *
 * **No pty, no transport, no shell.** The producer below is the whole "server side": it generates
 * bytes and calls `session.receive`, which is the same entry point a real transport would use.
 * That is what makes this module publishable-package-shaped — it depends on `:terminal-compose`
 * and nothing else of supermux — and what makes a measurement repeatable.
 *
 * **Who owns what.** This class owns the session: it opens it, feeds it and closes it. The
 * `Terminal` composable owns only what it draws; unmounting it (see [mounted]) must NOT stop the
 * terminal, and this class is where that is proven — output keeps arriving while nothing is on
 * screen, and remounting shows the up-to-date screen because the surface asks for a full frame.
 */
@Stable
class SampleTerminal(
    /** 1-based, for the label. */
    val index: Int,
    private val scope: CoroutineScope,
    val diagnostics: SampleDiagnostics = SampleDiagnostics(),
    private val config: TerminalSessionConfig = TerminalSessionConfig(),
    private val limits: TerminalLimits = TerminalLimits(),
) {
    /** The host's effect bridge: without one the surface's `onTitle` never fires. */
    val effects: TerminalEffectRelay = TerminalEffectRelay()

    /** The live session, or null before [open] and after [dispose]. */
    var session: TerminalSession? by mutableStateOf(null)
        private set

    /** Is the `Terminal` composable in the composition at all? [hide] / [show] flip it. */
    var mounted: Boolean by mutableStateOf(true)
        private set

    /** The surface's `active` flag: a mounted-but-inactive surface releases its renderer lease. */
    var active: Boolean by mutableStateOf(true)

    var fixture: SampleFixture by mutableStateOf(SampleFixture.SHELL)
        private set

    /** Target output rate. 0 = unthrottled (as fast as the session accepts it). */
    var rateBytesPerSecond: Int by mutableStateOf(DEFAULT_RATE_BYTES_PER_SECOND)

    var status: String by mutableStateOf("not opened")
        private set

    var title: String? by mutableStateOf(null)
        private set

    var lastLink: String? by mutableStateOf(null)
        private set

    var failure: Throwable? by mutableStateOf(null)
        private set

    private var producer: Job? = null
    private var opening: Job? = null

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Open a session and start producing. Idempotent while one is already open or opening.
     *
     * [size] is the grid the session STARTS with; the surface resizes it to whatever actually fits
     * as soon as it is measured, which is why the sample never has to guess a cell size.
     */
    fun open(size: TerminalSize = INITIAL_SIZE, theme: TerminalTheme = TerminalTheme()) {
        if (session != null || opening?.isActive == true) return
        status = "opening"
        failure = null
        opening = scope.launch {
            val opened = try {
                TerminalSession.open(
                    size = size,
                    limits = limits,
                    colors = theme.engineColors(),
                    config = config,
                    effects = effects.wrap(::countEffect),
                    onEngineError = { error -> diagnostics.onFailure("recoverable: ${error.message}") },
                )
            } catch (error: Throwable) {
                // An engine that cannot start is the FIRST thing a sample has to show honestly:
                // on a host with no packaged native library this is the whole user experience.
                if (error is kotlinx.coroutines.CancellationException) throw error
                failure = error
                status = "engine unavailable: ${error::class.simpleName}: ${error.message}"
                diagnostics.onFailure(error.message ?: error.toString())
                return@launch
            }
            diagnostics.outputQueueCapBytes = config.maxPendingOutputBytes
            diagnostics.inputQueueCapBytes = config.maxPendingInputBytes
            diagnostics.mailboxCapacity = config.mailboxCapacity
            diagnostics.historyLinesLimit = limits.historyLines
            diagnostics.historyBytesLimit = limits.historyBytes
            diagnostics.onSessionOpened()
            session = opened
            status = "running ${fixture.id}"
            startProducer(opened)
        }
    }

    /** Switch fixture: stop the producer, undo the old fixture's preamble, start the new one. */
    fun selectFixture(next: SampleFixture) {
        if (next == fixture && producer?.isActive == true) return
        fixture = next
        val open = session ?: return
        startProducer(open)
        status = "running ${next.id}"
    }

    /**
     * Full reset (RIS) plus a measurement reset.
     *
     * The engine keeps its size, colours and limits and throws away the screen and the scrollback;
     * the producer restarts from the fixture's first byte, so a reset makes two runs comparable.
     */
    fun reset() {
        val open = session ?: return
        scope.launch {
            producer?.cancelAndJoinQuietly()
            runCatching { open.reset() }
            diagnostics.reset()
            diagnostics.onReset()
            startProducer(open)
            status = "reset · running ${fixture.id}"
        }
    }

    /**
     * Take the surface out of the composition entirely — the strongest thing "hide" can mean.
     *
     * The session keeps parsing: this is the case that proves the renderer is not load-bearing.
     * [show] puts it back, and the remounted surface has no rows to patch, so it asks the session
     * for a full frame (visible as `full frames` ticking up in the panel).
     */
    fun hide() {
        mounted = false
    }

    fun show() {
        mounted = true
    }

    /** Close the session and stop the producer. The terminal is gone; [open] makes a new one. */
    fun dispose() {
        val open = session ?: return
        session = null
        status = "disposed"
        scope.launch {
            producer?.cancelAndJoinQuietly()
            producer = null
            withContext(NonCancellable) {
                runCatching { open.close() }
            }
            diagnostics.onSessionDisposed()
        }
    }

    /** Called by the surface's `onFailure`: the session died and nothing will change on screen again. */
    fun onFailure(error: Throwable) {
        failure = error
        status = "failed: ${error.message}"
        diagnostics.onFailure(error.message ?: error.toString())
    }

    fun onTitle(value: String) {
        title = value
    }

    fun onLink(uri: String) {
        // The surface never opens anything; a sample least of all. Showing it is the whole point.
        lastLink = uri
    }

    // ---- production ------------------------------------------------------------------------------

    private fun startProducer(open: TerminalSession) {
        val previous = producer
        // `Dispatchers.Default`, NOT the composition's dispatcher the scope carries. Generating a
        // fixture chunk is a few hundred microseconds of StringBuilder work; on the UI thread it
        // would land inside the frame it is supposed to be measured against, and the sample's own
        // frame times would be measuring the sample. (On wasmJs there is one thread anyway, which
        // is exactly why the session's owner loop yields per batch.)
        producer = scope.launch(Dispatchers.Default) {
            previous?.cancelAndJoinQuietly()
            val stream = fixture.stream()
            try {
                feed(open, stream.preamble())
                val start = TimeSource.Monotonic.markNow()
                var baselineMillis = 0L
                var baselineBytes = 0L
                var sent = 0L
                var rate = rateBytesPerSecond
                while (true) {
                    coroutineContext.ensureActive()
                    val chunk = stream.next()
                    feed(open, chunk)
                    sent += chunk.size
                    val wanted = rateBytesPerSecond
                    if (wanted != rate) {
                        // Re-base the pacing so a slider move does not "owe" or "credit" bytes.
                        rate = wanted
                        baselineMillis = start.elapsedNow().inWholeMilliseconds
                        baselineBytes = sent
                    }
                    if (rate <= 0) {
                        // Unthrottled still yields: the browser runs the session's owner loop on
                        // the event loop, and a producer that never suspends would starve it.
                        yield()
                        continue
                    }
                    val dueMillis = baselineMillis + (sent - baselineBytes) * 1000L / rate
                    val elapsed = start.elapsedNow().inWholeMilliseconds
                    if (dueMillis > elapsed) delay(dueMillis - elapsed) else yield()
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { open.receive(stream.epilogue()) }
                }
            }
        }
    }

    /** One `receive`, timed: the only place this sample can observe the session's backpressure. */
    private suspend fun feed(open: TerminalSession, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        diagnostics.onReceiveStart(bytes.size)
        val mark = TimeSource.Monotonic.markNow()
        var suspended = 0L
        try {
            open.receive(bytes, OutputOrigin.LIVE)
            val micros = mark.elapsedNow().inWholeMicroseconds
            if (micros > BACKPRESSURE_THRESHOLD_MICROS) suspended = micros
            diagnostics.onOutput(bytes.size)
        } finally {
            diagnostics.onReceiveEnd(bytes.size, suspended)
        }
    }

    /** The effects consumer a host installs. Counting them is all this sample does with them. */
    private fun countEffect(effect: TerminalEffect) {
        when (effect) {
            is TerminalEffect.Input -> diagnostics.onInput(effect.bytes.size)
            is TerminalEffect.Response -> diagnostics.onResponse(effect.bytes.size)
            is TerminalEffect.Title -> diagnostics.onTitle()
            TerminalEffect.Bell -> diagnostics.onBell()
            is TerminalEffect.ClipboardRequest -> diagnostics.onClipboardRequest()
        }
    }

    companion object {
        /** 120x40, the grid the benchmark specifies; the surface resizes it to what fits. */
        val INITIAL_SIZE = TerminalSize(columns = 120, rows = 40, cellWidthPx = 8, cellHeightPx = 16)

        /** 256 KiB/s: fast enough to keep the screen moving, slow enough to read. */
        const val DEFAULT_RATE_BYTES_PER_SECOND: Int = 256 * 1024

        /** Below this, a `receive` did not really wait — it is scheduler noise, not backpressure. */
        private const val BACKPRESSURE_THRESHOLD_MICROS = 1_000L
    }
}

private suspend fun Job.cancelAndJoinQuietly() {
    cancel()
    runCatching { join() }
}
