import { existsSync, readFileSync, readdirSync } from "fs"
import { dirname, join } from "path"
import { Database } from "bun:sqlite"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../shared/agents"
import { makeLogger } from "../../shared/log"
import { SIGN_IN_EXPIRED } from "../accounts/broker-accounts"
import { CURSOR_SIGN_IN_EXPIRED } from "../agents/cursor/auth"
import { readClaudeStoredLogin } from "../agents/claude/stored-login"
import { authCredPath } from "../agents/detect"
import { openCodeDataDir } from "../agents/opencode/auth"
import { fetchOpenCodePlan, type OpenCodePlan } from "./opencode-go"
import { home } from "../../shared/home"

const log = makeLogger("usage")

// ── Types ──

// `resetsAt` is the provider's raw value and its unit differs per provider:
// ISO strings (claude/grok/antigravity), unix seconds (codex), unix-ms strings (cursor).
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

// One banked usage-limit reset grant (Claude Code's `/limit-reset`, program
// "cedar_ember"). A grant can hold several resets; `clears` names the limit
// windows one use refills (five_hour, seven_day, …).
export interface ClaudeResetGrant {
  id: string
  label: string
  resetsTotal: number
  resetsLeft: number
  startsAtIso: string | null
  /** Use-by date; the grant expires after it. */
  endsAtIso: string | null
  clears: string[]
  paused: boolean
  usableNow: boolean
  /** true = only usable while at a limit; false = usable any time. */
  useRequiresLimit: boolean
}

export interface ClaudeResets {
  eligible: boolean
  ineligibleReason: string | null
  atLimit: boolean
  grants: ClaudeResetGrant[]
  /** The grant a redeem spends next; null when none is usable. */
  nextGrantId: string | null
  /** Sum of resetsLeft across grants. */
  resetsLeft: number
  cooldownUntilIso: string | null
}

export interface ClaudeUsage {
  fiveHour: UsageWindow
  sevenDay: UsageWindow
  // Per-model weekly caps. null when Anthropic returns no such limit — clients hide the row.
  sevenDaySonnet: UsageWindow | null
  sevenDayFable: UsageWindow | null
  extraUsage: ClaudeExtraUsage | null
  /** Banked limit resets. null when the account has no reset program; absent
   *  on data that never saw a live fetch (local seed), which the store fills
   *  from the last live value. */
  resets?: ClaudeResets | null
}

// A per-model gate from the payload's `model_usage` map, independent of the
// 5h/7d windows: a model can be locked out while both windows still have room
// (this is what OpenAI shipped the Astra models with). `availableAt` is the raw
// unix-seconds value; clients read `availableAtIso`.
export interface CodexModelUsage {
  id: string
  label: string
  available: boolean
  availableAt: number | null
  availableAtIso: string | null
  creditsWouldEnable: boolean
}

export interface CodexUsage {
  plan: string
  windows: CodexUsageWindow[]
  models: CodexModelUsage[]
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
  /** The OpenCode Go plan's limits; absent when they weren't asked for. */
  plan?: OpenCodePlan
}

// Grok Build (xAI SuperGrok) subscription credits via cli-chat-proxy.
// Vals are opaque credit units from the billing API (not USD cents).

/** Cadence of the quota window xAI is currently billing against. Unified billing
 *  moved accounts from monthly to weekly, so this is no longer a constant. */
export type GrokPeriodType = "weekly" | "monthly" | "unknown"

/** One row of the `productUsage` breakdown (e.g. GrokBuild) under unified billing. */
export interface GrokProductUsage { product: string; percentUsed: number }

export interface GrokUsage {
  plan: string
  percentUsed: number
  used: number
  monthlyLimit: number
  onDemandCap: number
  onDemandUsed: number
  prepaidBalance: number
  /** Cadence of billingPeriodStart/End below — "weekly" under unified billing. */
  periodType: GrokPeriodType
  /** Per-product percentages; empty on accounts the breakdown is not reported for. */
  products: GrokProductUsage[]
  /** Start of the ACTIVE quota window (the weekly one when xAI reports one). */
  billingPeriodStart: string
  /** Raw ISO string from the Grok API. Clients read billingPeriodEndIso. */
  billingPeriodEnd: string
  billingPeriodEndIso?: string | null
  /**
   * Whether percentUsed is the account's real credit usage: the credits format answered, with its own
   * percentage or beside a real monthly limit. False when that call failed or told neither: percentUsed
   * is then the legacy /billing figure, which reads 0% on unified-billing accounts. A failed credits call
   * leaves it false even beside a real monthly limit: without that format a legacy account cannot be told
   * from a unified one. Absent on snapshots cached before this field existed.
   */
  creditsKnown?: boolean
}

