import { join } from "node:path"
import { CoreError } from "../../errors.js"
import { codexLayout, codexRoot } from "../homes.js"
import type { AccountIdentity, AddAccountOptions, AuthAdapter, FetchLike, HomeRoots, UsageWindow, Materialized } from "../types.js"
import { epochDate, jwtPayload, readJson, requireSecret, str, unsupported } from "./shared.js"

export const CODEX_TOKEN_ENV = "SUPERMUX_CODEX_ACCESS_TOKEN"
const PROVIDER = "supermux_chatgpt"

/** The Codex CLI's ChatGPT OAuth token endpoint and client id (subscription login). */
export const CODEX_OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
export const CODEX_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
/** Server error codes meaning the login is gone (sign in again), as the Codex CLI treats them. */
const EXPIRED_CODES = new Set(["refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated", "invalid_grant"])

/** Vault secret of a Codex `token` account (ChatGPT login used without auth.json). */
export type CodexTokenSecret = { access_token: string; refresh_token?: string; id_token?: string; account_id: string; expires_at?: number | string }

/** A token secret from a Codex `auth.json` (`tokens`), expiry taken from the access token's JWT exp. */
export function codexTokenFromAuth(auth: unknown): CodexTokenSecret | undefined {
  const tokens = auth && typeof auth === "object" ? (auth as Record<string, unknown>).tokens : undefined
  if (!tokens || typeof tokens !== "object") return undefined
  const t = tokens as Record<string, unknown>
  const access = str(t.access_token)
  const accountId = str(t.account_id)
  if (!access || !accountId) return undefined
  const exp = jwtPayload(access)?.exp
  return {
    access_token: access, account_id: accountId,
    ...(str(t.refresh_token) ? { refresh_token: str(t.refresh_token) } : {}),
    ...(str(t.id_token) ? { id_token: str(t.id_token) } : {}),
    ...(typeof exp === "number" && Number.isFinite(exp) ? { expires_at: exp } : {}),
  }
}

/**
 * refresh_token grant against the Codex OAuth endpoint. Keeps the old refresh token when the reply
 * omits one. Failures carry only the server's error code (no token, no response body).
 */
export async function refreshCodexToken(token: CodexTokenSecret, fetch: FetchLike): Promise<CodexTokenSecret> {
  if (!token.refresh_token) throw new CoreError("account_expired", "This Codex token has no refresh token")
  let response: Awaited<ReturnType<FetchLike>>
  try {
    response = await fetch(CODEX_OAUTH_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: new URLSearchParams({ grant_type: "refresh_token", client_id: CODEX_OAUTH_CLIENT_ID, refresh_token: token.refresh_token }).toString(),
    })
  } catch (cause) {
    throw new CoreError("account_refresh_failed", `Codex token refresh request failed: ${cause instanceof Error ? cause.message : String(cause)}`)
  }
  let body: Record<string, unknown> | undefined
  try {
    const value = JSON.parse(await response.text())
    body = value && typeof value === "object" && !Array.isArray(value) ? value : undefined
  } catch { body = undefined }
  if (!response.ok) {
    const error = body?.error
    const code = str(error && typeof error === "object" ? (error as Record<string, unknown>).code : error) ?? str(body?.code)
    const detail = code ? ` (${code})` : ""
    if (code && EXPIRED_CODES.has(code)) throw new CoreError("account_expired", `Codex login expired; sign in again${detail}`)
    throw new CoreError("account_refresh_failed", `Codex token refresh failed: HTTP ${response.status}${detail}`)
  }
  const access = str(body?.access_token)
  if (!access) throw new CoreError("account_refresh_failed", "Codex token refresh returned no access_token")
  const exp = jwtPayload(access)?.exp
  const expiresIn = body?.expires_in
  const expiresAt = typeof exp === "number" && Number.isFinite(exp) ? exp
    : typeof expiresIn === "number" && Number.isFinite(expiresIn) ? Math.floor(Date.now() / 1000) + expiresIn : undefined
  return {
    access_token: access,
    refresh_token: str(body?.refresh_token) ?? token.refresh_token,
    ...(str(body?.id_token) ?? token.id_token ? { id_token: str(body?.id_token) ?? token.id_token } : {}),
    account_id: token.account_id,
    ...(expiresAt !== undefined ? { expires_at: expiresAt } : {}),
  }
}

export function parseCodexToken(secret: string): CodexTokenSecret {
  let value: unknown
  try { value = JSON.parse(secret) } catch { value = undefined }
  const v = value as Record<string, unknown> | undefined
  if (!v || typeof v !== "object" || !str(v.access_token) || !str(v.account_id) || !/^[A-Za-z0-9_-]{1,128}$/.test(v.account_id as string)
    || (v.refresh_token !== undefined && !str(v.refresh_token))
    || (v.id_token !== undefined && !str(v.id_token))
    || (v.expires_at !== undefined && typeof v.expires_at !== "number" && typeof v.expires_at !== "string")) {
    throw new CoreError("invalid_input", "Codex token secret must be JSON {access_token, account_id, refresh_token?, expires_at?}")
  }
  return v as CodexTokenSecret
}

