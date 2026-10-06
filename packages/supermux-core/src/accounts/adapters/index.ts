import type { AuthAdapter } from "../types.js"
import { claudeAdapter } from "./claude.js"
import { codexAdapter } from "./codex.js"
import { cursorAdapter } from "./cursor.js"
import { grokAdapter } from "./grok.js"
import { opencodeAdapter } from "./opencode.js"

/** Agents with no known adapter only have their system account. */
export const genericAdapter: AuthAdapter = {
  kind: "generic",
  methods: ["system"],
  async materialize() { return { env: {} } },
}

const ADAPTERS: Record<string, AuthAdapter> = {
  claude: claudeAdapter, codex: codexAdapter, cursor: cursorAdapter, grok: grokAdapter, opencode: opencodeAdapter,
}

/** The adapter for a core agent id (matched by id: "claude", "codex", "cursor", "grok", "opencode"). */
export function adapterFor(agent: string): AuthAdapter {
  return Object.hasOwn(ADAPTERS, agent) ? ADAPTERS[agent]! : genericAdapter
}

export { claudeAdapter, claudeUsage, readClaudeIdentity, claudeIdentityFile } from "./claude.js"
export {
  codexAdapter, codexUsage, readCodexIdentity, codexTokenArgs, parseCodexToken, codexTokenExpiry, codexTokenFromAuth,
  refreshCodexToken, CODEX_TOKEN_ENV, CODEX_OAUTH_TOKEN_URL, CODEX_OAUTH_CLIENT_ID,
} from "./codex.js"
export type { CodexTokenSecret } from "./codex.js"
export { cursorAdapter, readCursorIdentity, cursorRoot } from "./cursor.js"
export { grokAdapter, readGrokIdentity, grokRoot } from "./grok.js"
export { opencodeAdapter, opencodeAuthContent } from "./opencode.js"
