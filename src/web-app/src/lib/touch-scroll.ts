/**
 * Touch-drag → scrollback math for the terminal pane.
 *
 * xterm.js v6 bundles a full touch-gesture engine (ported from VS Code) but
 * never wires it up — no element is ever registered as a gesture target — so a
 * finger swipe over the terminal does nothing. Mouse-wheel scrolling lives on a
 * separate path, which is why it works on desktop but not on touch devices. We
 * bridge the gap by translating drags into Terminal.scrollLines() ourselves.
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
