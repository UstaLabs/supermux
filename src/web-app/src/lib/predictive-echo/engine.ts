import type { InputEvent, CursorPos, DisplayOp, PredictionConfig } from "./types"

interface Pending {
  id: number
  row: number
  col: number
  char: string
  predictedAt: number
  drawn: boolean
}

/**
 * Predictive local echo engine (Step 2: caret rewrite). Pure logic — no terminal
 * dependency — so it is exhaustively unit-testable and shareable across platforms.
 *
 * It ORCHESTRATES what the terminal writes via abstract DisplayOps (the adapter
 * maps them to xterm/SwiftTerm/termlib). Two tracked cursors, VS Code style:
 *   - physical:  where the server's authoritative caret is (advances on confirms)
 *   - tentative: physical + still-pending predictions = where the visible caret
 *                should sit so it rides the user's typing
 * Confirms are implicit: the server's echo bytes, passed through over the dim
 * cells, ARE the confirm. Divergence erases all dim + replays the chunk + resyncs.
 * The Step-1 epoch gate (wait-for-first-confirmation) and prompt boundary carry over.
 */
export class PredictionEngine {
  private cfg: PredictionConfig
  private now: () => number
  private nextId = 1
  private pending: Pending[] = []
  private latencyMs = 0
  private physical: CursorPos | null = null
  private tentative: CursorPos | null = null
  private cooldownUntil = 0
  // Wait-for-first-confirmation: predictions stay tracked-but-undrawn until the
  // server confirms one. Persists across a natural pending-drain; resets only on
  // opaque input / divergence. (See the v1 password-ghost note in onServerData.)
  private epochConfirmed = false
  // Leftmost column the line's editing may touch (prompt boundary). Set once on
  // the pending 0->1 transition; reset on opaque/divergence/reset. See onInput.
  private epochStartCol: number | null = null

  constructor(cfg: PredictionConfig, now: () => number) {
    this.cfg = cfg
    this.now = now
  }

  setLatencyEstimate(ms: number): void { this.latencyMs = ms }
  /** test-only: prime the estimate so predictions are allowed past the latency gate */
  primeForTest(): void { this.latencyMs = this.cfg.latencyThresholdMs }

  private active(): boolean {
    return this.latencyMs >= this.cfg.latencyThresholdMs && this.now() >= this.cooldownUntil
  }

  onInput(ev: InputEvent, serverCursor: CursorPos): DisplayOp[] {
    if (!this.active()) return []
    // Opaque input (Enter, Tab, Ctrl-keys, escapes, paste) changes the line
    // unpredictably → erase drawn predictions, snap the caret back, reset.
    if (ev.kind === "opaque") return this.resetEpoch()
    if (this.pending.length === 0) {
      this.physical = { ...serverCursor }
      this.tentative = { ...serverCursor }
      if (this.epochStartCol === null) this.epochStartCol = serverCursor.col
    }
    if (this.pending.length >= this.cfg.maxPending) return []
    // invariant: tentative/physical are non-null here (seeded above when pending
    // was empty, and never cleared without also clearing pending).
    const t = this.tentative!

    if (ev.kind === "char") {
      const p = this.push(t.row, t.col, ev.text)
      t.col += 1
      return p.drawn ? [{ op: "drawDim", id: p.id, row: p.row, col: p.col, char: p.char }, this.caret()] : []
    }
    if (ev.kind === "backspace") {
      // Never predict-delete past the line's start column (into the prompt).
      // `?? 0` is defensive only — epochStartCol is non-null whenever pending > 0.
      if (t.col <= (this.epochStartCol ?? 0)) return []
      t.col -= 1
      const p = this.push(t.row, t.col, " ")
      return p.drawn ? [{ op: "drawDim", id: p.id, row: p.row, col: p.col, char: " " }, this.caret()] : []
    }
    // Arrows move the predicted cursor for positioning the next char, but (v1)
    // do not move the visible caret predictively — that waits for the server.
    if (ev.kind === "cursorLeft") { if (t.col > (this.epochStartCol ?? 0)) t.col -= 1; return [] }
    if (ev.kind === "cursorRight") { t.col += 1; return [] }
    return []
  }

