import { expect, test } from "bun:test"
import { FONT_SIZE, clampFont, stepFont, pinchFont } from "./editor-font-zoom"

test("FONT_SIZE exposes the shared range and default", () => {
  expect(FONT_SIZE.default).toBe(13)
  expect(FONT_SIZE.min).toBe(10)
  expect(FONT_SIZE.max).toBe(24)
})

test("clampFont rounds and clamps into the range", () => {
  expect(clampFont(13.4)).toBe(13)
  expect(clampFont(13.6)).toBe(14)
  expect(clampFont(99)).toBe(24)
  expect(clampFont(2)).toBe(10)
})

test("clampFont falls back to the default on NaN or non-number", () => {
  expect(clampFont(NaN)).toBe(13)
  expect(clampFont("big" as unknown)).toBe(13)
})

test("stepFont bumps by the delta and clamps at both ends", () => {
  expect(stepFont(13, +1)).toBe(14)
  expect(stepFont(13, -1)).toBe(12)
  expect(stepFont(24, +1)).toBe(24)
  expect(stepFont(10, -1)).toBe(10)
})

test("pinchFont scales the base size by the pinch distance ratio", () => {
  expect(pinchFont(12, 100, 200)).toBe(24) // 2x
  expect(pinchFont(20, 100, 50)).toBe(10) // 0.5x
  expect(pinchFont(13, 100, 100)).toBe(13) // unchanged
})

test("pinchFont clamps the scaled size into the range", () => {
  expect(pinchFont(20, 100, 400)).toBe(24)
  expect(pinchFont(12, 100, 10)).toBe(10)
})

test("pinchFont returns the clamped base when the base distance is non-positive", () => {
  expect(pinchFont(15, 0, 200)).toBe(15)
  expect(pinchFont(15, -5, 200)).toBe(15)
})
