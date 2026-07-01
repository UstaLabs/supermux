import type { Terminal } from "@xterm/xterm"
import type { CursorPos, DisplayOp } from "./types"

const DIM = "\x1b[2m"
const UNDIM = "\x1b[22m" // un-dim only (not \x1b[0m) — preserves the cell's other attributes
const HIDE = "\x1b[?25l"
const SHOW = "\x1b[?25h"
const cup = (row: number, col: number) => `\x1b[${row + 1};${col + 1}H`

/**
 * Renders the engine's Step-2 DisplayOps against an xterm Terminal. The engine
 * owns all reconcile logic and cursor math; this adapter is a thin, mechanical
 * translator (the only per-platform, throttled-link-validated layer).
 *
 * Rendering strategy: each op is written to xterm separately, but xterm batches
 * all writes issued in one tick into a single render, so a whole op batch paints
 * in one frame with no intermediate caret flicker (the hide/show ops the engine
 * brackets reconcile batches with are belt-and-suspenders on top of that).
 *
 * The adapter owns the pre-prediction cell snapshots: it captures a cell's prior
 * character on `drawDim` (keyed by prediction id) and restores it on `restoreCell`.
 * Confirmed predictions are dropped implicitly (their echo paints over them via a
 * `passthrough`, so they never emit a `restoreCell`), so the snapshot map is
 * bounded by eviction — it never needs to exceed the engine's maxPending.
 */
export class XtermPredictionAdapter {
  private snapshots = new Map<number, string>()

  constructor(private term: Terminal) {}

  /** Current caret position, viewport-relative (matches CUP coordinates). */
  cursor(): CursorPos {
    const b = this.term.buffer.active
    return { row: b.cursorY, col: b.cursorX }
  }

  /** Render a batch of engine ops. Multiple writes in one tick → one xterm frame. */
  render(ops: DisplayOp[]): void {
    for (const op of ops) {
      switch (op.op) {
        case "hideCaret":
          this.term.write(HIDE)
          break
        case "showCaret":
          this.term.write(SHOW)
          break
        case "moveCaret":
          this.term.write(cup(op.row, op.col))
          break
        case "drawDim":
          this.snapshots.set(op.id, this.readCell(op.row, op.col))
          this.evictIfNeeded()
          this.term.write(`${cup(op.row, op.col)}${DIM}${op.char}${UNDIM}`)
          break
        case "restoreCell": {
          const prev = this.snapshots.get(op.id) || " "
          this.snapshots.delete(op.id)
          this.term.write(`${cup(op.row, op.col)}${prev}`)
          break
        }
        case "passthrough":
          // Authoritative bytes, written as-is (lossless). Confirmed echoes paint
          // over their dim cells here — that IS the confirm.
          this.term.write(op.bytes)
          break
      }
    }
  }

  /** Read the character currently in a (viewport-relative) cell. getLine() takes an
   *  ABSOLUTE buffer index, so we add baseY to the viewport-relative row. */
  private readCell(row: number, col: number): string {
    const buf = this.term.buffer.active
    return buf.getLine(buf.baseY + row)?.getCell(col)?.getChars() || " "
  }

  /** Keep the snapshot map bounded. Confirmed predictions never emit restoreCell,
   *  so their snapshots would otherwise linger; the cap sits comfortably above the
   *  engine's maxPending (50), so a live snapshot is never wrongly evicted. */
  private evictIfNeeded(): void {
    if (this.snapshots.size <= 64) return
    const oldest = this.snapshots.keys().next().value
    if (oldest !== undefined) this.snapshots.delete(oldest)
  }
}
