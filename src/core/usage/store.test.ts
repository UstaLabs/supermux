import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { ClaudeUsage, CursorUsage, UsageResponse } from "./index"
import { UsageStore, type UsageSnapshot } from "./store"

let tmp: string
const stores: UsageStore[] = []

afterEach(() => {
  for (const s of stores) s.dispose()
  stores.length = 0
  if (tmp) rmSync(tmp, { recursive: true, force: true })
})

function filePath(): string {
  tmp = mkdtempSync(join(tmpdir(), "usage-store-"))
  return join(tmp, "usage-snapshot.json")
}

function claude(used = 1): ClaudeUsage {
  return {
    fiveHour: { used, resetsAt: null, resetsAtIso: null },
    sevenDay: { used: 0, resetsAt: null, resetsAtIso: null },
    sevenDaySonnet: null,
    sevenDayFable: null,
    extraUsage: null,
  }
}

function cursor(pct = 10): CursorUsage {
  return {
    totalPercentUsed: pct,
    totalSpendCents: 0,
    includedCents: 0,
    limitCents: 0,
    spendAvailable: false,
    billingCycleStart: "",
    billingCycleEnd: "",
    billingCycleEndIso: null,
  }
}

function makeStore(opts: ConstructorParameters<typeof UsageStore>[0] = {}): UsageStore {
  const store = new UsageStore({
    filePath: filePath(),
    fetchers: {},
    localReaders: {},
    ...opts,
  })
  stores.push(store)
  return store
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (err: unknown) => void
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}

async function flush(ms = 0): Promise<void> {
  for (let i = 0; i < 12; i++) await Promise.resolve()
  if (ms > 0) await new Promise((r) => setTimeout(r, ms))
  for (let i = 0; i < 12; i++) await Promise.resolve()
}

test("restores from disk with source cache; snapshot() is sync and never awaits fetchers", () => {
  const path = filePath()
  const data = claude(42)
  writeFileSync(
    path,
    JSON.stringify({
      claude: data,
      codex: null,
      cursor: null,
      opencode: null,
      grok: null,
      errors: { cursor: "credentials not found" },
      fetchedAt: {
        claude: "2026-01-02T03:04:05.000Z",
        codex: null,
        cursor: null,
        opencode: null,
        grok: null,
      },
      source: {
        claude: "live",
        codex: null,
        cursor: null,
        opencode: null,
        grok: null,
      },
    }),
  )

  let fetcherCalls = 0
  const store = new UsageStore({
    filePath: path,
    fetchers: {
      claude: async () => {
        fetcherCalls++
        return claude(99)
      },
    },
    localReaders: {},
  })
  stores.push(store)

  const snap = store.snapshot()
  expect(fetcherCalls).toBe(0)
  expect(snap.claude).toEqual(data)
  expect(snap.fetchedAt.claude).toBe("2026-01-02T03:04:05.000Z")
  expect(snap.source.claude).toBe("cache")
  expect(snap.errors.cursor).toBe("credentials not found")
  expect(snap.refreshing).toEqual([])
})

test("corrupt snapshot file is ignored", () => {
  const path = filePath()
  writeFileSync(path, "{not-json")
  const store = makeStore({ filePath: path })
  const snap = store.snapshot()
  expect(snap.claude).toBeNull()
  expect(snap.source.claude).toBeNull()
})

test("ensureFresh triggers one refresh for stale providers only; concurrent calls dedupe", async () => {
  const claudeGate = deferred<ClaudeUsage>()
  const cursorGate = deferred<CursorUsage>()
  let claudeCalls = 0
  let cursorCalls = 0
  let now = Date.parse("2026-06-01T00:00:00.000Z")

  const store = makeStore({
    now: () => now,
    staleMs: 5 * 60_000,
    fetchers: {
      claude: async () => {
        claudeCalls++
        return claudeGate.promise
      },
      cursor: async () => {
        cursorCalls++
        return cursorGate.promise
      },
    },
  })

  store.apply("claude", claude(1), "live", new Date(now - 60_000))

  store.ensureFresh()
  store.ensureFresh()
  await flush()

  expect(claudeCalls).toBe(0)
  expect(cursorCalls).toBe(1)
  expect(store.snapshot().refreshing).toEqual(["cursor"])

  cursorGate.resolve(cursor(7))
  await flush(10)
  expect(store.snapshot().cursor?.totalPercentUsed).toBe(7)
  expect(store.snapshot().refreshing).toEqual([])
  expect(store.snapshot().source.cursor).toBe("live")
})

test("refresh(force) bypasses throttle; refresh without force within staleMs is a no-op", async () => {
  let now = Date.parse("2026-06-01T00:00:00.000Z")
  let calls = 0
  const store = makeStore({
    now: () => now,
    staleMs: 5 * 60_000,
    fetchers: {
      claude: async () => {
        calls++
        return claude(calls)
      },
    },
  })

  await store.refresh(["claude"])
  expect(calls).toBe(1)
  expect(store.snapshot().claude?.fiveHour.used).toBe(1)

  await store.refresh(["claude"])
  expect(calls).toBe(1)

  await store.refresh(["claude"], { force: true })
  expect(calls).toBe(2)
  expect(store.snapshot().claude?.fiveHour.used).toBe(2)
})

