import { existsSync } from "fs"
import { join } from "path"

export type OpenCodeAuthResult = {
  /** Extra env for the `opencode serve` child. Empty by default: we deliberately
   * leave XDG_DATA_HOME at the user's value so opencode uses the credentials it
   * wrote via `opencode auth login` (shared, multi-provider). */
  env: Record<string, string>
  dataDir: string
  authPath: string
  authed: boolean
}

// Provider API keys opencode picks up from the environment. Presence of any one
// means opencode can talk to at least one provider even without an auth.json.
const PROVIDER_KEY_ENV = [
  "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "OPENROUTER_API_KEY",
  "GEMINI_API_KEY", "GOOGLE_GENERATIVE_AI_API_KEY", "GROQ_API_KEY",
]

/** Resolve opencode's credential location and whether it looks authenticated.
 * Pure + dependency-injected (fileExists/env) so it unit-tests without I/O. */
export function resolveOpenCodeAuth(opts: {
  home: string
  xdgDataHome?: string
  env?: Record<string, string | undefined>
  fileExists?: (p: string) => boolean
}): OpenCodeAuthResult {
  const exists = opts.fileExists ?? ((p: string) => existsSync(p))
  const env = opts.env ?? process.env
  const base = opts.xdgDataHome || env.XDG_DATA_HOME || join(opts.home, ".local", "share")
  const dataDir = join(base, "opencode")
  const authPath = join(dataDir, "auth.json")
  const hasProviderKey = PROVIDER_KEY_ENV.some((k) => !!env[k])
  const authed = exists(authPath) || hasProviderKey
  return { env: {}, dataDir, authPath, authed }
}
