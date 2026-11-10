// OpenCode Go plan limits: the 5-hour ("rolling"), weekly and monthly allowance of the workspace a login belongs to.
//
// GET https://opencode.ai/zen/go/v1/usage, Bearer <OpenCode key | Console sign-in token> (+ x-opencode-org-id for a
// Console sign-in) → { usage: { rolling|weekly|monthly: { status: "ok" | "rate-limited", percent, resetsAt } } }.
// It is the source upstream opencode 2.x reads (packages/core/src/usage-limits.ts); 403 means the workspace has no
// Go plan. The login: the Console sign-in (`opencode console login`, opencode.db) while its token is fresh, else the
// OpenCode key in auth.json (one key serves Zen and Go).
import { existsSync, readFileSync } from "fs"
import { readOpenCodeConsole } from "../agents/opencode/console"
import type { OpenCodeFreeTier } from "./opencode-free"

export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>

export const OPENCODE_GO_USAGE_URL = "https://opencode.ai/zen/go/v1/usage"

export interface OpenCodeGoMeter {
  percent: number
  /** Go refuses requests until the window resets. */
  limited: boolean
  resetsAtIso: string
}

export interface OpenCodeGoUsage {
  /** The 5-hour window. */
  rolling: OpenCodeGoMeter
  weekly: OpenCodeGoMeter
  monthly: OpenCodeGoMeter
}

/**
 * active: Go meters below · none: the login's workspace has no Go plan · signed_out: no OpenCode key or sign-in ·
 * expired: OpenCode refused the key, or the sign-in's token ran out · unavailable: couldn't read them (`error`).
 */
export type OpenCodePlanStatus = "active" | "none" | "signed_out" | "expired" | "unavailable"

export interface OpenCodePlan {
  status: OpenCodePlanStatus
  go: OpenCodeGoUsage | null
  via?: "console" | "api_key"
  /** The Console sign-in's email. */
  email?: string
  error?: string
  /** The free models' daily limit: when it resets, and whether a session hit it. */
  free?: OpenCodeFreeTier
}

const TIMEOUT_MS = 10_000

function apiKey(authPath: string): string | null {
  if (!existsSync(authPath)) return null
  try {
    const auth = JSON.parse(readFileSync(authPath, "utf8")) as Record<string, { type?: string; key?: unknown }>
    for (const provider of ["opencode-go", "opencode"]) {
      const entry = auth[provider]
      if (entry?.type === "api" && typeof entry.key === "string" && entry.key) return entry.key
    }
  } catch { /* unreadable: no key */ }
  return null
}

function meter(raw: unknown): OpenCodeGoMeter | null {
  const m = raw as { status?: unknown; percent?: unknown; resetsAt?: unknown } | null
  if (!m || typeof m.percent !== "number" || !Number.isFinite(m.percent) || typeof m.resetsAt !== "string") return null
  const resets = Date.parse(m.resetsAt)
  if (Number.isNaN(resets)) return null
  return { percent: m.percent, limited: m.status === "rate-limited", resetsAtIso: new Date(resets).toISOString() }
}

export function parseOpenCodeGoUsage(body: unknown): OpenCodeGoUsage | null {
  const usage = (body as { usage?: Record<string, unknown> } | null)?.usage
  if (!usage) return null
  const rolling = meter(usage.rolling)
  const weekly = meter(usage.weekly)
  const monthly = meter(usage.monthly)
  return rolling && weekly && monthly ? { rolling, weekly, monthly } : null
}

export async function fetchOpenCodePlan(opts: {
  dbPath: string
  authPath: string
  fetch?: FetchLike
  now?: () => number
}): Promise<OpenCodePlan> {
  const now = opts.now ?? Date.now
  const doFetch = opts.fetch ?? ((url, init) => fetch(url, init))
  const signIn = readOpenCodeConsole(opts.dbPath)
  const signInFresh = !!signIn && (signIn.tokenExpiry === null || signIn.tokenExpiry > now())
  const key = apiKey(opts.authPath)

  let via: "console" | "api_key"
  const headers: Record<string, string> = { accept: "application/json" }
  const who = signIn ? { email: signIn.email } : {}
  if (signIn && signInFresh) {
    via = "console"
    headers.authorization = `Bearer ${signIn.accessToken}`
    if (signIn.orgId) headers["x-opencode-org-id"] = signIn.orgId
  } else if (key) {
    via = "api_key"
    headers.authorization = `Bearer ${key}`
  } else if (signIn) {
    return { status: "expired", go: null, via: "console", ...who }
  } else {
    return { status: "signed_out", go: null }
  }
  const tag = via === "console" ? { via, ...who } : { via }

  try {
    const res = await doFetch(OPENCODE_GO_USAGE_URL, { headers, signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (res.status === 403) return { status: "none", go: null, ...tag }
    if (res.status === 401) return { status: "expired", go: null, ...tag }
    if (!res.ok) return { status: "unavailable", go: null, ...tag, error: `OpenCode answered ${res.status}` }
    const go = parseOpenCodeGoUsage(await res.json().catch(() => null))
    if (!go) return { status: "unavailable", go: null, ...tag, error: "OpenCode sent usage in a shape supermux doesn't know" }
    return { status: "active", ...tag, go }
  } catch (err) {
    return { status: "unavailable", go: null, ...tag, error: err instanceof Error ? err.message : String(err) }
  }
}
