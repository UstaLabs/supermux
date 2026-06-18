/**
 * Touch-drag → scrollback math for the terminal pane.
 *
 * xterm.js v6 bundles a full touch-gesture engine (ported from VS Code) but
 * never wires it up — no element is ever registered as a gesture target — so a
 * finger swipe over the terminal does nothing. Mouse-wheel scrolling lives on a
 * separate path, which is why it works on desktop but not on touch devices. We
 * bridge the gap by translating drags into row deltas ourselves.
 *
 * A row delta is then applied one of two ways (see TerminalPane):
 *   - plain shell  → Terminal.scrollLines(), scrolling xterm's own scrollback.
 *   - full-screen app that grabbed the mouse (tmux `mouse on`, our terminals'
 *     default) → the screen is in the alternate buffer, which has NO xterm
 *     scrollback, so scrollLines() is a silent no-op. There we forward
 *     mouse-wheel events via wheelEventsFromLines() — exactly what the desktop
 *     mouse wheel does — and let the app scroll its own history.
 *
 * This is the one piece worth isolating and testing: converting an accumulated
 * vertical pixel delta into a whole number of rows. The remainder is carried
 * back into the next move so a slow drag still scrolls — without it, every move
 * smaller than a row would round to zero and the buffer would never budge.
 *
 * Sign convention matches xterm's scrollLines(): positive scrolls down toward
 * newer output (finger moving up), negative scrolls back into history.
 */
export function linesFromPixels(
  accumPx: number,
  cellHeightPx: number,
): { lines: number; remainderPx: number } {
  if (!Number.isFinite(cellHeightPx) || cellHeightPx <= 0) {
    return { lines: 0, remainderPx: 0 }
  }
  const lines = Math.trunc(accumPx / cellHeightPx)
  return { lines, remainderPx: accumPx - lines * cellHeightPx }
}

// SGR (1006) mouse button codes for the wheel. tmux negotiates SGR encoding on
// attach, so this is the format it expects on input.
const WHEEL_UP = 64 // scroll back into history
const WHEEL_DOWN = 65 // scroll toward newer output

/**
 * Encode a whole-row scroll delta as the SGR mouse-wheel escape sequence a
 * mouse-tracking app (e.g. tmux) expects, one wheel event per row.
 *
 * Sign matches linesFromPixels()/scrollLines(): negative scrolls back into
 * history (wheel up, button 64), positive scrolls toward newer output (wheel
 * down, button 65). `col`/`row` are 1-based pointer cell coordinates; any
 * in-pane coordinate works for our single-pane windows. Returns "" for a zero
 * or non-finite delta.
 */
export function wheelEventsFromLines(lines: number, col: number, row: number): string {
  if (!Number.isFinite(lines)) return ""
  const count = Math.abs(Math.trunc(lines))
  if (count === 0) return ""
  const button = lines < 0 ? WHEEL_UP : WHEEL_DOWN
  const c = Math.max(1, Math.floor(col))
  const r = Math.max(1, Math.floor(row))
  return `\x1b[<${button};${c};${r}M`.repeat(count)
}
