import { existsSync, readFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import { openCodeDataDir } from "../agents/opencode/auth"

// ── Types ──

// `resetsAt` is the provider's raw value and its unit differs per provider:
// ISO strings (claude/grok), unix seconds (codex), unix-ms strings (cursor).
// `resetsAtIso` is the broker-normalized ISO timestamp — clients read it and
// need no per-provider unit logic. Optional in the type only so old fixtures
// still compile; every fetcher sets it.
export interface UsageWindow { used: number; resetsAt: string | number | null; resetsAtIso?: string | null }

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
  /** Raw unix-ms string from the Cursor API. Clients read billingCycleEndIso. */
  billingCycleEnd: string
  billingCycleEndIso?: string | null
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

// Grok Build (xAI SuperGrok) subscription credits via cli-chat-proxy.
// Vals are opaque credit units from the billing API (not USD cents).
export interface GrokUsage {
  plan: string
  percentUsed: number
  used: number
  monthlyLimit: number
  onDemandCap: number
  onDemandUsed: number
  prepaidBalance: number
  billingPeriodStart: string
  /** Raw ISO string from the Grok API. Clients read billingPeriodEndIso. */
  billingPeriodEnd: string
  billingPeriodEndIso?: string | null
}

export interface UsageResponse {
  claude: ClaudeUsage | null
  codex: CodexUsage | null
  cursor: CursorUsage | null
  opencode: OpenCodeUsage | null
  grok: GrokUsage | null
  errors: Record<string, string>
}

// ── Credential paths ──

const CLAUDE_CREDS = join(homedir(), ".claude", ".credentials.json")
const CODEX_AUTH   = join(homedir(), ".codex", "auth.json")
const CURSOR_DB   = join(homedir(), ".config", "Cursor", "User", "globalStorage", "state.vscdb")
const OPENCODE_DB = join(openCodeDataDir({ home: homedir() }), "opencode.db")
const GROK_AUTH   = join(homedir(), ".grok", "auth.json")

// Undocumented but what `grok /usage` hits (cli-chat-proxy). Override via env for tests/mirrors.
const GROK_BILLING_BASE =
  process.env.GROK_CLI_CHAT_PROXY_BASE_URL?.replace(/\/$/, "") ||
  "https://cli-chat-proxy.grok.com/v1"

const TIMEOUT_MS = 10_000

// Normalize a reset timestamp to ISO for the DTO. Returns null when the input
// does not parse — clients hide the reset line then, same as a missing value.
function isoFromMs(ms: number): string | null {
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null
}
function isoFromIsoLike(value: unknown): string | null {
  if (typeof value !== "string" || value === "") return null
  return isoFromMs(new Date(value).getTime())
}
function isoFromUnixSeconds(value: unknown): string | null {
  if (value == null || value === "") return null
  return isoFromMs(Number(value) * 1000)
}
function isoFromUnixMsString(value: unknown): string | null {
  if (value == null || value === "") return null
  return isoFromMs(Number(value))
}

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
    resetsAtIso: isoFromIsoLike(w?.resets_at),
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
    return e ? { used: e.percent ?? 0, resetsAt: e.resets_at ?? null, resetsAtIso: isoFromIsoLike(e.resets_at) } : null
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
    const resetsAt = w?.reset_at ?? w?.resets_at ?? null
    return {
      id,
      label: windowLabel(windowSeconds, fallbackLabel),
      windowSeconds,
      used: w?.used_percent ?? 0,
      resetsAt,
      resetsAtIso: isoFromUnixSeconds(resetsAt),
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
    billingCycleEndIso: isoFromUnixMsString(data.billingCycleEnd),
  }
}

// ── opencode ──
//
// opencode has no usage API — it records every assistant turn's token counts and
// cost in its own SQLite store (the native OpenCode data dir's opencode.db, the same data
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


// ── Grok ──
//
// SuperGrok subscription credit pool lives on the Grok Build cli-chat-proxy, not
// api.x.ai. Auth is the OIDC access token in ~/.grok/auth.json (any entry's
// `key` field). Two companion GETs:
//   /billing                        → monthly used / limit + billing period
//   /user?include=subscription      → plan name (subscriptionTier, singular)
//   /billing?format=credits         → prepaid balance + on-demand caps
// All three are best-effort; billing alone is enough for a card.

function grokMoneyVal(v: any): number {
  if (v == null) return 0
  if (typeof v === "number" && Number.isFinite(v)) return v
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v)
    return Number.isFinite(n) ? n : 0
  }
  if (typeof v === "object") {
    if (typeof v.val === "number" && Number.isFinite(v.val)) return v.val
    if (typeof v.val === "string") {
      const n = Number(v.val)
      return Number.isFinite(n) ? n : 0
    }
  }
  return 0
}

/** Pull a still-valid OIDC access token from ~/.grok/auth.json. The file is a
 * map of provider-key → credential; each credential has `key` + optional
 * `expires_at`. Prefer a non-expired entry; fall back to any key if no expiry. */
