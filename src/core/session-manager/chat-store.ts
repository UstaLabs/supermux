import type { Database } from "bun:sqlite"
import type { SessionStore } from "./session-store"

type CachedChat = {
  active_session_id?: string
  history: string[] // session IDs, most-recent first
}

export class ChatStore {
  private cache = new Map<string, CachedChat>()

  constructor(private readonly db: Database) {
    this.loadFromDb()
  }

  private loadFromDb() {
    const chatRows = this.db.query("SELECT * FROM chats").all() as {
      chat_id: string
      active_session_id: string | null
    }[]
    for (const row of chatRows) {
      const historyRows = this.db
        .query("SELECT session_id FROM chat_history WHERE chat_id = ? ORDER BY position ASC")
        .all(row.chat_id) as { session_id: string }[]
      this.cache.set(row.chat_id, {
        active_session_id: row.active_session_id ?? undefined,
        history: historyRows.map((h) => h.session_id),
      })
    }
  }

  private ensureChat(chat_id: string): CachedChat {
    let c = this.cache.get(chat_id)
    if (!c) {
      c = { history: [] }
      this.cache.set(chat_id, c)
      this.db.run("INSERT OR IGNORE INTO chats (chat_id) VALUES (?)", [chat_id])
    }
    return c
  }

  setActive(chat_id: string, session_id: string): void {
    const c = this.ensureChat(chat_id)
    // Push current active into history (if it differs from new session)
    if (c.active_session_id && c.active_session_id !== session_id) {
      c.history = c.history.filter((id) => id !== c.active_session_id)
      c.history.unshift(c.active_session_id!)
    }
    // Remove the new session_id from history (it's now active, not history)
    c.history = c.history.filter((id) => id !== session_id)
    this.persistHistory(chat_id, c.history)
    c.active_session_id = session_id
    this.db.run(
      "INSERT INTO chats (chat_id, active_session_id) VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET active_session_id = ?",
      [chat_id, session_id, session_id],
    )
  }

  getActive(chat_id: string): string | undefined {
    return this.cache.get(chat_id)?.active_session_id
  }

  getHistory(chat_id: string): string[] {
    return this.cache.get(chat_id)?.history ?? []
  }

  clearActive(chat_id: string): void {
    const c = this.cache.get(chat_id)
    if (!c) return
    c.active_session_id = undefined
    this.db.run("UPDATE chats SET active_session_id = NULL WHERE chat_id = ?", [chat_id])
  }

  activeFallback(chat_id: string, sessions: SessionStore): string | undefined {
    const c = this.cache.get(chat_id)
    if (!c) return undefined
    for (const id of c.history) {
      const s = sessions.getById(id)
      if (s && s.status !== "archived") return id
    }
    return undefined
  }

  removeSessionFromChats(session_id: string): void {
    for (const [chat_id, c] of this.cache) {
      if (c.active_session_id === session_id) {
        c.active_session_id = undefined
        this.db.run("UPDATE chats SET active_session_id = NULL WHERE chat_id = ?", [chat_id])
      }
      const before = c.history.length
      c.history = c.history.filter((id) => id !== session_id)
      if (c.history.length !== before) {
        this.persistHistory(chat_id, c.history)
      }
    }
  }

  allChats(): Map<string, CachedChat> {
    return new Map(this.cache)
  }

  private persistHistory(chat_id: string, history: string[]): void {
    this.db.run("DELETE FROM chat_history WHERE chat_id = ?", [chat_id])
    for (const [i, sessionId] of history.entries()) {
      this.db.run(
        "INSERT INTO chat_history (chat_id, session_id, position) VALUES (?, ?, ?)",
        [chat_id, sessionId, i],
      )
    }
  }
}
