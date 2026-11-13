/** OpenCode's Console sign-in (`opencode console login`, the OpenCode Go browser login), read from its own store.
 *
 * opencode 1.x keeps it in opencode.db (`account` + `account_state`), not auth.json, and every opencode process then
 * loads Zen/Go from the Console with that token. Read-only and synchronous: two tiny rows. */
import { Database } from "bun:sqlite"
import { createHash } from "crypto"
import { existsSync } from "fs"

export interface OpenCodeConsoleLogin {
  accountId: string
  email: string
  /** The Console server, e.g. https://opencode.ai/console. */
  url: string
  accessToken: string
  /** Epoch ms; opencode refreshes the token itself when it runs. */
  tokenExpiry: number | null
  /** The workspace the sign-in chose; Go meters are per workspace. */
  orgId: string | null
}

type Row = { id: string; email: string; url: string; access_token: string; token_expiry: number | null; time_updated: number; active_org_id: string | null }

function activeRow(dbPath: string): Row | null {
  if (!existsSync(dbPath)) return null
  let db: Database | undefined
  try {
    db = new Database(dbPath, { readonly: true })
    return (db.query(
      `SELECT a.id, a.email, a.url, a.access_token, a.token_expiry, a.time_updated, s.active_org_id
         FROM account_state s JOIN account a ON a.id = s.active_account_id LIMIT 1`,
    ).get() as Row | null) ?? null
  } catch {
    // An older opencode store has no account tables; a locked or broken one reads as signed out.
    return null
  } finally {
    db?.close()
  }
}

export function readOpenCodeConsole(dbPath: string): OpenCodeConsoleLogin | null {
  const row = activeRow(dbPath)
  if (!row) return null
  return { accountId: row.id, email: row.email, url: row.url, accessToken: row.access_token, tokenExpiry: row.token_expiry ?? null, orgId: row.active_org_id ?? null }
}

/** Changes with every new sign-in (a new token); null when signed out. Never carries the token itself. */
export function openCodeConsoleFingerprint(dbPath: string): string | null {
  const row = activeRow(dbPath)
  if (!row) return null
  const token = createHash("sha256").update(row.access_token).digest("hex").slice(0, 16)
  return `${row.id}:${row.time_updated}:${token}`
}