export function readGrokAccessToken(authPath: string = GROK_AUTH): string | null {
  if (!existsSync(authPath)) return null
  let raw: any
  try {
    raw = JSON.parse(readFileSync(authPath, "utf-8"))
  } catch {
    return null
  }
  if (!raw || typeof raw !== "object") return null

  const entries = Object.values(raw).filter(
    (v): v is Record<string, any> => !!v && typeof v === "object" && typeof (v as any).key === "string",
  )
  if (entries.length === 0) return null

  const now = Date.now()
  for (const e of entries) {
    const exp = e.expires_at
    if (typeof exp === "string" && new Date(exp).getTime() < now) continue
    if (typeof exp === "number" && exp < now) continue
    return e.key as string
  }
  // All expired (or no expires_at field) — still try the first key; the API
  // will 401 if it's truly dead, matching Claude's "return null on expired".
  return null
}

export async function fetchGrokUsage(
  authPath: string = GROK_AUTH,
  baseUrl: string = GROK_BILLING_BASE,
): Promise<GrokUsage | null> {
  const token = readGrokAccessToken(authPath)
  if (!token) return null

  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: "application/json",
  }
  const signal = AbortSignal.timeout(TIMEOUT_MS)
  const base = baseUrl.replace(/\/$/, "")

  const [billingRes, userRes, creditsRes] = await Promise.all([
    fetch(`${base}/billing`, { headers, signal }),
    fetch(`${base}/user?include=subscription`, { headers, signal }).catch(() => null),
    fetch(`${base}/billing?format=credits`, { headers, signal }).catch(() => null),
  ])

  if (!billingRes.ok) {
    throw new Error(`Grok billing API ${billingRes.status}: ${await billingRes.text()}`)
  }
  const billing = (await billingRes.json()) as any
  const cfg = billing?.config ?? billing ?? {}

  let plan = "unknown"
  if (userRes && userRes.ok) {
    try {
      // Prefer text→JSON.parse: Bun's Response.json() has returned a host object
      // where some fields are only reachable via bracket access (dot access and
      // JSON.stringify drop them). Bracket + plain parse is the reliable path.
      const user = JSON.parse(await userRes.text()) as any
      // Field is singular `subscriptionTier` (not Tiers) on cli-chat-proxy.
      const tiers =
        user?.["subscriptionTier"] ??
        user?.["subscriptionTiers"] ??
        user?.["subscription_tier"] ??
        user?.["subscription_tiers"]
      if (typeof tiers === "string" && tiers.trim()) plan = tiers
    } catch {
      // plan stays unknown
    }
  }

  let prepaidBalance = 0
  let onDemandCap = grokMoneyVal(cfg.onDemandCap)
  let onDemandUsed = grokMoneyVal(cfg.onDemandUsed)
  if (creditsRes && creditsRes.ok) {
    try {
      const credits = (await creditsRes.json()) as any
      const ccfg = credits?.config ?? credits ?? {}
      prepaidBalance = grokMoneyVal(ccfg.prepaidBalance)
      // credits format is the authoritative source for on-demand when present
      if (ccfg.onDemandCap != null) onDemandCap = grokMoneyVal(ccfg.onDemandCap)
      if (ccfg.onDemandUsed != null) onDemandUsed = grokMoneyVal(ccfg.onDemandUsed)
    } catch {
      // ignore credits parse failures
    }
  }

  const used = grokMoneyVal(cfg.used)
  const monthlyLimit = grokMoneyVal(cfg.monthlyLimit)
  const percentUsed =
    monthlyLimit > 0 ? Math.min(100, (used / monthlyLimit) * 100) : 0

  return {
    plan,
    percentUsed,
    used,
    monthlyLimit,
    onDemandCap,
    onDemandUsed,
    prepaidBalance,
    billingPeriodStart: cfg.billingPeriodStart ?? "",
    billingPeriodEnd: cfg.billingPeriodEnd ?? "",
    billingPeriodEndIso: isoFromIsoLike(cfg.billingPeriodEnd),
  }
}

// ── All ──

export interface UsagePaths {
  claudeCredsPath?: string
  codexAuthPath?: string
  cursorDbPath?: string
  opencodeDbPath?: string
  grokAuthPath?: string
  grokBillingBase?: string
}

export async function fetchAllUsage(paths?: UsagePaths): Promise<UsageResponse> {
  const [claudeResult, codexResult, cursorResult, opencodeResult, grokResult] = await Promise.allSettled([
    fetchClaudeUsage(paths?.claudeCredsPath),
    fetchCodexUsage(paths?.codexAuthPath),
    fetchCursorUsage(paths?.cursorDbPath),
    fetchOpenCodeUsage(paths?.opencodeDbPath),
    fetchGrokUsage(paths?.grokAuthPath, paths?.grokBillingBase),
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

  let grok: GrokUsage | null = null
  if (grokResult.status === "fulfilled") {
    grok = grokResult.value
    if (!grok) errors.grok = "credentials not found or token expired"
  } else {
    errors.grok = grokResult.reason?.message ?? String(grokResult.reason)
  }

  return { claude, codex, cursor, opencode, grok, errors }
}
