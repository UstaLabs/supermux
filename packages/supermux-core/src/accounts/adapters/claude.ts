import { homedir } from "node:os"
import { join, resolve } from "node:path"
import { claudeLayout, claudeRoot } from "../homes.js"
import type { AccountIdentity, AuthAdapter, HomeRoots, UsageWindow, Materialized } from "../types.js"
import { epochDate, readJson, requireSecret, str, unsupported } from "./shared.js"

const CREDENTIAL_ENV = ["CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"]

/** `.claude.json` sits inside CLAUDE_CONFIG_DIR, except for the real default (~/.claude → ~/.claude.json). */
export function claudeIdentityFile(configDir: string): string {
  return resolve(configDir) === join(homedir(), ".claude") ? join(homedir(), ".claude.json") : join(configDir, ".claude.json")
}

export async function readClaudeIdentity(configDir: string): Promise<AccountIdentity | undefined> {
  const account = (await readJson(claudeIdentityFile(configDir)))?.oauthAccount
  if (!account || typeof account !== "object") return undefined
  const a = account as Record<string, unknown>
  const identity: AccountIdentity = {
    ...(str(a.emailAddress) ? { email: str(a.emailAddress) } : {}),
    ...(str(a.organizationUuid) ? { org: str(a.organizationUuid) } : {}),
    ...(str(a.accountUuid) ? { accountId: str(a.accountUuid) } : {}),
  }
  return Object.keys(identity).length ? identity : undefined
}

/** `rate_limit_event.rate_limit_info`: unifiedWindows utilization 0..1; status "rejected" marks the named window full. */
export function claudeUsage(rateLimits: unknown): UsageWindow[] {
  if (!rateLimits || typeof rateLimits !== "object") return []
  const info = rateLimits as Record<string, unknown>
  const windows: UsageWindow[] = []
  const unified = info.unifiedWindows
  if (unified && typeof unified === "object") {
    for (const [name, raw] of Object.entries(unified as Record<string, unknown>)) {
      if (!raw || typeof raw !== "object") continue
      const w = raw as Record<string, unknown>
      if (typeof w.utilization !== "number" || !Number.isFinite(w.utilization)) continue
      const resetsAt = epochDate(w.resetsAt)
      windows.push({ name, usedPercent: w.utilization * 100, ...(resetsAt ? { resetsAt } : {}) })
    }
  }
  const type = str(info.rateLimitType)
  if (info.status === "rejected" && type) {
    const existing = windows.find(w => w.name === type)
    if (existing) existing.usedPercent = Math.max(existing.usedPercent, 100)
    else {
      const resetsAt = epochDate(info.resetsAt)
      windows.push({ name: type, usedPercent: 100, ...(resetsAt ? { resetsAt } : {}) })
    }
  }
  return windows
}

export const claudeAdapter: AuthAdapter = {
  kind: "claude",
  methods: ["system", "api_key", "token", "subscription"],
  layout: (roots: HomeRoots) => claudeLayout(roots),
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "token": return { env: { CLAUDE_CODE_OAUTH_TOKEN: requireSecret(account, context.secret) }, unset: ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"] }
      case "api_key": return { env: { ANTHROPIC_API_KEY: requireSecret(account, context.secret) }, unset: ["CLAUDE_CODE_OAUTH_TOKEN"] }
      case "subscription": return { env: { CLAUDE_CONFIG_DIR: await context.home() }, unset: [...CREDENTIAL_ENV] }
    }
    return unsupported(account)
  },
  readIdentity: (home, roots) => readClaudeIdentity(home ?? claudeRoot(roots)),
  usage: claudeUsage,
}
