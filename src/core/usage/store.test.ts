import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AntigravityUsage, ClaudeUsage, CursorUsage, UsageResponse } from "./index"
import { UsageStore, isUsageProvider, type UsageFailed, type UsageSnapshot } from "./store"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"

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

// ── Added accounts ──

test("lists every added account and fetches the ones with a quota", async () => {
  let calls = 0
  const store = makeStore({
    accounts: async () => [
      { id: "claude-work", agent: "claude", label: "Work", method: "subscription", fetch: async () => { calls++; return claude(42) } },
      { id: "claude-key", agent: "claude", label: "API", method: "api_key" },
      { id: "codex-bad", agent: "codex", label: "Old", method: "token", fetch: async () => { throw new Error("Codex usage API 401: nope") } },
    ],
  })
  const snap = await store.refresh()
  expect(snap.accounts.map((a) => [a.id, a.label, a.quota])).toEqual([
    ["claude-work", "Work", true],
    ["claude-key", "API", false],
    ["codex-bad", "Old", true],
  ])
  expect(snap.accounts[0]!.claude?.fiveHour.used).toBe(42)
  expect(snap.accounts[0]!.fetchedAt).not.toBeNull()
  expect(snap.accounts[1]!.claude).toBeUndefined()
  expect(snap.accounts[2]!.error).toBe("Codex usage API 401: nope")
  expect(snap.accounts.every((a) => !a.refreshing)).toBe(true)

  // Within the stale window a plain refresh does not re-fetch; force does.
  await store.refresh()
  expect(calls).toBe(1)
  await store.refresh(undefined, { force: true })
  expect(calls).toBe(2)
  // A provider-scoped refresh leaves the accounts alone.
  await store.refresh(["claude"], { force: true })
  expect(calls).toBe(2)
})

test("drops a removed account and keeps account usage across a restart", async () => {
  let list = [
    { id: "a", agent: "claude" as const, label: "A", method: "subscription" as const, fetch: async () => claude(7) },
    { id: "b", agent: "claude" as const, label: "B", method: "subscription" as const, fetch: async () => claude(8) },
  ]
  const store = makeStore({ accounts: async () => list })
  await store.refresh()
  list = [list[0]!]
  const snap = await store.refresh()
  expect(snap.accounts.map((a) => a.id)).toEqual(["a"])
  store.dispose()

  const path = join(tmp, "usage-snapshot.json")
  const reloaded = new UsageStore({ filePath: path, fetchers: {}, localReaders: {} })
  stores.push(reloaded)
  const cached = reloaded.snapshot().accounts
  expect(cached.map((a) => [a.id, a.quota, a.claude?.fiveHour.used])).toEqual([["a", true, 7]])
})

test("every live fetch, of a system login or an added account, emits fetched with the account id", async () => {
  const fetched: Array<{ provider: string; account: string }> = []
  const store = makeStore({
    fetchers: { claude: async () => claude(10) },
    accounts: async () => [
      { id: "claude-work", agent: "claude", label: "Work", method: "subscription", fetch: async () => claude(42) },
      { id: "claude-none", agent: "claude", label: "Gone", method: "subscription", fetch: async () => null },
    ],
  })
  store.on("fetched", (e) => fetched.push({ provider: e.provider, account: e.account }))
  await store.refresh()
  expect(fetched.sort((a, b) => a.account.localeCompare(b.account))).toEqual([
    { provider: "claude", account: "claude-work" },
    { provider: "claude", account: "claude:system" },
  ])
})

test("refreshAgent fetches that agent's system login and accounts only, within the stale window once", async () => {
  const calls: string[] = []
  const store = makeStore({
    fetchers: { claude: async () => { calls.push("claude:system"); return claude(1) }, grok: async () => { calls.push("grok:system"); return null } },
    accounts: async () => [
      { id: "claude-work", agent: "claude", label: "Work", method: "subscription", fetch: async () => { calls.push("claude-work"); return claude(2) } },
      { id: "grok-a", agent: "grok", label: "A", method: "subscription", fetch: async () => { calls.push("grok-a"); return null } },
    ],
  })
  await store.refreshAgent("grok")
  expect(calls.sort()).toEqual(["grok-a", "grok:system"])
  await store.refreshAgent("grok")
  expect(calls.sort()).toEqual(["grok-a", "grok:system"])
})

