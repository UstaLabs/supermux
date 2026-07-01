package dev.supermux.net

import kotlin.math.floor

/**
 * Predictive local echo engine (Step 2: caret rewrite). Pure logic — no terminal
 * dependency — so it is exhaustively unit-testable and shareable across platforms
 * (iOS SwiftTerm, Android termlib, and the web xterm adapter).
 *
 * It ORCHESTRATES what the terminal writes via abstract [DisplayOp]s (the adapter
 * maps them to the platform terminal's primitives). Two tracked cursors, VS Code style:
 *   - physical:  where the server's authoritative caret is (advances on confirms)
 *   - tentative: physical + still-pending predictions = where the visible caret
 *                should sit so it rides the user's typing
 * Confirms are implicit: the server's echo bytes, passed through over the dim cells,
 * ARE the confirm. Divergence erases all dim + replays the chunk + resyncs.
 * The Step-1 epoch gate (wait-for-first-confirmation) and prompt boundary carry over.
 *
 * Ported faithfully from src/web-app/src/lib/predictive-echo/engine.ts +
 * types.ts (Step 2 caret rewrite). The TypeScript source is the authoritative spec.
 */

// ---------------------------------------------------------------------------
// DisplayOp — abstract operations the engine emits; the adapter executes them
// ---------------------------------------------------------------------------

sealed interface DisplayOp

/** Draw an unconfirmed glyph at a cell (adapter: dim SGR). */
data class DrawDim(val id: Int, val row: Int, val col: Int, val char: String) : DisplayOp

/** Reposition the real caret (adapter: absolute CUP). */
data class MoveCaret(val row: Int, val col: Int) : DisplayOp

/** Erase a rolled-back prediction, restoring the pre-prediction snapshot. */
data class RestoreCell(val id: Int, val row: Int, val col: Int) : DisplayOp

/**
 * Write authoritative server bytes as-is (adapter: term.write). Confirmed echoes
 * paint over the dim cells here — that IS the confirm.
 *
 * NOT a data class: Kotlin data classes compare ByteArray by reference, so
 * assertEquals on op lists containing Passthrough would mis-pass or mis-fail.
 * We override equals/hashCode with contentEquals/contentHashCode instead.
 */
class Passthrough(val bytes: ByteArray) : DisplayOp {
    override fun equals(other: Any?) = other is Passthrough && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Passthrough(${bytes.decodeToString().take(40)})"
}

/** Bracket a reconcile batch to hide repositioning flicker. */
data object HideCaret : DisplayOp

/** Bracket a reconcile batch to hide repositioning flicker. */
data object ShowCaret : DisplayOp

// ---------------------------------------------------------------------------
// InputEvent — decoded xterm onData payloads
// ---------------------------------------------------------------------------

sealed interface InputEvent

/** A single printable character the user typed. */
data class CharInput(val text: String) : InputEvent

/** DEL (0x7f) or BS (0x08). */
data object Backspace : InputEvent

/** ESC[D — left arrow. */
data object CursorLeft : InputEvent

/** ESC[C — right arrow. */
data object CursorRight : InputEvent

/** Anything else: Enter, Tab, Ctrl-keys, pastes, unknown escapes. */
data object Opaque : InputEvent

// ---------------------------------------------------------------------------
// CursorPos + PredictionConfig
// ---------------------------------------------------------------------------

data class CursorPos(val row: Int, val col: Int)

data class PredictionConfig(
    val latencyThresholdMs: Int,
    val cooldownMs: Int,
    val maxPending: Int,
)

/** Engage only above ~typical-WiFi RTT; 600 ms cooldown after a mispredict;
 *  cap outstanding predictions so state can't grow unbounded if the server stalls. */
val DEFAULT_CONFIG = PredictionConfig(
    latencyThresholdMs = 40,
    cooldownMs = 600,
    maxPending = 50,
)

// ---------------------------------------------------------------------------
// decodeInput — mirrors types.ts decodeInput exactly
// ---------------------------------------------------------------------------

