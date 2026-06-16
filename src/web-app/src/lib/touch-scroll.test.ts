import { test, expect } from "bun:test"
import { linesFromPixels } from "./touch-scroll"

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
