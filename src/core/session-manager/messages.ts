import type { Db } from "../storage/db"
import type { FileStore } from "../files/store"
import { makeLogger } from "../../shared/log"

const log = makeLogger("core/session-manager/messages")

export interface AttachmentRef {
  file_id: string
  kind: string
  mime?: string
  size?: number
  name?: string
}

export interface Message {
  id: string
  ts: string
  direction: "inbound" | "outbound"
  channel: string
  chat_id: string
  message_id?: string
  op?: "reply" | "react" | "edit_message"
  text?: string
  edited_at?: string
  attachments?: AttachmentRef[]
  reactions?: Array<{ emoji: string; ts: string }>
}

type AppendL  = (sessionId: string, entry: Message) => void
type UpdateL  = (sessionId: string, entry_id: string, patch: { text?: string; edited_at?: string }) => void
type ReactL   = (sessionId: string, entry_id: string, emoji: string, ts: string) => void
type RemoveL  = (sessionId: string) => void

export class MessageStore {
  private appendListeners: AppendL[] = []
  private updateListeners: UpdateL[] = []
  private reactListeners:  ReactL[] = []
  private removeListeners: RemoveL[] = []

  constructor(private readonly db: Db, private readonly fileStore?: FileStore) {}

  on(event: "append", h: AppendL): void
  on(event: "update", h: UpdateL): void
  on(event: "reaction", h: ReactL): void
  on(event: "remove", h: RemoveL): void
  on(event: string, h: any): void {
    if (event === "append") this.appendListeners.push(h)
    else if (event === "update") this.updateListeners.push(h)
    else if (event === "reaction") this.reactListeners.push(h)
    else if (event === "remove") this.removeListeners.push(h)
  }

