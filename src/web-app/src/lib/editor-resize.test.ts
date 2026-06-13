import { describe, expect, it } from "bun:test"
import {
  clamp,
  clampTreeWidth,
  resolveTreeResize,
  TREE_COLLAPSE_AT,
  TREE_WIDTH,
} from "./editor-resize"

describe("clamp", () => {
  it("returns the value when within bounds", () => {
    expect(clamp(200, 100, 300)).toBe(200)
  })
  it("clamps to the lower bound", () => {
    expect(clamp(50, 100, 300)).toBe(100)
  })
  it("clamps to the upper bound", () => {
    expect(clamp(500, 100, 300)).toBe(300)
  })
})

describe("clampTreeWidth", () => {
  it("keeps a valid width", () => {
    expect(clampTreeWidth(250)).toBe(250)
  })
  it("rounds fractional widths", () => {
    expect(clampTreeWidth(199.6)).toBe(200)
  })
  it("clamps below the minimum", () => {
    expect(clampTreeWidth(10)).toBe(TREE_WIDTH.min)
  })
  it("clamps above the maximum", () => {
    expect(clampTreeWidth(9999)).toBe(TREE_WIDTH.max)
  })
  it("falls back to default for non-numbers", () => {
    expect(clampTreeWidth("nope" as unknown as number)).toBe(TREE_WIDTH.default)
    expect(clampTreeWidth(NaN)).toBe(TREE_WIDTH.default)
    expect(clampTreeWidth(Infinity)).toBe(TREE_WIDTH.default)
  })
})

describe("resolveTreeResize", () => {
  const opts = { min: TREE_WIDTH.min, max: 500, collapseAt: TREE_COLLAPSE_AT }

  it("resizes to the desired width within bounds", () => {
    expect(resolveTreeResize(300, opts)).toEqual({ type: "resize", width: 300 })
  })

  it("clamps to the min when desired is between collapseAt and min", () => {
    // 130 is below the 140 min but at/above the 110 collapse threshold → snap to min.
    expect(resolveTreeResize(130, opts)).toEqual({ type: "resize", width: TREE_WIDTH.min })
  })

  it("clamps to the provided max", () => {
    expect(resolveTreeResize(900, opts)).toEqual({ type: "resize", width: 500 })
  })

  it("collapses when desired drops below the collapse threshold", () => {
    expect(resolveTreeResize(80, opts)).toEqual({ type: "collapse" })
  })

  it("does not collapse exactly at the threshold", () => {
    expect(resolveTreeResize(TREE_COLLAPSE_AT, opts)).toEqual({ type: "resize", width: TREE_WIDTH.min })
  })

  it("falls back to min for non-finite input", () => {
    expect(resolveTreeResize(NaN, opts)).toEqual({ type: "resize", width: TREE_WIDTH.min })
  })
})
