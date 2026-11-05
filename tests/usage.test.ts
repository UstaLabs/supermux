import { test, expect, beforeEach, afterEach, spyOn } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import {
  fetchClaudeUsage,
  fetchCodexUsage,
  fetchCursorUsage,
  fetchOpenCodeUsage,
  fetchGrokUsage,
  fetchAllUsage,
  redeemCodexReset,
  redeemClaudeReset,
  claudeCliVersion,
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
    expect(url).toBe("https://api.anthropic.com/api/oauth/usage?cedar_ember=1")
    expect(init?.headers.Authorization).toBe("Bearer test-token")
    expect(init?.headers["anthropic-beta"]).toBe("oauth-2025-04-20")
    // The reset block is only answered for the Claude Code CLI surface.
    expect(init?.headers["User-Agent"]).toMatch(/^claude-cli\/\d+\.\d+\.\d+ \(external, cli\)$/)
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
  // Broker-normalized ISO timestamp for clients (no per-provider unit logic).
  expect(result!.fiveHour.resetsAtIso).toBe("2026-05-25T12:00:00.000Z")
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

test("fetchClaudeUsage maps the current extra-usage money shape", async () => {
  const credsPath = join(tmpDir, "credentials-current.json")
  writeFileSync(credsPath, JSON.stringify({
    claudeAiOauth: { accessToken: "t", expiresAt: Date.now() + 3600_000 },
  }))

  globalThis.fetch = (async () => new Response(JSON.stringify({
    five_hour: { utilization: 1 },
    seven_day: { utilization: 2 },
    extra_usage: {
      is_enabled: true,
      monthly_limit: 5000,
      used_credits: 125,
      decimal_places: 2,
      currency: "USD",
    },
    spend: {
      enabled: true,
      used: { amount_minor: 125, exponent: 2, currency: "USD" },
      limit: { amount_minor: 5000, exponent: 2, currency: "USD" },
    },
  }))) as unknown as typeof fetch

  const result = await fetchClaudeUsage(credsPath)
  expect(result!.extraUsage).toEqual({
    enabled: true,
    monthlyLimit: 50,
    usedCredits: 1.25,
    currency: "USD",
  })
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
          primary_window: { used_percent: 60, reset_at: 1748200000, limit_window_seconds: 18000 },
          secondary_window: { used_percent: 20, reset_at: 1748300000, limit_window_seconds: 604800 },
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
  expect(result!.windows).toEqual([
    { id: "primary", used: 60, resetsAt: 1748200000, resetsAtIso: new Date(1748200000 * 1000).toISOString(), label: "5-hour window", windowSeconds: 18000 },
    { id: "secondary", used: 20, resetsAt: 1748300000, resetsAtIso: new Date(1748300000 * 1000).toISOString(), label: "7-day window", windowSeconds: 604800 },
  ])
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
  expect(result!.windows[0]).toEqual({
    id: "primary", used: 10, resetsAt: 1748200000, resetsAtIso: new Date(1748200000 * 1000).toISOString(), label: "5-hour window", windowSeconds: null,
  })
  expect(result!.windows[1]).toEqual({
    id: "secondary", used: 5, resetsAt: 1748300000, resetsAtIso: new Date(1748300000 * 1000).toISOString(), label: "7-day window", windowSeconds: null,
  })
  expect(result!.resetCredits).toBe(0)
})

test("fetchCodexUsage labels the live single primary window by duration", async () => {
  const authPath = join(tmpDir, "auth-current.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () => new Response(JSON.stringify({
    plan_type: "plus",
    rate_limit: {
      primary_window: {
        used_percent: 25,
        limit_window_seconds: 604800,
        reset_at: 1784788528,
      },
      secondary_window: null,
    },
  }))) as unknown as typeof fetch

  const result = await fetchCodexUsage(authPath)
  expect(result!.windows).toEqual([{
    id: "primary",
    used: 25,
    resetsAt: 1784788528,
    resetsAtIso: new Date(1784788528 * 1000).toISOString(),
    label: "7-day window",
    windowSeconds: 604800,
  }])
})

test("fetchCodexUsage returns null when auth missing", async () => {
  const result = await fetchCodexUsage(join(tmpDir, "nonexistent.json"))
  expect(result).toBeNull()
})

