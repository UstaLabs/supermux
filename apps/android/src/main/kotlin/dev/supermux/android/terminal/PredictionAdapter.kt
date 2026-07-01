package dev.supermux.android.terminal

import dev.supermux.net.CursorPos
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.DisplayOp
import dev.supermux.net.DrawDim
import dev.supermux.net.HideCaret
import dev.supermux.net.MoveCaret
import dev.supermux.net.Passthrough
import dev.supermux.net.RestoreCell
import dev.supermux.net.ShowCaret
import java.lang.reflect.Method
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator

/**
 * Renders the shared `PredictionEngine`'s Step-2 [DisplayOp]s against the ConnectBot
 * termlib emulator. The engine (shared Kotlin, imported DIRECTLY - no SKIE bridging)
 * owns ALL reconcile logic and cursor math; this adapter is the thin, mechanical
 * translator - the Android twin of the web `xterm-adapter.ts` and iOS
 * `PredictionAdapter.swift`.
 *
 * ## Rendering: escape-feed (SPIKE-verified)
 * termlib 0.0.35 wraps **libvterm** (the same C VT engine Neovim uses) via JNI, so
 * `writeInput` parses the SAME ANSI escapes the web/iOS adapters feed xterm/SwiftTerm -
 * CUP (`ESC[{r};{c}H`), SGR dim/un-dim (`ESC[2m`/`ESC[22m`), and DECTCEM hide/show
 * (`ESC[?25l`/`ESC[?25h`). The three platforms therefore render predictions identically.
 *
 * ## Reading the cursor + cells: reflection into termlib's internal snapshot
 * Unlike xterm.js (`buffer.active.cursorX/Y`) and SwiftTerm (`getCursorLocation()`),
 * termlib 0.0.35 exposes **NO public cursor/cell read API**: the `TerminalEmulator`
 * interface has none, and the entire read model (`TerminalEmulatorImpl.snapshot`,
 * `TerminalSnapshot`, `TerminalLine`) is Kotlin-`internal`, so the app module cannot
 * name it. We therefore read the live [StateFlow]`<TerminalSnapshot>` reflectively from
 * the impl's `getSnapshot$lib()` accessor and pull `cursorRow`/`cursorCol`/`lines` off the
 * snapshot object. This is safe here because R8/minification is OFF for this app (its
 * build.gradle keeps termlib's JNI callback names) and the termlib version is pinned to
 * 0.0.35 - but it reaches past a module boundary and is the one part of this adapter that a
 * termlib bump could break. If any of the reflection is unavailable, [available] is false and
 * prediction is disabled wholesale (the terminal still works, just without predictive echo).
 * See the phase-4 plan's SPIKE for the alternatives (a public termlib cursor accessor, or a
 * Java-interop shim).
 *
 * Snapshots: captures a cell's prior character on [DrawDim] (keyed by prediction id, BEFORE
 * the dim write) and restores it on [RestoreCell]. Confirmed predictions never emit a
 * [RestoreCell] (their echo paints over the dim cell via a [Passthrough]), so the map is
 * bounded by eviction - [cap] is DERIVED from the engine's maxPending (+16 headroom) so a
 * live snapshot is never wrongly evicted.
 */
class PredictionAdapter(private val emulator: TerminalEmulator) {
    /** prediction id -> the character that occupied the cell before the dim glyph. */
    private val snapshots = HashMap<Int, String>()

    /** Cap on live snapshots, DERIVED from the engine's maxPending (not a bare literal) so it
     *  can't silently drift below it - the cap must stay above maxPending or a live snapshot
     *  could be evicted and its rollback would repaint a space. +16 headroom (mirrors the
     *  web's 64 = 50 + 14 and iOS's +16). */
    private val cap = DEFAULT_CONFIG.maxPending + 16

    // --- termlib internal-snapshot access (see the class KDoc) --------------------------------
    /** The emulator's live `StateFlow<TerminalSnapshot>` (internal `getSnapshot$lib`), or null
     *  if the reflection failed - in which case [available] is false and prediction is disabled. */
    private val snapshotFlow: StateFlow<*>? = runCatching {
        emulator.javaClass.getMethod("getSnapshot\$lib").invoke(emulator) as StateFlow<*>
    }.getOrNull()

    /** Cache reflected getters by class+name (called at keystroke/prediction rates, not per frame).
     *  Declared before [available] so its initializer can use [method] during construction. */
    private val methodCache = HashMap<String, Method>()
    private fun method(target: Any, name: String): Method =
        methodCache.getOrPut(target.javaClass.name + "#" + name) { target.javaClass.getMethod(name) }

