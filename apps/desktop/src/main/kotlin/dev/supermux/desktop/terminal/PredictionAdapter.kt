package dev.supermux.desktop.terminal

import com.jediterm.terminal.Terminal
import com.jediterm.terminal.model.TerminalTextBuffer
import dev.supermux.net.CursorPos
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.DisplayOp
import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.net.MoveCaret
import dev.supermux.net.Passthrough
import dev.supermux.net.RestoreCell
import dev.supermux.net.ShowCaret

/**
 * Renders the shared `PredictionEngine`'s Step-2 [DisplayOp]s against a JediTerm terminal — the
 * desktop twin of Android's `terminal/PredictionAdapter.kt` and the web `xterm-adapter.ts`. The
 * engine (shared Kotlin, imported directly) owns ALL reconcile/cursor math; this adapter is the
 * thin, mechanical translator.
 *
 * ## Rendering: escape-feed via the ORDERED injection lane (not a cross-thread write)
 * Unlike Android (which calls `emulator.writeInput` synchronously on the main thread) and the web
 * (`term.write`), the desktop adapter emits every op as ANSI bytes through
 * [MuxTtyConnector.injectDisplayBytes], which enqueues them into the SAME FIFO as server output.
 * JediTerm's own emulator thread drains that queue and parses the escapes, so predicted glyphs and
 * authoritative server bytes render in strict enqueue order with no cross-thread rendering race
 * (the whole reason the desktop path uses the connector instead of poking the buffer directly). The
 * escapes are identical to the other platforms: CUP (`ESC[{r};{c}H`), SGR dim/un-dim
 * (`ESC[2m`/`ESC[22m`), DECTCEM hide/show (`ESC[?25l`/`ESC[?25h`).
 *
 * ## Reading the cursor + cells: JediTerm PUBLIC model APIs (NO reflection)
 * Android had to reflect into termlib's `internal` snapshot because termlib exposes no public
 * cursor/cell read API; JediTerm does, so this adapter uses it directly and [available] is a
 * constant `true` (the reflection-failure disable path Android needed simply cannot occur). Reads
 * take the buffer lock (`buffer.lock()/unlock()`, a `ReentrantLock`) so they are consistent against
 * the emulator thread's concurrent writes.
 *
 * ### CursorPos convention (matched to the Android adapter — consistency matters, base doesn't)
 * The shared engine only ever COMPARES cursor positions and does relative `col ± 1` math, so any
 * self-consistent base works — but this adapter matches the Android adapter's semantics exactly so
 * the three platforms stay identical. Android's `cursor()` (PredictionAdapter.kt:99-108) returns
 * termlib's snapshot `cursorRow`/`cursorCol` as-is and its `cup()` (PredictionAdapter.kt:175-176)
 * adds 1 to reach the 1-based CUP escape — i.e. Android treats [CursorPos] as **0-based in both
 * axes**, and `readCell(row, col)` (PredictionAdapter.kt:149-157) indexes the snapshot 0-based.
 * This adapter uses the SAME 0-based [CursorPos]; the JediTerm↔CursorPos conversions are the ONLY
 * platform difference (JediTerm's public cursor getters are 1-based, its `getCharAt` is 0-based):
 *  - [cursor]:  JediTerm `cursorX/cursorY` are 1-based → subtract 1 → 0-based [CursorPos].
 *  - [cup]:     0-based [CursorPos] → CUP is 1-based → add 1 (identical to Android's cup()).
 *  - [readCell]: 0-based (row, col) → `getCharAt(col, row)` (getCharAt is 0-based both axes).
 * These mappings are pinned empirically by `JediTermSmokeTest` (do not re-derive them) — the net
 * "write at cursor / read it back" identity there is `writeString(cursorX-1, cursorY, …)` /
 * `getCharAt(cursorX-1, cursorY-1)`, i.e. exactly the col=cursorX-1, row=cursorY-1 basis used here.
 *
 * ## Snapshot cache
 * Captures a cell's prior character on [DrawDim] (keyed by prediction id, BEFORE the dim write) and
 * restores it on [RestoreCell]. Confirmed predictions never emit a [RestoreCell] (their echo paints
 * over the dim cell via a [Passthrough]), so the map is bounded by [cap], DERIVED from the engine's
 * `maxPending` (+16 headroom) so a live snapshot is never wrongly evicted — identical to Android.
 *
 * ## Read/write timing note
 * Reads ([cursor]/[readCell]) query the buffer synchronously, while renders are QUEUED (drained
 * async by the emulator thread). In steady typing the predicted cell is at/after the caret and
 * already drained, and rollback uses the STORED snapshot (never a re-read), so this matches
 * Android's restore semantics. The trade-off is deliberate: ordered rendering (no interleaving
 * race) in exchange for reads that may momentarily precede a just-queued write.
 */
