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
  return opencodeAuthContentFor(accountId, [provider], key)
}

/** An OpenCode Console key (opencode.ai/auth) serves both Zen (`opencode`) and Go (`opencode-go`). */
export const OPENCODE_CONSOLE_PROVIDERS = ["opencode", "opencode-go"]

function opencodeAuthContentFor(accountId: string, providers: string[], key: string): string {
  const reserved = providers.find(p => ["version", "accounts", "active"].includes(p))
  if (reserved) throw new CoreError("invalid_input", `OpenCode provider id ${reserved} is reserved`)
  const id = (provider: string) => providers.length === 1 ? accountId : `${accountId}:${provider}`
  const credential = { type: "api", key }
  return JSON.stringify({
    version: 2,
    accounts: Object.fromEntries(providers.map(p => [id(p), { id: id(p), serviceID: p, description: "supermux", credential }])),
    active: Object.fromEntries(providers.map(p => [p, id(p)])),
    ...Object.fromEntries(providers.map(p => [p, credential])),
  })
}

export const opencodeAdapter: AuthAdapter = {
  kind: "opencode",
  methods: ["system", "api_key"],
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": {
        const providers = account.provider ? [account.provider] : OPENCODE_CONSOLE_PROVIDERS
        return { env: { OPENCODE_AUTH_CONTENT: opencodeAuthContentFor(account.id, providers, requireSecret(account, context.secret)) } }
      }
    }
    return unsupported(account)
  },
  validate(options: AddAccountOptions) {
    if (options.method === "api_key" && options.provider !== undefined && (typeof options.provider !== "string" || !/^[a-zA-Z0-9_.-]{1,64}$/.test(options.provider))) {
      throw new CoreError("invalid_input", "An OpenCode provider id is letters, digits, _ . - (e.g. \"anthropic\")")
    }
  },
}
