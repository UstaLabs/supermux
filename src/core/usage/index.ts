import { existsSync, readFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"

// ── Types ──

export interface UsageWindow { used: number; resetsAt: string | number | null }

export interface ClaudeExtraUsage {
  enabled: boolean
  monthlyLimit: number
  usedCredits: number
  currency: string
}

export interface ClaudeUsage {
  fiveHour: UsageWindow
  sevenDay: UsageWindow
  sevenDaySonnet: UsageWindow
  extraUsage: ClaudeExtraUsage | null
}

export interface CodexUsage {
  plan: string
  primaryWindow: UsageWindow
  secondaryWindow: UsageWindow
  credits: { hasCredits: boolean; balance: string } | null
  limitReached: boolean
}

export interface CursorUsage {
  totalPercentUsed: number
  totalSpendCents: number
  includedCents: number
  limitCents: number
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

  const extra = data.extra_usage
  const extraUsage: ClaudeExtraUsage | null = extra
    ? {
        enabled: extra.enabled ?? false,
        monthlyLimit: extra.monthly_limit ?? 0,
        usedCredits: extra.used_credits ?? 0,
        currency: extra.currency ?? "usd",
      }
    : null

  return {
    fiveHour: mapWindow(data.five_hour),
    sevenDay: mapWindow(data.seven_day),
    sevenDaySonnet: mapWindow(data.seven_day_sonnet),
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

  const mapWindow = (w: any): UsageWindow => ({
    used: w?.used_percent ?? 0,
    resetsAt: w?.reset_at ?? w?.resets_at ?? null,
  })

  const credits = data.credits
    ? { hasCredits: data.credits.has_credits ?? false, balance: data.credits.balance ?? "0" }
    : null

  return {
    plan: data.plan_type ?? data.plan ?? "unknown",
    primaryWindow: mapWindow(rl.primary_window),
    secondaryWindow: mapWindow(rl.secondary_window),
    credits,
    limitReached: rl.limit_reached ?? false,
  }
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

  return {
    totalPercentUsed: plan.totalPercentUsed ?? 0,
    totalSpendCents: plan.totalSpend ?? plan.totalSpendCents ?? 0,
    includedCents: plan.includedSpend ?? plan.includedCents ?? 0,
    limitCents: plan.limit ?? plan.limitCents ?? 0,
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
