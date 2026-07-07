import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import {
  fetchClaudeUsage,
  fetchCodexUsage,
  fetchCursorUsage,
  fetchOpenCodeUsage,
  fetchAllUsage,
  redeemCodexReset,
} from "../src/core/usage"

let tmpDir: string
let originalFetch: typeof globalThis.fetch

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-usage-"))
  originalFetch = globalThis.fetch
})

afterEach(() => {
  globalThis.fetch = originalFetch
  rmSync(tmpDir, { recursive: true, force: true })
})

// ── Claude ──

test("fetchClaudeUsage returns usage when credentials valid", async () => {
  const credsPath = join(tmpDir, "credentials.json")
  writeFileSync(
    credsPath,
    JSON.stringify({
      claudeAiOauth: {
        accessToken: "test-token",
        refreshToken: "test-refresh",
        expiresAt: Date.now() + 3600_000,
      },
    }),
  )

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe("https://api.anthropic.com/api/oauth/usage")
    expect(init?.headers.Authorization).toBe("Bearer test-token")
    expect(init?.headers["anthropic-beta"]).toBe("oauth-2025-04-20")
    return new Response(
      JSON.stringify({
        five_hour: { utilization: 42, resets_at: "2026-05-25T12:00:00Z" },
        seven_day: { utilization: 15, resets_at: "2026-05-30T00:00:00Z" },
        seven_day_sonnet: { utilization: 8, resets_at: "2026-05-30T00:00:00Z" },
        extra_usage: {
          enabled: true,
          monthly_limit: 100,
          used_credits: 23.5,
          currency: "usd",
        },
      }),
    )
  }) as typeof fetch

  const result = await fetchClaudeUsage(credsPath)
  expect(result).not.toBeNull()
  expect(result!.fiveHour.used).toBe(42)
  expect(result!.fiveHour.resetsAt).toBe("2026-05-25T12:00:00Z")
  expect(result!.sevenDay.used).toBe(15)
  // No limits[] array → Sonnet falls back to the legacy top-level field.
  expect(result!.sevenDaySonnet!.used).toBe(8)
  // No Fable limit anywhere → hidden.
  expect(result!.sevenDayFable).toBeNull()
  expect(result!.extraUsage).not.toBeNull()
  expect(result!.extraUsage!.enabled).toBe(true)
  expect(result!.extraUsage!.monthlyLimit).toBe(100)
  expect(result!.extraUsage!.usedCredits).toBe(23.5)
  expect(result!.extraUsage!.currency).toBe("usd")
})

test("fetchClaudeUsage reads per-model weekly caps from limits[]", async () => {
  const credsPath = join(tmpDir, "credentials.json")
  writeFileSync(
    credsPath,
    JSON.stringify({
      claudeAiOauth: { accessToken: "t", refreshToken: "r", expiresAt: Date.now() + 3600_000 },
    }),
  )

  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        five_hour: { utilization: 12, resets_at: "2026-07-07T08:00:00Z" },
        seven_day: { utilization: 4, resets_at: "2026-07-13T12:00:00Z" },
        // legacy per-model fields are being phased out — null now
        seven_day_sonnet: null,
        limits: [
          { kind: "session", group: "session", percent: 12, resets_at: "2026-07-07T08:00:00Z", scope: null },
          { kind: "weekly_all", group: "weekly", percent: 4, resets_at: "2026-07-13T12:00:00Z", scope: null },
          {
            kind: "weekly_scoped",
            group: "weekly",
            percent: 7,
            resets_at: "2026-07-13T12:00:00Z",
            scope: { model: { id: null, display_name: "Fable" }, surface: null },
          },
          {
            kind: "weekly_scoped",
            group: "weekly",
            percent: 3,
            resets_at: "2026-07-13T12:00:00Z",
            scope: { model: { id: null, display_name: "Sonnet" }, surface: null },
          },
        ],
      }),
    )) as unknown as typeof fetch

  const result = await fetchClaudeUsage(credsPath)
  expect(result).not.toBeNull()
  // Fable is only present in limits[] — sourced from there.
  expect(result!.sevenDayFable!.used).toBe(7)
  expect(result!.sevenDayFable!.resetsAt).toBe("2026-07-13T12:00:00Z")
  // limits[] wins over the (null) legacy seven_day_sonnet field.
  expect(result!.sevenDaySonnet!.used).toBe(3)
})

