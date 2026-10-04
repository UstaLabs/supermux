import { mkdir } from "node:fs/promises"
import { join } from "node:path"
import type { AuthAdapter, Materialized } from "../types.js"
import { requireSecret, unsupported } from "./shared.js"

/** Auth lives under XDG_CONFIG_HOME/cursor; chats stay under ~/.cursor. Cursor accounts are never auto-switched. */
export const cursorAdapter: AuthAdapter = {
  kind: "cursor",
  methods: ["system", "api_key", "subscription"],
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": return { env: { CURSOR_API_KEY: requireSecret(account, context.secret) } }
      case "subscription": {
        const xdg = join(await context.home(), "xdg")
        await mkdir(xdg, { recursive: true, mode: 0o700 })
        return { env: { XDG_CONFIG_HOME: xdg }, unset: ["CURSOR_API_KEY", "CURSOR_AUTH_TOKEN"] }
      }
    }
    return unsupported(account)
  },
}
