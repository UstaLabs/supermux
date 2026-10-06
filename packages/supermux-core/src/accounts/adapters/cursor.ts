import { mkdir } from "node:fs/promises"
import { homedir } from "node:os"
import { join, resolve } from "node:path"
import type { AccountIdentity, AuthAdapter, HomeRoots, Materialized } from "../types.js"
import { jwtPayload, readJson, requireSecret, str, unsupported } from "./shared.js"

export function cursorRoot(roots: HomeRoots = {}): string {
  return resolve(roots.cursorRoot ?? process.env.XDG_CONFIG_HOME ?? join(homedir(), ".config"))
}

/** `<config>/cursor/auth.json` `{ accessToken, refreshToken }`: the JWT `sub` names the user (email when present). */
export async function readCursorIdentity(configRoot: string): Promise<AccountIdentity | undefined> {
  const claims = jwtPayload((await readJson(join(configRoot, "cursor", "auth.json")))?.accessToken)
  const accountId = str(claims?.sub)
  const email = str(claims?.email)
  if (!accountId && !email) return undefined
  return { ...(email ? { email } : {}), ...(accountId ? { accountId } : {}) }
}

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
  readIdentity: (home, roots) => readCursorIdentity(home ? join(home, "xdg") : cursorRoot(roots)),
}
