import { test, expect } from "bun:test"
import { ModelCache } from "../src/core/models/cache"
import { refreshModelCache } from "../src/core/models/refresh"
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
