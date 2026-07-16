import { existsSync, readFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"

// ── Types ──

export interface UsageWindow { used: number; resetsAt: string | number | null }

export interface CodexUsageWindow extends UsageWindow {
  id: string
  label: string
  windowSeconds: number | null
}

export interface ClaudeExtraUsage {
  enabled: boolean
  monthlyLimit: number
  usedCredits: number
  currency: string
}

export interface ClaudeUsage {
  fiveHour: UsageWindow
  sevenDay: UsageWindow
  // Per-model weekly caps. null when Anthropic returns no such limit — clients hide the row.
  sevenDaySonnet: UsageWindow | null
  sevenDayFable: UsageWindow | null
  extraUsage: ClaudeExtraUsage | null
}

export interface CodexUsage {
  plan: string
  windows: CodexUsageWindow[]
  credits: { hasCredits: boolean; balance: string } | null
  limitReached: boolean
  resetCredits: number
}

export interface CursorUsage {
  totalPercentUsed: number
  totalSpendCents: number
  includedCents: number
  limitCents: number
  spendAvailable: boolean
  billingCycleStart: string
  billingCycleEnd: string
}

// opencode has no subscription quota — it tracks cumulative local token usage and
// cost, so its "usage" is all-time totals rather than a percent-of-limit window.
export interface OpenCodeUsage {
  sessions: number
  messages: number
  totalCostUsd: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
}

export interface UsageResponse {
  claude: ClaudeUsage | null
  codex: CodexUsage | null
  cursor: CursorUsage | null
  opencode: OpenCodeUsage | null
  errors: Record<string, string>
}

// ── Credential paths ──

const CLAUDE_CREDS = join(homedir(), ".claude", ".credentials.json")
const CODEX_AUTH   = join(homedir(), ".codex", "auth.json")
const CURSOR_DB   = join(homedir(), ".config", "Cursor", "User", "globalStorage", "state.vscdb")
const OPENCODE_DB = join(process.env.XDG_DATA_HOME || join(homedir(), ".local", "share"), "opencode", "opencode.db")

const TIMEOUT_MS = 10_000

// ── Claude ──

export async function fetchClaudeUsage(
  credsPath: string = CLAUDE_CREDS,
): Promise<ClaudeUsage | null> {
  if (!existsSync(credsPath)) return null

  const raw = JSON.parse(readFileSync(credsPath, "utf-8"))
  const oauth = raw.claudeAiOauth ?? raw
  const expiresAt = oauth.expiresAt
  if (typeof expiresAt === "string" && new Date(expiresAt).getTime() < Date.now()) return null
  if (typeof expiresAt === "number" && expiresAt < Date.now()) return null

  const token = oauth.accessToken ?? oauth.access_token
  if (!token) return null

  const res = await fetch("https://api.anthropic.com/api/oauth/usage", {
    headers: {
      Authorization: `Bearer ${token}`,
      "anthropic-beta": "oauth-2025-04-20",
    },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (!res.ok) throw new Error(`Claude usage API ${res.status}: ${await res.text()}`)

  const data = await res.json() as any

  const mapWindow = (w: any): UsageWindow => ({
    used: w?.utilization ?? 0,
    resetsAt: w?.resets_at ?? null,
  })

  // Anthropic moved per-model weekly caps into a `limits[]` array; the legacy
  // top-level `seven_day_<model>` fields are being phased out (now null on many
  // accounts). Read each scoped window from `limits[]` by model display name,
  // falling back to the legacy field only while it's still populated. Returns
  // null when neither exists so clients hide the row.
  const limits: any[] = Array.isArray(data.limits) ? data.limits : []
  const scopedWindow = (displayName: string): UsageWindow | null => {
    const e = limits.find(
      (l) =>
        l?.group === "weekly" &&
        typeof l?.scope?.model?.display_name === "string" &&
        l.scope.model.display_name.toLowerCase() === displayName.toLowerCase(),
    )
    return e ? { used: e.percent ?? 0, resetsAt: e.resets_at ?? null } : null
  }
  const legacyWindow = (w: any): UsageWindow | null =>
    w && typeof w === "object" ? mapWindow(w) : null

  const extra = data.extra_usage
  const spend = data.spend
  const moneyAmount = (value: any): number | null => {
    if (typeof value?.amount_minor !== "number") return null
    const exponent = typeof value.exponent === "number" ? value.exponent : 0
    return value.amount_minor / 10 ** exponent
  }
  const legacyAmount = (value: any): number => {
    const amount = Number(value) || 0
    const decimals = Number(extra?.decimal_places)
    return Number.isInteger(decimals) && decimals >= 0 ? amount / 10 ** decimals : amount
  }
  const extraUsage: ClaudeExtraUsage | null = extra
    ? {
        enabled: spend?.enabled ?? extra.is_enabled ?? extra.enabled ?? false,
        monthlyLimit: moneyAmount(spend?.limit) ?? legacyAmount(extra.monthly_limit),
        usedCredits: moneyAmount(spend?.used) ?? legacyAmount(extra.used_credits),
        currency: spend?.limit?.currency ?? spend?.used?.currency ?? extra.currency ?? "usd",
      }
    : null

  return {
    fiveHour: mapWindow(data.five_hour),
    sevenDay: mapWindow(data.seven_day),
    sevenDaySonnet: scopedWindow("Sonnet") ?? legacyWindow(data.seven_day_sonnet),
    sevenDayFable: scopedWindow("Fable"),
    extraUsage,
  }
}

// ── Codex ──

export async function fetchCodexUsage(
  authPath: string = CODEX_AUTH,
): Promise<CodexUsage | null> {
  if (!existsSync(authPath)) return null

  const raw = JSON.parse(readFileSync(authPath, "utf-8"))
  const token = raw.tokens?.access_token
  if (!token) return null

  const res = await fetch("https://chatgpt.com/backend-api/wham/usage", {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (!res.ok) throw new Error(`Codex usage API ${res.status}: ${await res.text()}`)

  const data = await res.json() as any
  const rl = data.rate_limit ?? {}

  const windowLabel = (seconds: number | null, fallback: string): string => {
    if (seconds == null || seconds <= 0) return fallback
    const units: Array<[number, string]> = [
      [86_400, "day"],
      [3_600, "hour"],
      [60, "minute"],
    ]
    for (const [unitSeconds, unit] of units) {
      if (seconds % unitSeconds !== 0) continue
      const count = seconds / unitSeconds
      return `${count}-${unit} window`
    }
    return fallback
  }

  const mapWindow = (id: string, w: any, fallbackLabel: string): CodexUsageWindow | null => {
    if (w == null || typeof w !== "object") return null
    const rawSeconds = w.limit_window_seconds == null ? NaN : Number(w.limit_window_seconds)
    const windowSeconds = Number.isFinite(rawSeconds) ? rawSeconds : null
    return {
      id,
      label: windowLabel(windowSeconds, fallbackLabel),
      windowSeconds,
      used: w?.used_percent ?? 0,
      resetsAt: w?.reset_at ?? w?.resets_at ?? null,
    }
  }

  const credits = data.credits
    ? { hasCredits: data.credits.has_credits ?? false, balance: data.credits.balance ?? "0" }
    : null

  return {
    plan: data.plan_type ?? data.plan ?? "unknown",
    windows: [
      mapWindow("primary", rl.primary_window, "5-hour window"),
      mapWindow("secondary", rl.secondary_window, "7-day window"),
    ].filter((window): window is CodexUsageWindow => window != null),
    credits,
    limitReached: rl.limit_reached ?? false,
    resetCredits: data.rate_limit_reset_credits?.available_count ?? 0,
  }
}

// Known backend codes (documentation + tests). `code` is typed as string on the
// result so an unrecognized future code passes through instead of crashing.
export type CodexResetCode = "reset" | "nothing_to_reset" | "no_credit" | "already_redeemed"
export interface CodexResetResult { code: string; windowsReset: number }

const CODEX_RESET_CONSUME_URL =
  "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume"

export async function redeemCodexReset(
  authPath: string = CODEX_AUTH,
  idempotencyKey: string = globalThis.crypto.randomUUID(),
): Promise<CodexResetResult> {
  if (!existsSync(authPath)) throw new Error("Codex auth not found")
  const raw = JSON.parse(readFileSync(authPath, "utf-8"))
  const token = raw.tokens?.access_token
  if (!token) throw new Error("Codex access token not found")

  const res = await fetch(CODEX_RESET_CONSUME_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ redeem_request_id: idempotencyKey }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (!res.ok) throw new Error(`Codex reset API ${res.status}: ${await res.text()}`)

  const data = (await res.json()) as any
  return { code: String(data.code ?? "unknown"), windowsReset: data.windows_reset ?? 0 }
}

// ── Cursor ──

export async function fetchCursorUsage(
  dbPath: string = CURSOR_DB,
): Promise<CursorUsage | null> {
  if (!existsSync(dbPath)) return null

  const db = new Database(dbPath, { readonly: true })
  let token: string | null = null
  try {
    const row = db.query("SELECT value FROM ItemTable WHERE key = ?").get("cursorAuth/accessToken") as
      | { value: string }
      | null
    token = row?.value ?? null
  } finally {
    db.close()
  }
  if (!token) return null

  const res = await fetch(
    "https://api2.cursor.sh/aiserver.v1.DashboardService/GetCurrentPeriodUsage",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Connect-Protocol-Version": "1",
        "Content-Type": "application/json",
      },
      body: "{}",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    },
  )
  if (!res.ok) throw new Error(`Cursor usage API ${res.status}: ${await res.text()}`)

  const data = await res.json() as any
  const plan = data.planUsage ?? data

  const finiteNumber = (...values: any[]): number | null => {
    for (const value of values) {
      if (value == null || value === "") continue
      const number = Number(value)
      if (Number.isFinite(number)) return number
    }
    return null
  }
  const totalSpendCents = finiteNumber(plan.totalSpend, plan.totalSpendCents)
  const includedCents = finiteNumber(plan.includedSpend, plan.includedCents)

  return {
    totalPercentUsed: plan.totalPercentUsed ?? 0,
    totalSpendCents: totalSpendCents ?? 0,
    includedCents: includedCents ?? 0,
    limitCents: plan.limit ?? plan.limitCents ?? 0,
    spendAvailable: totalSpendCents != null && includedCents != null,
    billingCycleStart: data.billingCycleStart ?? "",
    billingCycleEnd: data.billingCycleEnd ?? "",
  }
}

// ── opencode ──
//
// opencode has no usage API — it records every assistant turn's token counts and
// cost in its own SQLite store (~/.local/share/opencode/opencode.db, the same data
// `opencode stats` reads). Each message row's `data` JSON carries, for assistant
// messages, `cost` and `tokens: { input, output, cache: { read, write } }`. We open
// the DB read-only (WAL permits concurrent readers while opencode is running) and
// aggregate all-time totals.
export async function fetchOpenCodeUsage(
  dbPath: string = OPENCODE_DB,
): Promise<OpenCodeUsage | null> {
  if (!existsSync(dbPath)) return null

  const db = new Database(dbPath, { readonly: true })
  try {
    const sessions = (db.query("SELECT COUNT(*) AS c FROM session").get() as { c: number } | null)?.c ?? 0
    const rows = db.query("SELECT data FROM message").all() as Array<{ data: string }>

    let messages = 0
    let totalCostUsd = 0
    let inputTokens = 0
    let outputTokens = 0
    let cacheReadTokens = 0
    let cacheWriteTokens = 0

    for (const row of rows) {
      messages++
      let d: any
      try { d = JSON.parse(row.data) } catch { continue }
      if (d?.role !== "assistant") continue
      totalCostUsd += Number(d.cost) || 0
      const t = d.tokens ?? {}
      inputTokens += Number(t.input) || 0
      outputTokens += Number(t.output) || 0
      cacheReadTokens += Number(t.cache?.read) || 0
      cacheWriteTokens += Number(t.cache?.write) || 0
    }

    return { sessions, messages, totalCostUsd, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens }
  } finally {
    db.close()
  }
}

// ── All ──

export interface UsagePaths {
  claudeCredsPath?: string
  codexAuthPath?: string
  cursorDbPath?: string
  opencodeDbPath?: string
}

export async function fetchAllUsage(paths?: UsagePaths): Promise<UsageResponse> {
  const [claudeResult, codexResult, cursorResult, opencodeResult] = await Promise.allSettled([
    fetchClaudeUsage(paths?.claudeCredsPath),
    fetchCodexUsage(paths?.codexAuthPath),
    fetchCursorUsage(paths?.cursorDbPath),
    fetchOpenCodeUsage(paths?.opencodeDbPath),
  ])

  const errors: Record<string, string> = {}

  let claude: ClaudeUsage | null = null
  if (claudeResult.status === "fulfilled") {
    claude = claudeResult.value
    if (!claude) errors.claude = "credentials not found or token expired"
  } else {
    errors.claude = claudeResult.reason?.message ?? String(claudeResult.reason)
  }

  let codex: CodexUsage | null = null
  if (codexResult.status === "fulfilled") {
    codex = codexResult.value
    if (!codex) errors.codex = "credentials not found"
  } else {
    errors.codex = codexResult.reason?.message ?? String(codexResult.reason)
  }

  let cursor: CursorUsage | null = null
  if (cursorResult.status === "fulfilled") {
    cursor = cursorResult.value
    if (!cursor) errors.cursor = "credentials not found"
  } else {
    errors.cursor = cursorResult.reason?.message ?? String(cursorResult.reason)
  }

  let opencode: OpenCodeUsage | null = null
  if (opencodeResult.status === "fulfilled") {
    opencode = opencodeResult.value
    if (!opencode) errors.opencode = "no usage recorded yet"
  } else {
    errors.opencode = opencodeResult.reason?.message ?? String(opencodeResult.reason)
  }

  return { claude, codex, cursor, opencode, errors }
}