// `model_usage` is a per-model gate that sits ON TOP of the 5h/7d windows: a model
// can be locked while both windows still have room (OpenAI shipped it with Astra).
test("fetchCodexUsage maps model_usage into per-model gates", async () => {
  const authPath = join(tmpDir, "auth-models.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () => new Response(JSON.stringify({
    plan_type: "plus",
    rate_limit: { primary_window: { used_percent: 43, limit_window_seconds: 18000, reset_at: 1789056894 } },
    model_usage: {
      "gpt-6-astra": { available: false, available_at: 1789056894, credits_would_enable: true },
      "gpt-6-codex": { available: true, available_at: null, credits_would_enable: false },
    },
  }))) as unknown as typeof fetch

  const result = await fetchCodexUsage(authPath)
  expect(result!.models).toEqual([
    {
      id: "gpt-6-astra",
      label: "GPT-6 Astra",
      available: false,
      availableAt: 1789056894,
      availableAtIso: new Date(1789056894 * 1000).toISOString(),
      creditsWouldEnable: true,
    },
    {
      id: "gpt-6-codex",
      label: "GPT-6 Codex",
      available: true,
      availableAt: null,
      availableAtIso: null,
      creditsWouldEnable: false,
    },
  ])
})

test("fetchCodexUsage returns an empty model list when the payload has no model_usage", async () => {
  const authPath = join(tmpDir, "auth-no-models.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () => new Response(JSON.stringify({
    plan_type: "plus",
    rate_limit: { primary_window: { used_percent: 10, limit_window_seconds: 18000, reset_at: 1789056894 } },
  }))) as unknown as typeof fetch

  const result = await fetchCodexUsage(authPath)
  expect(result!.models).toEqual([])
})

// ── Cursor ──

// cursor-agent's own login, as `cursor-agent login` writes it (Linux: $XDG_CONFIG_HOME/cursor/auth.json).
function writeCursorAuth(dir: string, auth: Record<string, unknown> = { accessToken: "cursor-token-abc", refreshToken: "r" }): string {
  mkdirSync(join(dir, "cursor"), { recursive: true })
  const path = join(dir, "cursor", "auth.json")
  writeFileSync(path, JSON.stringify(auth))
  return path
}

// What api2.cursor.sh answers for a session token that expired or was revoked (captured 2026-11-04).
const CURSOR_NOT_LOGGED_IN = JSON.stringify({
  code: "unauthenticated",
  message: "Error",
  details: [{
    type: "aiserver.v1.ErrorDetails",
    debug: {
      error: "ERROR_NOT_LOGGED_IN",
      details: { title: "Authentication error", detail: "If you are logged in, try logging out and back in.", isRetryable: false, analyticsMetadata: { actionRequired: "login" } },
      isExpected: true,
    },
    value: "CAISVQoUQXV0aGVudGljYXRpb24gZXJyb3I",
  }],
})

test("fetchCursorUsage returns usage for cursor-agent's own login", async () => {
  const authPath = writeCursorAuth(tmpDir)

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

  const result = await fetchCursorUsage(authPath)
  expect(result).not.toBeNull()
  expect(result!.totalPercentUsed).toBe(45.3)
  expect(result!.totalSpendCents).toBe(1200)
  expect(result!.includedCents).toBe(2000)
  expect(result!.limitCents).toBe(5000)
  expect(result!.spendAvailable).toBe(true)
  expect(result!.billingCycleStart).toBe("2026-05-01T00:00:00Z")
  expect(result!.billingCycleEnd).toBe("2026-06-01T00:00:00Z")
})

test("fetchCursorUsage marks spend unavailable for the current planUsage shape", async () => {
  const authPath = writeCursorAuth(tmpDir, { accessToken: "t" })
  globalThis.fetch = (async () => new Response(JSON.stringify({
    billingCycleStart: "1782894225499",
    billingCycleEnd: "1785572625499",
    planUsage: { totalPercentUsed: 0 },
    spendLimitUsage: { overallLimit: 0, overallRemaining: 0 },
  }))) as unknown as typeof fetch

  const result = await fetchCursorUsage(authPath)
  expect(result!.totalPercentUsed).toBe(0)
  expect(result!.spendAvailable).toBe(false)
  expect(result!.totalSpendCents).toBe(0)
  expect(result!.includedCents).toBe(0)
  // Unix-ms string from the API is normalized to ISO for clients.
  expect(result!.billingCycleEndIso).toBe(new Date(1785572625499).toISOString())
})