/**
 * One Antigravity limit as agy's `/usage` reports it: a model group's 5-hour or weekly window. `used` is what is gone
 * (agy reports what is left); `label` is "<agy's group name> · 5-hour" or "· weekly"; `resetsAt` is agy's ISO string.
 */
export interface AntigravityUsageWindow extends UsageWindow {
  id: string
  label: string
}

/** Antigravity's limits per model group (Gemini; Claude and GPT; Gemini 4 Argon on a Pro plan). agy reports no plan. */
export interface AntigravityUsage {
  windows: AntigravityUsageWindow[]
}

export interface UsageResponse {
  claude: ClaudeUsage | null
  codex: CodexUsage | null
  cursor: CursorUsage | null
  opencode: OpenCodeUsage | null
  grok: GrokUsage | null
  /** The usage store's (./antigravity); the /usage command (fetchAllUsage) leaves Antigravity out. */
  antigravity?: AntigravityUsage | null
  errors: Record<string, string>
  /** The code of an error that has one (`account_expired`: the provider refused the login, sign in again). */
  errorCodes?: Record<string, string>
}

// ── Credential paths ──

const CLAUDE_CREDS = join(home(), ".claude", ".credentials.json")
const CLAUDE_JSON  = join(home(), ".claude.json")
const CLAUDE_VERSIONS_DIR = join(home(), ".local", "share", "claude", "versions")
const CODEX_AUTH   = join(home(), ".codex", "auth.json")
const OPENCODE_DB = join(openCodeDataDir({ home: home() }), "opencode.db")
const GROK_AUTH   = join(home(), ".grok", "auth.json")

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
export function isoFromIsoLike(value: unknown): string | null {
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

// The banked-reset block (`cedar_ember`) is only answered for the Claude Code
// CLI: the server reads the surface and version from the User-Agent, and
// answers `ineligible_reason: "surface"` or `"cli_version"` to anything else.
// So we send the UA of the newest installed CLI, with a known-good fallback.
const CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage?cedar_ember=1"
const CLAUDE_RESET_PROGRAM = "cedar_ember"
const CLAUDE_CLI_FALLBACK_VERSION = "2.1.280"

function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map(Number)
  const pb = b.split(".").map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}

export function claudeCliVersion(versionsDir: string = CLAUDE_VERSIONS_DIR): string {
  try {
    const versions = readdirSync(versionsDir).filter((v) => /^\d+\.\d+\.\d+$/.test(v))
    const newest = versions.sort(compareVersions).pop()
    if (newest && compareVersions(newest, CLAUDE_CLI_FALLBACK_VERSION) > 0) return newest
  } catch {
    // no native install — use the fallback
  }
  return CLAUDE_CLI_FALLBACK_VERSION
}

function claudeHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    "anthropic-beta": "oauth-2025-04-20",
    "User-Agent": `claude-cli/${claudeCliVersion()} (external, cli)`,
  }
}

/** Reads the login Claude Code keeps outside its credentials file (Windows Credential Manager); null when there is none. */
export type ClaudeStoredLoginRead = () => Promise<string | null>

/** The system login's: Claude Code run without CLAUDE_CONFIG_DIR. */
const readSystemStoredLogin: ClaudeStoredLoginRead = () => readClaudeStoredLogin()

/** The access token of a Claude login (`claudeAiOauth`, or the bare oauth object); null when it has none or it expired. */
function claudeLoginToken(login: any): string | null {
  const oauth = login?.claudeAiOauth ?? login
  const expiresAt = oauth?.expiresAt
  if (typeof expiresAt === "string" && new Date(expiresAt).getTime() < Date.now()) return null
  if (typeof expiresAt === "number" && expiresAt < Date.now()) return null
  const token = oauth?.accessToken ?? oauth?.access_token
  return typeof token === "string" && token ? token : null
}

