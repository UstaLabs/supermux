import { CoreError } from "../../errors.js"
import type { AddAccountOptions, AuthAdapter, Materialized } from "../types.js"
import { requireSecret, unsupported } from "./shared.js"

/**
 * OPENCODE_AUTH_CONTENT for opencode 1.16.2, which reads it in two places (verified 2026-10-04):
 * - the v2 Account service takes `{ version: 2, accounts: { <id>: { id, serviceID, description,
 *   credential: { type: "api", key } } }, active: { <serviceID>: <id> } }` as is; any other shape
 *   is migrated and WRITTEN to <data>/opencode/account.json;
 * - the legacy Auth service (provider loading: `opencode models`, sessions) reads the same JSON as
 *   `{ <provider>: { type: "api", key } }`; with only the v2 keys the provider has no credential.
 * So the content carries both: the v2 keys (no migration write) and the provider entry.
 */
export function opencodeAuthContent(accountId: string, provider: string, key: string): string {
  if (["version", "accounts", "active"].includes(provider)) throw new CoreError("invalid_input", `OpenCode provider id ${provider} is reserved`)
  return JSON.stringify({
    version: 2,
    accounts: { [accountId]: { id: accountId, serviceID: provider, description: "supermux", credential: { type: "api", key } } },
    active: { [provider]: accountId },
    [provider]: { type: "api", key },
  })
}

export const opencodeAdapter: AuthAdapter = {
  kind: "opencode",
  methods: ["system", "api_key"],
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": {
        if (!account.provider) throw new CoreError("invalid_input", `OpenCode account ${account.id} has no provider`)
        return { env: { OPENCODE_AUTH_CONTENT: opencodeAuthContent(account.id, account.provider, requireSecret(account, context.secret)) } }
      }
    }
    return unsupported(account)
  },
  validate(options: AddAccountOptions) {
    if (options.method === "api_key" && (typeof options.provider !== "string" || !/^[a-zA-Z0-9_.-]{1,64}$/.test(options.provider))) {
      throw new CoreError("invalid_input", "OpenCode api_key accounts need a provider id (e.g. \"anthropic\")")
    }
  },
}