/** Expiry: explicit expires_at (epoch seconds or ISO), else the access token's JWT exp. */
export function codexTokenExpiry(token: CodexTokenSecret): Date | undefined {
  if (typeof token.expires_at === "number") return epochDate(token.expires_at)
  if (typeof token.expires_at === "string") {
    const at = Date.parse(token.expires_at)
    return Number.isFinite(at) ? new Date(at) : undefined
  }
  return epochDate(jwtPayload(token.access_token)?.exp)
}

/** Custom model provider reading the ChatGPT access token from the environment (verified on codex 0.159.2). */
export function codexTokenArgs(accountId: string): string[] {
  const p = `model_providers.${PROVIDER}`
  return [
    "-c", `model_provider=${PROVIDER}`,
    "-c", `${p}.name=${JSON.stringify("ChatGPT (supermux)")}`,
    "-c", `${p}.base_url=${JSON.stringify("https://chatgpt.com/backend-api/codex")}`,
    "-c", `${p}.env_key=${JSON.stringify(CODEX_TOKEN_ENV)}`,
    "-c", `${p}.wire_api=${JSON.stringify("responses")}`,
    "-c", `${p}.requires_openai_auth=false`,
    "-c", `${p}.http_headers={ "chatgpt-account-id" = ${JSON.stringify(accountId)} }`,
  ]
}

export async function readCodexIdentity(home: string): Promise<AccountIdentity | undefined> {
  const tokens = (await readJson(join(home, "auth.json")))?.tokens
  if (!tokens || typeof tokens !== "object") return undefined
  const t = tokens as Record<string, unknown>
  const email = str(jwtPayload(t.id_token)?.email)
  const accountId = str(t.account_id)
  if (!email && !accountId) return undefined
  return { ...(email ? { email } : {}), ...(accountId ? { accountId } : {}) }
}

/** `account/rateLimits/updated` params.rateLimits: primary/secondary {usedPercent 0..100, windowDurationMins, resetsAt}. */
export function codexUsage(rateLimits: unknown): UsageWindow[] {
  if (!rateLimits || typeof rateLimits !== "object") return []
  const r = rateLimits as Record<string, unknown>
  const windows: UsageWindow[] = []
  for (const name of ["primary", "secondary"] as const) {
    const w = r[name]
    if (!w || typeof w !== "object") continue
    const window = w as Record<string, unknown>
    if (typeof window.usedPercent !== "number" || !Number.isFinite(window.usedPercent)) continue
    const resetsAt = epochDate(window.resetsAt)
    windows.push({ name: typeof window.windowDurationMins === "number" ? `${window.windowDurationMins}m` : name, usedPercent: window.usedPercent, ...(resetsAt ? { resetsAt } : {}) })
  }
  const reached = str(r.rateLimitReachedType)
  if (reached && !windows.some(w => w.usedPercent >= 100)) {
    const latest = windows.map(w => w.resetsAt).filter((d): d is Date => !!d).sort((a, b) => b.getTime() - a.getTime())[0]
    windows.push({ name: reached, usedPercent: 100, ...(latest ? { resetsAt: latest } : {}) })
  }
  return windows
}

export const codexAdapter: AuthAdapter = {
  kind: "codex",
  methods: ["system", "api_key", "token", "subscription"],
  layout: (roots: HomeRoots) => codexLayout(roots),
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": {
        const key = requireSecret(account, context.secret)
        return { env: { CODEX_API_KEY: key, OPENAI_API_KEY: key } }
      }
      case "token": {
        const token = parseCodexToken(requireSecret(account, context.secret))
        const expiry = codexTokenExpiry(token)
        // The registry refreshes tokens that carry a refresh token before this point; without one, an expired token is final.
        if (expiry && expiry.getTime() <= Date.now()) throw new CoreError("account_expired", `Account ${account.id}: the ChatGPT access token expired at ${expiry.toISOString()}${token.refresh_token ? "" : "; it has no refresh token, so store a fresh one or sign in again"}`)
        return { env: { [CODEX_TOKEN_ENV]: token.access_token }, unset: ["CODEX_API_KEY", "OPENAI_API_KEY", "OPENAI_BASE_URL"], args: codexTokenArgs(token.account_id) }
      }
      case "subscription": return { env: { CODEX_HOME: await context.home() }, unset: ["CODEX_API_KEY", "OPENAI_API_KEY"] }
    }
    return unsupported(account)
  },
  readIdentity: (home, roots) => readCodexIdentity(home ?? codexRoot(roots)),
  secretIdentity(secret) {
    try { return { accountId: parseCodexToken(secret).account_id } } catch { return undefined }
  },
  secretRotates(secret) {
    try { return !!parseCodexToken(secret).refresh_token } catch { return false }
  },
  secretExpiry(secret) {
    try { return codexTokenExpiry(parseCodexToken(secret)) } catch { return undefined }
  },
  async refreshSecret(secret, fetch) {
    return JSON.stringify(await refreshCodexToken(parseCodexToken(secret), fetch))
  },
  usage: codexUsage,
  validate(options: AddAccountOptions) {
    if (options.method === "token" && options.secret !== undefined) parseCodexToken(options.secret)
  },
}
