import { test, expect } from "bun:test"
import { ModelCache } from "../src/core/models/cache"
import type { ModelInfo } from "../src/core/models/discovery"

test("get returns empty array for unknown agent", () => {
  const cache = new ModelCache()
  expect(cache.get("claude")).toEqual([])
})

test("set + get round-trips", () => {
  const cache = new ModelCache()
  const models: ModelInfo[] = [
    { id: "claude-opus-4-7", displayName: "Claude Opus 4.7", agent: "claude" },
  ]
  cache.set("claude", models)
  expect(cache.get("claude")).toEqual(models)
})

test("isExpired returns true when TTL exceeded", () => {
  const cache = new ModelCache()
  cache.set("claude", [], Date.now() - 3601_000)
  expect(cache.isExpired("claude", 3600_000)).toBe(true)
})

test("isExpired returns false within TTL", () => {
  const cache = new ModelCache()
  cache.set("claude", [])
  expect(cache.isExpired("claude", 3600_000)).toBe(false)
})

test("isExpired returns true for never-set agent", () => {
  const cache = new ModelCache()
  expect(cache.isExpired("codex", 3600_000)).toBe(true)
})
