import { homedir } from "node:os"
import { join, resolve } from "node:path"
import type { AccountIdentity, AuthAdapter, HomeRoots, Materialized } from "../types.js"
import { jwtPayload, readJson, requireSecret, str, unsupported } from "./shared.js"

/** An account's own agy cli dir (token, settings). The driver links it into the session's shadow HOME as ~/.gemini/antigravity-cli and shares the real conversations/brain into it. */
export const ANTIGRAVITY_CLI_DIR_ENV = "SUPERMUX_ANTIGRAVITY_CLI_DIR"

/** The system login's cli dir: agy finds it only through $HOME, so always <home>/.gemini/antigravity-cli. */
export function antigravityRoot(roots: HomeRoots = {}): string {
  return resolve(roots.antigravityRoot ?? join(homedir(), ".gemini", "antigravity-cli"))
}

/** `<cliDir>/antigravity-oauth-token` = `{ token, auth_method, id_token }`; the id_token JWT names the Google user. No file (or unreadable) → undefined; no id_token → {}. */
export async function readAntigravityIdentity(cliDir: string): Promise<AccountIdentity | undefined> {
  const token = await readJson(join(cliDir, "antigravity-oauth-token"))
  if (!token) return undefined
  const claims = jwtPayload(token.id_token)
  const email = str(claims?.email)
  const accountId = str(claims?.sub)
  return { ...(email ? { email } : {}), ...(accountId ? { accountId } : {}) }
}

/**
 * system: the user's own ~/.gemini/antigravity-cli, never touched. subscription (Sign in with Google): a private
 * cli dir holding the account's token. api_key: GEMINI_API_KEY plus an empty private cli dir, so agy can never
 * fall back to the user's own OAuth token. No `layout`: history sharing is the driver's (shareAntigravityHistory);
 * ensureHome would create entries under the real ~/.gemini.
 */
export const antigravityAdapter: AuthAdapter = {
  kind: "antigravity",
  methods: ["system", "api_key", "subscription"],
  async materialize(account, context): Promise<Materialized> {
    switch (account.method) {
      case "system": return { env: {} }
      case "api_key": return { env: { GEMINI_API_KEY: requireSecret(account, context.secret), [ANTIGRAVITY_CLI_DIR_ENV]: await context.home() } }
      case "subscription": return { env: { [ANTIGRAVITY_CLI_DIR_ENV]: await context.home() }, unset: ["GEMINI_API_KEY"] }
    }
    return unsupported(account)
  },
  readIdentity: (home, roots) => readAntigravityIdentity(home ?? antigravityRoot(roots)),
}
