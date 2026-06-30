import type { Terminal } from "@xterm/xterm"
import type { CursorPos, DisplayOp } from "./types"

const DIM = "\x1b[2m"
const RESET = "\x1b[0m"

/** Applies predictive-echo DisplayOps to an xterm Terminal. Predictions are
 *  written directly with a dim SGR, with the cursor saved/restored (\x1b7…\x1b8)
 *  so the real cursor position is unaffected. `confirm` is a no-op for display —
 *  the authoritative server byte that matched overwrites the same cell at full
 *  intensity as it streams in. `rollback` is handled by the wiring via
 *  restoreCell() (the wiring owns the id→cell map and the pre-prediction snapshot).
 *
 *  This is the fragile, best-effort rendering layer — it is validated on a
 *  throttled link, not by a unit test. Known v1 risk: DECSC/DECRC (\x1b7/\x1b8)
 *  uses xterm's single saved-cursor slot, so if the foreground app's PTY bytes
 *  split its own \x1b7…\x1b8 pair across two writes, our injected pair clobbers
 *  that slot — a transient cursor jump until the next redraw (uncommon; PTY
 *  writes are usually atomic). If direct dim-writes end up fighting xterm's
 *  rendering, switch to xterm decorations (term.registerDecoration) and record
 *  it as a finding. */
export class XtermPredictionAdapter {
  constructor(private term: Terminal) {}

  /** Current cursor position, viewport-relative (matches CUP coordinates). */
  cursor(): CursorPos {
    const b = this.term.buffer.active
    return { row: b.cursorY, col: b.cursorX }
  }

  /** Draw the dim predicted glyph for any `predict` ops (other ops ignored). */
  apply(ops: DisplayOp[]): void {
    for (const op of ops) {
      if (op.op !== "predict") continue
      this.term.write(`\x1b7\x1b[${op.row + 1};${op.col + 1}H${DIM}${op.char}${RESET}\x1b8`)
    }
  }

  /** Read the character currently in a (viewport-relative) cell, so the wiring
   *  can snapshot it BEFORE a prediction overwrites it. getLine() takes an
   *  ABSOLUTE buffer index, so we add baseY to the viewport-relative row.
   *  Returns a single space for an empty or missing cell. */
  readCell(row: number, col: number): string {
    const buf = this.term.buffer.active
    return buf.getLine(buf.baseY + row)?.getCell(col)?.getChars() || " "
  }

  /** Erase a rolled-back prediction by restoring the cell to the snapshot the
   *  wiring took before predicting. We must NOT re-read the cell here: reconcile
   *  runs before the server's authoritative bytes are painted, so at rollback
   *  time the cell still holds our own dim guess — re-reading it would just
   *  rewrite that wrong guess at full intensity (the bug this replaces). v1
   *  caveats: the repaint uses default SGR (the cell's original color/weight is
   *  briefly lost until the server corrects it), and a viewport scroll between
   *  predict and restore lands on the wrong line (rare; self-corrects). */
  restoreCell(row: number, col: number, ch: string): void {
    this.term.write(`\x1b7\x1b[${row + 1};${col + 1}H${ch || " "}\x1b8`)
  }
}