private val DEL = ""   // 0x7f — what xterm sends for the delete/backspace key
private val BS  = ""   // 0x08 — ASCII backspace
private val ESC = ""   // 0x1b — escape character
private val LEFT_ARROW  = "${ESC}[D"
private val RIGHT_ARROW = "${ESC}[C"

/**
 * Decode one xterm onData payload into a prediction input event.
 * Only single printable chars, lone DEL/BS, and lone left/right arrows are
 * predictable; everything else (Enter, Tab, Ctrl-keys, other escapes, and any
 * multi-character payload such as a paste) is opaque.
 *
 * Mirrors types.ts `decodeInput` exactly. The surrogate-pair code-point count
 * mirrors TS `[...data].length` (one element per Unicode scalar, not per UTF-16 unit).
 */
fun decodeInput(data: String): InputEvent {
    if (data == DEL || data == BS) return Backspace
    if (data == LEFT_ARROW) return CursorLeft
    if (data == RIGHT_ARROW) return CursorRight
    // Count Unicode code points (handle surrogate pairs like TS `[...data]`).
    // Uses only commonMain Char APIs — no java.lang.Character.
    var codePointCount = 0
    var firstCp = -1
    var i = 0
    while (i < data.length) {
        val hi = data[i]
        val cp: Int
        if (hi.isHighSurrogate() && i + 1 < data.length && data[i + 1].isLowSurrogate()) {
            val lo = data[i + 1]
            cp = 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
            i += 2
        } else {
            cp = hi.code
            i += 1
        }
        if (codePointCount == 0) firstCp = cp
        codePointCount++
    }
    if (codePointCount == 1 && firstCp >= 0x20 && firstCp != 0x7f) return CharInput(data)
    return Opaque
}

// ---------------------------------------------------------------------------
// PredictionEngine
// ---------------------------------------------------------------------------

private data class Pending(
    val id: Int,
    val row: Int,
    val col: Int,
    val char: String,
    val predictedAt: Long,
    var drawn: Boolean,
)

/**
 * @param cfg configuration (latency threshold, cooldown, max pending).
 * @param now clock returning milliseconds (injectable for testing).
 */
class PredictionEngine(private val cfg: PredictionConfig, private val now: () -> Long) {

    private var nextId = 1
    private val pending = mutableListOf<Pending>()
    private var latencyMs = 0L
    private var physical: CursorPos? = null
    private var tentative: CursorPos? = null
    private var cooldownUntil = 0L
    // Wait-for-first-confirmation: predictions stay tracked-but-undrawn until the
    // server confirms one. Persists across a natural pending-drain; resets only on
    // opaque input / divergence. (See the v1 password-ghost note in onServerData.)
    private var epochConfirmed = false
    // Leftmost column the line's editing may touch (prompt boundary). Set once on
    // the pending 0->1 transition; reset on opaque/divergence/reset. See onInput.
    private var epochStartCol: Int? = null

    fun setLatencyEstimate(ms: Long) { latencyMs = ms }

    /** Test-only: prime the estimate so predictions are allowed past the latency gate. */
    fun primeForTest() { latencyMs = cfg.latencyThresholdMs.toLong() }

    private fun active(): Boolean =
        latencyMs >= cfg.latencyThresholdMs && now() >= cooldownUntil

