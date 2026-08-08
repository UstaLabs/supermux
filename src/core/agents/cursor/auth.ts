import { existsSync, mkdirSync, copyFileSync, chmodSync } from "fs"
import { posix, win32 } from "path"
import { home } from "../../../shared/home"
import { ensureSharedCursorRuntime } from "../shared-runtime"
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"

export type CursorAuthResult =
  | { mode: "api_key"; env: { CURSOR_API_KEY: string } }
  | { mode: "oauth_copy"; env: Record<string, string> }

// cursor-agent reads auth from TWO locations (found via strace):
//   ~/.cursor/cli-config.json         — settings + user identity (via HOME)
//   ~/.config/cursor/auth.json        — OAuth token (via XDG_CONFIG_HOME)
// Plus optional state:
//   ~/.cursor/agent-cli-state.json    — CLI state
// We copy all three into the per-session HOME so each session's env is
// self-contained. Everything else in ~/.cursor (plugins, extensions,
// skills, projects) is irrelevant for headless operation and previously
// caused EACCES / cpSync-overlap bugs.
const CURSOR_DIR_FILES = ["cli-config.json", "agent-cli-state.json"]

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
    const sessionConfigBase = platform === "win32"
      ? pathJoin(opts.sessionHome, "AppData", "Roaming")
      : pathJoin(opts.sessionHome, ".config")
    const destAuthDir = pathJoin(sessionConfigBase, "cursor")
    mkdirSync(destAuthDir, { recursive: true, mode: 0o700 })
    const destAuth = pathJoin(destAuthDir, "auth.json")
    copyFileSync(xdgAuthSrc, destAuth)
    chmodSync(destAuth, 0o600)
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