test("fetchCursorUsage returns null when cursor-agent has no login", async () => {
  expect(await fetchCursorUsage(join(tmpDir, "nowhere", "cursor", "auth.json"))).toBeNull()
  expect(await fetchCursorUsage(writeCursorAuth(join(tmpDir, "no-token"), { refreshToken: "r" }))).toBeNull()
})

// The system login's card read the Cursor IDE's token store (~/.config/Cursor/User/globalStorage/state.vscdb),
// a different app's login that supermux never runs: an IDE not opened for months answered 401 while the
// cursor-agent login every Cursor session uses was fine.
test("by default fetchCursorUsage reads the login cursor-agent sessions use, never the Cursor IDE's", async () => {
  const xdg = join(tmpDir, "xdg")
  writeCursorAuth(xdg, { accessToken: "agent-token" })
  const savedXdg = process.env.XDG_CONFIG_HOME
  process.env.XDG_CONFIG_HOME = xdg
  let bearer: string | undefined
  globalThis.fetch = (async (_url: any, init: any) => {
    bearer = init?.headers.Authorization
    return new Response(JSON.stringify({ planUsage: { totalPercentUsed: 12 } }))
  }) as typeof fetch
  try {
    const result = await fetchCursorUsage()
    expect(bearer).toBe("Bearer agent-token")
    expect(result!.totalPercentUsed).toBe(12)
  } finally {
    if (savedXdg === undefined) delete process.env.XDG_CONFIG_HOME
    else process.env.XDG_CONFIG_HOME = savedXdg
  }
})

test("an expired or revoked Cursor sign-in is account_expired, in plain words", async () => {
  const authPath = writeCursorAuth(tmpDir)
  globalThis.fetch = (async () => new Response(CURSOR_NOT_LOGGED_IN, { status: 401 })) as unknown as typeof fetch

  const error = await fetchCursorUsage(authPath).then(() => undefined, (err: unknown) => err as Error & { code?: string })
  expect(error?.code).toBe("account_expired")
  expect(error?.message).toBe("Cursor sign-in expired — sign in to Cursor again.")
})

test("other Cursor usage failures read plainly, never as an HTTP status or a response body", async () => {
  const authPath = writeCursorAuth(tmpDir)
  globalThis.fetch = (async () => new Response("{\"code\":\"unavailable\",\"message\":\"upstream connect error\"}", { status: 503 })) as unknown as typeof fetch

  const error = await fetchCursorUsage(authPath).then(() => undefined, (err: unknown) => err as Error & { code?: string })
  expect(error).toBeDefined()
  expect(error!.code).toBeUndefined()
  expect(error!.message).toBe("Couldn't read Cursor usage right now — try again in a moment.")
})

// ── opencode ──