    fun onInput(ev: InputEvent, serverCursor: CursorPos): List<DisplayOp> {
        if (!active()) return emptyList()
        // Opaque input (Enter, Tab, Ctrl-keys, escapes, paste) changes the line
        // unpredictably → erase drawn predictions, snap the caret back, reset.
        if (ev === Opaque) return resetEpoch()
        if (pending.isEmpty()) {
            physical = serverCursor.copy()
            tentative = serverCursor.copy()
            if (epochStartCol == null) epochStartCol = serverCursor.col
        }
        if (pending.size >= cfg.maxPending) return emptyList()
        // invariant: tentative/physical are non-null here (seeded above when pending
        // was empty, and never cleared without also clearing pending).
        val t = tentative!!

        if (ev is CharInput) {
            val p = push(t.row, t.col, ev.text)
            tentative = t.copy(col = t.col + 1)
            return if (p.drawn) listOf(DrawDim(p.id, p.row, p.col, p.char), caret()) else emptyList()
        }
        if (ev === Backspace) {
            // Never predict-delete past the line's start column (into the prompt).
            // `?: 0` is defensive only — epochStartCol is non-null whenever pending > 0.
            if (t.col <= (epochStartCol ?: 0)) return emptyList()
            tentative = t.copy(col = t.col - 1)
            val newT = tentative!!
            val p = push(newT.row, newT.col, " ")
            return if (p.drawn) listOf(DrawDim(p.id, p.row, p.col, p.char), caret()) else emptyList()
        }
        // Arrows move the predicted cursor for positioning the next char, but (v1)
        // do not move the visible caret predictively — that waits for the server.
        if (ev === CursorLeft) {
            if (t.col > (epochStartCol ?: 0)) tentative = t.copy(col = t.col - 1)
            return emptyList()
        }
        if (ev === CursorRight) {
            tentative = t.copy(col = t.col + 1)
            return emptyList()
        }
        return emptyList()
    }

    // v1 reconciliation limitations (self-heal via divergence-erase + cooldown/resync):
    // - a CSI sequence split across two onServerData calls misreads the leading '[' (spurious divergence);
    // - only ESC[ (CSI) is skipped for matching, so a mid-stream OSC (ESC]) can leak bytes to the matcher;
    // - wide chars (CJK/emoji) advance col by 1 not 2, so predictions after one are a column early;
    // - no-echo input (password prompts) never confirms the epoch, so nothing is ever drawn (ghost-free);
    // - any chunk containing an escape/control byte suppresses the tentative caret reposition (conservative:
    //   the caret then sits where the authoritative bytes left it), and anything the cursor model can't
    //   track resyncs on the next drain. Cross-call escape buffering + wcwidth are deferred.
    fun onServerData(bytes: ByteArray): List<DisplayOp> {
        if (pending.isEmpty()) return if (bytes.isNotEmpty()) listOf(Passthrough(bytes)) else emptyList()
        val origPhysical = physical!!.copy()
        // Cells of every currently-drawn prediction, captured BEFORE the walk mutates
        // pending — needed to erase them all on divergence.
        val drawnCells = pending.filter { it.drawn }.map { Triple(it.id, it.row, it.col) }
        val chars = bytes.decodeToString().toList()   // List<Char>; ASCII/BMP only (wide-char deferred)
        val backlog = mutableListOf<DisplayOp>()
        var diverged = false
        var sawComplex = false // any escape/control byte → suppress tentative reposition
        var i = 0
        while (i < chars.size) {
            val ch = chars[i]
            val cp = ch.code
            if (cp == 0x1b) { // skip a CSI escape for MATCHING (still passed through in `bytes`)
                sawComplex = true
                i++
                if (i < chars.size && chars[i] == '[') {
                    i++
                    while (i < chars.size && !(chars[i].code >= 0x40 && chars[i].code <= 0x7e)) i++
                }
                // Advance past the final byte of the escape (mirrors the for-loop i++ in TS after `continue`).
                i++
                continue
            }
            if (cp < 0x20 || cp == 0x7f) { sawComplex = true; i++; continue } // CR/LF / control
            val head = pending.firstOrNull()
            if (head == null) break // pending drained; the rest of `bytes` is trailing content
            if (ch.toString() == head.char) {
                sampleLatency(now() - head.predictedAt)
                pending.removeAt(0)
                physical = physical!!.copy(col = physical!!.col + 1)
                if (!epochConfirmed) {
                    // First confirmation → trust the echo: draw the backlog of tentative
                    // predictions queued behind the head (they become visible now).
                    epochConfirmed = true
                    for (p in pending) {
                        if (!p.drawn) {
                            p.drawn = true
                            backlog.add(DrawDim(p.id, p.row, p.col, p.char))
                        }
                    }
                }
            } else {
                diverged = true
                break
            }
            i++
        }

        // Divergence: erase every originally-drawn prediction (reverse order, so stacked
        // predictions at one cell restore the earliest/true original), replay the whole
        // chunk from the chunk-start physical (repaints confirmed echoes solid + the
        // divergent content), reset + cooldown.
        if (diverged) {
            val ops = mutableListOf<DisplayOp>(HideCaret)
            for (k in drawnCells.indices.reversed()) {
                val c = drawnCells[k]
                ops.add(RestoreCell(c.first, c.second, c.third))
            }
            ops.add(MoveCaret(origPhysical.row, origPhysical.col))
            ops.add(Passthrough(bytes))
            ops.add(ShowCaret)
            clear()
            cooldownUntil = now() + cfg.cooldownMs
            return ops
        }

        // Resync: a cursor-moving control (destructive backspace "\b \b", CR, …) can move
        // the real caret by something other than +1 per matched printable, drifting our
        // physical.col. If predictions SURVIVE this chunk we can no longer trust physical
        // for them → erase the surviving dim tail, replay the chunk, and reset so the next
        // input re-seeds from the real caret. No cooldown: the epoch reset alone quiets a
        // redraw-heavy app, while a lone backspace stays responsive.
        if (sawComplex && pending.isNotEmpty()) {
            val stillPending = pending.map { it.id }.toSet()
            val ops = mutableListOf<DisplayOp>(HideCaret)
            for (k in drawnCells.indices.reversed()) {
                val c = drawnCells[k]
                if (c.first in stillPending) ops.add(RestoreCell(c.first, c.second, c.third))
            }
            ops.add(MoveCaret(origPhysical.row, origPhysical.col))
            ops.add(Passthrough(bytes))
            ops.add(ShowCaret)
            clear()
            return ops
        }

        // No divergence: draw any newly-confirmed backlog BEFORE the passthrough (so an
        // echo that also confirms a backlog char in the same chunk paints it solid over
        // the dim, not the reverse), pass the chunk through (echoes confirm in place over
        // the dim cells), then re-place the caret after the still-dim tail.
        val ops = mutableListOf<DisplayOp>(HideCaret)
        ops.addAll(backlog)
        ops.add(MoveCaret(origPhysical.row, origPhysical.col))
        ops.add(Passthrough(bytes))
        if (pending.isEmpty()) {
            physical = null
            tentative = null
        } else {
            ops.add(caret()) // sawComplex + surviving pending returned above, so this is safe
        }
        ops.add(ShowCaret)
        return ops
    }

