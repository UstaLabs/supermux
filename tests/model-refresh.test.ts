import { test, expect } from "bun:test"
import { ModelCache } from "../src/core/models/cache"
import { refreshModelCache, seedModelCache, SharedRefreshes } from "../src/core/models/refresh"
import type { ModelInfo } from "../src/core/models/discovery"

const claudeModels: ModelInfo[] = [
  { id: "claude-opus-4-8", displayName: "Claude Opus 4.8", agent: "claude" },
]

test("refreshModelCache stores discovered models", async () => {
  const cache = new ModelCache()
  await refreshModelCache(cache, { claude: async () => claudeModels })
  expect(cache.get("claude")).toEqual(claudeModels)
})

test("refreshModelCache recovers after an initial empty result", async () => {
  const cache = new ModelCache()
  // boot: discovery comes back empty (token not yet refreshed / network not ready)
  await refreshModelCache(cache, { claude: async () => [] })
  expect(cache.get("claude")).toEqual([])
  // a later refresh succeeds — the cache MUST pick it up (this is the bug)
  await refreshModelCache(cache, { claude: async () => claudeModels })
  expect(cache.get("claude")).toEqual(claudeModels)
})

test("refreshModelCache keeps the last good list when a later discovery returns empty", async () => {
  const cache = new ModelCache()
  await refreshModelCache(cache, { claude: async () => claudeModels })
  // a transient failure must NOT wipe a previously good list
  await refreshModelCache(cache, { claude: async () => [] })
  expect(cache.get("claude")).toEqual(claudeModels)
})

test("refreshModelCache reports agents that come back empty", async () => {
  const cache = new ModelCache()
  const empties: string[] = []
  await refreshModelCache(
    cache,
    { claude: async () => [] },
    { onEmpty: (agent) => empties.push(agent) },
  )
  expect(empties).toEqual(["claude"])
})

test("refreshModelCache treats a throwing discoverer as empty", async () => {
  const cache = new ModelCache()
  await refreshModelCache(cache, {
    claude: async () => {
      throw new Error("network")
    },
  })
  expect(cache.get("claude")).toEqual([])
})

test("refreshModelCache resolves to the agents whose list actually changed", async () => {
  const cache = new ModelCache()
  expect(await refreshModelCache(cache, { claude: async () => claudeModels })).toEqual(["claude"])
  // Same list again: nothing to announce.
  expect(await refreshModelCache(cache, { claude: async () => claudeModels })).toEqual([])
  // A transient empty keeps the old list — also nothing to announce.
  expect(await refreshModelCache(cache, { claude: async () => [] })).toEqual([])
  const renamed = [{ ...claudeModels[0]!, displayName: "Claude Opus 4.8 (new)" }]
  expect(await refreshModelCache(cache, { claude: async () => renamed })).toEqual(["claude"])
})

// #129 follow-up: GET /models waits for discovery while the cache is empty, and Claude's discovery runs Claude Code
// (~2.5 s), so a boot without the lists learned last time showed "Settings" on the model chip until it was done.
test("seedModelCache fills an empty cache from the lists learned before, of any age", () => {
  const cache = new ModelCache()
  const learned = { get: (agent: string) => (agent === "claude" ? claudeModels : undefined) }
  expect(seedModelCache(cache, learned, ["claude", "codex"])).toEqual(["claude"])
  expect(cache.get("claude")).toEqual(claudeModels)
  expect(cache.get("codex")).toEqual([])
  // Seeded entries are expired, so the next refresh still asks the agent.
  expect(cache.isExpired("claude", 60_000)).toBe(true)
})

test("seedModelCache never replaces a list already in the cache", () => {
  const cache = new ModelCache()
  const fresh: ModelInfo[] = [{ id: "claude-fable-5-1", displayName: "Fable 5.1", agent: "claude" }]
  cache.set("claude", fresh)
  expect(seedModelCache(cache, { get: () => claudeModels }, ["claude"])).toEqual([])
  expect(cache.get("claude")).toEqual(fresh)
})

test("SharedRefreshes runs one discovery per agent at a time, so a /models during the boot refresh joins it", async () => {
  const refreshes = new SharedRefreshes()
  let starts = 0
  let finish!: () => void
  const start = () => { starts++; return new Promise<void>((resolve) => { finish = resolve }) }
  const boot = refreshes.run("claude", start)
  const request = refreshes.run("claude", start)
  expect(request).toBe(boot)
  expect(starts).toBe(1)
  finish()
  await boot
  // Once done, the next refresh starts afresh.
  void refreshes.run("claude", start)
  expect(starts).toBe(2)
  finish()
})
