/** Claude credential resolution — PRIVATE to the claude module.
 *
 * Transport: NONE. Claude runs in the broker's own home and reads the broker's
 * own environment, so there is no per-session copy, no isolation, and no refresh
 * drift to heal. This module therefore only ANSWERS the question "is claude
 * logged in", which claude previously answered in three places:
 *
 *   - `detect.ts`               probed ~/.claude/.credentials.json
 *   - `claude-auth-status.ts`   ran `claude auth status` on darwin
 *   - `main.ts` (two sites)     read the stored settings credentials
 *
 * The three sources now live here, in one order, behind one predicate.
 *
 * FAILS OPEN: the resolver never throws. A claude session spawns whatever the
 * answer is, and the CLI reports its own auth error. The answer drives the
 * status badge and the login flow only.
 */
import { spawnSync } from "node:child_process"
import { existsSync } from "fs"
import { join, win32 } from "path"
import type { AgentAuthResult } from "../auth-result"

/** How claude proves it is logged in. `none` means no source answered. */
export type ClaudeAuthMode =
  | "oauth_token"        // CLAUDE_CODE_OAUTH_TOKEN in the environment
  | "api_key"            // ANTHROPIC_API_KEY in the environment
  | "stored_credential"  // a credential the user saved in supermux settings
  | "credentials_file"   // ~/.claude/.credentials.json
  | "cli_keychain"       // darwin: `claude auth status` accepts the Keychain login
  | "none"

export type ClaudeAuthResult = AgentAuthResult<ClaudeAuthMode>

export type AuthStatusRunner = (command: string, args: string[]) => boolean

/** Environment variables that authenticate claude on their own. Order matters:
 * the first hit names the mode. */
const CLAUDE_ENV_MODES: readonly (readonly [string, ClaudeAuthMode])[] = [
  ["CLAUDE_CODE_OAUTH_TOKEN", "oauth_token"],
  ["ANTHROPIC_API_KEY", "api_key"],
]

const runAuthStatus: AuthStatusRunner = (command, args) => {
  const result = spawnSync(command, args, {
    env: process.env,
    stdio: "ignore",
    timeout: 5_000,
  })
  return result.status === 0 && !result.error
}

/**
 * Claude stores browser-login credentials in the macOS Keychain. The broker's
 * launch-agent process can ask Claude to verify that credential, while checking
 * only ~/.claude/.credentials.json incorrectly reports a successful login as
 * unauthenticated.
 */
export function claudeCliIsAuthenticated(
  platform = process.platform,
  runner: AuthStatusRunner = runAuthStatus,
): boolean {
  if (platform !== "darwin") return false
  try {
    return runner("claude", ["auth", "status"])
  } catch {
    return false
  }
}

/** Absolute path of the credential file `claude login` writes. */
export function claudeCredentialsPath(opts: { home: string; platform?: NodeJS.Platform }): string {
  const pathJoin = (opts.platform ?? process.platform) === "win32" ? win32.join : join
  return pathJoin(opts.home, ".claude", ".credentials.json")
}

/** Everything the answer depends on. Every source is injected, so the predicate
 * reads no ambient state and unit-tests without I/O. An omitted source counts as
 * absent: `env` defaults to empty, NOT to `process.env`. */
export type ClaudeAuthProbe = {
  home: string
  platform?: NodeJS.Platform
  /** Environment to read. Empty by default, so a caller opts in to env overrides. */
  env?: Record<string, string | undefined>
  fileExists?: (path: string) => boolean
  /** A credential the user stored in supermux settings. */
  storedCredential?: boolean
  runner?: AuthStatusRunner
}

/** Which source authenticates claude, in order: environment, stored settings,
 * credential file, then the darwin Keychain probe (which is the slowest, and
 * answers false everywhere else). */
export function claudeAuthMode(opts: ClaudeAuthProbe): ClaudeAuthMode {
  const env = opts.env ?? {}
  for (const [name, mode] of CLAUDE_ENV_MODES) {
    if (env[name]) return mode
  }
  if (opts.storedCredential) return "stored_credential"
  const exists = opts.fileExists ?? ((p: string) => existsSync(p))
  if (exists(claudeCredentialsPath(opts))) return "credentials_file"
  if (claudeCliIsAuthenticated(opts.platform ?? process.platform, opts.runner ?? runAuthStatus)) {
    return "cli_keychain"
  }
  return "none"
}

/** True when any source authenticates claude. */
export function claudeIsAuthed(opts: ClaudeAuthProbe): boolean {
  return claudeAuthMode(opts) !== "none"
}

/** The normalized resolver. `env` is always empty: claude inherits the broker's
 * environment and needs no per-session credential of its own. */
export async function resolveClaudeAuth(opts: ClaudeAuthProbe): Promise<ClaudeAuthResult> {
  return { mode: claudeAuthMode(opts), env: {} }
}