test("fetchClaudeUsage hides per-model caps when neither limits[] nor legacy fields have them", async () => {
  const credsPath = join(tmpDir, "credentials.json")
  writeFileSync(
    credsPath,
    JSON.stringify({
      claudeAiOauth: { accessToken: "t", refreshToken: "r", expiresAt: Date.now() + 3600_000 },
    }),
  )

  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        five_hour: { utilization: 12, resets_at: "2026-07-07T08:00:00Z" },
        seven_day: { utilization: 4, resets_at: "2026-07-13T12:00:00Z" },
        seven_day_sonnet: null,
        limits: [
          { kind: "session", group: "session", percent: 12, resets_at: "2026-07-07T08:00:00Z", scope: null },
          { kind: "weekly_all", group: "weekly", percent: 4, resets_at: "2026-07-13T12:00:00Z", scope: null },
        ],
      }),
    )) as unknown as typeof fetch

  const result = await fetchClaudeUsage(credsPath)
  expect(result!.sevenDaySonnet).toBeNull()
  expect(result!.sevenDayFable).toBeNull()
})

test("fetchClaudeUsage returns null when credentials missing", async () => {
  const result = await fetchClaudeUsage(join(tmpDir, "nonexistent.json"))
  expect(result).toBeNull()
})

test("fetchClaudeUsage returns null when token expired", async () => {
  const credsPath = join(tmpDir, "expired.json")
  writeFileSync(
    credsPath,
    JSON.stringify({
      claudeAiOauth: {
        accessToken: "expired-token",
        refreshToken: "test-refresh",
        expiresAt: Date.now() - 3600_000,
      },
    }),
  )

  // fetch should never be called for expired token
  globalThis.fetch = (async () => {
    throw new Error("should not be called")
  }) as unknown as typeof fetch

  const result = await fetchClaudeUsage(credsPath)
  expect(result).toBeNull()
})

// ── Codex ──

test("fetchCodexUsage returns usage when auth valid", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(
    authPath,
    JSON.stringify({
      tokens: { access_token: "codex-token-123" },
    }),
  )

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe("https://chatgpt.com/backend-api/wham/usage")
    expect(init?.headers.Authorization).toBe("Bearer codex-token-123")
    return new Response(
      JSON.stringify({
        plan: "plus",
        rate_limit: {
          primary_window: { used_percent: 60, reset_at: 1748200000 },
          secondary_window: { used_percent: 20, reset_at: 1748300000 },
          limit_reached: false,
        },
        credits: { has_credits: true, balance: "15.00" },
        rate_limit_reset_credits: { available_count: 3 },
      }),
    )
  }) as typeof fetch

  const result = await fetchCodexUsage(authPath)
  expect(result).not.toBeNull()
  expect(result!.plan).toBe("plus")
  expect(result!.primaryWindow.used).toBe(60)
  expect(result!.primaryWindow.resetsAt).toBe(1748200000)
  expect(result!.secondaryWindow.used).toBe(20)
  expect(result!.credits).not.toBeNull()
  expect(result!.credits!.hasCredits).toBe(true)
  expect(result!.credits!.balance).toBe("15.00")
  expect(result!.limitReached).toBe(false)
  expect(result!.resetCredits).toBe(3)
})

test("fetchCodexUsage accepts legacy resets_at field", async () => {
  const authPath = join(tmpDir, "auth-legacy.json")
  writeFileSync(
    authPath,
    JSON.stringify({
      tokens: { access_token: "codex-token-legacy" },
    }),
  )

  globalThis.fetch = (async () => {
    return new Response(
      JSON.stringify({
        plan: "plus",
        rate_limit: {
          primary_window: { used_percent: 10, resets_at: 1748200000 },
          secondary_window: { used_percent: 5, resets_at: 1748300000 },
        },
      }),
    )
  }) as unknown as typeof fetch

  const result = await fetchCodexUsage(authPath)
  expect(result!.primaryWindow.resetsAt).toBe(1748200000)
  expect(result!.secondaryWindow.resetsAt).toBe(1748300000)
  expect(result!.resetCredits).toBe(0)
})

test("fetchCodexUsage returns null when auth missing", async () => {
  const result = await fetchCodexUsage(join(tmpDir, "nonexistent.json"))
  expect(result).toBeNull()
})

// ── Cursor ──

test("fetchCursorUsage returns usage from sqlite + API", async () => {
  const dbPath = join(tmpDir, "state.vscdb")
  const db = new Database(dbPath)
  db.run("CREATE TABLE ItemTable (key TEXT PRIMARY KEY, value TEXT)")
  db.run("INSERT INTO ItemTable (key, value) VALUES (?, ?)", [
    "cursorAuth/accessToken",
    "cursor-token-abc",
  ])
  db.close()

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe(
      "https://api2.cursor.sh/aiserver.v1.DashboardService/GetCurrentPeriodUsage",
    )
    expect(init?.headers.Authorization).toBe("Bearer cursor-token-abc")
    expect(init?.headers["Connect-Protocol-Version"]).toBe("1")
    expect(init?.method).toBe("POST")
    return new Response(
      JSON.stringify({
        totalPercentUsed: 45.3,
        totalSpendCents: 1200,
        includedCents: 2000,
        limitCents: 5000,
        billingCycleStart: "2026-05-01T00:00:00Z",
        billingCycleEnd: "2026-06-01T00:00:00Z",
      }),
    )
  }) as typeof fetch

  const result = await fetchCursorUsage(dbPath)
  expect(result).not.toBeNull()
  expect(result!.totalPercentUsed).toBe(45.3)
  expect(result!.totalSpendCents).toBe(1200)
  expect(result!.includedCents).toBe(2000)
  expect(result!.limitCents).toBe(5000)
  expect(result!.billingCycleStart).toBe("2026-05-01T00:00:00Z")
  expect(result!.billingCycleEnd).toBe("2026-06-01T00:00:00Z")
})