    private fun push(row: Int, col: Int, char: String): Pending {
        val p = Pending(id = nextId++, row = row, col = col, char = char, predictedAt = now(), drawn = epochConfirmed)
        pending.add(p)
        return p
    }

    /** moveCaret op to the current tentative position. */
    private fun caret(): DisplayOp = MoveCaret(tentative!!.row, tentative!!.col)

    private fun sampleLatency(ms: Long) {
        // Gotcha 2: TS Math.round rounds half toward +inf; kotlin.math.round rounds half-to-even.
        // Use floor(x + 0.5) to match TS exactly (safe for ms >= 0).
        latencyMs = if (latencyMs == 0L) ms else floor(0.7 * latencyMs + 0.3 * ms + 0.5).toLong()
    }

    private fun clear() {
        pending.clear()
        physical = null
        tentative = null
        epochConfirmed = false
        epochStartCol = null
    }

    /** Erase drawn predictions, snap the caret back to physical, and clear state.
     *  Used on opaque input (Enter/Tab/Ctrl/paste) and on reconnect via reset(). */
    private fun resetEpoch(): List<DisplayOp> {
        val drawn = pending.filter { it.drawn }
        val phys = physical
        clear()
        if (drawn.isEmpty()) return emptyList()
        val ops = mutableListOf<DisplayOp>(HideCaret)
        for (p in drawn) ops.add(RestoreCell(p.id, p.row, p.col))
        if (phys != null) ops.add(MoveCaret(phys.row, phys.col))
        ops.add(ShowCaret)
        return ops
    }

    fun reset(): List<DisplayOp> = resetEpoch()
}
