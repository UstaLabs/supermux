import { homedir } from "node:os"
import { join, resolve } from "node:path"
import type { AccountIdentity, AuthAdapter, HomeRoots, Materialized } from "../types.js"
import { readJson, requireSecret, str, unsupported } from "./shared.js"

export function grokRoot(roots: HomeRoots = {}): string {
  return resolve(roots.grokRoot ?? process.env.GROK_HOME ?? join(homedir(), ".grok"))
}

/** grok 1.0.46 auth.json: `{ "<issuer>::<client>": { email, user_id, team_id, key, refresh_token, ... } }`. */
export async function readGrokIdentity(authFile: string): Promise<AccountIdentity | undefined> {
  const value = await readJson(authFile)
  for (const entry of Object.values(value ?? {})) {
    if (!entry || typeof entry !== "object") continue
    const e = entry as Record<string, unknown>
    const email = str(e.email)
    const accountId = str(e.user_id)
    const org = str(e.team_id)
    if (!email && !accountId) continue
    return { ...(email ? { email } : {}), ...(org ? { org } : {}), ...(accountId ? { accountId } : {}) }
  }
  return undefined
}

/** GROK_AUTH_PATH holds the login; history stays in GROK_HOME. The provider-command helper mode is not built yet. */
export const grokAdapter: AuthAdapter = {
  kind: "grok",
  methods: ["system", "api_key", "subscription"],
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": return { env: { XAI_API_KEY: requireSecret(account, context.secret) } }
      case "subscription": return { env: { GROK_AUTH_PATH: join(await context.home(), "auth.json") }, unset: ["XAI_API_KEY"] }
    }
    return unsupported(account)
  },
  readIdentity: (home, roots) => readGrokIdentity(join(home ?? grokRoot(roots), "auth.json")),
}
