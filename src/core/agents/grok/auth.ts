import {
  chmodSync,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  type Stats,
} from "fs"
import { randomUUID } from "crypto"
import { dirname, join } from "path"

export type GrokAuthResult = {
  mode: "cached_token" | "none"
  /** Env for the grok child. HOME keeps config session-private while
   * GROK_AUTH_PATH keeps authentication canonical and shared. */
  env: Record<string, string>
}

/** grok resolves its config (~/.grok/config.toml) from HOME and its credential
 * from GROK_AUTH_PATH when set. It also auto-imports Claude Code's config from
 * ~/.claude.json (skills, plugins, MCP servers) by default. That import is the
 * problem: it pulls the user's global `mux-shim` AND `mux-channel` into every grok
 * session, so a session would talk through the wrong shim.
 *
 * Pointing HOME at a session-private dir kills the import at the root (no
 * ~/.claude.json there) and gives us a private config.toml to declare this session's
 * mux-shim in — the same isolation cursor gets from HOME=<sessionHome>, codex from
 * CODEX_HOME, and opencode from XDG_CONFIG_HOME.
 *
 * HOME therefore stays session-private for config isolation, while
 * GROK_AUTH_PATH points every Grok process directly at the user's canonical
 * ~/.grok/auth.json. This must be an environment override rather than a symlink:
 * Grok refreshes credentials with an atomic rename, which replaces the symlink
 * itself and silently forks the session onto a private credential lineage.
 *
 * Older supermux versions copied auth.json, and one version symlinked it. Before
 * switching a session to the explicit canonical path, preserve a legacy private
 * file when it has a later token expiry than the canonical file.
 * This matters during migration: a private Grok process may already have rotated
 * the refresh token while the user's original credential stayed stale.
 *
 * NOT fail-closed: a session with no credential still spawns; grok reports the auth
 * error on the first turn, which is the right place to surface it.
 */
export function resolveGrokAuth(opts: { userGrokDir: string; sessionHome: string; platform?: NodeJS.Platform }): GrokAuthResult {
  const sessionGrokDir = join(opts.sessionHome, ".grok")
  mkdirSync(sessionGrokDir, { recursive: true, mode: 0o700 })

  const src = join(opts.userGrokDir, "auth.json")
  const dest = join(sessionGrokDir, "auth.json")
  try {
    const current = lstatSafe(dest)
    if (current?.isFile() && (!existsSync(src) || credentialExpiry(dest) > credentialExpiry(src))) {
      try {
        promoteCredential(dest, src)
      } catch {
        // Keep the usable private credential if promotion fails. A later resume
        // can retry without losing the only refreshed token.
        return sessionEnv(opts.sessionHome, "cached_token", dest, opts.platform)
      }
    }

    if (!existsSync(src)) {
      return sessionEnv(opts.sessionHome, "none", src, opts.platform)
    }

    return sessionEnv(opts.sessionHome, "cached_token", src, opts.platform)
  } catch {
    // If migration cannot promote a newer legacy credential, keep that usable
    // private lineage for this spawn. A later resume can retry the promotion.
    if (existsSync(dest)) return sessionEnv(opts.sessionHome, "cached_token", dest, opts.platform)
  }
  return sessionEnv(opts.sessionHome, existsSync(src) ? "cached_token" : "none", src, opts.platform)
}

function sessionEnv(
  sessionHome: string,
  mode: GrokAuthResult["mode"],
  authPath: string,
  platform?: NodeJS.Platform,
): GrokAuthResult {
  const plat = platform ?? process.platform
  return {
    mode,
    env: {
      HOME: sessionHome,
      GROK_AUTH_PATH: authPath,
      ...(plat === "win32" ? { USERPROFILE: sessionHome } : {}),
    },
  }
}

function lstatSafe(path: string): Stats | undefined {
  try {
    return lstatSync(path)
  } catch {
    return undefined
  }
}

function credentialExpiry(path: string): number {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8"))
    if (!parsed || typeof parsed !== "object") return Number.NEGATIVE_INFINITY
    let latest = Number.NEGATIVE_INFINITY
    for (const credential of Object.values(parsed)) {
      if (!credential || typeof credential !== "object") continue
      const raw = (credential as { expires_at?: unknown }).expires_at
      if (typeof raw !== "string" && typeof raw !== "number") continue
      const expiry = typeof raw === "number" ? raw : Date.parse(raw)
      if (Number.isFinite(expiry)) latest = Math.max(latest, expiry)
    }
    return latest
  } catch {
    return Number.NEGATIVE_INFINITY
  }
}

function promoteCredential(from: string, canonical: string): void {
  mkdirSync(dirname(canonical), { recursive: true, mode: 0o700 })
  const temp = `${canonical}.mux-${process.pid}-${randomUUID()}.tmp`
  try {
    copyFileSync(from, temp)
    chmodSync(temp, 0o600)
    renameSync(temp, canonical)
  } finally {
    rmSync(temp, { force: true })
  }
}
