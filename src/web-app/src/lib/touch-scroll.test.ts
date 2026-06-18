import { test, expect } from "bun:test"
import { linesFromPixels, wheelEventsFromLines } from "./touch-scroll"

test("a drag shorter than one row scrolls nothing but carries the pixels forward", () => {
  const { lines, remainderPx } = linesFromPixels(10, 15.6)
  expect(lines).toBe(0)
  expect(remainderPx).toBeCloseTo(10)
})

test("slow drags accumulate across moves instead of being rounded away", () => {
  const cell = 15.6
  // Three small 6px nudges = 18px > one row: the carried remainder must let the
  // second/third move cross the threshold even though each move alone is < a row.
  let accum = 0
  let scrolled = 0
  for (let i = 0; i < 3; i++) {
    accum += 6
    const r = linesFromPixels(accum, cell)
    accum = r.remainderPx
    scrolled += r.lines
  }
  expect(scrolled).toBe(1)
  expect(accum).toBeCloseTo(18 - 15.6)
})

test("a fast swipe scrolls multiple rows at once", () => {
  const { lines, remainderPx } = linesFromPixels(50, 15.6)
  expect(lines).toBe(3) // trunc(50/15.6) === 3
  expect(remainderPx).toBeCloseTo(50 - 3 * 15.6)
})

test("an exact multiple leaves no remainder", () => {
  const { lines, remainderPx } = linesFromPixels(31.2, 15.6)
  expect(lines).toBe(2)
  expect(remainderPx).toBeCloseTo(0)
})

test("negative pixels (finger down) scroll back into history symmetrically", () => {
  const { lines, remainderPx } = linesFromPixels(-50, 15.6)
  expect(lines).toBe(-3)
  expect(remainderPx).toBeCloseTo(-50 + 3 * 15.6)
})

test("a non-positive or non-finite cell height is a safe no-op", () => {
  for (const bad of [0, -15.6, NaN, Infinity]) {
    const r = linesFromPixels(100, bad)
    expect(r.lines).toBe(0)
    expect(r.remainderPx).toBe(0)
  }
})

// wheelEventsFromLines: under a full-screen app that grabs the mouse (tmux with
// `mouse on`), the screen is in the alternate buffer with no xterm scrollback,
// so scrollLines() is a no-op. We forward SGR (1006) mouse-wheel events to the
// app instead — the same path the desktop mouse wheel uses — and let it scroll
// its own history. Button 64 = wheel up (back into history), 65 = wheel down.

test("no scroll delta emits no wheel events", () => {
  expect(wheelEventsFromLines(0, 10, 5)).toBe("")
})

test("scrolling back into history emits one SGR wheel-up (button 64) per row", () => {
  expect(wheelEventsFromLines(-1, 10, 5)).toBe("\x1b[<64;10;5M")
})

test("scrolling toward newer output emits one SGR wheel-down (button 65) per row", () => {
  expect(wheelEventsFromLines(1, 10, 5)).toBe("\x1b[<65;10;5M")
})

test("a multi-row swipe emits one wheel event per row, matching the line sign", () => {
  expect(wheelEventsFromLines(-3, 1, 1)).toBe("\x1b[<64;1;1M".repeat(3))
  expect(wheelEventsFromLines(2, 8, 4)).toBe("\x1b[<65;8;4M".repeat(2))
})

test("pointer coordinates are floored to a valid 1-based cell (tmux ignores 0/out-of-range)", () => {
  expect(wheelEventsFromLines(-1, 0, 0)).toBe("\x1b[<64;1;1M")
  expect(wheelEventsFromLines(-1, 3.7, 9.2)).toBe("\x1b[<64;3;9M")
})

test("a non-finite line count is a safe no-op", () => {
  for (const bad of [NaN, Infinity, -Infinity]) {
    expect(wheelEventsFromLines(bad, 5, 5)).toBe("")
  }
})