test("fetchCursorUsage returns null when db missing", async () => {
  const result = await fetchCursorUsage(join(tmpDir, "nonexistent.vscdb"))
  expect(result).toBeNull()
})

// ── opencode ──

test("fetchOpenCodeUsage aggregates token usage and cost from opencode.db", async () => {
  const dbPath = join(tmpDir, "opencode.db")
  const db = new Database(dbPath)
  db.run("CREATE TABLE session (id TEXT PRIMARY KEY)")
  db.run("CREATE TABLE message (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
  db.run("INSERT INTO session (id) VALUES ('s1'), ('s2')")
  // a user message carries no tokens/cost and must be skipped by the aggregation,
  // but it still counts toward the message total (matching `opencode stats`).
  db.run("INSERT INTO message (id, data) VALUES (?, ?)", ["m0", JSON.stringify({ role: "user" })])
  db.run("INSERT INTO message (id, data) VALUES (?, ?)", ["m1", JSON.stringify({
    role: "assistant", cost: 0.05,
    tokens: { input: 100, output: 50, cache: { read: 10, write: 5 } },
  })])
  db.run("INSERT INTO message (id, data) VALUES (?, ?)", ["m2", JSON.stringify({
    role: "assistant", cost: 0.03,
    tokens: { input: 200, output: 80, cache: { read: 0, write: 0 } },
  })])
  db.close()

  const result = await fetchOpenCodeUsage(dbPath)
  expect(result).not.toBeNull()
  expect(result!.sessions).toBe(2)
  expect(result!.messages).toBe(3)
  expect(result!.totalCostUsd).toBeCloseTo(0.08, 5)
  expect(result!.inputTokens).toBe(300)
  expect(result!.outputTokens).toBe(130)
  expect(result!.cacheReadTokens).toBe(10)
  expect(result!.cacheWriteTokens).toBe(5)
})

test("fetchOpenCodeUsage returns null when the db is missing (free tier, no sessions yet)", async () => {
  const result = await fetchOpenCodeUsage(join(tmpDir, "nonexistent.db"))
  expect(result).toBeNull()
})

// ── fetchAllUsage ──

test("fetchAllUsage assembles all providers, captures errors when all creds missing", async () => {
  const result = await fetchAllUsage({
    claudeCredsPath: join(tmpDir, "no-claude.json"),
    codexAuthPath: join(tmpDir, "no-codex.json"),
    cursorDbPath: join(tmpDir, "no-cursor.vscdb"),
    opencodeDbPath: join(tmpDir, "no-opencode.db"),
  })

  expect(result.claude).toBeNull()
  expect(result.codex).toBeNull()
  expect(result.cursor).toBeNull()
  expect(result.opencode).toBeNull()
  expect(result.errors.claude).toBe("credentials not found or token expired")
  expect(result.errors.codex).toBe("credentials not found")
  expect(result.errors.cursor).toBe("credentials not found")
  expect(result.errors.opencode).toBe("no usage recorded yet")
})

// ── Codex reset redemption ──

test("redeemCodexReset posts idempotency key and maps reset code", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "codex-token-xyz" } }))

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume")
    expect(init?.method).toBe("POST")
    expect(init?.headers.Authorization).toBe("Bearer codex-token-xyz")
    expect(init?.headers["Content-Type"]).toBe("application/json")
    expect(JSON.parse(init.body)).toEqual({ redeem_request_id: "fixed-key-1" })
    return new Response(JSON.stringify({ code: "reset", windows_reset: 2 }))
  }) as typeof fetch

  const result = await redeemCodexReset(authPath, "fixed-key-1")
  expect(result.code).toBe("reset")
  expect(result.windowsReset).toBe(2)
})

test("redeemCodexReset maps no_credit code", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () =>
    new Response(JSON.stringify({ code: "no_credit", windows_reset: 0 }))) as unknown as typeof fetch
  const result = await redeemCodexReset(authPath, "k")
  expect(result.code).toBe("no_credit")
  expect(result.windowsReset).toBe(0)
})

test("redeemCodexReset throws when auth missing", async () => {
  await expect(redeemCodexReset(join(tmpDir, "nope.json"), "k")).rejects.toThrow()
})

test("redeemCodexReset throws on API error", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () => new Response("boom", { status: 500 })) as unknown as typeof fetch
  await expect(redeemCodexReset(authPath, "k")).rejects.toThrow()
})