/**
 * The login's token from its credentials file, else from where Claude Code keeps it instead: on Windows, with
 * Anthropic's `tengu_windows_credman` rollout on, it moves the login into Credential Manager and deletes the file.
 */
async function readClaudeToken(credsPath: string, readStored: ClaudeStoredLoginRead): Promise<string | null> {
  if (existsSync(credsPath)) {
    const token = claudeLoginToken(JSON.parse(readFileSync(credsPath, "utf-8")))
    if (token) return token
  }
  try {
    const stored = await readStored()
    return stored ? claudeLoginToken(JSON.parse(stored)) : null
  } catch (err) {
    // The store's own words stay in the broker log; the card gets a sentence.
    log.warn("claude_stored_login_unreadable", { err: String(err) })
    throw new Error("Couldn't read Claude's sign-in from Windows Credential Manager.")
  }
}

const RESET_ID = /^[a-z0-9_-]{1,40}$/

function mapClaudeResets(block: any): ClaudeResets | null {
  if (!block || typeof block !== "object") return null
  const grants: ClaudeResetGrant[] = (Array.isArray(block.grants) ? block.grants : [])
    .filter((g: any) => typeof g?.id === "string" && RESET_ID.test(g.id))
    .map((g: any) => ({
      id: g.id,
      label: typeof g.label === "string" ? g.label : "",
      resetsTotal: Number.isInteger(g.resets_total) ? g.resets_total : 0,
      resetsLeft: Number.isInteger(g.resets_left) && g.resets_left > 0 ? g.resets_left : 0,
      startsAtIso: isoFromIsoLike(g.starts_at),
      endsAtIso: isoFromIsoLike(g.ends_at),
      clears: Array.isArray(g.clears) ? g.clears.filter((c: unknown) => typeof c === "string") : [],
      paused: g.paused === true,
      usableNow: g.usable_now === true,
      useRequiresLimit: g.use_requires_limit !== false,
    }))
  const next = typeof block.next_grant_id === "string" && grants.some((g) => g.id === block.next_grant_id)
    ? block.next_grant_id
    : null
  return {
    eligible: block.eligible === true,
    ineligibleReason: typeof block.ineligible_reason === "string" ? block.ineligible_reason : null,
    atLimit: block.at_limit === true,
    grants,
    nextGrantId: next,
    resetsLeft: grants.reduce((sum, g) => sum + g.resetsLeft, 0),
    cooldownUntilIso: isoFromIsoLike(block.cooldown_until),
  }
}

/**
 * [readStored] reads the same login's copy in the OS credential store: an added account's (its own CLAUDE_CONFIG_DIR)
 * must pass its own, or it would read the system login's.
 */
export async function fetchClaudeUsage(
  credsPath: string = CLAUDE_CREDS,
  readStored: ClaudeStoredLoginRead = readSystemStoredLogin,
): Promise<ClaudeUsage | null> {
  const token = await readClaudeToken(credsPath, readStored)
  if (!token) return null
  return fetchClaudeUsageWithToken(token)
}

