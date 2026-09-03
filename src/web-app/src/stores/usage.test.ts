import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useUsage, type UsageSnapshot } from "./usage"

beforeEach(() => setActivePinia(createPinia()))

function snap(over: Partial<UsageSnapshot> = {}): UsageSnapshot {
  return {
    claude: null,
    codex: null,
    cursor: null,
    opencode: null,
    grok: null,
    errors: {},
    fetchedAt: { claude: null, codex: null, cursor: null, opencode: null, grok: null },
    source: { claude: null, codex: null, cursor: null, opencode: null, grok: null },
    refreshing: [],
    ...over,
  }
}

test("starts empty: no snapshot, not refreshing, no fetchedAt", () => {
  const u = useUsage()
  expect(u.snapshot).toBeNull()
  expect(u.fetchedAt("claude")).toBeNull()
  expect(u.isRefreshing("claude")).toBe(false)
})

test("set stores the snapshot; fetchedAt and isRefreshing read it", () => {
  const u = useUsage()
  u.set(snap({
    claude: { fiveHour: { used: 10 } },
    fetchedAt: {
      claude: "2026-09-02T12:00:00.000Z",
      codex: null,
      cursor: null,
      opencode: null,
      grok: null,
    },
    refreshing: ["codex"],
  }))
  expect(u.snapshot?.claude).toEqual({ fiveHour: { used: 10 } })
  expect(u.fetchedAt("claude")).toBe("2026-09-02T12:00:00.000Z")
  expect(u.fetchedAt("codex")).toBeNull()
  expect(u.isRefreshing("codex")).toBe(true)
  expect(u.isRefreshing("claude")).toBe(false)
})

test("set ignores malformed payloads", () => {
  const u = useUsage()
  u.set(undefined as any)
  expect(u.snapshot).toBeNull()
  u.set(snap())
  u.set(null as any)
  expect(u.snapshot).not.toBeNull()
})

test("set replaces the previous snapshot wholesale", () => {
  const u = useUsage()
  u.set(snap({ refreshing: ["claude"], errors: { claude: "old" } }))
  u.set(snap({ grok: { plan: "SuperGrok" }, refreshing: ["grok"] }))
  expect(u.snapshot?.errors).toEqual({})
  expect(u.isRefreshing("claude")).toBe(false)
  expect(u.isRefreshing("grok")).toBe(true)
  expect(u.snapshot?.grok).toEqual({ plan: "SuperGrok" })
})
