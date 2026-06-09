import type { Database } from "bun:sqlite"

export type MsgRow = {
  session_id: string
  session: string // resolved session name (joined), may be empty
  workdir: string // the project path the session ran in — the "where" of the chat
  chat_id: string
  direction: string
  text: string
  ts: string
}

/**
 * All non-empty messages at or after `sinceIso`, joined to their session so each
 * carries the session name + workdir (project path). ISO timestamps sort
 * lexicographically. Ordered by session_id then ts so a conversation stays
 * together even when many sessions share one (web) chat_id.
 */
export function queryLast24h(db: Database, sinceIso: string): MsgRow[] {
  return db
    .query(
      `SELECT COALESCE(m.session_id,'')                AS session_id,
              COALESCE(NULLIF(m.session,''), se.name, '') AS session,
              COALESCE(se.workdir,'')                  AS workdir,
              COALESCE(m.chat_id,'')                   AS chat_id,
              m.direction,
              COALESCE(m.text,'')                      AS text,
              m.ts
         FROM messages m
         LEFT JOIN sessions se ON se.id = m.session_id
        WHERE m.ts >= ? AND m.text IS NOT NULL AND m.text != ''
        ORDER BY session_id, m.ts`,
    )
    .all(sinceIso) as MsgRow[]
}

/**
 * Render rows as a readable transcript grouped by the real session (not the
 * shared chat_id), labeling each block with its workdir so the curator can
 * attribute knowledge to the right project/domain.
 */
export function formatLast24h(rows: MsgRow[]): string {
  if (!rows.length) return "(no messages in the window)"
  const groups = new Map<string, MsgRow[]>()
  for (const r of rows) {
    const key = r.session_id || r.chat_id || "(unknown)"
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(r)
  }
  const out: string[] = []
  for (const msgs of groups.values()) {
    const head = msgs[0]!
    const label = head.session || head.chat_id || "(unknown)"
    const where = head.workdir ? `workdir: ${head.workdir}` : `chat: ${head.chat_id || "?"}`
    out.push(`\n=== session: ${label} · ${where} (${msgs.length} msgs) ===`)
    for (const m of msgs) {
      const who = m.direction === "inbound" ? "USER" : "AGENT"
      out.push(`[${m.ts}] ${who}: ${m.text}`)
    }
  }
  return out.join("\n")
}