test("fetchOpenCodeUsage aggregates token usage and cost from the messages of a store without per-session totals", async () => {
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

test("fetchOpenCodeUsage sums the totals opencode keeps per session and parses no message body", async () => {
  // opencode keeps each session's cost and tokens on its row (its `session_usage` migration backfilled
  // them). Message bodies can be huge — a real store holds a 290 MB one — and parsing all of them on
  // the broker's event loop stalled it past the other providers' 10 s fetch timeouts.
  const dbPath = join(tmpDir, "opencode.db")
  const db = new Database(dbPath)
  db.run(`CREATE TABLE session (
    id TEXT PRIMARY KEY, cost REAL DEFAULT 0 NOT NULL,
    tokens_input INTEGER DEFAULT 0 NOT NULL, tokens_output INTEGER DEFAULT 0 NOT NULL,
    tokens_reasoning INTEGER DEFAULT 0 NOT NULL, tokens_cache_read INTEGER DEFAULT 0 NOT NULL,
    tokens_cache_write INTEGER DEFAULT 0 NOT NULL)`)
  db.run("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, data TEXT NOT NULL)")
  db.run("INSERT INTO session VALUES ('s1', 0.05, 100, 50, 7, 10, 5), ('s2', 0.03, 200, 80, 0, 0, 0)")
  const bodies = [
    JSON.stringify({ role: "user" }),
    JSON.stringify({ role: "assistant", cost: 0.05, tokens: { input: 100, output: 50, reasoning: 7, cache: { read: 10, write: 5 } } }),
    JSON.stringify({ role: "assistant", cost: 0.03, tokens: { input: 200, output: 80, reasoning: 0, cache: { read: 0, write: 0 } } }),
  ]
  bodies.forEach((data, i) => db.run("INSERT INTO message VALUES (?, ?, ?)", [`m${i}`, i < 2 ? "s1" : "s2", data]))
  db.close()

  const parse = spyOn(JSON, "parse")
  try {
    const result = await fetchOpenCodeUsage(dbPath)
    expect(parse.mock.calls.filter(([text]) => bodies.includes(text as string))).toEqual([])
    expect(result).not.toBeNull()
    expect(result!.sessions).toBe(2)
    expect(result!.messages).toBe(3)
    expect(result!.totalCostUsd).toBeCloseTo(0.08, 5)
    expect(result!.inputTokens).toBe(300)
    expect(result!.outputTokens).toBe(130)
    expect(result!.cacheReadTokens).toBe(10)
    expect(result!.cacheWriteTokens).toBe(5)
  } finally {
    parse.mockRestore()
  }
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
    cursorAuthPath: join(tmpDir, "no-cursor", "auth.json"),
    opencodeDbPath: join(tmpDir, "no-opencode.db"),
    grokAuthPath: join(tmpDir, "no-grok.json"),
  })

  expect(result.claude).toBeNull()
  expect(result.codex).toBeNull()
  expect(result.cursor).toBeNull()
  expect(result.opencode).toBeNull()
  expect(result.grok).toBeNull()
  expect(result.errors.claude).toBe("credentials not found or token expired")
  expect(result.errors.codex).toBe("credentials not found")
  expect(result.errors.cursor).toBe("credentials not found")
  expect(result.errors.opencode).toBe("no usage recorded yet")
  expect(result.errors.grok).toBe("credentials not found or token expired")
})

test("fetchAllUsage names the code of a refused login beside its words (the /usage command)", async () => {
  const authPath = writeCursorAuth(tmpDir)
  globalThis.fetch = (async () => new Response(CURSOR_NOT_LOGGED_IN, { status: 401 })) as unknown as typeof fetch
  const result = await fetchAllUsage({
    claudeCredsPath: join(tmpDir, "no-claude.json"),
    codexAuthPath: join(tmpDir, "no-codex.json"),
    cursorAuthPath: authPath,
    opencodeDbPath: join(tmpDir, "no-opencode.db"),
    grokAuthPath: join(tmpDir, "no-grok.json"),
  })
  expect(result.cursor).toBeNull()
  expect(result.errors.cursor).toBe("Cursor sign-in expired — sign in to Cursor again.")
  expect(result.errorCodes).toEqual({ cursor: "account_expired" })
})

// ── Claude banked resets ──

function writeClaudeCreds(): string {
  const credsPath = join(tmpDir, "credentials.json")
  writeFileSync(credsPath, JSON.stringify({ claudeAiOauth: { accessToken: "tok", expiresAt: Date.now() + 3600_000 } }))
  return credsPath
}

test("fetchClaudeUsage maps the cedar_ember banked-reset block", async () => {
  const credsPath = writeClaudeCreds()
  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        five_hour: { utilization: 5, resets_at: "2026-09-23T11:00:00Z" },
        seven_day: { utilization: 51, resets_at: "2026-09-28T12:00:00Z" },
        cedar_ember: {
          eligible: true,
          ineligible_reason: null,
          at_limit: false,
          exhausted: [],
          grants: [
            {
              id: "opus55-launch-promax-20260921",
              label: "Claude Opus 5.5 launch: one usage-limit reset for Pro and Max",
              resets_total: 1,
              resets_left: 1,
              starts_at: "2026-09-22T16:00:00+00:00",
              ends_at: "2026-10-22T16:00:00+00:00",
              clears: ["five_hour", "seven_day", "seven_day_overage_included"],
              paused: false,
              usable_now: true,
              use_requires_limit: false,
            },
            { id: "BAD ID", resets_left: 3 },
          ],
          next_grant_id: "opus55-launch-promax-20260921",
          cooldown_until: null,
        },
      }),
    )) as unknown as typeof fetch

  const r = (await fetchClaudeUsage(credsPath))!.resets!
  expect(r.eligible).toBe(true)
  expect(r.nextGrantId).toBe("opus55-launch-promax-20260921")
  // The malformed grant is dropped, so it adds nothing to the count.
  expect(r.grants).toHaveLength(1)
  expect(r.resetsLeft).toBe(1)
  expect(r.grants[0]!.endsAtIso).toBe("2026-10-22T16:00:00.000Z")
  expect(r.grants[0]!.useRequiresLimit).toBe(false)
  expect(r.grants[0]!.clears).toEqual(["five_hour", "seven_day", "seven_day_overage_included"])
})

