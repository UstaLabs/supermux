/** OpenCode credential resolution — PRIVATE to the opencode module.
 *
 * Transport: NONE. Only the config dir is isolated; `XDG_DATA_HOME` keeps the
 * user's value, so opencode reads the one shared `auth.json` that
 * `opencode auth login` wrote. There is no copy, therefore no refresh drift and
 * nothing to promote.
 *
 * FAILS OPEN: opencode ships a free `opencode/*` tier that runs with no
 * credential, so the resolver reports `authed` and never throws. The caller uses
 * `authed` for the status badge only.
 */
import { existsSync } from "fs"
import { join, win32 } from "path"
import type { AgentAuthResult } from "../auth-result"

export type OpenCodeAuthResult = AgentAuthResult<"shared_auth" | "provider_key" | "none"> & {
  dataDir: string
  authPath: string
  authed: boolean
}

export function openCodeDataDir(opts: {
  home: string
  xdgDataHome?: string
  env?: Record<string, string | undefined>
  platform?: NodeJS.Platform
  localAppData?: string
}): string {
  const env = opts.env ?? process.env
  const platform = opts.platform ?? process.platform
  const pathJoin = platform === "win32" ? win32.join : join
  const base = platform === "win32"
    ? (opts.localAppData || env.LOCALAPPDATA || pathJoin(opts.home, "AppData", "Local"))
    : (opts.xdgDataHome || env.XDG_DATA_HOME || pathJoin(opts.home, ".local", "share"))
  return pathJoin(base, "opencode")
}

// Provider API keys opencode picks up from the environment. Presence of any one
// means opencode can talk to at least one provider even without an auth.json.
const PROVIDER_KEY_ENV = [
  "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "OPENROUTER_API_KEY",
  "GEMINI_API_KEY", "GOOGLE_GENERATIVE_AI_API_KEY", "GROQ_API_KEY",
]

/** Resolve opencode's credential location and whether it looks authenticated.
 * Pure + dependency-injected (fileExists/env) so it unit-tests without I/O.
 * `env` is empty on purpose: the child inherits the broker's XDG_DATA_HOME. */
export async function resolveOpenCodeAuth(opts: {
  home: string
  xdgDataHome?: string
  env?: Record<string, string | undefined>
  fileExists?: (p: string) => boolean
  platform?: NodeJS.Platform
  localAppData?: string
}): Promise<OpenCodeAuthResult> {
  const exists = opts.fileExists ?? ((p: string) => existsSync(p))
  const env = opts.env ?? process.env
  const dataDir = openCodeDataDir(opts)
  const authPath = (opts.platform ?? process.platform) === "win32"
    ? win32.join(dataDir, "auth.json") : join(dataDir, "auth.json")
  const hasProviderKey = PROVIDER_KEY_ENV.some((k) => !!env[k])
  const mode = exists(authPath) ? "shared_auth" : hasProviderKey ? "provider_key" : "none"
  return { mode, env: {}, dataDir, authPath, authed: mode !== "none" }
}

/** opencode has no CLI device-login flow the broker can drive; auth goes
 * through the provider OAuth/API-key ops (auth-ops.ts). null = unsupported. */
export function loginSpawnCommand(): null {
  return null
}
