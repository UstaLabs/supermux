// Per-open launch housekeeping for sessions on an account (slice A3a).
//
// The account's adapter (supermux-core accounts) puts the credential into the launch env. What the
// broker must add is removing what it set up for the system login: a session on another account
// must not keep a COPY of the canonical credential (codex/cursor), must not point at the user's
// Grok login file, and a Claude account home has its own .claude.json without the user's mux-shim
// MCP entry. These run in each host's driver factory, i.e. on EVERY open — the first one and every
// Core-internal reopen (limit switch, token refresh) that does not run `prepare` again.

import { existsSync, mkdirSync, writeFileSync } from "fs"
import { join } from "path"
import {
  codexCredentialFreshness,
  cursorCredentialFreshness,
  refreshSessionCredential,
  releaseSessionCredential,
} from "../../../packages/supermux-core/src/environment/index.js"
import { accountMethod, isSystemAccount } from "../accounts/broker-accounts"
import { shimSpawnSpec } from "../session-manager/shim-spawn"
import { CLAUDE_SHIM_SERVER } from "../session-manager/trust"
import { muxShimModeFor } from "../mux-tools/mode"

/** Codex: no session copy of ~/.codex/auth.json on an account; a fresh one when back on the system login. */
export function syncCodexSessionCredential(opts: { sessionHome: string; canonicalHome: string; account: string | undefined; systemApiKey: string | undefined }): void {
  const sessionCopy = join(opts.sessionHome, "auth.json")
  const canonical = join(opts.canonicalHome, "auth.json")
  if (!isSystemAccount(opts.account)) {
    releaseSessionCredential({ sessionCopy, canonical, freshness: codexCredentialFreshness })
  } else if (!opts.systemApiKey && !existsSync(sessionCopy)) {
    refreshSessionCredential({ sessionCopy, canonical, freshness: codexCredentialFreshness })
  }
}

/** Cursor: same rule for the session copy of cursor/auth.json. */
export function syncCursorSessionCredential(opts: { sessionHome: string; userConfigDir: string; account: string | undefined; systemApiKey: string | undefined }): void {
  const base = process.platform === "win32" ? join(opts.sessionHome, "AppData", "Roaming") : join(opts.sessionHome, ".config")
  const sessionCopy = join(base, "cursor", "auth.json")
  const canonical = join(opts.userConfigDir, "cursor", "auth.json")
  if (!isSystemAccount(opts.account)) {
    releaseSessionCredential({ sessionCopy, canonical, freshness: cursorCredentialFreshness })
  } else if (!opts.systemApiKey && !existsSync(sessionCopy)) {
    refreshSessionCredential({ sessionCopy, canonical, freshness: cursorCredentialFreshness })
  }
}

/**
 * Grok: the session env points GROK_AUTH_PATH at the user's login, which grok prefers over
 * XAI_API_KEY. An api_key account gets a session-private path with no file instead (a subscription
 * account's adapter sets its own GROK_AUTH_PATH, which wins anyway).
 */
export async function grokAccountEnv(env: Record<string, string>, opts: { sessionHome: string; account: string | undefined }): Promise<Record<string, string>> {
  if (isSystemAccount(opts.account)) return env
  if ((await accountMethod(opts.account)) !== "api_key") return env
  return { ...env, GROK_AUTH_PATH: join(opts.sessionHome, ".grok", "account-none", "auth.json") }
}

/**
 * Claude: a subscription account runs with CLAUDE_CONFIG_DIR=<account home>, whose private
 * .claude.json has no `mux-shim` user MCP server (the broker registers it in ~/.claude.json). Add
 * it through an extra --mcp-config file. Strict (rpc) sessions keep their own config untouched.
 */
export async function claudeAccountArgs(args: string[], opts: { sessionHome: string; account: string | undefined }): Promise<string[]> {
  if (isSystemAccount(opts.account) || args.includes("--strict-mcp-config")) return args
  // C3b "host": mux-shim is a context server for every account; no account file.
  if (muxShimModeFor("claude") === "host") return args
  if ((await accountMethod(opts.account)) !== "subscription") return args
  const spec = shimSpawnSpec()
  mkdirSync(opts.sessionHome, { recursive: true, mode: 0o700 })
  const file = join(opts.sessionHome, "mcp-account.json")
  writeFileSync(file, JSON.stringify({ mcpServers: { [CLAUDE_SHIM_SERVER]: { type: "stdio", command: spec.shimCommand, args: spec.shimArgs, env: {} } } }, null, 2), { mode: 0o600 })
  const at = args.indexOf("--mcp-config")
  if (at >= 0 && at + 1 < args.length) return [...args.slice(0, at + 2), file, ...args.slice(at + 2)]
  return [...args, "--mcp-config", file]
}
