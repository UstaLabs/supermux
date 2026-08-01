import { test, expect } from "bun:test"
import { parseCuratorConfig, toCron, defaultCuratorConfig } from "../src/core/settings/curator-config"

test("parseCuratorConfig clamps hour/minute and ignores stray chatId", () => {
  expect(parseCuratorConfig({ enabled: true, hour: 9, minute: 30 })).toEqual({
    enabled: true, hour: 9, minute: 30, agent: "claude",
  })
  // out of range → clamped
  expect(parseCuratorConfig({ hour: 99, minute: -5 })).toMatchObject({ hour: 23, minute: 0 })
  // a stray chatId from an older stored config is dropped
  expect(parseCuratorConfig({ enabled: true, hour: 2, minute: 0, chatId: "web:x" } as any)).toEqual({
    enabled: true, hour: 2, minute: 0, agent: "claude",
  })
  // garbage → safe defaults, never throws
  expect(() => parseCuratorConfig("nonsense")).not.toThrow()
  expect(parseCuratorConfig(undefined)).toEqual(defaultCuratorConfig)
})

test("parseCuratorConfig accepts agent/model/reasoningLevel", () => {
  expect(parseCuratorConfig({
    enabled: true, hour: 3, minute: 15, agent: "codex", model: "gpt-5.4", reasoningLevel: "high",
  })).toEqual({
    enabled: true, hour: 3, minute: 15, agent: "codex", model: "gpt-5.4", reasoningLevel: "high",
  })
  // bad agent → keep base (default claude)
  expect(parseCuratorConfig({ agent: "nope" }).agent).toBe("claude")
  // empty model/reasoning clears when explicitly provided
  expect(parseCuratorConfig(
    { model: "  ", reasoningLevel: "" },
    { ...defaultCuratorConfig, model: "opus", reasoningLevel: "high" },
  )).toEqual({ enabled: false, hour: 1, minute: 0, agent: "claude" })
  // null also clears
  expect(parseCuratorConfig(
    { model: null, reasoningLevel: null },
    { ...defaultCuratorConfig, model: "opus", reasoningLevel: "high" },
  )).toEqual({ enabled: false, hour: 1, minute: 0, agent: "claude" })
  // missing keys preserve base
  expect(parseCuratorConfig(
    { enabled: true },
    { ...defaultCuratorConfig, agent: "cursor", model: "m1", reasoningLevel: "medium" },
  )).toEqual({
    enabled: true, hour: 1, minute: 0, agent: "cursor", model: "m1", reasoningLevel: "medium",
  })
})

test("toCron compiles daily HH:MM", () => {
  expect(toCron({ enabled: true, hour: 1, minute: 0, agent: "claude" })).toBe("0 1 * * *")
  expect(toCron({ enabled: true, hour: 13, minute: 45, agent: "claude" })).toBe("45 13 * * *")
})
