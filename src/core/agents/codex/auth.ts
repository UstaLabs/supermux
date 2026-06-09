import { existsSync, copyFileSync, mkdirSync, chmodSync } from "fs"
import { join } from "path"

export type CodexAuthResult =
  | { mode: "api_key"; env: { OPENAI_API_KEY: string } }
  | { mode: "oauth_copy"; env: Record<string, string> }

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
  if (existsSync(userAuth)) {
    const sessionAuth = join(opts.sessionCodexHome, "auth.json")
    copyFileSync(userAuth, sessionAuth)
    chmodSync(sessionAuth, 0o600)
    return { mode: "oauth_copy", env: {} }
  }

  throw new Error(
    `Codex auth not found at ${userAuth} and OPENAI_API_KEY is unset. ` +
    `Run \`codex login\` first.`
  )
}
