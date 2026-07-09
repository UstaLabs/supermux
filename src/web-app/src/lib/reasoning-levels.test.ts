import { expect, test } from "bun:test"
import { resolveReasoningLevel, showReasoningPicker } from "./reasoning-levels"

// The broker hands back low→high ordered levels; these mirror what it returns
// for Claude and for a typical Codex model.
const CLAUDE = [{ id: "low" }, { id: "medium" }, { id: "high" }, { id: "xhigh" }, { id: "max" }]
const CODEX = [{ id: "minimal" }, { id: "low" }, { id: "medium" }, { id: "high" }]

test("resolveReasoningLevel defaults a new session to High", () => {
  expect(resolveReasoningLevel(CLAUDE, undefined)).toBe("high")
  expect(resolveReasoningLevel(CLAUDE, "")).toBe("high")
  expect(resolveReasoningLevel(CODEX, undefined)).toBe("high")
})

test("resolveReasoningLevel keeps a valid stored choice", () => {
  expect(resolveReasoningLevel(CLAUDE, "max")).toBe("max")
  expect(resolveReasoningLevel(CLAUDE, "low")).toBe("low")
  expect(resolveReasoningLevel(CODEX, "minimal")).toBe("minimal")
})

test("resolveReasoningLevel falls back to the default when the stored value isn't offered", () => {
  // e.g. a stored "max" from Claude carried onto a Codex model that lacks it.
  expect(resolveReasoningLevel(CODEX, "max")).toBe("high")
  expect(resolveReasoningLevel(CLAUDE, "bogus")).toBe("high")
})

test("resolveReasoningLevel returns undefined when there are no levels to pick", () => {
  expect(resolveReasoningLevel([], undefined)).toBeUndefined()
  expect(resolveReasoningLevel([], "high")).toBeUndefined()
})

test("resolveReasoningLevel falls back to the highest level when High isn't available", () => {
  const levels = [{ id: "low" }, { id: "medium" }]
  expect(resolveReasoningLevel(levels, undefined)).toBe("medium")
  expect(resolveReasoningLevel(levels, "low")).toBe("low")
})

test("showReasoningPicker only shows when there's a real choice", () => {
  expect(showReasoningPicker(CLAUDE)).toBe(true)
  expect(showReasoningPicker(CODEX)).toBe(true)
  expect(showReasoningPicker([])).toBe(false)
  expect(showReasoningPicker([{ id: "only" }])).toBe(false)
})