test("fetchClaudeUsage reports null resets when the payload has no cedar_ember block", async () => {
  const credsPath = writeClaudeCreds()
  globalThis.fetch = (async () =>
    new Response(JSON.stringify({ five_hour: {}, seven_day: {} }))) as unknown as typeof fetch
  expect((await fetchClaudeUsage(credsPath))!.resets).toBeNull()
})

test("claudeCliVersion picks the newest installed CLI, never below the fallback", () => {
  const dir = join(tmpDir, "versions")
  rmSync(dir, { recursive: true, force: true })
  require("fs").mkdirSync(dir)
  for (const v of ["2.1.9", "2.1.300", "2.1.281", "notes"]) require("fs").mkdirSync(join(dir, v))
  expect(claudeCliVersion(dir)).toBe("2.1.300")
  expect(claudeCliVersion(join(tmpDir, "missing"))).toBe("2.1.280")
})

test("redeemClaudeReset posts the grant to the org reset endpoint", async () => {
  const credsPath = writeClaudeCreds()
  const claudeJsonPath = join(tmpDir, "claude.json")
  writeFileSync(claudeJsonPath, JSON.stringify({ oauthAccount: { organizationUuid: "org-1" } }))

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe("https://api.anthropic.com/api/organizations/org-1/reset_rate_limits")
    expect(init?.method).toBe("POST")
    expect(init?.headers.Authorization).toBe("Bearer tok")
    expect(JSON.parse(init.body)).toEqual({ program: "cedar_ember", grant_id: "grant-1", request_id: "req-1" })
    return new Response(JSON.stringify({ result: "reset", resets_left: 0, cleared: ["five_hour", "seven_day"] }))
  }) as typeof fetch

  const r = await redeemClaudeReset("grant-1", { credsPath, claudeJsonPath, requestId: "req-1" })
  expect(r).toEqual({ result: "reset", reason: null, resetsLeft: 0, cleared: ["five_hour", "seven_day"] })
})

test("redeemClaudeReset refuses a malformed grant id without calling the API", async () => {
  let called = false
  globalThis.fetch = (async () => { called = true; return new Response("{}") }) as unknown as typeof fetch
  await expect(redeemClaudeReset("../x", { credsPath: writeClaudeCreds() })).rejects.toThrow()
  expect(called).toBe(false)
})

test("redeemClaudeReset throws when the organization is unknown", async () => {
  const credsPath = writeClaudeCreds()
  await expect(
    redeemClaudeReset("grant-1", { credsPath, claudeJsonPath: join(tmpDir, "nope.json") }),
  ).rejects.toThrow("organization")
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


// ── Grok ──

test("fetchGrokUsage maps monthly credits and plan from cli-chat-proxy", async () => {
  const authPath = join(tmpDir, "grok-auth.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": {
      key: "grok-token-xyz",
      expires_at: new Date(Date.now() + 3_600_000).toISOString(),
    },
  }))

  const seen: string[] = []
  globalThis.fetch = (async (url: any) => {
    const u = String(url)
    seen.push(u)
    if (u.endsWith("/billing") && !u.includes("format=")) {
      return new Response(JSON.stringify({
        config: {
          monthlyLimit: { val: 150000 },
          used: { val: 15000 },
          onDemandCap: { val: 0 },
          billingPeriodStart: "2026-07-01T00:00:00+00:00",
          billingPeriodEnd: "2026-08-01T00:00:00+00:00",
        },
      }))
    }
    if (u.includes("/user?include=subscription")) {
      return new Response(JSON.stringify({ subscriptionTier: "SuperGrokPro" }))
    }
    if (u.includes("format=credits")) {
      return new Response(JSON.stringify({
        config: {
          prepaidBalance: { val: 500 },
          onDemandCap: { val: 1000 },
          onDemandUsed: { val: 25 },
        },
      }))
    }
    return new Response("not found", { status: 404 })
  }) as typeof fetch

  const result = await fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")
  expect(result).not.toBeNull()
  expect(result!.plan).toBe("SuperGrokPro")
  expect(result!.used).toBe(15000)
  expect(result!.monthlyLimit).toBe(150000)
  expect(result!.percentUsed).toBe(10)
  expect(result!.prepaidBalance).toBe(500)
  expect(result!.onDemandCap).toBe(1000)
  expect(result!.onDemandUsed).toBe(25)
  expect(result!.billingPeriodEnd).toBe("2026-08-01T00:00:00+00:00")
  expect(result!.billingPeriodEndIso).toBe("2026-08-01T00:00:00.000Z")
  expect(result!.periodType).toBe("monthly")
  expect(result!.products).toEqual([])
  expect(result!.creditsKnown).toBe(true)
  expect(seen.some((u) => u.includes("/billing"))).toBe(true)
})