  append(sessionId: string, entry: Message): void {
    this.db.prepare(`
      INSERT INTO messages (id, session, session_id, ts, direction, channel, chat_id, message_id, op, text, edited_at, attachments, reactions)
      VALUES (?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      entry.id,
      sessionId,
      entry.ts,
      entry.direction,
      entry.channel,
      entry.chat_id,
      entry.message_id ?? null,
      entry.op ?? null,
      entry.text ?? null,
      entry.edited_at ?? null,
      entry.attachments ? JSON.stringify(entry.attachments) : null,
      entry.reactions ? JSON.stringify(entry.reactions) : null,
    )
    // Bump ref_count for each attachment so the hourly GC sweep doesn't reap a
    // file that's still referenced by this row. fileStore is optional (tests
    // that don't exercise attachment lifecycle skip wiring it). bumpRef is
    // async by signature, but bun:sqlite is synchronous under the hood — the
    // UPDATE has already landed by the time the promise settles, so .catch()
    // is just promise-rejection bookkeeping, not an ordering concern.
    if (this.fileStore && entry.attachments?.length) {
      for (const a of entry.attachments) {
        this.fileStore.bumpRef(a.file_id).catch((err) =>
          log.error("messages_bumpref_failed", { file_id: a.file_id, err: err?.message ?? String(err) }),
        )
      }
    }
    for (const h of this.appendListeners) h(sessionId, entry)
  }

  get(sessionId: string, limit: number = 200): Message[] {
    // Fetch the newest `limit` rows (DESC), then reverse so the caller sees
    // them in ascending (oldest-first) order — same shape as the old in-memory
    // ring. This is what makes `get(s, 1)` return the newest single entry
    // rather than the oldest.
    const rows = this.db.prepare(`
      SELECT * FROM (
        SELECT rowid AS _rowid, * FROM messages
        WHERE session_id = ?
        ORDER BY ts DESC, rowid DESC
        LIMIT ?
      ) ORDER BY ts ASC, _rowid ASC
    `).all(sessionId, limit) as any[]
    return rows.map(rowToMessage)
  }

  update(sessionId: string, entry_id: string, patch: { text?: string; edited_at?: string }): boolean {
    const sets: string[] = []
    const args: any[] = []
    if (patch.text !== undefined)      { sets.push("text = ?");      args.push(patch.text) }
    if (patch.edited_at !== undefined) { sets.push("edited_at = ?"); args.push(patch.edited_at) }
    if (sets.length === 0) return false
    args.push(entry_id, sessionId)
    const info = this.db.prepare(`UPDATE messages SET ${sets.join(", ")} WHERE id = ? AND session_id = ?`).run(...args)
    if (info.changes === 0) return false
    for (const h of this.updateListeners) h(sessionId, entry_id, patch)
    return true
  }

  addReaction(sessionId: string, entry_id: string, emoji: string, ts: string): boolean {
    const row = this.db.prepare("SELECT reactions FROM messages WHERE id = ? AND session_id = ?").get(entry_id, sessionId) as { reactions: string | null } | undefined
    if (!row) return false
    const arr = row.reactions ? JSON.parse(row.reactions) : []
    arr.push({ emoji, ts })
    this.db.prepare("UPDATE messages SET reactions = ? WHERE id = ? AND session_id = ?").run(JSON.stringify(arr), entry_id, sessionId)
    for (const h of this.reactListeners) h(sessionId, entry_id, emoji, ts)
    return true
  }

  removeSession(sessionId: string): void {
    // Collect attachment file_ids BEFORE the DELETE so we can decrement
    // ref_count for each. Without this, files referenced only by this
    // session's messages would linger at ref_count > 0 forever (GC skips
    // anything ref_count > 0) — a slow but real leak.
    let referencedFileIds: string[] = []
    if (this.fileStore) {
      const rows = this.db.prepare("SELECT attachments FROM messages WHERE session_id = ? AND attachments IS NOT NULL").all(sessionId) as Array<{ attachments: string | null }>
      for (const row of rows) {
        if (!row.attachments) continue
        try {
          const arr = JSON.parse(row.attachments) as AttachmentRef[]
          for (const a of arr) if (a?.file_id) referencedFileIds.push(a.file_id)
        } catch (err: any) {
          log.warn("messages_remove_parse_attachments_failed", { sessionId, err: err?.message ?? String(err) })
        }
      }
    }
    this.db.prepare("DELETE FROM messages WHERE session_id = ?").run(sessionId)
    // Fire-and-forget release for each referenced file_id (same sync-under-the-
    // hood reasoning as append's bumpRef — bun:sqlite settles synchronously).
    if (this.fileStore && referencedFileIds.length > 0) {
      for (const file_id of referencedFileIds) {
        this.fileStore.release(file_id).catch((err) =>
          log.error("messages_release_failed", { file_id, err: err?.message ?? String(err) }),
        )
      }
    }
    for (const h of this.removeListeners) h(sessionId)
  }

  allSessions(): string[] {
    const rows = this.db.prepare("SELECT DISTINCT session_id FROM messages WHERE session_id IS NOT NULL").all() as Array<{ session_id: string }>
    return rows.map((r) => r.session_id)
  }

  /** ISO timestamp of the newest message in a session, or null if it has none. */
  newestTs(sessionId: string): string | null {
    const row = this.db.prepare(
      "SELECT ts FROM messages WHERE session_id = ? ORDER BY ts DESC, rowid DESC LIMIT 1"
    ).get(sessionId) as { ts: string } | undefined
    return row?.ts ?? null
  }
}

function rowToMessage(row: any): Message {
  return {
    id: row.id,
    ts: row.ts,
    direction: row.direction,
    channel: row.channel,
    chat_id: row.chat_id,
    message_id: row.message_id ?? undefined,
    op: row.op ?? undefined,
    text: row.text ?? undefined,
    edited_at: row.edited_at ?? undefined,
    attachments: row.attachments ? JSON.parse(row.attachments) : undefined,
    reactions: row.reactions ? JSON.parse(row.reactions) : undefined,
  }
}
