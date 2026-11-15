// Which Anthropic organization a Claude account belongs to (its `.claude.json` → oauthAccount.organizationUuid).
// Seats of one Team/Enterprise org share the prompt cache (#61: a chat continued on another seat of the same org stayed
// warm 83 of 112 times), so #146 (compact before a limit wait) and #159 (auto-switch prefers a same-org seat) both ask it.
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { dirs } from "@supermux/platform"
import { homePath } from "./homes.js"
import { systemAccountId } from "./registry.js"
import type { Account } from "./types.js"

/** Where a Claude account's `.claude.json` is: the CLI's own (system) login, or the account's supermux home. */
export function claudeJsonPath(homesDirectory: string, account: Pick<Account, "id" | "agent">, env: NodeJS.ProcessEnv = process.env): string {
  if (account.id === systemAccountId("claude")) return env.CLAUDE_CONFIG_DIR ? join(env.CLAUDE_CONFIG_DIR, ".claude.json") : join(dirs.home(), ".claude.json")
  return join(homePath(homesDirectory, account), ".claude.json")
}

/** The org's id from a `.claude.json`, or undefined (no file, no login, unreadable). Never throws. */
export function readClaudeOrg(path: string): string | undefined {
  try {
    const org = JSON.parse(readFileSync(path, "utf-8"))?.oauthAccount?.organizationUuid
    return typeof org === "string" && org ? org : undefined
  } catch {
    return undefined
  }
}

/** A Claude account's Anthropic org, or undefined when it can't be read (not a Claude account, signed out, no file). */
export function claudeAccountOrg(homesDirectory: string, account: Pick<Account, "id" | "agent">, env: NodeJS.ProcessEnv = process.env): string | undefined {
  if (account.agent !== "claude") return undefined
  return readClaudeOrg(claudeJsonPath(homesDirectory, account, env))
}