open class PredictionAdapter(
    private val terminal: Terminal,
    private val buffer: TerminalTextBuffer,
    private val connector: MuxTtyConnector,
) {
    /** prediction id -> the character that occupied the cell before the dim glyph. */
    private val snapshots = HashMap<Int, String>()

    /** Cap on live snapshots, DERIVED from the engine's maxPending (+16 headroom) so it can't drift
     *  below it — the cap must stay above maxPending or a live snapshot could be evicted and its
     *  rollback would repaint a space. Mirrors Android (and web 64 = 50 + 14, iOS +16). */
    private val cap = DEFAULT_CONFIG.maxPending + 16

    /** Always reachable — JediTerm's cursor/cell reads are PUBLIC (no reflection to fail). The field
     *  is kept purely for pipeline-shape parity with Android, whose adapter can be unavailable when
     *  termlib's internal snapshot is unreachable; on desktop that branch is dead but the pipeline
     *  keeps the same `if (!adapter.available) …` shape. `open` for the pipeline's throwing-adapter
     *  test seam. */
    open val available: Boolean = true

    /** Current caret, screen-relative, as a 0-based [CursorPos] (see the class KDoc convention). */
    open fun cursor(): CursorPos {
        buffer.lock()
        try {
            return CursorPos(row = terminal.cursorY - 1, col = terminal.cursorX - 1)
        } finally {
            buffer.unlock()
        }
    }

    /** Render a batch of engine ops by injecting escapes/bytes into the connector's ordered FIFO. */
    open fun render(ops: List<DisplayOp>) {
        for (op in ops) {
            // Assign the `when` to a Unit val so it is an EXHAUSTIVE EXPRESSION: a future 7th
            // DisplayOp added to the shared sealed interface then fails to compile here instead of
            // being silently dropped on desktop (same trick as Android).
            @Suppress("UNUSED_VARIABLE")
            val rendered: Unit = when (op) {
                is HideCaret -> feed(HIDE)
                is ShowCaret -> feed(SHOW)
                is MoveCaret -> feed(cup(op.row, op.col))
                is DrawDim -> {
                    // Snapshot the pre-prediction cell BEFORE the dim write (mirror web/iOS/Android).
                    snapshots[op.id] = readCell(op.row, op.col)
                    evictIfNeeded()
                    feed(cup(op.row, op.col) + DIM + op.char + UNDIM)
                }
                is RestoreCell -> {
                    val prev = snapshots.remove(op.id) ?: " "
                    feed(cup(op.row, op.col) + prev)
                }
                is Passthrough ->
                    // Authoritative server bytes, injected as-is (lossless). Confirmed echoes paint
                    // over their dim cells here — that IS the confirm.
                    connector.injectDisplayBytes(op.bytes)
            }
        }
    }

    /** Inject an escape/text string into the ordered FIFO (parsed by JediTerm exactly like a PTY byte-run). */
    private fun feed(s: String) = connector.injectDisplayBytes(s.encodeToByteArray())

    /** Read the (screen-relative, 0-based) cell's char for a snapshot: `getCharAt(col, row)`. A
     *  fresh/blank cell reads back as a space in JediTerm; NUL / a failed read are treated as a
     *  space too, mirroring the web's `|| " "` and Android's NUL guard. */
    private fun readCell(row: Int, col: Int): String {
        buffer.lock()
        try {
            val ch = buffer.getCharAt(col, row)
            return if (ch == ' ') " " else ch.toString()
        } catch (_: Throwable) {
            return " "
        } finally {
            buffer.unlock()
        }
    }

    /** Keep the snapshot map bounded. Confirmed predictions never emit RestoreCell, so their
     *  snapshots would otherwise linger. Engine ids are monotonic, so the smallest key is the
     *  oldest snapshot — evicting it mirrors the web/iOS/Android "drop the oldest". [cap] sits above
     *  the engine's maxPending, so a live snapshot is never the smallest, hence never evicted. */
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