// Unified billing moved the real quota to a WEEKLY window reported only by
// `?format=credits`; legacy `/billing` still answers monthly with a zero limit,
// which would compute 0% while the account is actually at 70%.
test("fetchGrokUsage prefers the weekly credits period over the legacy monthly billing", async () => {
  const authPath = join(tmpDir, "grok-weekly.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": {
      key: "grok-token-weekly",
      expires_at: new Date(Date.now() + 3_600_000).toISOString(),
    },
  }))

  globalThis.fetch = (async (url: any) => {
    const u = String(url)
    if (u.endsWith("/billing") && !u.includes("format=")) {
      return new Response(JSON.stringify({
        config: {
          monthlyLimit: { val: 0 },
          used: { val: 3 },
          onDemandCap: { val: 0 },
          billingPeriodStart: "2026-09-01T00:00:00+00:00",
          billingPeriodEnd: "2026-10-01T00:00:00+00:00",
        },
      }))
    }
    if (u.includes("/user?include=subscription")) {
      return new Response(JSON.stringify({ subscriptionTier: "GrokPro" }))
    }
    if (u.includes("format=credits")) {
      return new Response(JSON.stringify({
        config: {
          currentPeriod: {
            type: "USAGE_PERIOD_TYPE_WEEKLY",
            start: "2026-09-05T16:28:51.524533+00:00",
            end: "2026-09-12T16:28:51.524533+00:00",
          },
          creditUsagePercent: 70.0,
          onDemandCap: { val: 0 },
          onDemandUsed: { val: 0 },
          productUsage: [{ product: "GrokBuild", usagePercent: 70.0 }],
          isUnifiedBillingUser: true,
          prepaidBalance: { val: 0 },
          billingPeriodStart: "2026-09-05T16:28:51.524533+00:00",
          billingPeriodEnd: "2026-09-12T16:28:51.524533+00:00",
        },
      }))
    }
    return new Response("not found", { status: 404 })
  }) as typeof fetch

  const result = await fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")
  expect(result!.plan).toBe("GrokPro")
  expect(result!.periodType).toBe("weekly")
  expect(result!.percentUsed).toBe(70)
  expect(result!.billingPeriodStart).toBe("2026-09-05T16:28:51.524533+00:00")
  expect(result!.billingPeriodEnd).toBe("2026-09-12T16:28:51.524533+00:00")
  expect(result!.billingPeriodEndIso).toBe(new Date("2026-09-12T16:28:51.524533+00:00").toISOString())
  expect(result!.products).toEqual([{ product: "GrokBuild", percentUsed: 70 }])
  expect(result!.creditsKnown).toBe(true)
  // The legacy monthly numbers still ride along — the card hides them at limit 0.
  expect(result!.used).toBe(3)
  expect(result!.monthlyLimit).toBe(0)
})