test("fetched follows live data in, wherever it was fetched (the reset routes, /usage); never agent pushes, local seeds or a failed fetch", async () => {
  const fetched: Array<{ provider: string; account: string }> = []
  const store = makeStore({ fetchers: { codex: async () => null } })
  store.on("fetched", (e) => fetched.push({ provider: e.provider, account: e.account }))
  store.apply("claude", claude(5), "live")
  store.apply("claude", claude(6), "agent")
  store.apply("claude", claude(7), "local")
  store.applyResponse({ claude: null, codex: null, cursor: cursor(10), opencode: null, grok: null, errors: { claude: "boom" } })
  await store.refresh(["codex"])
  expect(fetched).toEqual([
    { provider: "claude", account: "claude:system" },
    { provider: "cursor", account: "cursor:system" },
  ])
})

// A refused login is a state the clients act on ("Sign in again"), so its code travels with the text.
function signInExpired(): Error {
  return new CoreError("account_expired", "Cursor sign-in expired — sign in to Cursor again.")
}

test("a fetch that failed for the login carries its error code until data arrives; other failures carry none", async () => {
  let mode: "expired" | "down" | "ok" = "expired"
  const store = makeStore({
    fetchers: {
      cursor: async () => {
        if (mode === "expired") throw signInExpired()
        if (mode === "down") throw new Error("Couldn't read Cursor usage right now — try again in a moment.")
        return cursor(30)
      },
    },
  })

  let snap = await store.refresh(["cursor"], { force: true })
  expect(snap.errors.cursor).toBe("Cursor sign-in expired — sign in to Cursor again.")
  expect(snap.errorCodes.cursor).toBe("account_expired")

  mode = "down"
  snap = await store.refresh(["cursor"], { force: true })
  expect(snap.errors.cursor).toBe("Couldn't read Cursor usage right now — try again in a moment.")
  expect(snap.errorCodes.cursor).toBeUndefined()

  mode = "expired"
  await store.refresh(["cursor"], { force: true })
  mode = "ok"
  snap = await store.refresh(["cursor"], { force: true })
  expect(snap.cursor?.totalPercentUsed).toBe(30)
  expect(snap.errors.cursor).toBeUndefined()
  expect(snap.errorCodes.cursor).toBeUndefined()
})

test("error codes survive a restart, for system logins and added accounts", async () => {
  const store = makeStore({
    fetchers: { cursor: async () => { throw signInExpired() } },
    accounts: async () => [
      { id: "cursor-work", agent: "cursor", label: "Work", method: "subscription", fetch: async () => { throw signInExpired() } },
      { id: "cursor-down", agent: "cursor", label: "Down", method: "subscription", fetch: async () => { throw new Error("Couldn't read Cursor usage right now — try again in a moment.") } },
    ],
  })
  const snap = await store.refresh()
  expect(snap.accounts.map((a) => [a.id, a.errorCode])).toEqual([["cursor-work", "account_expired"], ["cursor-down", null]])
  store.dispose()

  const reloaded = new UsageStore({ filePath: join(tmp, "usage-snapshot.json"), fetchers: {}, localReaders: {} })
  stores.push(reloaded)
  const cached = reloaded.snapshot()
  expect(cached.errorCodes.cursor).toBe("account_expired")
  expect(cached.accounts.map((a) => [a.id, a.errorCode])).toEqual([["cursor-work", "account_expired"], ["cursor-down", null]])
})

test("an added account's code clears once its usage arrives", async () => {
  let expired = true
  const store = makeStore({
    accounts: async () => [
      { id: "cursor-work", agent: "cursor", label: "Work", method: "subscription", fetch: async () => { if (expired) throw signInExpired(); return cursor(5) } },
    ],
  })
  expect((await store.refresh()).accounts[0]!.errorCode).toBe("account_expired")
  expired = false
  const snap = await store.refresh(undefined, { force: true })
  expect(snap.accounts[0]!.errorCode).toBeNull()
  expect(snap.accounts[0]!.error).toBeNull()
})

