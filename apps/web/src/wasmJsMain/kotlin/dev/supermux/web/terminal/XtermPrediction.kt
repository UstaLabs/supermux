package dev.supermux.web.terminal

import dev.supermux.net.CursorPos
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.DisplayOp
import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.net.MoveCaret
import dev.supermux.net.Passthrough
import dev.supermux.net.PredictionEngine
import dev.supermux.net.RestoreCell
import dev.supermux.net.ShowCaret
import dev.supermux.net.decodeInput
import dev.supermux.ui.terminal.PredictionSink

/**
 * Renders the shared `PredictionEngine`'s display ops against an xterm.js [Terminal] — the web
 * host's twin of `JediTermPredictionAdapter` (desktop) and `TermlibPredictionAdapter` (Android),
 * and a direct port of the retired Vue PWA's xterm predictive-echo adapter
 * (retired Vue PWA; see git history before 2026-09-12). The engine owns ALL reconcile and cursor math; this is the thin,
 * mechanical translator.
 *
 * ## Rendering
 * Every op is a separate `term.write`, exactly as the TS adapter did: xterm batches all writes
 * issued in one tick into a single render, so a whole op batch paints in one frame with no
 * intermediate caret flicker (the engine's Hide/ShowCaret bracket is belt-and-braces on top).
 * Escapes are the ones all four clients emit: CUP (`ESC[{r};{c}H`, **1-based** — [CursorPos] is
 * 0-based, hence the `+ 1`), SGR dim/un-dim (`ESC[2m`/`ESC[22m` — un-dim only, so the cell keeps
 * its other attributes), DECTCEM hide/show (`ESC[?25l`/`ESC[?25h`).
 *
 * ## Snapshots
 * The adapter owns the pre-prediction cell snapshots: a cell's prior character is captured on
 * [DrawDim] (BEFORE the dim write, keyed by prediction id) and rewritten on [RestoreCell].
 * Confirmed predictions never emit a [RestoreCell] — their echo paints over the dim cell through a
 * [Passthrough], which IS the confirm — so the map is bounded by [cap], derived from the engine's
 * `maxPending` (+16 headroom) so a live snapshot can never be evicted. Ids are monotonic, so the
 * smallest key is the oldest snapshot.
 *
 * ## available
 * Always true: xterm's cursor and cell reads are public API (unlike Android's reflection into
 * termlib's internal snapshot, which is the reason the flag exists at all).
 */
class XtermPredictionSink(private val term: Terminal) : PredictionSink {
    private val snapshots = HashMap<Int, String>()
    private val cap = DEFAULT_CONFIG.maxPending + 16

    override val available: Boolean = true

    /**
     * Current caret, viewport-relative and 0-based on both axes (matches CUP after the `+ 1`).
     *
     * TIMING: `term.write` is ASYNCHRONOUS — xterm queues the data and its parser drains it on a
     * later task — so this reads the caret as of the last DRAINED write, which can lag bytes
     * already queued. That is the old PWA adapter's behaviour verbatim (`xterm-adapter.ts` read
     * `buffer.active` the same way) and desktop's too (reads are synchronous against a buffer the
     * emulator thread writes asynchronously), so the three clients mispredict identically and the
     * engine's own reconcile is what corrects it: a wrong prediction is rolled back from the
     * STORED snapshot on the next server batch, never from a re-read. If this ever needs to be
     * exact, gate [XtermPredictionPipeline.handleInput] on the `write(data, callback)` overload —
     * at the cost of pushing every keystroke's echo a task later, which is the latency prediction
     * exists to hide.
     */
    override fun cursor(): CursorPos = CursorPos(row = xtermCursorRow(term), col = xtermCursorCol(term))

    override fun render(ops: List<DisplayOp>) {
        for (op in ops) {
            // Assigned to a Unit val so the `when` is an EXHAUSTIVE EXPRESSION: a seventh DisplayOp
            // added to the shared sealed interface then fails to compile here instead of being
            // silently dropped on web (same trick as desktop/Android).
            @Suppress("UNUSED_VARIABLE")
            val rendered: Unit = when (op) {
                is HideCaret -> write(HIDE)
                is ShowCaret -> write(SHOW)
                is MoveCaret -> write(cup(op.row, op.col))
                is DrawDim -> {
                    snapshots[op.id] = xtermReadCell(term, op.row, op.col)
                    evictIfNeeded()
                    write(cup(op.row, op.col) + DIM + op.char + UNDIM)
                }
                is RestoreCell -> {
                    val prev = snapshots.remove(op.id) ?: " "
                    write(cup(op.row, op.col) + prev)
                }
                // Authoritative server bytes, written as-is (lossless: a multi-byte glyph split
                // across two frames is xterm's parser's problem, not ours).
                is Passthrough -> term.write(op.bytes.toJsBytes())
            }
        }
    }

