import { test, expect } from "bun:test"
import { claudeModelSupportsFast, resolveSessionFast, supportsFastMode } from "./fast-mode"
import type { ModelInfo } from "./discovery"

test("claude fast mode follows the CLI's Opus 4.8 / Opus 5+ rule", () => {
  expect(claudeModelSupportsFast("claude-opus-5-5")).toBe(true)
  expect(claudeModelSupportsFast("claude-opus-5")).toBe(true)
  expect(claudeModelSupportsFast("claude-opus-4-8")).toBe(true)
  expect(claudeModelSupportsFast("opus")).toBe(true)
  expect(claudeModelSupportsFast(undefined)).toBe(true)
  expect(claudeModelSupportsFast("claude-opus-4-7")).toBe(false)
  expect(claudeModelSupportsFast("claude-sonnet-5-5")).toBe(false)
  expect(claudeModelSupportsFast("claude-fable-5-1")).toBe(false)
})

test("codex fast mode comes from the model catalog and needs a model", () => {
  const models: ModelInfo[] = [
    { id: "gpt-6-sol", displayName: "GPT-6 Sol", agent: "codex", supportsFast: true },
    { id: "gpt-6-luna", displayName: "GPT-6 Luna", agent: "codex" },
  ]
  expect(supportsFastMode("codex", models, "gpt-6-sol")).toBe(true)
  expect(supportsFastMode("codex", models, "gpt-6-luna")).toBe(false)
  expect(supportsFastMode("codex", models, undefined)).toBe(false)
  expect(supportsFastMode("grok", [], "grok-4.7")).toBe(false)
})

test("a discovered claude model's flag wins over the name rule", () => {
  const models: ModelInfo[] = [{ id: "claude-opus-5-5", displayName: "Opus", agent: "claude" }]
  expect(supportsFastMode("claude", models, "claude-opus-5-5")).toBe(false)
})

test("resolveSessionFast is asked-for AND supported", () => {
  const lookup = () => [] as ModelInfo[]
  expect(resolveSessionFast({ agent: "claude", model: "claude-opus-5-5", fastMode: true }, lookup)).toBe(true)
  expect(resolveSessionFast({ agent: "claude", model: "claude-sonnet-5-5", fastMode: true }, lookup)).toBe(false)
  expect(resolveSessionFast({ agent: "claude", model: "claude-opus-5-5", fastMode: false }, lookup)).toBe(false)
})