test("failed fetch keeps old data and sets error", async () => {
  let now = Date.parse("2026-06-01T00:00:00.000Z")
  let fail = false
  const store = makeStore({
    now: () => now,
    staleMs: 5 * 60_000,
    fetchers: {
      claude: async () => {
        if (fail) throw new Error("Claude usage API 429: slow down")
        return claude(11)
      },
    },
  })

  await store.refresh(["claude"])
  const before = store.snapshot()
  expect(before.claude?.fiveHour.used).toBe(11)
  expect(before.errors.claude).toBeUndefined()

  fail = true
  now += 10 * 60_000
  await store.refresh(["claude"])
  const after = store.snapshot()
  expect(after.claude).toEqual(before.claude)
  expect(after.fetchedAt.claude).toBe(before.fetchedAt.claude)
  expect(after.source.claude).toBe("live")
  expect(after.errors.claude).toContain("429")
})

test("apply() with older fetchedAt is ignored; newer replaces and emits updated", () => {
  const store = makeStore()
  const updates: UsageSnapshot[] = []
  store.on("updated", (snap: UsageSnapshot) => updates.push(snap))

  store.apply("claude", claude(1), "agent", new Date("2026-03-01T00:00:00.000Z"))
  expect(updates).toHaveLength(1)
  expect(store.snapshot().claude?.fiveHour.used).toBe(1)
  expect(store.snapshot().source.claude).toBe("agent")

  store.apply("claude", claude(99), "agent", new Date("2026-02-01T00:00:00.000Z"))
  expect(updates).toHaveLength(1)
  expect(store.snapshot().claude?.fiveHour.used).toBe(1)

  store.apply("claude", claude(3), "local", new Date("2026-04-01T00:00:00.000Z"))
  expect(updates).toHaveLength(2)
  expect(store.snapshot().claude?.fiveHour.used).toBe(3)
  expect(store.snapshot().source.claude).toBe("local")
  expect(store.snapshot().fetchedAt.claude).toBe("2026-04-01T00:00:00.000Z")
})

test("noteActivity: stale last-live refreshes immediately; fresh last-live arms remainder timer once", async () => {
  let now = 1_000_000
  let calls = 0
  const store = makeStore({
    now: () => now,
    staleMs: 80,
    fetchers: {
      claude: async () => {
        calls++
        return claude(calls)
      },
    },
  })

  store.apply("claude", claude(0), "live", new Date(now - 80))
  store.noteActivity("claude")
  await flush(20)
  expect(calls).toBe(1)

  store.apply("claude", claude(1), "live", new Date(now - 20))
  const callsAfterLive = calls
  store.noteActivity("claude")
  store.noteActivity("claude")
  await flush(10)
  expect(calls).toBe(callsAfterLive)

  await flush(100)
  expect(calls).toBe(callsAfterLive + 1)

  await flush(120)
  expect(calls).toBe(callsAfterLive + 1)
})

test("applyResponse feeds live data and records errors without wiping other providers", () => {
  const store = makeStore({ now: () => Date.parse("2026-07-01T00:00:00.000Z") })
  store.apply("codex", { plan: "plus", windows: [], models: [], credits: null, limitReached: false, resetCredits: 2 }, "agent", new Date("2026-06-01T00:00:00.000Z"))

  const res: UsageResponse = {
    claude: claude(5),
    codex: null,
    cursor: null,
    opencode: null,
    grok: null,
    errors: { codex: "credentials not found", cursor: "credentials not found" },
  }
  store.applyResponse(res, new Date("2026-07-01T00:00:00.000Z"))

  const snap = store.snapshot()
  expect(snap.claude?.fiveHour.used).toBe(5)
  expect(snap.source.claude).toBe("live")
  expect(snap.codex).toEqual({
    plan: "plus",
    windows: [],
    models: [],
    credits: null,
    limitReached: false,
    resetCredits: 2,
  })
  expect(snap.errors.codex).toBe("credentials not found")
  expect(snap.errors.cursor).toBe("credentials not found")
})

test("seedFromLocal applies fresher local data only", async () => {
  const store = makeStore({
    now: () => Date.parse("2026-08-01T00:00:00.000Z"),
    localReaders: {
      claude: async () => ({
        data: claude(8),
        fetchedAt: new Date("2026-08-01T00:00:00.000Z"),
      }),
      cursor: async () => ({
        data: cursor(1),
        fetchedAt: new Date("2026-01-01T00:00:00.000Z"),
      }),
    },
  })
  store.apply("cursor", cursor(9), "agent", new Date("2026-07-01T00:00:00.000Z"))

  await store.seedFromLocal()
  expect(store.snapshot().claude?.fiveHour.used).toBe(8)
  expect(store.snapshot().source.claude).toBe("local")
  expect(store.snapshot().cursor?.totalPercentUsed).toBe(9)
  expect(store.snapshot().source.cursor).toBe("agent")
})

test("a local Claude seed keeps the banked resets the last live fetch found", () => {
  const store = makeStore()
  const resets = {
    eligible: true,
    ineligibleReason: null,
    atLimit: false,
    grants: [],
    nextGrantId: null,
    resetsLeft: 1,
    cooldownUntilIso: null,
  }
  store.apply("claude", { ...claude(10), resets }, "live", new Date(1_000))
  // The local reader (~/.claude.json cache) has no resets field at all.
  store.apply("claude", claude(20), "local", new Date(2_000))
  expect(store.snapshot().claude!.fiveHour.used).toBe(20)
  expect(store.snapshot().claude!.resets).toEqual(resets)
  // A live fetch that reports none (null) does clear it.
  store.apply("claude", { ...claude(30), resets: null }, "live", new Date(3_000))
  expect(store.snapshot().claude!.resets).toBeNull()
})