    /** Drop every snapshot (teardown / re-attach). */
    fun reset() = snapshots.clear()

    private fun write(s: String) = term.write(s.toJsString())

    private fun evictIfNeeded() {
        if (snapshots.size <= cap) return
        val oldest = snapshots.keys.minOrNull() ?: return
        snapshots.remove(oldest)
    }

    private companion object {
        const val DIM = "[2m"
        const val UNDIM = "[22m" // un-dim only (not [0m) — preserves the cell's other attrs
        const val HIDE = "[?25l"
        const val SHOW = "[?25h"

        /** Absolute cursor position (CUP). Row/col are 0-based here, 1-based in the escape. */
        fun cup(row: Int, col: Int) = "[${row + 1};${col + 1}H"
    }
}

/**
 * Engine + [XtermPredictionSink] + keystroke→echo RTT clock for one terminal — the web port of
 * desktop's `PredictionPipeline`, MINUS its monitor.
 *
 * THREADING: the browser is single-threaded and every entry point here lands on that one thread —
 * `onData` (a DOM event), the `client.output` collector (a coroutine on the single wasm
 * dispatcher), and attach/teardown (composition). Desktop needs `synchronized` because JediTerm
 * taps input on its own write-executor thread; there is no second thread here, and `synchronized`
 * does not exist on wasm. The ORDERING contract is unchanged: [handleInput] renders the predicted
 * ops BEFORE the bytes leave for the pty.
 *
 * @param nowMs monotonic-enough millisecond clock; injectable so tests can drive the RTT.
 */
class XtermPredictionPipeline(private val nowMs: () -> Long = { monotonicMs() }) {
    private var engine: PredictionEngine? = null
    private var sink: XtermPredictionSink? = null

    /** nowMs of the last keystroke still awaiting its echo (0 = none). Bootstraps the latency gate
     *  from a real RTT, independently of the prediction path — without it the gate never opens
     *  (latency starts at 0, predictions need latency >= threshold). */
    private var lastKeyAt = 0L

    /** Build the engine + sink once the terminal exists (mirrors every other host's `attach`). */
    fun attach(term: Terminal) = attachSink(XtermPredictionSink(term))

    /** Seam shared by [attach] and tests. Keeps the engine null when the sink is unavailable — a
     *  dead branch on web (as on desktop), kept so the ports stay line-for-line comparable. */
    internal fun attachSink(s: XtermPredictionSink) {
        lastKeyAt = 0L
        if (!s.available) {
            sink = null
            engine = null
            return
        }
        sink = s
        engine = PredictionEngine(DEFAULT_CONFIG) { nowMs() }
    }

    /** INPUT: decode the keystroke, render the predicted ops, then stamp the RTT clock. Called from
     *  `onData` BEFORE the bytes reach `sendInput`. No-op until attached. */
    fun handleInput(data: ByteArray) {
        val e = engine ?: return
        val s = sink ?: return
        s.render(e.onInput(decodeInput(data.decodeToString()), s.cursor()))
        lastKeyAt = nowMs()
    }

    /** OUTPUT: bootstrap the latency estimate from the keystroke→echo RTT, then let the engine
     *  reconcile and re-emit the server bytes through its ops (the Passthrough op carries them —
     *  there is NO separate write). Before attach / after teardown, [fallback] writes them so no
     *  byte is lost. */
    fun handleOutput(bytes: ByteArray, fallback: () -> Unit) {
        val e = engine ?: return fallback()
        val s = sink ?: return fallback()
        if (lastKeyAt > 0L) {
            e.setLatencyEstimate(nowMs() - lastKeyAt)
            lastKeyAt = 0L
        }
        // Guard ONLY the predicted-output render: an exception here would propagate out of
        // output.collect and CANCEL the collector — the terminal would freeze, which is far worse
        // than losing prediction. The INPUT path is deliberately NOT guarded: engine bugs should
        // surface there rather than be masked.
        runCatching { s.render(e.onServerData(bytes)) }.onFailure { fallback() }
    }

    /** Drop the engine + sink (teardown). Later output falls back to a direct write. */
    fun teardown() {
        sink?.reset()
        engine = null
        sink = null
        lastKeyAt = 0L
    }
}

/** `performance.now()` — monotonic and immune to a wall-clock jump mid-keystroke, which is what an
 *  RTT estimate needs. `kotlin.js.Date` does not exist on the wasm target. */
private fun monotonicMs(): Long = performanceNow().toLong()

private fun performanceNow(): Double = js("performance.now()")
