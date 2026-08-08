/** Codex credential resolution — PRIVATE to the codex module.
 *
 * Transport: a COPY of `~/.codex/auth.json` into the session's `CODEX_HOME`.
 * Codex gives no documented way to point the child at a canonical file, and a
 * symlink is wrong because an atomic rename on refresh replaces the symlink
 * itself. The copy therefore stays, and the drift it causes is healed on every
 * spawn and every resume by `promoteIfNewer` (see credential-file.ts).
 *
 * Freshness signal: `tokens.access_token` is a JWT, and its `exp` claim moves
 * forward on every refresh. `auth.json` also carries `last_refresh`, but one
 * metric must serve both files, and `exp` is derived from the refresh, so `exp`
 * alone gives the same order.
 *
 * FAILS CLOSED: with no API key and no canonical credential the function
 * throws, because the codex app-server child cannot start without one. An
 * absent canonical file does NOT resurrect the session copy: the user logged
 * out on the host, and a promotion would undo that logout.
 */
import { existsSync, copyFileSync, mkdirSync, chmodSync } from "fs"
import { join } from "path"
import { homedir } from "os"
import type { AgentAuthResult } from "../auth-result"
import { jwtExpiryMs, promoteIfNewer, readCredentialJson } from "../credential-file"
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"

export type CodexAuthResult = AgentAuthResult<"api_key" | "oauth_copy">

/** Expiry claim of a codex credential file, in milliseconds since the epoch. */
export function codexCredentialFreshness(path: string): number {
  return jwtExpiryMs(readCredentialJson(path)?.tokens?.access_token)
}

export async function resolveCodexAuth(opts: {
  apiKey?: string
  userCodexHome: string         // typically ~/.codex
  sessionCodexHome: string      // ~/.mux/state/agents/codex/<name>
}): Promise<CodexAuthResult> {
  mkdirSync(opts.sessionCodexHome, { recursive: true, mode: 0o700 })

  if (opts.apiKey) {
    return { mode: "api_key", env: { OPENAI_API_KEY: opts.apiKey } }
  }

  const userAuth = join(opts.userCodexHome, "auth.json")
  const sessionAuth = join(opts.sessionCodexHome, "auth.json")

  // Heal first, copy second. A token this session refreshed goes back to the
  // user's file BEFORE the copy below overwrites it, so a sibling session gets
  // the refreshed token instead of the token it replaced.
  promoteIfNewer({
    sessionCopy: sessionAuth,
    canonical: userAuth,
    freshness: codexCredentialFreshness,
  })

  if (existsSync(userAuth)) {
    copyFileSync(userAuth, sessionAuth)
    chmodSync(sessionAuth, 0o600)
    return { mode: "oauth_copy", env: {} }
  }

  throw new Error(
    `Codex auth not found at ${userAuth} and OPENAI_API_KEY is unset. ` +
    `Run \`codex login\` first.`
  )
}

/** Device-login spawn descriptor. `codex login --device-auth` prints the
 * device URL + code on plain stdout — no PTY needed. */
export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["codex"], env, process.platform) ?? "codex"
  env.CODEX_HOME = join(homedir(), ".codex")
  return { cmd, args: ["login", "--device-auth"], env }
}
