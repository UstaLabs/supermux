import type { Terminal } from "@xterm/xterm"
import type { CursorPos, DisplayOp } from "./types"

const DIM = "\x1b[2m"
const RESET = "\x1b[0m"

/** Applies predictive-echo DisplayOps to an xterm Terminal. Predictions are
 *  written directly with a dim SGR, with the cursor saved/restored (\x1b7…\x1b8)
 *  so the real cursor position is unaffected. `confirm` is a no-op for display —
 *  the authoritative server byte that matched overwrites the same cell at full
 *  intensity as it streams in. `rollback` is handled by the wiring via
 *  erasePredicted() (the wiring owns the id→cell map).
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

  /** Erase a predicted glyph at a (viewport-relative) cell by repainting the
   *  authoritative char currently in xterm's buffer there (or a space). Note:
   *  getLine() takes an ABSOLUTE buffer index, so we add baseY to the
   *  viewport-relative row. v1 caveats: the repaint uses default SGR (the cell's
   *  color/weight is briefly lost until the server corrects it), and a viewport
   *  scroll between predict and erase reads the wrong line (rare; self-corrects). */
  erasePredicted(row: number, col: number): void {
    const buf = this.term.buffer.active
    const line = buf.getLine(buf.baseY + row)
    const ch = line?.getCell(col)?.getChars() || " "
    this.term.write(`\x1b7\x1b[${row + 1};${col + 1}H${ch}\x1b8`)
  }
}