    /** True iff the internal snapshot is reachable AND exposes the cursor/line getters we need.
     *  When false the pipeline skips creating the engine, so the terminal runs unaffected. */
    val available: Boolean = snapshotFlow?.value?.let { snap ->
        runCatching {
            method(snap, "getCursorRow"); method(snap, "getCursorCol"); method(snap, "getLines")
        }.isSuccess
    } ?: false

    /** Current caret position, screen-relative (matches CUP coordinates). Reads `cursorRow`/
     *  `cursorCol` off the live snapshot; falls back to (0,0) only if a read unexpectedly fails
     *  (shouldn't happen once [available] is true - the pipeline gates on it). */
    fun cursor(): CursorPos = runCatching {
        val snap = snapshotFlow?.value ?: return CursorPos(0, 0)
        CursorPos(
            row = method(snap, "getCursorRow").invoke(snap) as Int,
            col = method(snap, "getCursorCol").invoke(snap) as Int,
        )
    }.getOrDefault(CursorPos(0, 0))

    /** Render a batch of engine ops. Each op is fed to termlib separately; libvterm applies them
     *  in order and the engine brackets reconcile batches with Hide/ShowCaret, so a whole op
     *  batch lands with no intermediate caret flicker. */
    fun render(ops: List<DisplayOp>) {
        for (op in ops) when (op) {
            is HideCaret -> feed(HIDE)
            is ShowCaret -> feed(SHOW)
            is MoveCaret -> feed(cup(op.row, op.col))
            is DrawDim -> {
                // Snapshot the pre-prediction cell BEFORE the dim write (mirror web/iOS order).
                snapshots[op.id] = readCell(op.row, op.col)
                evictIfNeeded()
                feed(cup(op.row, op.col) + DIM + op.char + UNDIM)
            }
            is RestoreCell -> {
                val prev = snapshots.remove(op.id) ?: " "
                feed(cup(op.row, op.col) + prev)
            }
            is Passthrough ->
                // Authoritative server bytes, written as-is (lossless). Confirmed echoes paint
                // over their dim cells here - that IS the confirm.
                emulator.writeInput(op.bytes)
        }
    }

    /** Feed an escape/text string to termlib (parsed by libvterm exactly like xterm's write). */
    private fun feed(s: String) = emulator.writeInput(s.encodeToByteArray())

    /** Read the character in a (screen-relative) cell for the snapshot: `snapshot.lines[row]
     *  .cells[col].char`. `snapshot.lines` is the live screen (local scrollback is a separate
     *  list), so `[row][col]` is the same cell CUP(row, col) paints. termlib stores an unwritten
     *  cell as NUL; treat NUL / out-of-bounds / a failed read as a space, mirroring the web's
     *  `|| " "` and iOS's NUL guard. */
    private fun readCell(row: Int, col: Int): String = runCatching {
        val snap = snapshotFlow?.value ?: return " "
        val lines = method(snap, "getLines").invoke(snap) as List<*>
        val line = lines.getOrNull(row) ?: return " "
        val cells = method(line, "getCells").invoke(line) as List<*>
        val cell = cells.getOrNull(col) ?: return " "
        val ch = method(cell, "getChar").invoke(cell) as Char
        if (ch == '\u0000') " " else ch.toString()
    }.getOrDefault(" ")

    /** Keep the snapshot map bounded. Confirmed predictions never emit RestoreCell, so their
     *  snapshots would otherwise linger. Engine ids are monotonic, so the smallest key is the
     *  oldest snapshot - evicting it mirrors the web/iOS "drop the oldest". [cap] sits above the
     *  engine's maxPending, so a live snapshot is never the smallest, hence never evicted. */
    private fun evictIfNeeded() {
        if (snapshots.size <= cap) return
        val oldest = snapshots.keys.minOrNull() ?: return
        snapshots.remove(oldest)
    }

    private companion object {
        const val DIM = "\u001b[2m"
        const val UNDIM = "\u001b[22m" // un-dim only (not [0m) - preserves the cell's other attrs
        const val HIDE = "\u001b[?25l"
        const val SHOW = "\u001b[?25h"

        /** Absolute cursor position (CUP). Row/col are 0-based here, 1-based in the escape. */
        fun cup(row: Int, col: Int) = "\u001b[${row + 1};${col + 1}H"
    }
}
