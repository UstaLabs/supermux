import type { Db } from "../storage/db"

/** How far supermux has delivered each inbox (spec §1.3, step 4). */
export class InboxCursorStore {
  constructor(private readonly db: Db) {}

  get(sessionId: string): string | undefined {
    const row = this.db.query("SELECT last_delivered_id FROM inbox_delivery WHERE session_id = ?").get(sessionId) as { last_delivered_id: string } | null
    return row?.last_delivered_id
  }

  set(sessionId: string, messageId: string, now: Date = new Date()): void {
    this.db.run(
      `INSERT INTO inbox_delivery (session_id, last_delivered_id, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(session_id) DO UPDATE SET last_delivered_id = excluded.last_delivered_id, updated_at = excluded.updated_at`,
      [sessionId, messageId, now.toISOString()],
    )
  }
}
