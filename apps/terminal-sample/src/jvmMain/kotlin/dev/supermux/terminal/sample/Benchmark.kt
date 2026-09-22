package dev.supermux.terminal.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalKeys
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSessionConfig
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.compose.LocalTerminalScroll
import dev.supermux.terminal.compose.ScrollController
import dev.supermux.terminal.compose.Terminal
import dev.supermux.terminal.compose.TerminalAccessoryState
import dev.supermux.terminal.compose.TerminalTheme
import dev.supermux.terminal.compose.ViewportModel
import dev.supermux.terminal.compose.ViewportRejection
import dev.supermux.terminal.compose.ViewportUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The repeatable terminal benchmark.
 *
 * ```
 * ./gradlew :terminal-sample:benchmark \
 *   -Pbenchmark.args="--minutes=10 --fixture=ansi --rate=4194304 --terminals=4 --out=run.md"
 * ```
 *
 * **What it does, and why in a real window.** Four terminals are mounted on one process, exactly
 * one is visible and focused, and the harness alternates two phases until the clock runs out:
 *
 * 1. **stream** — the fixed 10 MiB fixture (plain or ANSI, generated once and replayed byte for
 *    byte) is fed into every session at the configured rate, while the focused terminal receives
 *    synthetic keys through the SAME accessory path a hardware key takes.
 * 2. **scroll** — the visible surface is scrolled through [BenchmarkOptions.scrollLines] lines of
 *    history, at pixel resolution, through the surface's own `ScrollController`, **while the
 *    stream keeps running**. This is the case the whole package exists for, and it is the only one
 *    where a frame budget can actually be missed.
 *
 * It runs in a real Compose window rather than offscreen because a frame time that never meets a
 * compositor is not a frame time. On a headless host, put a display under it (`xvfb-run`); see the
 * module README.
 *
 * The report is markdown on stdout and, with `--out`, in a file.
 */
fun main(args: Array<String>) {
    val options = try {
        BenchmarkOptions.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("terminal-sample benchmark: ${error.message}")
        System.err.println(BenchmarkOptions.USAGE)
        exitProcess(2)
    }
    println(options.describe())

    if (options.mode == BenchmarkMode.PARSE) {
        // No window, no Compose: this mode exists to say how much of a frame is NOT drawing.
        val text = runParseProfile(options)
        emitReport(text, options)
        exitProcess(0)
    }

    var reported = false
    // `exitProcessOnExit = false`: Compose's `application()` calls `exitProcess(0)` when the last
    // window closes, and with the default it does so BEFORE anything after this block runs — the
    // report would be computed, handed back, and never printed. (Measured the hard way.)
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "terminal-sample benchmark",
            state = rememberWindowState(width = 1400.dp, height = 1000.dp),
        ) {
            BenchmarkHarness(options) { finished ->
                // Written from INSIDE the callback, before the window closes: a ten-minute run
                // whose report is lost to a shutdown race is a ten-minute run wasted.
                reported = true
                emitReport(finished, options)
                exitApplication()
            }
        }
    }
    if (!reported) {
        System.err.println("benchmark: the window closed before the run finished — no report")
        exitProcess(1)
    }
    // AWT keeps non-daemon threads alive; without this the JVM would sit there after the report.
    exitProcess(0)
}

private fun emitReport(text: String, options: BenchmarkOptions) {
    println(text)
    options.out?.let { file ->
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(text)
        println("benchmark: report written to ${file.absolutePath}")
    }
}

// ---------------------------------------------------------------------------------------------
// Options
// ---------------------------------------------------------------------------------------------

