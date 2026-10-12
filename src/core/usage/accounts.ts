// Live usage for the accounts added beside each agent's system login (Settings → Accounts).
//
// The provider fetchers in ./index read the system logins (~/.claude, ~/.codex, …). An added
// account keeps its credentials elsewhere — a subscription home, or a vault token — so this module
// asks the registry for the account's launch env (the same `materialize` a session gets, which
// also refreshes an expiring Codex token) and points the same fetchers at it. API-key accounts
// are billed per token and have no quota to show; OpenCode's usage is local totals, not per login.

import { existsSync, readFileSync } from "fs"
import { join } from "path"
import type { Account, Materialized } from "../../../packages/supermux-core/src/accounts/index.js"
import { CODEX_TOKEN_ENV } from "../../../packages/supermux-core/src/accounts/adapters/codex.js"
import { displayLabel, sharedAccounts } from "../accounts/broker-accounts"
import {
  fetchClaudeUsage,
  fetchClaudeUsageWithToken,
  fetchCodexUsage,
  fetchCodexUsageWithToken,
  fetchCursorUsageWithToken,
  fetchGrokUsage,
} from "./index"
import type { UsageProvider } from "./store"

/** One added account the Usage screen lists, and how to fetch its usage (absent: no quota). */
export interface AccountUsageTarget {
  id: string
  agent: UsageProvider
  label: string
  method: Account["method"]
  fetch?: () => Promise<unknown | null>
}

const QUOTA_AGENTS = new Set<string>(["claude", "codex", "cursor", "grok"])

/** Fetches one account's usage from its materialized launch env; null = no credentials found. */
export async function fetchUsageForEnv(
  agent: string,
  materialized: Materialized,
  account: Pick<Account, "identity">,
): Promise<unknown | null> {
  const env = materialized.env
  switch (agent) {
    case "claude":
      if (env.CLAUDE_CODE_OAUTH_TOKEN) return fetchClaudeUsageWithToken(env.CLAUDE_CODE_OAUTH_TOKEN)
      if (env.CLAUDE_CONFIG_DIR) return fetchClaudeUsage(join(env.CLAUDE_CONFIG_DIR, ".credentials.json"))
      return null
    case "codex":
      if (env[CODEX_TOKEN_ENV]) return fetchCodexUsageWithToken(env[CODEX_TOKEN_ENV], account.identity?.accountId)
      if (env.CODEX_HOME) return fetchCodexUsage(join(env.CODEX_HOME, "auth.json"))
      return null
    case "cursor": {
      if (!env.XDG_CONFIG_HOME) return null
      const path = join(env.XDG_CONFIG_HOME, "cursor", "auth.json")
      if (!existsSync(path)) return null
      const token = JSON.parse(readFileSync(path, "utf-8"))?.accessToken
      return typeof token === "string" && token ? fetchCursorUsageWithToken(token) : null
    }
    case "grok":
      return env.GROK_AUTH_PATH ? fetchGrokUsage(env.GROK_AUTH_PATH) : null
  }
  return null
}

/** Every added (non-system) account, in Settings order, with a fetcher where it has a quota. */
export async function listAccountUsageTargets(): Promise<AccountUsageTarget[]> {
  const { registry } = sharedAccounts()
  const accounts = (await registry.list())
    .filter((a) => a.method !== "system")
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  return accounts.map((account) => {
    const target: AccountUsageTarget = {
      id: account.id,
      agent: account.agent as UsageProvider,
      label: displayLabel(account),
      method: account.method,
    }
    if (account.method !== "api_key" && QUOTA_AGENTS.has(account.agent)) {
      target.fetch = async () => fetchUsageForEnv(account.agent, await registry.materialize(account), account)
    }
    return target
  })
}
