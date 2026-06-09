import { existsSync, mkdirSync, copyFileSync, chmodSync } from "fs"
import { join } from "path"
import { home } from "../../../shared/home"
import { ensureSharedCursorRuntime } from "../shared-runtime"

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
}): Promise<CursorAuthResult> {
  mkdirSync(opts.sessionHome, { recursive: true, mode: 0o700 })

  // Point this home's cursor-agent runtime at the one shared copy (and seed it
  // on first use) so each isolated $HOME doesn't carry its own ~178 MB. Runs
  // before the CLI starts, on both spawn and resume.
  ensureSharedCursorRuntime(opts.sessionHome)

  if (opts.apiKey) {
    return { mode: "api_key", env: { CURSOR_API_KEY: opts.apiKey } }
  }

  // XDG auth token — the actual credential cursor-agent needs to call APIs.
  // Stored at ~/.config/cursor/auth.json (XDG_CONFIG_HOME/cursor/auth.json).
  const userHome = home()
  const configBase = opts.userConfigDir ?? process.env.XDG_CONFIG_HOME ?? join(userHome, ".config")
  const xdgAuthSrc = join(configBase, "cursor", "auth.json")
  const configSrc = join(opts.userCursorDir, "cli-config.json")

  if (!existsSync(xdgAuthSrc) && !existsSync(configSrc)) {
    throw new Error(
      `Cursor auth not found at ${xdgAuthSrc} and CURSOR_API_KEY is unset. ` +
      `Run \`cursor-agent login\` first.`
    )
  }

  // Copy ~/.cursor/{cli-config,agent-cli-state}.json → sessionHome/.cursor/
  const destCursorDir = join(opts.sessionHome, ".cursor")
  mkdirSync(destCursorDir, { recursive: true, mode: 0o700 })
  chmodSync(destCursorDir, 0o700)
  for (const f of CURSOR_DIR_FILES) {
    const src = join(opts.userCursorDir, f)
    if (existsSync(src)) {
      const dst = join(destCursorDir, f)
      copyFileSync(src, dst)
      chmodSync(dst, 0o600)
    }
  }

  // Copy ~/.config/cursor/auth.json → sessionHome/.config/cursor/auth.json
  if (existsSync(xdgAuthSrc)) {
    const destAuthDir = join(opts.sessionHome, ".config", "cursor")
    mkdirSync(destAuthDir, { recursive: true, mode: 0o700 })
    const destAuth = join(destAuthDir, "auth.json")
    copyFileSync(xdgAuthSrc, destAuth)
    chmodSync(destAuth, 0o600)
  }

  return { mode: "oauth_copy", env: {} }
}