test("fetchGrokUsage keeps the monthly period when credits report a monthly window", async () => {
  const authPath = join(tmpDir, "grok-monthly-credits.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": {
      key: "grok-token-monthly",
      expires_at: new Date(Date.now() + 3_600_000).toISOString(),
    },
  }))

  globalThis.fetch = (async (url: any) => {
    const u = String(url)
    if (u.endsWith("/billing") && !u.includes("format=")) {
      return new Response(JSON.stringify({
        config: {
          monthlyLimit: { val: 150000 },
          used: { val: 30000 },
          billingPeriodStart: "2026-09-01T00:00:00+00:00",
          billingPeriodEnd: "2026-10-01T00:00:00+00:00",
        },
      }))
    }
    if (u.includes("format=credits")) {
      return new Response(JSON.stringify({
        config: {
          currentPeriod: {
            type: "USAGE_PERIOD_TYPE_MONTHLY",
            start: "2026-09-01T00:00:00+00:00",
            end: "2026-10-01T00:00:00+00:00",
          },
          prepaidBalance: { val: 500 },
        },
      }))
    }
    return new Response("not found", { status: 404 })
  }) as typeof fetch

  const result = await fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")
  expect(result!.periodType).toBe("monthly")
  // No creditUsagePercent → fall back to the credit-pool ratio.
  expect(result!.percentUsed).toBe(20)
  expect(result!.creditsKnown).toBe(true)
  expect(result!.billingPeriodEnd).toBe("2026-10-01T00:00:00+00:00")
  expect(result!.prepaidBalance).toBe(500)
})

// Unified billing reports its quota only in the credits format; without it the legacy zero limit reads 0%.
test("fetchGrokUsage says the credit usage is unknown when the credits format did not tell it", async () => {
  const authPath = join(tmpDir, "grok-partial.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": { key: "t", expires_at: new Date(Date.now() + 3_600_000).toISOString() },
  }))
  const stub = (credits: () => Promise<Response>) => {
    globalThis.fetch = (async (url: any) => {
      const u = String(url)
      if (u.includes("format=credits")) return credits()
      if (u.endsWith("/billing")) return new Response(JSON.stringify({ config: { monthlyLimit: { val: 0 }, used: { val: 3 } } }))
      return new Response(JSON.stringify({ subscriptionTier: "GrokPro" }))
    }) as typeof fetch
  }
  for (const credits of [
    () => Promise.reject(new Error("socket hang up")),
    async () => new Response("unavailable", { status: 503 }),
    async () => new Response("{not json"),
    async () => new Response(JSON.stringify({ config: { prepaidBalance: { val: 0 } } })),
  ]) {
    stub(credits)
    const result = await fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")
    expect(result!.percentUsed).toBe(0)
    expect(result!.creditsKnown).toBe(false)
  }
})

// Without the credits format a legacy account cannot be told from a unified one, so its ratio is not trusted.
test("fetchGrokUsage leaves the credit usage unknown when the credits call fails, even beside a real monthly limit", async () => {
  const authPath = join(tmpDir, "grok-legacy-partial.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": { key: "t", expires_at: new Date(Date.now() + 3_600_000).toISOString() },
  }))
  globalThis.fetch = (async (url: any) => {
    const u = String(url)
    if (u.includes("format=credits")) throw new Error("socket hang up")
    if (u.endsWith("/billing")) return new Response(JSON.stringify({ config: { monthlyLimit: { val: 150000 }, used: { val: 30000 } } }))
    return new Response(JSON.stringify({ subscriptionTier: "SuperGrokPro" }))
  }) as typeof fetch
  const result = await fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")
  // The Usage screen still shows the legacy ratio; the account policy does not take it.
  expect(result!.percentUsed).toBe(20)
  expect(result!.creditsKnown).toBe(false)
})

test("fetchGrokUsage returns null when auth missing", async () => {
  const result = await fetchGrokUsage(join(tmpDir, "no-grok-auth.json"))
  expect(result).toBeNull()
})

test("fetchGrokUsage returns null when token expired", async () => {
  const authPath = join(tmpDir, "grok-expired.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": {
      key: "old",
      expires_at: new Date(Date.now() - 60_000).toISOString(),
    },
  }))
  const result = await fetchGrokUsage(authPath)
  expect(result).toBeNull()
})

test("fetchGrokUsage throws on billing API error", async () => {
  const authPath = join(tmpDir, "grok-auth-err.json")
  writeFileSync(authPath, JSON.stringify({
    "https://auth.x.ai::client": {
      key: "t",
      expires_at: new Date(Date.now() + 3_600_000).toISOString(),
    },
  }))
  globalThis.fetch = (async () => new Response("boom", { status: 500 })) as unknown as typeof fetch
  await expect(fetchGrokUsage(authPath, "https://cli-chat-proxy.test/v1")).rejects.toThrow()
})
