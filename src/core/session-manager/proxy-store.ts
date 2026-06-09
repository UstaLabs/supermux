import type { Database } from "bun:sqlite"

/**
 * A persisted proxy as stored on disk. The owning session is identified by its
 * UUID (`sessionId`) — never its name — so renames are a non-event and a reused
 * name can't hijack a proxy. The human-readable session name is derived by the
 * Registry at read time.
 */
export type StoredProxy = {
  domain: string
  sessionId: string
  port: number
  createdAt: string
  isPublic: boolean
}

type ProxyRow = {
  domain: string
  session_id: string
  port: number
  created_at: string
  is_public: number
}

/**
 * Write-through store for exposed proxies: an in-memory cache backed by the
 * `proxies` SQLite table. Mirrors the SessionStore/ChatStore pattern — the cache
 * is the working set, every mutation also hits the DB, so the table can never
 * drift. Business rules (domain validation, per-session caps, name resolution)
 * live in the Registry; this class is pure storage.
 */
export class ProxyStore {
  private cache = new Map<string, StoredProxy>()

  constructor(private readonly db: Database) {
    this.loadFromDb()
  }

  private loadFromDb() {
    const rows = this.db.query("SELECT * FROM proxies").all() as ProxyRow[]
    for (const row of rows) {
      this.cache.set(row.domain, {
        domain: row.domain,
        sessionId: row.session_id,
        port: row.port,
        createdAt: row.created_at,
        isPublic: row.is_public !== 0,
      })
    }
  }

  /** Upsert a proxy (cache + DB). Keyed by domain. */
  put(entry: StoredProxy): void {
    this.cache.set(entry.domain, entry)
    this.db.run(
      `INSERT INTO proxies (domain, session_id, port, created_at, is_public) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(domain) DO UPDATE SET
         session_id = excluded.session_id,
         port = excluded.port,
         is_public = excluded.is_public`,
      [entry.domain, entry.sessionId, entry.port, entry.createdAt, entry.isPublic ? 1 : 0],
    )
  }

  remove(domain: string): void {
    this.cache.delete(domain)
    this.db.run("DELETE FROM proxies WHERE domain = ?", [domain])
  }

  /** Remove every proxy owned by a session. Returns the removed domains. */
  removeForSession(sessionId: string): string[] {
    const removed = [...this.cache.values()]
      .filter((e) => e.sessionId === sessionId)
      .map((e) => e.domain)
    for (const d of removed) this.cache.delete(d)
    if (removed.length > 0) {
      this.db.run("DELETE FROM proxies WHERE session_id = ?", [sessionId])
    }
    return removed
  }

  get(domain: string): StoredProxy | undefined {
    return this.cache.get(domain)
  }

  list(): StoredProxy[] {
    return [...this.cache.values()]
  }

  countForSession(sessionId: string): number {
    return [...this.cache.values()].filter((e) => e.sessionId === sessionId).length
  }
}