data class BenchmarkOptions(
    val minutes: Double = 10.0,
    val fixture: BenchmarkFixture = BenchmarkFixture.ANSI,
    /** Bytes per second per terminal; 0 = unthrottled. */
    val rateBytesPerSecond: Int = 4 * 1024 * 1024,
    val terminals: Int = 4,
    val columns: Int = 120,
    val rows: Int = 40,
    val scrollLines: Int = 50_000,
    /** How long one stream phase lasts before the scroll phase starts. */
    val streamSeconds: Int = 60,
    val fixtureBytes: Int = 10 * 1024 * 1024,
    val warmupSeconds: Int = 10,
    val mode: BenchmarkMode = BenchmarkMode.UI,
    val out: File? = null,
) {
    fun describe(): String = buildString {
        appendLine("terminal-sample benchmark (${mode.id})")
        appendLine("  duration     ${formatDecimal(minutes, 1)} min (plus ${warmupSeconds}s warm-up, not counted)")
        appendLine("  fixture      ${fixture.id}, ${formatBytes(fixtureBytes.toLong())} generated once and replayed")
        appendLine("  rate         ${if (rateBytesPerSecond <= 0) "unthrottled" else formatBytes(rateBytesPerSecond.toLong()) + "/s per terminal"}")
        appendLine("  terminals    $terminals mounted, 1 visible + focused")
        appendLine("  grid target  ${columns}x$rows cells")
        appendLine("  phases       ${streamSeconds}s stream, then $scrollLines lines of scrolling while it continues")
        appendLine("  build        ${if (isReleaseRun()) "release (runRelease/benchmark)" else "DEBUG — do not quote these numbers"}")
    }

    companion object {
        val USAGE = """
            usage: benchmark [options]
              --minutes=<n>       wall-clock minutes to measure (default 10)
              --fixture=plain|ansi
              --rate=<bytes/s>    per terminal, or `max` for unthrottled (default 4194304)
              --terminals=<n>     1..4 (default 4)
              --columns=<n> --rows=<n>   target grid (default 120x40)
              --scroll-lines=<n>  lines per scroll phase (default 50000)
              --stream-seconds=<n> stream phase length before each scroll phase (default 60)
              --fixture-bytes=<n> fixed fixture size (default 10485760)
              --warmup=<seconds>  excluded from the report (default 10)
              --mode=ui|blank|parse
                                  ui    the full harness (default)
                                  blank the same window with NO terminal mounted — the
                                        host's own frame floor on this machine, which is
                                        what a terminal frame time has to be read against
                                  parse no UI at all: engine + codec + ViewportModel only
              --out=<file>        also write the markdown report there
        """.trimIndent()

        fun parse(args: Array<String>): BenchmarkOptions {
            var options = BenchmarkOptions()
            for (arg in args) {
                if (arg.isBlank()) continue
                require(arg.startsWith("--") && arg.contains('=')) { "unrecognised argument `$arg`" }
                val key = arg.substringBefore('=').removePrefix("--")
                val value = arg.substringAfter('=')
                options = when (key) {
                    "minutes" -> options.copy(minutes = value.toDoubleOrNull() ?: bad(arg))
                    "fixture" -> options.copy(
                        fixture = BenchmarkFixture.byId(value) ?: bad(arg),
                    )
                    "rate" -> options.copy(
                        rateBytesPerSecond = if (value == "max") 0 else value.toIntOrNull() ?: bad(arg),
                    )
                    "terminals" -> options.copy(terminals = (value.toIntOrNull() ?: bad(arg)).coerceIn(1, 4))
                    "columns" -> options.copy(columns = value.toIntOrNull() ?: bad(arg))
                    "rows" -> options.copy(rows = value.toIntOrNull() ?: bad(arg))
                    "scroll-lines" -> options.copy(scrollLines = value.toIntOrNull() ?: bad(arg))
                    "stream-seconds" -> options.copy(streamSeconds = value.toIntOrNull() ?: bad(arg))
                    "fixture-bytes" -> options.copy(fixtureBytes = value.toIntOrNull() ?: bad(arg))
                    "warmup" -> options.copy(warmupSeconds = value.toIntOrNull() ?: bad(arg))
                    "mode" -> options.copy(mode = BenchmarkMode.byId(value) ?: bad(arg))
                    "out" -> options.copy(out = File(value))
                    else -> throw IllegalArgumentException("unknown option `--$key`")
                }
            }
            return options
        }

        private fun bad(arg: String): Nothing = throw IllegalArgumentException("bad value in `$arg`")
    }
}

/**
 * What a run measures.
 *
 * The three exist so a missed frame budget can be ATTRIBUTED rather than guessed at: [BLANK] is the
 * host's floor on this machine with no terminal in the window at all, [PARSE] is the engine, the
 * codec and the row model with no window at all, and [UI] is the whole thing. Terminal draw cost is
 * `UI - BLANK`; everything that is not draw shows up in `PARSE`.
 */
enum class BenchmarkMode(val id: String) {
    UI("ui"),
    BLANK("blank"),
    PARSE("parse"),
    ;

    companion object {
        fun byId(id: String): BenchmarkMode? = entries.firstOrNull { it.id == id }
    }
}

// ---------------------------------------------------------------------------------------------
// Measurement
// ---------------------------------------------------------------------------------------------

/** Every frame interval of one phase, kept in full: ten minutes at 60 Hz is 36 000 longs. */
class FrameSeries(private val name: String) {
    private var values = LongArray(1 shl 14)
    private var size = 0

    fun add(micros: Long) {
        if (micros < 0) return
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = micros
    }

    val count: Int get() = size

    /** Throw the samples away (a warm-up pass must not be in the numbers). */
    fun clear() {
        size = 0
    }

