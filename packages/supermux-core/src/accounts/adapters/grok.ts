import { join } from "node:path"
import type { AuthAdapter, Materialized } from "../types.js"
import { requireSecret, unsupported } from "./shared.js"

/** GROK_AUTH_PATH holds the login; history stays in GROK_HOME. The provider-command helper mode is not in A1. */
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
}