  // v1 reconciliation limitations (self-heal via divergence-erase + cooldown/resync):
  // - a CSI sequence split across two onServerData calls misreads the leading '[' (spurious divergence);
  // - only ESC[ (CSI) is skipped for matching, so a mid-stream OSC (ESC]) can leak bytes to the matcher;
  // - wide chars (CJK/emoji) advance col by 1 not 2, so predictions after one are a column early;
  // - no-echo input (password prompts) never confirms the epoch, so nothing is ever drawn (ghost-free);
  // - any chunk containing an escape/control byte suppresses the tentative caret reposition (conservative:
  //   the caret then sits where the authoritative bytes left it), and anything the cursor model can't
  //   track resyncs on the next drain. Cross-call escape buffering + wcwidth are deferred.
  onServerData(bytes: Uint8Array): DisplayOp[] {
    if (this.pending.length === 0) return bytes.length ? [{ op: "passthrough", bytes }] : []
    const origPhysical = { ...this.physical! }
    // Cells of every currently-drawn prediction, captured BEFORE the walk mutates
    // pending — needed to erase them all on divergence.
    const drawnCells = this.pending.filter((p) => p.drawn).map((p) => ({ id: p.id, row: p.row, col: p.col }))
    const chars = [...new TextDecoder().decode(bytes)]
    const backlog: DisplayOp[] = []
    let diverged = false
    let sawComplex = false // any escape/control byte → suppress tentative reposition
    for (let i = 0; i < chars.length; i++) {
      const ch = chars[i]!
      const cp = ch.codePointAt(0)!
      if (cp === 0x1b) { // skip a CSI escape for MATCHING (still passed through in `bytes`)
        sawComplex = true
        i++
        if (chars[i] === "[") {
          i++
          while (i < chars.length && !(chars[i]!.codePointAt(0)! >= 0x40 && chars[i]!.codePointAt(0)! <= 0x7e)) i++
        }
        continue
      }
      if (cp < 0x20 || cp === 0x7f) { sawComplex = true; continue } // CR/LF / control
      const head = this.pending[0]
      if (!head) break // pending drained; the rest of `bytes` is trailing content
      if (ch === head.char) {
        this.sampleLatency(this.now() - head.predictedAt)
        this.pending.shift()
        this.physical!.col += 1
        if (!this.epochConfirmed) {
          // First confirmation → trust the echo: draw the backlog of tentative
          // predictions queued behind the head (they become visible now).
          this.epochConfirmed = true
          for (const p of this.pending) {
            if (!p.drawn) {
              p.drawn = true
              backlog.push({ op: "drawDim", id: p.id, row: p.row, col: p.col, char: p.char })
            }
          }
        }
      } else {
        diverged = true
        break
      }
    }

    // Divergence: erase every originally-drawn prediction (reverse order, so stacked
    // predictions at one cell restore the earliest/true original), replay the whole
    // chunk from the chunk-start physical (repaints confirmed echoes solid + the
    // divergent content), reset + cooldown.
    if (diverged) {
      const ops: DisplayOp[] = [{ op: "hideCaret" }]
      for (let k = drawnCells.length - 1; k >= 0; k--) {
        const c = drawnCells[k]!
        ops.push({ op: "restoreCell", id: c.id, row: c.row, col: c.col })
      }
      ops.push({ op: "moveCaret", row: origPhysical.row, col: origPhysical.col })
      ops.push({ op: "passthrough", bytes })
      ops.push({ op: "showCaret" })
      this.clear()
      this.cooldownUntil = this.now() + this.cfg.cooldownMs
      return ops
    }

    // Resync: a cursor-moving control (destructive backspace "\b \b", CR, …) can move
    // the real caret by something other than +1 per matched printable, drifting our
    // physical.col. If predictions SURVIVE this chunk we can no longer trust physical
    // for them → erase the surviving dim tail, replay the chunk, and reset so the next
    // input re-seeds from the real caret. No cooldown: the epoch reset alone quiets a
    // redraw-heavy app, while a lone backspace stays responsive. (A chunk that fully
    // drains is safe — physical is nulled below and never reused.)
    if (sawComplex && this.pending.length > 0) {
      const stillPending = new Set(this.pending.map((p) => p.id))
      const ops: DisplayOp[] = [{ op: "hideCaret" }]
      for (let k = drawnCells.length - 1; k >= 0; k--) {
        const c = drawnCells[k]!
        if (stillPending.has(c.id)) ops.push({ op: "restoreCell", id: c.id, row: c.row, col: c.col })
      }
      ops.push({ op: "moveCaret", row: origPhysical.row, col: origPhysical.col })
      ops.push({ op: "passthrough", bytes })
      ops.push({ op: "showCaret" })
      this.clear()
      return ops
    }

    // No divergence: draw any newly-confirmed backlog BEFORE the passthrough (so an
    // echo that also confirms a backlog char in the same chunk paints it solid over
    // the dim, not the reverse), pass the chunk through (echoes confirm in place over
    // the dim cells), then re-place the caret after the still-dim tail.
    const ops: DisplayOp[] = [{ op: "hideCaret" }, ...backlog]
    ops.push({ op: "moveCaret", row: origPhysical.row, col: origPhysical.col })
    ops.push({ op: "passthrough", bytes })
    if (this.pending.length === 0) {
      this.physical = null
      this.tentative = null
    } else {
      ops.push(this.caret()) // sawComplex + surviving pending returned above, so this is safe
    }
    ops.push({ op: "showCaret" })
    return ops
  }

  private push(row: number, col: number, char: string): Pending {
    const p: Pending = { id: this.nextId++, row, col, char, predictedAt: this.now(), drawn: this.epochConfirmed }
    this.pending.push(p)
    return p
  }

  /** moveCaret op to the current tentative position. */
  private caret(): DisplayOp {
    return { op: "moveCaret", row: this.tentative!.row, col: this.tentative!.col }
  }

  private sampleLatency(ms: number): void {
    this.latencyMs = this.latencyMs === 0 ? ms : Math.round(this.latencyMs * 0.7 + ms * 0.3)
  }

  private clear(): void {
    this.pending = []
    this.physical = null
    this.tentative = null
    this.epochConfirmed = false
    this.epochStartCol = null
  }

  /** Erase drawn predictions, snap the caret back to physical, and clear state.
   *  Used on opaque input (Enter/Tab/Ctrl/paste) and on reconnect via reset(). */
  private resetEpoch(): DisplayOp[] {
    const drawn = this.pending.filter((p) => p.drawn)
    const phys = this.physical
    this.clear()
    if (!drawn.length) return []
    const ops: DisplayOp[] = [{ op: "hideCaret" }]
    for (const p of drawn) ops.push({ op: "restoreCell", id: p.id, row: p.row, col: p.col })
    if (phys) ops.push({ op: "moveCaret", row: phys.row, col: phys.col })
    ops.push({ op: "showCaret" })
    return ops
  }

  reset(): DisplayOp[] {
    return this.resetEpoch()
  }
}
