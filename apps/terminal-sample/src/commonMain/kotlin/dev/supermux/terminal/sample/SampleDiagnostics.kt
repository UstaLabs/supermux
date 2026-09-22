package dev.supermux.terminal.sample

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * The numbers this sample shows, and **only** this sample.
 *
 * None of this lives in `:terminal-compose`. A product terminal shows a terminal: it does not show
 * frame-time percentiles, byte counters or queue high-water marks, and a renderer that carried that
 * machinery would pay for it in every app that embeds it. The surface exposes exactly what a host
 * needs (frames, failures, titles, links) and the instrumentation below is built on top of those
 * public seams — `TerminalSession`'s effects consumer, the frames the surface publishes through
 * `ViewportModel`, and Compose's own frame clock. Nothing here reaches into the package.
 *
 * **Three threads write here** and that is why every counter is an [AtomicLong] rather than a plain
 * `var`: the producer coroutine (`Dispatchers.Default`) counts output bytes and backpressure, the
 * session's owner coroutine (also `Dispatchers.Default`) counts effect bytes from the effects
 * consumer, and the composition's dispatcher counts frames and reads [snapshot]. A `Long` write is
 * not atomic on every target this package builds for, and a torn byte counter in a performance
 * report is worse than no counter at all.
 */
@OptIn(ExperimentalAtomicApi::class)
class SampleDiagnostics(
    /** How many frame intervals the percentiles are computed over. */
    private val frameWindow: Int = 2048,
) {
    // ---- frame times (composition dispatcher only) --------------------------------------------

    private val frameMicros = LongArray(frameWindow)
    private var frameCount = 0
    private var frameIndex = 0
    private var lastFrameNanos = 0L

    /**
     * A separate flag rather than `lastFrameNanos == 0L`: Compose's frame clock is free to hand out
     * 0 (the test harness does, and so does a clock started at process start), and a sentinel that
     * a real timestamp can collide with silently drops the frame after it.
     */
    private var haveBaseline = false

    private val framesObserved = AtomicLong(0)
    private val worstFrameMicros = AtomicLong(0)
    private val stallsOver100ms = AtomicLong(0)
    private val stallsOver33ms = AtomicLong(0)

    /**
     * Record one frame boundary. [nanos] is Compose's own frame time (`withFrameNanos`), so the
     * interval between two calls is the time the host actually took to produce a frame — including
     * whatever else the process, and the machine, was doing.
     *
     * The FIRST call after a reset only establishes the baseline; there is no interval yet.
     */
    fun onFrameNanos(nanos: Long) {
        val previous = lastFrameNanos
        val hadBaseline = haveBaseline
        lastFrameNanos = nanos
        haveBaseline = true
        if (!hadBaseline) return
        val micros = (nanos - previous) / 1_000
        if (micros < 0) return
        framesObserved.fetchAndAdd(1)
        if (micros > worstFrameMicros.load()) worstFrameMicros.store(micros)
        if (micros > 100_000) stallsOver100ms.fetchAndAdd(1)
        if (micros > 33_333) stallsOver33ms.fetchAndAdd(1)
        frameMicros[frameIndex] = micros
        frameIndex = (frameIndex + 1) % frameWindow
        if (frameCount < frameWindow) frameCount++
    }

    /** Percentile of the frame intervals in the window, in milliseconds; 0 before any frame. */
    fun frameMillisPercentile(percentile: Double): Double {
        if (frameCount == 0) return 0.0
        val sorted = frameMicros.copyOf(frameCount)
        sorted.sort()
        val rank = (percentile / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = minOf(low + 1, sorted.size - 1)
        val fraction = rank - low
        return (sorted[low] + (sorted[high] - sorted[low]) * fraction) / 1000.0
    }

    // ---- byte counters (producer + owner coroutines) ------------------------------------------

    private val outputBytes = AtomicLong(0)
    private val inputBytes = AtomicLong(0)
    private val responseBytes = AtomicLong(0)
    private val titles = AtomicLong(0)
    private val bells = AtomicLong(0)
    private val clipboardRequests = AtomicLong(0)

    /** Bytes handed to `TerminalSession.receive` (what a pty would have produced). */
    fun onOutput(bytes: Int) {
        outputBytes.fetchAndAdd(bytes.toLong())
    }

    /**
     * Bytes the engine produced as `TerminalEffect.Input` — key, mouse, paste and focus encodings.
     * This is exactly what a host would write BACK to the pty, which is the only honest definition
     * of "input bytes" a terminal package can have: the surface never invents bytes of its own.
     */
    fun onInput(bytes: Int) {
        inputBytes.fetchAndAdd(bytes.toLong())
    }

    /** Bytes the engine answered a query with (`TerminalEffect.Response`: DSR, DA, DECRQM, …). */
    fun onResponse(bytes: Int) {
        responseBytes.fetchAndAdd(bytes.toLong())
    }

    fun onTitle() {
        titles.fetchAndIncrement()
    }

    fun onBell() {
        bells.fetchAndIncrement()
    }

    fun onClipboardRequest() {
        clipboardRequests.fetchAndIncrement()
    }

    // ---- queues --------------------------------------------------------------------------------

    /**
     * The session's configured caps, copied in when the session is opened. A terminal package that
     * is doing its job keeps its queues inside these; the sample shows them so "stayed within the
     * cap" is a number rather than a claim.
     */
    var outputQueueCapBytes: Int = 0
    var inputQueueCapBytes: Int = 0
    var mailboxCapacity: Int = 0

    private val pendingOutputBytes = AtomicLong(0)
    private val pendingOutputHighWaterBytes = AtomicLong(0)
    private val backpressureMicros = AtomicLong(0)
    private val backpressureEvents = AtomicLong(0)
    private val rejectedEnqueues = AtomicLong(0)

    /**
     * Bytes entering a `receive` call. `receive` suspends exactly while the pending-output budget is
     * exhausted, so the high-water mark of "bytes inside a call that has not returned" is the
     * closest a HOST can get to "how full did the output queue get" without the package exporting
     * an internal counter it has no other reason to have.
     */
    fun onReceiveStart(bytes: Int) {
        val pending = pendingOutputBytes.addAndFetch(bytes.toLong())
        // A plain compare-and-store race here can only ever lose a tie, never invent a value.
        if (pending > pendingOutputHighWaterBytes.load()) pendingOutputHighWaterBytes.store(pending)
    }

    /** [suspendedMicros] is counted as backpressure only when the call actually waited. */
    fun onReceiveEnd(bytes: Int, suspendedMicros: Long) {
        pendingOutputBytes.fetchAndAdd(-bytes.toLong())
        if (suspendedMicros > 0) {
            backpressureMicros.fetchAndAdd(suspendedMicros)
            backpressureEvents.fetchAndIncrement()
        }
    }

    /** A non-blocking enqueue the session refused (`EnqueueResult.Rejected`). */
    fun onRejectedEnqueue() {
        rejectedEnqueues.fetchAndIncrement()
    }

    // ---- engine / history (composition dispatcher) ---------------------------------------------

    private val historyRows = AtomicLong(0)
    private val historyRowsHighWater = AtomicLong(0)
    private val framesPublished = AtomicLong(0)
    private val framesFull = AtomicLong(0)
    private val sequenceGaps = AtomicLong(0)
    private val framesMissed = AtomicLong(0)

    /** Scrollback limits the session was opened with; the engine enforces them. */
    var historyLinesLimit: Int = 0
    var historyBytesLimit: Long = 0L

    private var columns: Int = 0
    private var rows: Int = 0
    private var cellWidthPx: Int = 0
    private var cellHeightPx: Int = 0
    /**
     * One frame the SESSION published, observed by collecting `session.viewports` beside the
     * surface.
     *
     * A second collector on a conflated `StateFlow` neither consumes frames nor acknowledges them
     * — only the surface does that — so this is a passive tap. It is also why the sample can report
     * these at all: the surface's own `ViewportModel` is its business, not a host's.
     */
    fun onPublishedFrame(
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        historyRows: Long,
        full: Boolean,
    ) {
        this.columns = columns
        this.rows = rows
        this.cellWidthPx = cellWidthPx
        this.cellHeightPx = cellHeightPx
        this.historyRows.store(historyRows)
        if (historyRows > historyRowsHighWater.load()) historyRowsHighWater.store(historyRows)
        framesPublished.fetchAndIncrement()
        if (full) framesFull.fetchAndIncrement()
    }

    /**
     * Frames THIS observer missed because it was slower than the session (a conflated flow drops
     * them). Not an error and not the surface's gap counter: it says how far behind a passive
     * collector fell, which is worth knowing when the box is loaded.
     */
    fun onSequenceGap(missed: Long) {
        sequenceGaps.fetchAndIncrement()
        framesMissed.fetchAndAdd(missed)
    }

    // ---- process memory -------------------------------------------------------------------------

    private var memory: SampleMemory = SampleMemory()
    private val peakRssBytes = AtomicLong(0)
    private val peakHeapBytes = AtomicLong(0)

    /** Re-read the process's memory. Cheap, but not free: the poller calls it, the frame loop does not. */
    fun sampleMemory(): SampleMemory {
        val now = readSampleMemory()
        memory = now
        if (now.rssBytes > peakRssBytes.load()) peakRssBytes.store(now.rssBytes)
        if (now.heapUsedBytes > peakHeapBytes.load()) peakHeapBytes.store(now.heapUsedBytes)
        return now
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    private val sessionsOpened = AtomicLong(0)
    private val sessionsDisposed = AtomicLong(0)
    private val resets = AtomicLong(0)
    private val failures = AtomicLong(0)
    private var lastFailure: String? = null

    fun onSessionOpened() {
        sessionsOpened.fetchAndIncrement()
    }

    fun onSessionDisposed() {
        sessionsDisposed.fetchAndIncrement()
    }

    fun onReset() {
        resets.fetchAndIncrement()
    }

    fun onFailure(message: String) {
        failures.fetchAndIncrement()
        lastFailure = message
    }

    /**
     * Clear every measurement. The lifecycle totals (sessions opened/disposed, resets, failures)
     * survive on purpose: "how many sessions has this process opened, and how much memory does it
     * hold now" is exactly the pair a leak makes interesting, and zeroing the left half would hide
     * one.
     */
    fun reset() {
        frameMicros.fill(0L)
        frameCount = 0
        frameIndex = 0
        lastFrameNanos = 0L
        haveBaseline = false
        listOf(
            framesObserved, worstFrameMicros, stallsOver100ms, stallsOver33ms,
            outputBytes, inputBytes, responseBytes, titles, bells, clipboardRequests,
            pendingOutputBytes, pendingOutputHighWaterBytes, backpressureMicros, backpressureEvents,
            rejectedEnqueues, historyRowsHighWater, framesPublished, framesFull, sequenceGaps,
            framesMissed, peakRssBytes, peakHeapBytes,
        ).forEach { it.store(0) }
    }

    /** An immutable copy for the UI (or a report) to read without racing the mutators. */
    fun snapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        columns = columns,
        rows = rows,
        cellWidthPx = cellWidthPx,
        cellHeightPx = cellHeightPx,
        outputBytes = outputBytes.load(),
        inputBytes = inputBytes.load(),
        responseBytes = responseBytes.load(),
        frameP50Millis = frameMillisPercentile(50.0),
        frameP95Millis = frameMillisPercentile(95.0),
        frameP99Millis = frameMillisPercentile(99.0),
        worstFrameMillis = worstFrameMicros.load() / 1000.0,
        framesObserved = framesObserved.load(),
        stallsOver33ms = stallsOver33ms.load(),
        stallsOver100ms = stallsOver100ms.load(),
        framesPublished = framesPublished.load(),
        framesFull = framesFull.load(),
        sequenceGaps = sequenceGaps.load(),
        framesMissed = framesMissed.load(),
        historyRows = historyRows.load(),
        historyRowsHighWater = historyRowsHighWater.load(),
        historyLinesLimit = historyLinesLimit,
        historyBytesLimit = historyBytesLimit,
        outputQueueCapBytes = outputQueueCapBytes,
        inputQueueCapBytes = inputQueueCapBytes,
        mailboxCapacity = mailboxCapacity,
        pendingOutputBytes = pendingOutputBytes.load(),
        pendingOutputHighWaterBytes = pendingOutputHighWaterBytes.load(),
        backpressureMicros = backpressureMicros.load(),
        backpressureEvents = backpressureEvents.load(),
        rejectedEnqueues = rejectedEnqueues.load(),
        titles = titles.load(),
        bells = bells.load(),
        clipboardRequests = clipboardRequests.load(),
        memory = memory,
        peakRssBytes = peakRssBytes.load(),
        peakHeapBytes = peakHeapBytes.load(),
        sessionsOpened = sessionsOpened.load(),
        sessionsDisposed = sessionsDisposed.load(),
        resets = resets.load(),
        failures = failures.load(),
        lastFailure = lastFailure,
    )
}

/** One consistent reading of a [SampleDiagnostics]; see that class for what each field means. */
data class DiagnosticsSnapshot(
    val columns: Int,
    val rows: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val outputBytes: Long,
    val inputBytes: Long,
    val responseBytes: Long,
    val frameP50Millis: Double,
    val frameP95Millis: Double,
    val frameP99Millis: Double,
    val worstFrameMillis: Double,
    val framesObserved: Long,
    val stallsOver33ms: Long,
    val stallsOver100ms: Long,
    val framesPublished: Long,
    val framesFull: Long,
    val sequenceGaps: Long,
    val framesMissed: Long,
    val historyRows: Long,
    val historyRowsHighWater: Long,
    val historyLinesLimit: Int,
    val historyBytesLimit: Long,
    val outputQueueCapBytes: Int,
    val inputQueueCapBytes: Int,
    val mailboxCapacity: Int,
    val pendingOutputBytes: Long,
    val pendingOutputHighWaterBytes: Long,
    val backpressureMicros: Long,
    val backpressureEvents: Long,
    val rejectedEnqueues: Long,
    val titles: Long,
    val bells: Long,
    val clipboardRequests: Long,
    val memory: SampleMemory,
    val peakRssBytes: Long,
    val peakHeapBytes: Long,
    val sessionsOpened: Long,
    val sessionsDisposed: Long,
    val resets: Long,
    val failures: Long,
    val lastFailure: String?,
)

/**
 * What this platform can say about the process's memory. Zero means "this platform does not expose
 * it" — never a guess, and never zero-as-a-measurement.
 *
 * - [heapUsedBytes]: managed heap in use (JVM/Android `Runtime`; 0 in the browser and on iOS).
 * - [rssBytes]: resident set size, which is the only number that shows NATIVE growth — the engine's
 *   scrollback lives outside the managed heap, so a heap graph alone cannot see a terminal leak.
 */
data class SampleMemory(
    val heapUsedBytes: Long = 0L,
    val heapTotalBytes: Long = 0L,
    val rssBytes: Long = 0L,
    val source: String = "unavailable",
)

/** This platform's [SampleMemory]; see the actuals for what each one can and cannot read. */
expect fun readSampleMemory(): SampleMemory

/** `1.23 MiB`-style formatting, used by the panel and by the benchmark report. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else "${formatDecimal(value, 2)} ${units[unit]}"
}

/** [value] with [decimals] digits after the point, without `String.format` (which wasm lacks). */
fun formatDecimal(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    val whole = scaled / factor
    val fraction = kotlin.math.abs(scaled % factor)
    if (decimals == 0) return whole.toString()
    val sign = if (value < 0 && whole == 0L) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
}
