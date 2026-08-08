/** Cursor credential resolution — PRIVATE to the cursor module.
 *
 * cursor-agent reads auth from TWO locations (found via strace):
 *   ~/.cursor/cli-config.json         — settings + user identity (via HOME)
 *   ~/.config/cursor/auth.json        — OAuth token (via XDG_CONFIG_HOME)
 * Plus optional state:
 *   ~/.cursor/agent-cli-state.json    — CLI state
 * We copy all three into the per-session HOME so each session's env is
 * self-contained. Everything else in ~/.cursor (plugins, extensions,
 * skills, projects) is irrelevant for headless operation and previously
 * caused EACCES / cpSync-overlap bugs.
 *
 * Transport: a COPY, like codex. cursor-agent gives no documented way to point
 * the child at a canonical credential, so the copy stays and the drift it
 * causes is healed on every spawn and every resume by `promoteIfNewer`. Only
 * `auth.json` is promoted: `cli-config.json` and `agent-cli-state.json` hold
 * identity and CLI state, not a credential, and they must not travel back.
 *
 * Freshness signal: `accessToken` is a JWT and its `exp` claim moves forward on
 * every refresh (verified against a real `~/.config/cursor/auth.json`).
 *
 * FAILS CLOSED: with no API key and no credential the function throws, because
 * the spawn would otherwise write a config for an agent that answers nothing.
 * An absent canonical file does NOT resurrect the session copy.
 */
import { existsSync, mkdirSync, copyFileSync, chmodSync } from "fs"
import { posix, win32 } from "path"
import { home } from "../../../shared/home"
import { ensureSharedCursorRuntime } from "../shared-runtime"
import type { AgentAuthResult } from "../auth-result"
import { jwtExpiryMs, promoteIfNewer, readCredentialJson } from "../credential-file"
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"

export type CursorAuthResult = AgentAuthResult<"api_key" | "oauth_copy">

const CURSOR_DIR_FILES = ["cli-config.json", "agent-cli-state.json"]

/** Expiry claim of a cursor credential file, in milliseconds since the epoch. */
export function cursorCredentialFreshness(path: string): number {
  return jwtExpiryMs(readCredentialJson(path)?.accessToken)
}

export async function resolveCursorAuth(opts: {
  apiKey?: string
  userCursorDir: string         // ~/.cursor
  userConfigDir?: string        // ~/.config (XDG_CONFIG_HOME; for testability)
  sessionHome: string           // ~/.mux/state/agents/cursor/<name>
  platform?: NodeJS.Platform
  env?: Record<string, string | undefined>
}): Promise<CursorAuthResult> {
  mkdirSync(opts.sessionHome, { recursive: true, mode: 0o700 })

  // Point this home's cursor-agent runtime at the one shared copy (and seed it
  // on first use) so each isolated $HOME doesn't carry its own ~178 MB. Runs
  // before the CLI starts, on both spawn and resume.
  const platform = opts.platform ?? process.platform
  const env = opts.env ?? process.env
  const pathJoin = platform === "win32" ? win32.join : posix.join
  const isolatedEnv: Record<string, string> = platform === "win32"
    ? { APPDATA: pathJoin(opts.sessionHome, "AppData", "Roaming"), USERPROFILE: opts.sessionHome }
    : {}
  if (platform !== "win32") ensureSharedCursorRuntime(opts.sessionHome)

  if (opts.apiKey) {
    return { mode: "api_key", env: { CURSOR_API_KEY: opts.apiKey, ...isolatedEnv } }
  }

  // XDG auth token — the actual credential cursor-agent needs to call APIs.
  // Stored at ~/.config/cursor/auth.json (XDG_CONFIG_HOME/cursor/auth.json).
  const userHome = platform === "win32" ? (env.USERPROFILE || home()) : home()
  const configBase = opts.userConfigDir ?? (platform === "win32"
    ? (env.APPDATA || pathJoin(userHome, "AppData", "Roaming"))
    : (env.XDG_CONFIG_HOME || pathJoin(userHome, ".config")))
  const xdgAuthSrc = pathJoin(configBase, "cursor", "auth.json")
  const configSrc = pathJoin(opts.userCursorDir, "cli-config.json")

  const sessionConfigBase = platform === "win32"
    ? pathJoin(opts.sessionHome, "AppData", "Roaming")
    : pathJoin(opts.sessionHome, ".config")
  const sessionAuth = pathJoin(sessionConfigBase, "cursor", "auth.json")

  // Heal first, throw or copy second. A token this session refreshed goes back
  // to the user's file before the copy below overwrites it.
  promoteIfNewer({
    sessionCopy: sessionAuth,
    canonical: xdgAuthSrc,
    freshness: cursorCredentialFreshness,
  })

  if (!existsSync(xdgAuthSrc) && !existsSync(configSrc)) {
    throw new Error(
      `Cursor auth not found at ${xdgAuthSrc} and CURSOR_API_KEY is unset. ` +
      `Run \`cursor-agent login\` first.`
    )
  }

  // Copy ~/.cursor/{cli-config,agent-cli-state}.json → sessionHome/.cursor/
  const destCursorDir = pathJoin(opts.sessionHome, ".cursor")
  mkdirSync(destCursorDir, { recursive: true, mode: 0o700 })
  chmodSync(destCursorDir, 0o700)
  for (const f of CURSOR_DIR_FILES) {
    const src = pathJoin(opts.userCursorDir, f)
    if (existsSync(src)) {
      const dst = pathJoin(destCursorDir, f)
      copyFileSync(src, dst)
      chmodSync(dst, 0o600)
    }
  }

  // Copy ~/.config/cursor/auth.json → sessionHome/.config/cursor/auth.json
  if (existsSync(xdgAuthSrc)) {
    const destAuthDir = pathJoin(sessionConfigBase, "cursor")
    mkdirSync(destAuthDir, { recursive: true, mode: 0o700 })
    copyFileSync(xdgAuthSrc, sessionAuth)
    chmodSync(sessionAuth, 0o600)
  }

  return { mode: "oauth_copy", env: isolatedEnv }
}

/** Device-login spawn descriptor. cursor-agent prints the login URL on
 * stdout; NO_OPEN_BROWSER stops it from opening one on the broker host. */
export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["cursor-agent", "agent"], env, process.platform) ?? "cursor-agent"
  env.NO_OPEN_BROWSER = "1"
  return { cmd, args: ["login"], env }
}
