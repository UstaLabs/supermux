import { copyFileSync, existsSync, mkdirSync } from "fs"
import { join } from "path"

export type GrokAuthResult = {
  mode: "cached_token" | "none"
  /** Env for the grok child. HOME is redirected so grok reads the session-private
   * ~/.grok (config + auth) instead of the user's. */
  env: Record<string, string>
}

/** grok resolves BOTH its config (~/.grok/config.toml) and its credentials
 * (~/.grok/auth.json) from HOME, and — by default — also auto-imports Claude Code's
 * config from ~/.claude.json (skills, plugins, MCP servers). That import is the
 * problem: it pulls the user's global `mux-shim` AND `mux-channel` into every grok
 * session, so a session would talk through the wrong shim.
 *
 * Pointing HOME at a session-private dir kills the import at the root (no
 * ~/.claude.json there) and gives us a private config.toml to declare this session's
 * mux-shim in — the same isolation cursor gets from HOME=<sessionHome>, codex from
 * CODEX_HOME, and opencode from XDG_CONFIG_HOME.
 *
 * The credential is copied (not symlinked): grok rewrites auth.json on token
 * refresh, and a symlink would write the refreshed token back into the user's real
 * ~/.grok. Copy is one-way — the session reuses the login without mutating it.
 *
 * NOT fail-closed: a session with no credential still spawns; grok reports the auth
 * error on the first turn, which is the right place to surface it.
 */
export function resolveGrokAuth(opts: { userGrokDir: string; sessionHome: string; platform?: NodeJS.Platform }): GrokAuthResult {
  const sessionGrokDir = join(opts.sessionHome, ".grok")
  mkdirSync(sessionGrokDir, { recursive: true, mode: 0o700 })

  const src = join(opts.userGrokDir, "auth.json")
  const dest = join(sessionGrokDir, "auth.json")
  let mode: GrokAuthResult["mode"] = "none"
  if (existsSync(src)) {
    try {
      copyFileSync(src, dest)
      mode = "cached_token"
    } catch {
      // fall through as unauthenticated — grok surfaces it on the first turn
    }
  }
  return { mode, env: {
    HOME: opts.sessionHome,
    ...((opts.platform ?? process.platform) === "win32" ? { USERPROFILE: opts.sessionHome } : {}),
  } }
}