/** The same fetch for an OAuth access token held elsewhere (an account's vault token). */
export async function fetchClaudeUsageWithToken(token: string): Promise<ClaudeUsage> {
  const res = await fetch(CLAUDE_USAGE_URL, {
    headers: claudeHeaders(token),
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
    resets: mapClaudeResets(data.cedar_ember),
  }
}

// `result` ∈ reset | already_used | not_limited | cooldown | ineligible |
// unavailable. Typed as string so an unknown future code passes through.
export interface ClaudeResetResult {
  result: string
  reason: string | null
  resetsLeft: number | null
  cleared: string[]
}

function readClaudeOrgUuid(claudeJsonPath: string): string | null {
  if (!existsSync(claudeJsonPath)) return null
  try {
    const raw = JSON.parse(readFileSync(claudeJsonPath, "utf-8"))
    const org = raw?.oauthAccount?.organizationUuid
    return typeof org === "string" && org ? org : null
  } catch {
    return null
  }
}

// Spends one banked reset of `grantId` (the status block's `nextGrantId`).
// `requestId` makes the claim idempotent: a retry with the same id cannot
// spend a second reset.
export async function redeemClaudeReset(
  grantId: string,
  opts: { credsPath?: string; claudeJsonPath?: string; requestId?: string; readStored?: ClaudeStoredLoginRead } = {},
): Promise<ClaudeResetResult> {
  if (!RESET_ID.test(grantId)) throw new Error("Invalid reset grant id")
  const token = await readClaudeToken(opts.credsPath ?? CLAUDE_CREDS, opts.readStored ?? readSystemStoredLogin)
  if (!token) throw new Error("Claude credentials not found or token expired")
  const org = readClaudeOrgUuid(opts.claudeJsonPath ?? CLAUDE_JSON)
  if (!org) throw new Error("Claude organization not found")

  const res = await fetch(`https://api.anthropic.com/api/organizations/${org}/reset_rate_limits`, {
    method: "POST",
    headers: { ...claudeHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({
      program: CLAUDE_RESET_PROGRAM,
      grant_id: grantId,
      request_id: opts.requestId ?? globalThis.crypto.randomUUID(),
    }),
    signal: AbortSignal.timeout(25_000),
  })
  if (!res.ok) throw new Error(`Claude reset API ${res.status}: ${await res.text()}`)

  const data = (await res.json()) as any
  return {
    result: String(data.result ?? "unavailable"),
    reason: typeof data.reason === "string" ? data.reason : null,
    resetsLeft: Number.isInteger(data.resets_left) ? data.resets_left : null,
    cleared: Array.isArray(data.cleared) ? data.cleared.filter((c: unknown) => typeof c === "string") : [],
  }
}

// ── Codex ──

// `model_usage` keys are API model slugs ("gpt-6-astra"); turn one into a card
// label ("GPT-6 Astra"). A digits-only segment stays hyphenated to the segment
// before it so version numbers read as one token.
export function codexModelLabel(id: string): string {
  const parts = id.split("-").filter((p) => p !== "")
  if (parts.length === 0) return id
  let label = ""
  for (const part of parts) {
    const word = /^(gpt|api|ai|o\d+)$/i.test(part)
      ? part.toUpperCase()
      : part.charAt(0).toUpperCase() + part.slice(1)
    if (label === "") label = word
    else label += /^\d+$/.test(part) ? `-${word}` : ` ${word}`
  }
  return label
}

export async function fetchCodexUsage(
  authPath: string = CODEX_AUTH,
): Promise<CodexUsage | null> {
  if (!existsSync(authPath)) return null

  const raw = JSON.parse(readFileSync(authPath, "utf-8"))
  const token = raw.tokens?.access_token
  if (!token) return null
  return fetchCodexUsageWithToken(token)
}

/** The same fetch for a ChatGPT access token held elsewhere (an account's vault token). */
export async function fetchCodexUsageWithToken(token: string, accountId?: string): Promise<CodexUsage> {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
  if (accountId) headers["ChatGPT-Account-Id"] = accountId
  const res = await fetch("https://chatgpt.com/backend-api/wham/usage", {
    headers,
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

  const models: CodexModelUsage[] = Object.entries(data.model_usage ?? {})
    .filter(([, m]) => m != null && typeof m === "object")
    .map(([id, m]: [string, any]) => {
      const rawAt = m.available_at ?? m.availableAt ?? null
      const availableAt = rawAt == null ? null : Number(rawAt)
      return {
        id,
        label: codexModelLabel(id),
        available: m.available ?? false,
        availableAt: availableAt != null && Number.isFinite(availableAt) ? availableAt : null,
        availableAtIso: isoFromUnixSeconds(rawAt),
        creditsWouldEnable: m.credits_would_enable ?? m.creditsWouldEnable ?? false,
      }
    })

  return {
    plan: data.plan_type ?? data.plan ?? "unknown",
    windows: [
      mapWindow("primary", rl.primary_window, "5-hour window"),
      mapWindow("secondary", rl.secondary_window, "7-day window"),
    ].filter((window): window is CodexUsageWindow => window != null),
    models,
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

/**
 * cursor-agent's own login: the file `cursor-agent login` writes and every Cursor session copies, which
 * Settings shows as Cursor's system login. Resolved per call (XDG_CONFIG_HOME / APPDATA). Not the Cursor
 * IDE's token store (~/.config/Cursor/User/globalStorage/state.vscdb): that is another app's login, kept
 * fresh only while the IDE runs. On macOS cursor-agent keeps its login in the Keychain and this file is
 * absent, so the card has no usage there rather than a false "signed out".
 */
export function cursorAuthPath(): string {
  return authCredPath(AgentKind.Cursor, {
    home: home(),
    xdgConfigHome: process.env.XDG_CONFIG_HOME,
    appData: process.env.APPDATA,
    platform: process.platform,
  })
}

/** The access token of a cursor-agent auth.json; null when the file or its token is missing. */
export function readCursorAccessToken(authPath: string): string | null {
  if (!existsSync(authPath)) return null
  try {
    const token = JSON.parse(readFileSync(authPath, "utf-8"))?.accessToken
    return typeof token === "string" && token ? token : null
  } catch {
    return null
  }
}

export async function fetchCursorUsage(
  authPath: string = cursorAuthPath(),
): Promise<CursorUsage | null> {
  const token = readCursorAccessToken(authPath)
  return token ? fetchCursorUsageWithToken(token) : null
}

/**
 * Whether api2.cursor.sh refused the token itself: 401, the Connect code `unauthenticated`, or
 * `ERROR_NOT_LOGGED_IN` (its answer for an expired or revoked session token).
 */
function cursorSignedOut(status: number, body: string): boolean {
  if (status === 401) return true
  try {
    const data = JSON.parse(body)
    if (data?.code === "unauthenticated") return true
    return Array.isArray(data?.details) && data.details.some((d: any) => d?.debug?.error === "ERROR_NOT_LOGGED_IN")
  } catch {
    return false
  }
}

/** The same fetch for an access token held elsewhere (an added account's cursor/auth.json). */
export async function fetchCursorUsageWithToken(token: string): Promise<CursorUsage> {
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
  if (!res.ok) {
    const body = await res.text().catch(() => "")
    if (cursorSignedOut(res.status, body)) throw new CoreError(SIGN_IN_EXPIRED, CURSOR_SIGN_IN_EXPIRED)
    // The status and Cursor's own words stay in the broker log; the card gets a sentence.
    log.warn("cursor_usage_failed", { status: res.status, body: body.slice(0, 300) })
    throw new Error("Couldn't read Cursor usage right now — try again in a moment.")
  }

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
// `opencode stats` reads). We open the DB read-only (WAL permits concurrent readers
// while opencode is running) and report all-time totals.
//
// Since its `session_usage` migration (2026-05) opencode keeps each session's cost and
// tokens on the session row, backfilled from the messages, so the totals are a sum over
// sessions. The message bodies stay unread then: they can be hundreds of MB (a real store
// holds a 290 MB one), and loading and parsing them all here blocked the broker's event
// loop for seconds, past the 10 s timeouts of the provider fetches running beside it.
// Only an older store falls back to the messages, where each row's `data` JSON carries,
// for assistant messages, `cost` and `tokens: { input, output, cache: { read, write } }`.
const OPENCODE_SESSION_TOTALS = ["cost", "tokens_input", "tokens_output", "tokens_cache_read", "tokens_cache_write"]

/** The local totals, and with `plan` the OpenCode Go limits beside them. null = nothing to show at all. */
export async function fetchOpenCodeUsage(
  dbPath: string = OPENCODE_DB,
  opts: { plan?: () => Promise<OpenCodePlan> } = {},
): Promise<OpenCodeUsage | null> {
  const plan = await opts.plan?.()
  const local = readOpenCodeTotals(dbPath)
  if (!plan) return local
  if (!local && plan.status === "signed_out") return null
  return { ...(local ?? EMPTY_OPENCODE_TOTALS), plan }
}

const EMPTY_OPENCODE_TOTALS: OpenCodeUsage = {
  sessions: 0, messages: 0, totalCostUsd: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0,
}

/** OpenCode Go limits for the system login: the native data dir's Console sign-in or OpenCode key. */
export function fetchSystemOpenCodePlan(): Promise<OpenCodePlan> {
  return fetchOpenCodePlan({ dbPath: OPENCODE_DB, authPath: join(dirname(OPENCODE_DB), "auth.json") })
}

function readOpenCodeTotals(dbPath: string): OpenCodeUsage | null {
  if (!existsSync(dbPath)) return null

  const db = new Database(dbPath, { readonly: true })
  try {
    const sessions = (db.query("SELECT COUNT(*) AS c FROM session").get() as { c: number } | null)?.c ?? 0
    const columns = (db.query("PRAGMA table_info(session)").all() as Array<{ name: string }>).map((c) => c.name)
    if (OPENCODE_SESSION_TOTALS.every((c) => columns.includes(c))) {
      const t = db.query(
        `SELECT COALESCE(SUM(cost), 0) AS cost, COALESCE(SUM(tokens_input), 0) AS input,
          COALESCE(SUM(tokens_output), 0) AS output, COALESCE(SUM(tokens_cache_read), 0) AS cacheRead,
          COALESCE(SUM(tokens_cache_write), 0) AS cacheWrite FROM session`,
      ).get() as { cost: number; input: number; output: number; cacheRead: number; cacheWrite: number }
      const messages = (db.query("SELECT COUNT(*) AS c FROM message").get() as { c: number } | null)?.c ?? 0
      return {
        sessions,
        messages,
        totalCostUsd: t.cost,
        inputTokens: t.input,
        outputTokens: t.output,
        cacheReadTokens: t.cacheRead,
        cacheWriteTokens: t.cacheWrite,
      }
    }

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
//   /billing                        → legacy monthly used / limit + billing period
//   /user?include=subscription      → plan name (subscriptionTier, singular)
//   /billing?format=credits         → the LIVE quota under unified billing —
//        `currentPeriod` (weekly), `creditUsagePercent`, `productUsage`, plus
//        prepaid balance and on-demand caps
// All three are best-effort; billing alone is enough for a card. On a unified
// account the legacy monthly limit is 0, so the credits percentage is the only
// truthful number — see fetchGrokUsage.

/** `USAGE_PERIOD_TYPE_WEEKLY` → "weekly". An unrecognized future cadence reports
 *  "unknown" so clients label the window generically instead of lying "monthly". */
function grokPeriodType(raw: unknown): GrokPeriodType {
  if (typeof raw !== "string" || raw === "") return "monthly"
  const t = raw.toUpperCase()
  if (t.includes("WEEK")) return "weekly"
  if (t.includes("MONTH")) return "monthly"
  return "unknown"
}

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
  // Unified billing reports the live quota only on the credits format: a
  // `currentPeriod` (weekly for unified accounts) plus a straight percentage.
  // Legacy `/billing` keeps answering monthly with a zero limit there, which
  // would read as 0% used, so the credits values win whenever they are present.
  let periodType: GrokPeriodType = "monthly"
  let periodStart: string | null = null
  let periodEnd: string | null = null
  let creditPercent: number | null = null
  let products: GrokProductUsage[] = []
  let creditsRead = false
  if (creditsRes && creditsRes.ok) {
    try {
      const credits = (await creditsRes.json()) as any
      const ccfg = credits?.config ?? credits ?? {}
      creditsRead = true
      prepaidBalance = grokMoneyVal(ccfg.prepaidBalance)
      // credits format is the authoritative source for on-demand when present
      if (ccfg.onDemandCap != null) onDemandCap = grokMoneyVal(ccfg.onDemandCap)
      if (ccfg.onDemandUsed != null) onDemandUsed = grokMoneyVal(ccfg.onDemandUsed)

      const period = ccfg.currentPeriod ?? null
      if (period && typeof period === "object") {
        periodType = grokPeriodType(period.type)
        if (typeof period.start === "string" && period.start) periodStart = period.start
        if (typeof period.end === "string" && period.end) periodEnd = period.end
      }
      // Falls back to the credits config's own billingPeriod* — same window as
      // currentPeriod on every payload seen so far, but present on older ones.
      if (periodStart == null && typeof ccfg.billingPeriodStart === "string") periodStart = ccfg.billingPeriodStart
      if (periodEnd == null && typeof ccfg.billingPeriodEnd === "string") periodEnd = ccfg.billingPeriodEnd

      // `!= null` first: Number(null) is 0, which would report a full pool as 0% used.
      if (ccfg.creditUsagePercent != null) {
        const pct = Number(ccfg.creditUsagePercent)
        if (Number.isFinite(pct)) creditPercent = pct
      }

      if (Array.isArray(ccfg.productUsage)) {
        products = ccfg.productUsage
          .filter((p: any) => p != null && typeof p === "object")
          .map((p: any) => ({
            product: String(p.product ?? ""),
            percentUsed: Number.isFinite(Number(p.usagePercent)) ? Number(p.usagePercent) : 0,
          }))
      }
    } catch {
      // ignore credits parse failures
    }
  }

  const used = grokMoneyVal(cfg.used)
  const monthlyLimit = grokMoneyVal(cfg.monthlyLimit)
  const percentUsed =
    creditPercent != null
      ? Math.max(0, Math.min(100, creditPercent))
      : monthlyLimit > 0
        ? Math.min(100, (used / monthlyLimit) * 100)
        : 0

  const billingPeriodStart = periodStart ?? cfg.billingPeriodStart ?? ""
  const billingPeriodEnd = periodEnd ?? cfg.billingPeriodEnd ?? ""

  return {
    plan,
    percentUsed,
    used,
    monthlyLimit,
    onDemandCap,
    onDemandUsed,
    prepaidBalance,
    periodType,
    products,
    billingPeriodStart,
    billingPeriodEnd,
    billingPeriodEndIso: isoFromIsoLike(billingPeriodEnd),
    creditsKnown: creditPercent != null || (creditsRead && monthlyLimit > 0),
  }
}

// ── All ──

export interface UsagePaths {
  claudeCredsPath?: string
  codexAuthPath?: string
  cursorAuthPath?: string
  opencodeDbPath?: string
  grokAuthPath?: string
  grokBillingBase?: string
}

export async function fetchAllUsage(paths?: UsagePaths): Promise<UsageResponse> {
  const [claudeResult, codexResult, cursorResult, opencodeResult, grokResult] = await Promise.allSettled([
    fetchClaudeUsage(paths?.claudeCredsPath),
    fetchCodexUsage(paths?.codexAuthPath),
    fetchCursorUsage(paths?.cursorAuthPath),
    fetchOpenCodeUsage(paths?.opencodeDbPath),
    fetchGrokUsage(paths?.grokAuthPath, paths?.grokBillingBase),
  ])

  const errors: Record<string, string> = {}
  const errorCodes: Record<string, string> = {}
  const failed = (provider: string, reason: unknown): void => {
    errors[provider] = (reason as { message?: string } | undefined)?.message ?? String(reason)
    if (reason instanceof CoreError && reason.code) errorCodes[provider] = reason.code
  }

  let claude: ClaudeUsage | null = null
  if (claudeResult.status === "fulfilled") {
    claude = claudeResult.value
    if (!claude) errors.claude = "credentials not found or token expired"
  } else {
    failed("claude", claudeResult.reason)
  }

  let codex: CodexUsage | null = null
  if (codexResult.status === "fulfilled") {
    codex = codexResult.value
    if (!codex) errors.codex = "credentials not found"
  } else {
    failed("codex", codexResult.reason)
  }

  let cursor: CursorUsage | null = null
  if (cursorResult.status === "fulfilled") {
    cursor = cursorResult.value
    if (!cursor) errors.cursor = "credentials not found"
  } else {
    failed("cursor", cursorResult.reason)
  }

  let opencode: OpenCodeUsage | null = null
  if (opencodeResult.status === "fulfilled") {
    opencode = opencodeResult.value
    if (!opencode) errors.opencode = "no usage recorded yet"
  } else {
    failed("opencode", opencodeResult.reason)
  }

  let grok: GrokUsage | null = null
  if (grokResult.status === "fulfilled") {
    grok = grokResult.value
    if (!grok) errors.grok = "credentials not found or token expired"
  } else {
    failed("grok", grokResult.reason)
  }

  return { claude, codex, cursor, opencode, grok, errors, ...(Object.keys(errorCodes).length ? { errorCodes } : {}) }
}
