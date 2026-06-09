import { test, expect } from "bun:test"
import {
  claudeReasoningLevels,
  effectiveReasoningLevel,
  highestReasoningLevel,
  shouldShowReasoningControl,
  clampReasoningLevel,
} from "../src/core/models/reasoning-levels"
import type { ModelInfo } from "../src/core/models/discovery"

test("highestReasoningLevel picks max tier", () => {
  expect(highestReasoningLevel([{ id: "low" }, { id: "high" }, { id: "medium" }])).toBe("high")
  expect(highestReasoningLevel(claudeReasoningLevels())).toBe("max")
})

test("effectiveReasoningLevel defaults to highest when unset", () => {
  const models: ModelInfo[] = [{
    id: "gpt-5.5",
    displayName: "GPT-5.5",
    agent: "codex",
    reasoningLevels: [{ id: "low" }, { id: "medium" }, { id: "high" }, { id: "xhigh" }],
  }]
  expect(effectiveReasoningLevel("codex", models, "gpt-5.5", undefined)).toBe("xhigh")
  expect(effectiveReasoningLevel("claude", [], undefined, undefined)).toBe("max")
})

test("effectiveReasoningLevel respects explicit stored level", () => {
  expect(effectiveReasoningLevel("claude", [], undefined, "medium")).toBe("medium")
})

test("clampReasoningLevel falls back to highest when level unsupported", () => {
  const models: ModelInfo[] = [{
    id: "small",
    displayName: "Small",
    agent: "codex",
    reasoningLevels: [{ id: "low" }, { id: "medium" }],
  }]
  expect(clampReasoningLevel("codex", models, "small", "xhigh")).toBe("medium")
})

test("shouldShowReasoningControl hidden for cursor and single-level codex", () => {
  expect(shouldShowReasoningControl("cursor", [], "auto")).toBe(false)
  const models: ModelInfo[] = [{
    id: "only",
    displayName: "Only",
    agent: "codex",
    reasoningLevels: [{ id: "medium" }],
  }]
  expect(shouldShowReasoningControl("codex", models, "only")).toBe(false)
  expect(shouldShowReasoningControl("claude", [], undefined)).toBe(true)
})