    fun percentileMillis(percentile: Double): Double {
        if (size == 0) return 0.0
        val sorted = values.copyOf(size)
        sorted.sort()
        val rank = (percentile / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = min(low + 1, sorted.size - 1)
        return (sorted[low] + (sorted[high] - sorted[low]) * (rank - low)) / 1000.0
    }

    fun maxMillis(): Double {
        var worst = 0L
        for (index in 0 until size) worst = max(worst, values[index])
        return worst / 1000.0
    }

    fun over(millis: Double): Int {
        val micros = (millis * 1000).toLong()
        var count = 0
        for (index in 0 until size) if (values[index] > micros) count++
        return count
    }

    fun row(): String = "| $name | $size | ${formatDecimal(percentileMillis(50.0), 2)} | " +
        "${formatDecimal(percentileMillis(95.0), 2)} | ${formatDecimal(percentileMillis(99.0), 2)} | " +
        "${formatDecimal(maxMillis(), 1)} | ${over(16.7)} | ${over(33.3)} | ${over(100.0)} |"
}

/** One `(elapsed, memory, history)` reading; the series is what says whether RSS grows. */
data class MemorySample(
    val elapsedSeconds: Double,
    val phase: String,
    val rssBytes: Long,
    val heapBytes: Long,
    val historyRows: Long,
    val outputBytes: Long,
)

// ---------------------------------------------------------------------------------------------
// The run
// ---------------------------------------------------------------------------------------------

/**
 * The harness state: the sessions, the phase script, the series.
 *
 * It is NOT a `SampleController`: the sample paces an endless fixture for a human to look at, and
 * the benchmark replays a fixed one to a schedule. Sharing the producer would have made one of the
 * two lie.
 */
class BenchmarkRun(
    val options: BenchmarkOptions,
    private val scope: CoroutineScope,
) {
    val diagnostics = SampleDiagnostics(frameWindow = 4096)
    private val config = TerminalSessionConfig()
    private val limits = TerminalLimits()

    var sessions: List<TerminalSession> by mutableStateOf(emptyList())
        private set
    var phase: String by mutableStateOf("starting")
        private set
    var note: String by mutableStateOf("")
        private set
    var finishedReport: String? by mutableStateOf(null)
        private set

    /** The visible surface's scroll controller, published by the harness's overlay. */
    var scroll: ScrollController? = null

    /** The visible surface's accessory sink — how the harness sends a key the way a user does. */
    val accessories = TerminalAccessoryState()

    /** The grid the visible surface actually settled on; the report quotes this, not the target. */
    var observedColumns: Int = 0
        private set
    var observedRows: Int = 0
        private set
    var observedCellWidthPx: Int = 0
        private set
    var observedCellHeightPx: Int = 0
        private set

    private val streamFrames = FrameSeries("stream")
    private val scrollFrames = FrameSeries("scroll + stream")
    private val warmupFrames = FrameSeries("warm-up (excluded)")
    private var currentSeries: FrameSeries = warmupFrames
    private var lastFrameNanos = 0L
    private var haveFrameBaseline = false

    private val memory = mutableListOf<MemorySample>()
    private val phaseLog = mutableListOf<String>()
    private var producers: List<Job> = emptyList()
    private var keyPresser: Job? = null
    private var start = TimeSource.Monotonic.markNow()

    private val fixtureChunks by lazy {
        benchmarkFixture(options.fixture, options.fixtureBytes)
    }

    /** Called from the harness's frame loop, on the composition's dispatcher. */
    fun onFrameNanos(nanos: Long) {
        val previous = lastFrameNanos
        val hadBaseline = haveFrameBaseline
        lastFrameNanos = nanos
        haveFrameBaseline = true
        if (hadBaseline) currentSeries.add((nanos - previous) / 1_000)
        diagnostics.onFrameNanos(nanos)
    }

    /**
     * Start attributing frames to [series]. The baseline is dropped so the FIRST interval of a
     * phase is not the gap across the phase boundary — a number that belongs to neither phase.
     */
    private fun startPhase(series: FrameSeries) {
        currentSeries = series
        haveFrameBaseline = false
    }

    fun onViewport(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        observedColumns = columns
        observedRows = rows
        observedCellWidthPx = cellWidthPx
        observedCellHeightPx = cellHeightPx
    }

    /** The whole script. Runs on the composition's dispatcher; the producers do not. */
    suspend fun execute() {
        if (options.mode == BenchmarkMode.BLANK) {
            executeBlankControl()
            return
        }
        phase = "opening ${options.terminals} sessions"
        val opened = ArrayList<TerminalSession>(options.terminals)
        repeat(options.terminals) {
            opened += TerminalSession.open(
                size = TerminalSize(options.columns, options.rows, 8, 16),
                limits = limits,
                colors = TerminalTheme().engineColors(),
                config = config,
                effects = { effect ->
                    when (effect) {
                        is TerminalEffect.Input -> diagnostics.onInput(effect.bytes.size)
                        is TerminalEffect.Response -> diagnostics.onResponse(effect.bytes.size)
                        else -> Unit
                    }
                },
            )
        }
        diagnostics.outputQueueCapBytes = config.maxPendingOutputBytes
        diagnostics.inputQueueCapBytes = config.maxPendingInputBytes
        diagnostics.mailboxCapacity = config.mailboxCapacity
        diagnostics.historyLinesLimit = limits.historyLines
        diagnostics.historyBytesLimit = limits.historyBytes
        sessions = opened
        // Exactly one terminal is focused, and the engine is told: focus reporting (mode 1004) is
        // part of what a program sees, so "one focused" has to be true at the ENGINE, not just in
        // the layout.
        opened.forEachIndexed { index, session -> session.focus(index == 0) }

        // Give the surfaces a chance to measure, resize and take their leases before anything is
        // timed: the first frames of a Compose window are not representative of anything.
        phase = "warm-up"
        startPhase(warmupFrames)
        startProducers(opened)
        startKeyPresser()
        repeat(options.warmupSeconds) {
            delay(1000)
            sampleMemory("warm-up")
        }

        diagnostics.reset()
        start = TimeSource.Monotonic.markNow()
        val deadline = (options.minutes * 60_000).toLong()
        var repeatIndex = 0
        while (start.elapsedNow().inWholeMilliseconds < deadline) {
            repeatIndex++
            // ---- stream phase ------------------------------------------------------------
            phase = "repeat $repeatIndex · stream"
            startPhase(streamFrames)
            val streamStart = start.elapsedNow().inWholeMilliseconds
            val streamBytesBefore = diagnostics.snapshot().outputBytes
            val streamPhaseMillis = options.streamSeconds * 1000L
            while (start.elapsedNow().inWholeMilliseconds - streamStart < streamPhaseMillis &&
                start.elapsedNow().inWholeMilliseconds < deadline
            ) {
                delay(500)
                sampleMemory("stream")
            }
            val streamMillis = start.elapsedNow().inWholeMilliseconds - streamStart
            val streamBytes = diagnostics.snapshot().outputBytes - streamBytesBefore
            phaseLog += "repeat $repeatIndex stream: ${formatBytes(streamBytes)} in ${streamMillis} ms " +
                "(${formatDecimal(streamBytes * 1000.0 / streamMillis / (1024 * 1024), 2)} MiB/s across " +
                "${options.terminals} terminals)"

            if (start.elapsedNow().inWholeMilliseconds >= deadline) break

            // ---- scroll phase ------------------------------------------------------------
            phase = "repeat $repeatIndex · scroll + stream"
            startPhase(scrollFrames)
            val scrolled = scrollThroughHistory(options.scrollLines.toLong())
            phaseLog += "repeat $repeatIndex scroll: $scrolled of ${options.scrollLines} lines " +
                "(history holds ${diagnostics.snapshot().historyRows} rows; the rest is what the " +
                "engine's line/byte budget had already evicted)"
            sampleMemory("scroll")
        }

        phase = "stopping"
        producers.forEach { it.cancel() }
        keyPresser?.cancel()
        withContext(NonCancellable) {
            sessions.forEach { runCatching { it.close() } }
        }
        diagnostics.onSessionDisposed()
        sampleMemory("after close")
        finishedReport = report()
        phase = "done"
    }

    /**
     * The control run: the SAME window, the SAME frame probe, and no terminal at all.
     *
     * It answers the only question that makes a missed frame budget interpretable on an unknown
     * machine — "what does a frame cost here before this package does anything?" — and it is why
     * the report of a `ui` run should never be read on its own.
     */
    private suspend fun executeBlankControl() {
        phase = "blank control · warm-up"
        startPhase(warmupFrames)
        repeat(options.warmupSeconds) {
            delay(1000)
            sampleMemory("warm-up")
        }
        diagnostics.reset()
        start = TimeSource.Monotonic.markNow()
        phase = "blank control"
        startPhase(streamFrames)
        val deadline = (options.minutes * 60_000).toLong()
        while (start.elapsedNow().inWholeMilliseconds < deadline) {
            delay(500)
            sampleMemory("blank")
        }
        phaseLog += "blank control: no session, no surface — this is the host's own frame floor"
        finishedReport = report()
        phase = "done"
    }

    /**
     * Scroll the visible surface up through history and back down, one frame at a time, WHILE the
     * producers keep feeding.
     *
     * This drives the real `ScrollController` — the one `Modifier.scrollable` drives from a finger
     * — so every row boundary is a real `scrollTo` enqueue that the session may refuse, and every
     * sub-row step is a real paint offset. Anything cheaper would measure a different program.
     *
     * Returns how many rows it actually covered: the engine evicts history under a live stream, so
     * "50 000 lines" is a target, not a promise, and the report says which it got.
     */
    private suspend fun scrollThroughHistory(lines: Long): Long {
        val controller = scroll ?: run {
            note = "no scroll controller: the visible surface never composed"
            return 0
        }
        val cell = max(1, observedCellHeightPx).toFloat()
        // Three rows per frame up: fast enough to cover 50k lines in minutes, slow enough that
        // every frame really does cross a row boundary (which is the expensive path).
        val stepPx = cell * ROWS_PER_FRAME
        var covered = 0L
        var lastRow = controller.position.row
        var idleFrames = 0
        while (covered < lines) {
            withFrameNanos { }
            controller.consumePx(-stepPx)
            val row = controller.position.row
            val moved = lastRow - row
            if (moved > 0) {
                covered += moved
                idleFrames = 0
            } else if (row == 0L) {
                // Bottom of history: turn around and go back down, which is the other direction's
                // overscan row and the follow-bottom transition.
                covered += rideBackToBottom(controller, cell)
                if (covered >= lines) break
                idleFrames = 0
            } else if (++idleFrames > IDLE_FRAME_LIMIT) {
                note = "scroll made no progress for $IDLE_FRAME_LIMIT frames at row $row"
                break
            }
            lastRow = row
        }
        rideBackToBottom(controller, cell)
        return covered
    }

    private suspend fun rideBackToBottom(controller: ScrollController, cell: Float): Long {
        var covered = 0L
        var lastRow = controller.position.row
        var guard = 0
        while (!controller.following && guard++ < BOTTOM_FRAME_LIMIT) {
            withFrameNanos { }
            controller.consumePx(cell * ROWS_PER_FRAME * 4)
            val row = controller.position.row
            covered += max(0L, row - lastRow)
            lastRow = row
            if (row >= controller.newestTop) break
        }
        controller.followBottom()
        return covered
    }

    private fun startProducers(sessions: List<TerminalSession>) {
        producers = sessions.map { session ->
            scope.launch(Dispatchers.Default) {
                val chunks = fixtureChunks
                val startedAt = TimeSource.Monotonic.markNow()
                var sent = 0L
                var index = 0
                while (true) {
                    val chunk = chunks[index]
                    index = (index + 1) % chunks.size
                    diagnostics.onReceiveStart(chunk.size)
                    val mark = TimeSource.Monotonic.markNow()
                    var waited = 0L
                    try {
                        session.receive(chunk, OutputOrigin.LIVE)
                        val micros = mark.elapsedNow().inWholeMicroseconds
                        if (micros > 1_000) waited = micros
                        diagnostics.onOutput(chunk.size)
                    } finally {
                        diagnostics.onReceiveEnd(chunk.size, waited)
                    }
                    sent += chunk.size
                    val rate = options.rateBytesPerSecond
                    if (rate <= 0) {
                        yield()
                    } else {
                        val due = sent * 1000L / rate
                        val elapsed = startedAt.elapsedNow().inWholeMilliseconds
                        if (due > elapsed) delay(due - elapsed) else yield()
                    }
                }
            }
        }
    }

    /**
     * The focused terminal's synthetic typing.
     *
     * It goes through [accessories], which is bound to the visible `Terminal` — so a key takes the
     * SAME path a hardware key takes (armed modifiers, the key router, the engine's encoder) and
     * the bytes it produces are counted as input bytes by the effects consumer. Sending
     * `session.key` directly would have skipped the surface entirely and measured less.
     */
    private fun startKeyPresser() {
        keyPresser = scope.launch {
            val keys = listOf(TerminalKeys.A, TerminalKeys.B, TerminalKeys.ENTER, TerminalKeys.ARROW_UP)
            var index = 0
            while (true) {
                delay(KEY_INTERVAL_MILLIS)
                if (phase.contains("scroll")) continue // typing would yank the view back to the bottom
                val code = keys[index++ % keys.size]
                val text = when (code) {
                    TerminalKeys.A -> "a"
                    TerminalKeys.B -> "b"
                    else -> ""
                }
                // Through the accessory sink the visible `Terminal` bound while it composed, so
                // the key takes the surface's own router and encoder path. If no terminal is
                // composed the state is unbound and this is a no-op by construction — which is
                // also the package's documented behaviour, not a silent drop invented here.
                accessories.sendKey(code, text)
            }
        }
    }

    private fun sampleMemory(phaseName: String) {
        val now = diagnostics.sampleMemory()
        val snapshot = diagnostics.snapshot()
        memory += MemorySample(
            elapsedSeconds = start.elapsedNow().inWholeMilliseconds / 1000.0,
            phase = phaseName,
            rssBytes = now.rssBytes,
            heapBytes = now.heapUsedBytes,
            historyRows = snapshot.historyRows,
            outputBytes = snapshot.outputBytes,
        )
    }

    // ---- report -------------------------------------------------------------------------------

    private fun report(): String {
        val snapshot = diagnostics.snapshot()
        val measured = memory.filter { it.phase != "warm-up" }
        val lastSample = measured.lastOrNull()
        val firstRss = measured.firstOrNull()?.rssBytes ?: 0
        val lastRss = measured.lastOrNull()?.rssBytes ?: 0
        return buildString {
            appendLine("# terminal-sample benchmark")
            appendLine()
            appendLine("```")
            append(options.describe())
            appendLine("```")
            appendLine()
            appendLine("Grid the visible surface settled on: **${observedColumns}x$observedRows cells** " +
                "(cell ${observedCellWidthPx}x$observedCellHeightPx px); target was " +
                "${options.columns}x${options.rows}.")
            if (note.isNotEmpty()) appendLine().appendLine("> note: $note")
            appendLine()
            appendLine("## Frame times (ms)")
            appendLine()
            appendLine("| phase | frames | p50 | p95 | p99 | max | >16.7 | >33.3 | >100 |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
            appendLine(streamFrames.row())
            appendLine(scrollFrames.row())
            appendLine(warmupFrames.row())
            appendLine()
            appendLine("## Throughput, queues, effects")
            appendLine()
            appendLine("| metric | value |")
            appendLine("|---|---|")
            appendLine("| output bytes fed | ${formatBytes(snapshot.outputBytes)} (${snapshot.outputBytes}) |")
            appendLine("| input bytes (engine-encoded) | ${snapshot.inputBytes} |")
            appendLine("| response bytes | ${snapshot.responseBytes} |")
            appendLine("| output queue cap | ${snapshot.outputQueueCapBytes} B per session |")
            appendLine("| output queue high water | ${snapshot.pendingOutputHighWaterBytes} B in flight |")
            appendLine("| backpressure | ${snapshot.backpressureEvents} waits, ${snapshot.backpressureMicros / 1000} ms total |")
            appendLine("| refused enqueues | ${snapshot.rejectedEnqueues} |")
            appendLine("| frames published | ${snapshot.framesPublished} (full ${snapshot.framesFull}) |")
            appendLine("| observer gaps | ${snapshot.sequenceGaps} |")
            appendLine("| engine history | ${snapshot.historyRows} rows now, peak ${snapshot.historyRowsHighWater}, " +
                "limit ${snapshot.historyLinesLimit} lines / ${formatBytes(snapshot.historyBytesLimit)} |")
            appendLine()
            appendLine("## Memory over time")
            appendLine()
            appendLine("| t (s) | phase | rss | heap | history rows | output so far |")
            appendLine("|---:|---|---:|---:|---:|---:|")
            // One row every ~10 s keeps the table readable; the shape is what matters.
            var lastPrinted = -100.0
            for (sample in measured) {
                if (sample.elapsedSeconds - lastPrinted < 10.0 && sample !== lastSample) continue
                lastPrinted = sample.elapsedSeconds
                appendLine(
                    "| ${formatDecimal(sample.elapsedSeconds, 0)} | ${sample.phase} | " +
                        "${formatBytes(sample.rssBytes)} | ${formatBytes(sample.heapBytes)} | " +
                        "${sample.historyRows} | ${formatBytes(sample.outputBytes)} |",
                )
            }
            appendLine()
            appendLine("RSS ${formatBytes(firstRss)} → ${formatBytes(lastRss)} " +
                "(peak ${formatBytes(snapshot.peakRssBytes)}); heap peak ${formatBytes(snapshot.peakHeapBytes)}.")
            appendLine()
            appendLine("## Phase log")
            appendLine()
            phaseLog.forEach { appendLine("- $it") }
        }
    }

    private companion object {
        const val ROWS_PER_FRAME = 8f
        const val IDLE_FRAME_LIMIT = 600
        const val BOTTOM_FRAME_LIMIT = 4000
        const val KEY_INTERVAL_MILLIS = 250L
    }
}

// ---------------------------------------------------------------------------------------------
// The harness UI
// ---------------------------------------------------------------------------------------------

@Composable
private fun BenchmarkHarness(options: BenchmarkOptions, onFinished: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val run = remember(options) { BenchmarkRun(options, scope) }
    LaunchedEffect(run) { run.execute() }
    LaunchedEffect(run) {
        while (true) withFrameNanos { run.onFrameNanos(it) }
    }
    val report = run.finishedReport
    LaunchedEffect(report) { report?.let(onFinished) }

    // The pane is grown/shrunk until the surface reports the target grid: `floor(px / cellWidth)`
    // with a fractional cell width cannot be inverted analytically, so the harness converges on it
    // instead of guessing — and the report quotes the grid it actually got either way.
    var paneWidthPx by remember { mutableStateOf(options.columns * 9f) }
    var paneHeightPx by remember { mutableStateOf(options.rows * 19f) }
    // Bounded: a pane that kept chasing a grid it cannot hit would resize the session forever, and
    // a resize is the one thing that throws away every surface's rows.
    var adjustments by remember { mutableStateOf(0) }
    // Only ever adjust once per DISTINCT observed grid. The viewport stream keeps reporting the
    // old grid for several frames after a resize is requested, and acting on each of those burns
    // the adjustment budget shrinking the pane over and over (measured: a 120x40 target settling
    // at 159x21 and then 134x34).
    var lastAdjustedGrid by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val density = LocalDensity.current
    val accessoryStates = remember(options.terminals) {
        List(options.terminals) { TerminalAccessoryState() }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0C0A))) {
        Text(
            "benchmark · ${run.phase} · grid ${run.observedColumns}x${run.observedRows}" +
                if (run.note.isEmpty()) "" else " · ${run.note}",
            color = Color(0xFFC2E58C),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(6.dp),
        )
        Box(Modifier.fillMaxSize()) {
            if (options.mode == BenchmarkMode.BLANK) {
                Text(
                    "blank control — no terminal is mounted",
                    color = Color(0xFF6F7A6C),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(6.dp),
                )
                return@Box
            }
            val sessions = run.sessions
            // Non-visible terminals first so the visible one is painted over them; all four are
            // composed and laid out at the same size.
            for (index in sessions.indices.sortedBy { it == 0 }) {
                val session = sessions[index]
                val visible = index == 0
                Box(
                    // `requiredSize`, not `size`: the pane must be the grid's size even when the
                    // window is smaller, or the harness would silently measure a different grid.
                    Modifier.requiredSize(
                        width = with(density) { paneWidthPx.toDp() },
                        height = with(density) { paneHeightPx.toDp() },
                    ),
                ) {
                    Terminal(
                        session = session,
                        modifier = Modifier.fillMaxSize(),
                        theme = TerminalTheme(),
                        active = visible,
                        accessories = if (visible) run.accessories else accessoryStates[index],
                        overlay = {
                            if (visible) {
                                val scroll = LocalTerminalScroll.current
                                SideEffect { run.scroll = scroll }
                            }
                        },
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                ObserveGrid(sessions[0], run) { columns, rows, _, _ ->
                    val observed = columns to rows
                    val onTarget = columns == options.columns && rows == options.rows
                    if (!onTarget && observed != lastAdjustedGrid &&
                        adjustments < MAX_GRID_ADJUSTMENTS && columns > 0 && rows > 0
                    ) {
                        adjustments++
                        lastAdjustedGrid = observed
                        paneWidthPx = paneForTarget(paneWidthPx, columns, options.columns)
                        paneHeightPx = paneForTarget(paneHeightPx, rows, options.rows)
                    }
                }
            }
        }
    }
}

/** How many times the harness may resize its pane chasing the target grid. */
private const val MAX_GRID_ADJUSTMENTS = 8

/**
 * The pane size that should yield [target] cells, given that [pane] px yielded [actual].
 *
 * The surface computes `cells = floor(pane / cellSize)` with a FRACTIONAL cell size the harness
 * cannot read (the published `cellWidthPx` is the rounded one, and rounding is exactly what makes
 * the naive `pane * target / actual` overshoot and then oscillate — measured: a 120x40 target
 * settling at 159x21). So invert it properly: the observation bounds the real cell size to
 * `(pane/(actual+1), pane/actual]`, take its midpoint, and aim at the MIDDLE of the target's
 * interval rather than its edge. Two observations are then enough, and a boundary can never be
 * straddled.
 */
private fun paneForTarget(pane: Float, actual: Int, target: Int): Float {
    if (actual == target || actual <= 0 || target <= 0) return pane
    val cellSize = pane / (actual + 0.5f)
    return (target + 0.5f) * cellSize
}

@Composable
private fun ObserveGrid(
    session: TerminalSession,
    run: BenchmarkRun,
    onGrid: (Int, Int, Float, Float) -> Unit,
) {
    LaunchedEffect(session) {
        session.viewports.collect { viewport ->
            run.onViewport(
                viewport.size.columns,
                viewport.size.rows,
                viewport.size.cellWidthPx,
                viewport.size.cellHeightPx,
            )
            run.diagnostics.onPublishedFrame(
                columns = viewport.size.columns,
                rows = viewport.size.rows,
                cellWidthPx = viewport.size.cellWidthPx,
                cellHeightPx = viewport.size.cellHeightPx,
                historyRows = viewport.historyRows,
                full = viewport.full,
            )
            onGrid(
                viewport.size.columns,
                viewport.size.rows,
                viewport.size.cellWidthPx.toFloat(),
                viewport.size.cellHeightPx.toFloat(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// `--mode=parse`: everything except the window
// ---------------------------------------------------------------------------------------------

/**
 * Feed the fixed fixture through a real session with a real renderer's ACK loop, and **no UI at
 * all**, timing the two stages a frame pays for before anything is drawn:
 *
 * - the **parser + codec + session**: how fast `receive` → parse → `viewport()` → publish can go
 *   when nobody is drawing. This is the ceiling a surface can never beat.
 * - the **row model**: `ViewportModel.apply` per published frame — the "snapshot copy" stage, which
 *   is where a partial frame is patched into the previous screen.
 *
 * Whatever a UI frame costs beyond these two is layout and draw. That subtraction is the whole
 * reason this mode exists: without it, "the frame budget was missed" cannot be attributed.
 */
private fun runParseProfile(options: BenchmarkOptions): String = runBlocking {
    val chunks = benchmarkFixture(options.fixture, options.fixtureBytes)
    val size = TerminalSize(options.columns, options.rows, 8, 16)
    val limits = TerminalLimits()
    val config = TerminalSessionConfig()
    val applySeries = FrameSeries("ViewportModel.apply")
    var published = 0L
    var fullFrames = 0L
    var rejected = 0L

    val session = TerminalSession.open(
        size = size,
        limits = limits,
        colors = TerminalTheme().engineColors(),
        config = config,
    )
    val model = ViewportModel()
    // A renderer that acknowledges as fast as it can: the session publishes the next frame only
    // after an ack, so without this loop the engine would parse the whole fixture and publish once.
    val renderer = launch(Dispatchers.Default) {
        session.viewports.collect { viewport ->
            val mark = TimeSource.Monotonic.markNow()
            val update = model.apply(viewport)
            applySeries.add(mark.elapsedNow().inWholeNanoseconds / 1000)
            published++
            if (viewport.full) fullFrames++
            if (update is ViewportUpdate.Rejected) {
                rejected++
                if (update.reason == ViewportRejection.NEEDS_FULL) session.requestFullFrame()
            }
            session.acknowledge(viewport.generation)
        }
    }

    // Warm-up pass, discarded: the first megabyte is JIT.
    for (chunk in chunks.take(chunks.size / 10)) session.receive(chunk, OutputOrigin.LIVE)
    applySeries.clear()
    published = 0
    fullFrames = 0
    rejected = 0

    val rssBefore = readSampleMemory()
    val started = TimeSource.Monotonic.markNow()
    var fed = 0L
    val repeats = max(1, (options.minutes * 60_000).toInt() / 20_000)
    repeat(repeats) {
        for (chunk in chunks) {
            session.receive(chunk, OutputOrigin.LIVE)
            fed += chunk.size
        }
    }
    val elapsedMillis = started.elapsedNow().inWholeMilliseconds
    val rssAfter = readSampleMemory()
    renderer.cancel()
    val historyRows = session.viewports.value.historyRows
    session.close()

    buildString {
        appendLine("# terminal-sample benchmark — parse profile (no UI)")
        appendLine()
        appendLine("```")
        append(options.describe())
        appendLine("```")
        appendLine()
        appendLine("| metric | value |")
        appendLine("|---|---|")
        appendLine("| fixture | ${options.fixture.id}, ${formatBytes(options.fixtureBytes.toLong())} x $repeats |")
        appendLine("| grid | ${options.columns}x${options.rows} |")
        appendLine("| bytes fed | ${formatBytes(fed)} |")
        appendLine("| wall time | $elapsedMillis ms |")
        appendLine("| throughput | ${formatDecimal(fed / 1024.0 / 1024.0 / (elapsedMillis / 1000.0), 2)} MiB/s |")
        appendLine("| frames published | $published (full $fullFrames, rejected $rejected) |")
        appendLine("| bytes per published frame | ${if (published > 0) fed / published else 0} |")
        appendLine("| ViewportModel.apply p50 | ${formatDecimal(applySeries.percentileMillis(50.0), 3)} ms |")
        appendLine("| ViewportModel.apply p95 | ${formatDecimal(applySeries.percentileMillis(95.0), 3)} ms |")
        appendLine("| ViewportModel.apply max | ${formatDecimal(applySeries.maxMillis(), 3)} ms |")
        appendLine("| engine history at end | $historyRows rows |")
        appendLine("| rss | ${formatBytes(rssBefore.rssBytes)} → ${formatBytes(rssAfter.rssBytes)} |")
        appendLine()
        appendLine(
            "Read this against a `--mode=ui` run of the same grid: the per-frame cost above is what " +
                "a frame pays BEFORE layout and draw, and a `--mode=blank` run is what the host " +
                "costs with no terminal in the window at all.",
        )
    }
}