test("a live fetch that failed with an error code emits failed with the account id, its words and its code", async () => {
  const failed: UsageFailed[] = []
  const store = makeStore({
    fetchers: { cursor: async () => { throw signInExpired() }, claude: async () => null, grok: async () => { throw new Error("Grok billing API 500") } },
    accounts: async () => [
      { id: "cursor-work", agent: "cursor", label: "Work", method: "subscription", fetch: async () => { throw signInExpired() } },
      { id: "cursor-down", agent: "cursor", label: "Down", method: "subscription", fetch: async () => { throw new Error("Couldn't read Cursor usage right now — try again in a moment.") } },
    ],
  })
  store.on("failed", (e: UsageFailed) => failed.push(e))
  await store.refresh()
  expect(failed.sort((a, b) => a.account.localeCompare(b.account))).toEqual([
    { provider: "cursor", account: "cursor-work", message: "Cursor sign-in expired — sign in to Cursor again.", code: "account_expired" },
    { provider: "cursor", account: "cursor:system", message: "Cursor sign-in expired — sign in to Cursor again.", code: "account_expired" },
  ])
})

test("applyResponse (the /usage command) records error codes and emits failed for them", () => {
  const failed: UsageFailed[] = []
  const store = makeStore()
  store.on("failed", (e: UsageFailed) => failed.push(e))
  store.applyResponse({
    claude: null, codex: null, cursor: null, opencode: null, grok: null,
    errors: { cursor: "Cursor sign-in expired — sign in to Cursor again.", codex: "credentials not found" },
    errorCodes: { cursor: "account_expired" },
  })
  const snap = store.snapshot()
  expect(snap.errorCodes).toEqual({ cursor: "account_expired" })
  expect(failed).toEqual([{ provider: "cursor", account: "cursor:system", message: "Cursor sign-in expired — sign in to Cursor again.", code: "account_expired" }])
})

test("Antigravity is a usage provider: its reading lands in the snapshot, the spinner ends, and it survives a restart", async () => {
  const antigravity: AntigravityUsage = {
    windows: [{ id: "gemini-5h", label: "Gemini Models · 5-hour", used: 60, resetsAt: "2026-11-07T17:19:08Z", resetsAtIso: "2026-11-07T17:19:08.000Z" }],
  }
  const gate = deferred<AntigravityUsage>()
  const path = filePath()
  const store = new UsageStore({ filePath: path, fetchers: { antigravity: () => gate.promise }, localReaders: {} })
  stores.push(store)
  expect(isUsageProvider("antigravity")).toBe(true)

  store.ensureFresh()
  await flush()
  expect(store.snapshot().refreshing).toEqual(["antigravity"])
  gate.resolve(antigravity)
  await flush(10)
  const snap = store.snapshot()
  expect(snap.antigravity).toEqual(antigravity)
  expect(snap.source.antigravity).toBe("live")
  expect(snap.refreshing).toEqual([])

  store.dispose()
  const again = new UsageStore({ filePath: path, fetchers: {}, localReaders: {} })
  stores.push(again)
  expect(again.snapshot().antigravity).toEqual(antigravity)
  expect(again.snapshot().source.antigravity).toBe("cache")
})

test("an Antigravity read that finds no sign-in says so in words", async () => {
  const store = makeStore({ fetchers: { antigravity: async () => null } })
  await store.refresh(["antigravity"])
  expect(store.snapshot().antigravity).toBeNull()
  expect(store.snapshot().errors.antigravity).toBe("Not signed in to Antigravity.")
})

test("opencode is throttled like the others now that it asks OpenCode for the Go limits; a forced refresh still fetches", async () => {
  let calls = 0
  let now = 1_000_000
  const store = makeStore({
    staleMs: 60_000,
    now: () => now,
    fetchers: { opencode: async () => { calls++; return { sessions: 0, messages: 0, totalCostUsd: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 } } },
  })
  await store.refresh(["opencode"])
  await store.refresh(["opencode"])
  expect(calls).toBe(1)
  await store.refresh(["opencode"], { force: true })
  expect(calls).toBe(2)
  now += 61_000
  await store.refresh(["opencode"])
  expect(calls).toBe(3)
})
