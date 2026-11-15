import type { Database } from "bun:sqlite"
import { randomUUID } from "crypto"

/**
 * #117: an applet as the broker lists it. `id` is stable; `name` is the folder and the /a/<name>/ route. `status`:
 *   ok        the folder and its applet.json are fine
 *   invalid   applet.json is missing or wrong (the reason is in the supervisor's state, not here)
 *   missing   the folder is gone (kept: only deleting an applet removes it)
 * There is no session id: an applet belongs to the host.
 */
export interface AppletRow {
  id: string
  name: string
  title: string
  icon: string | null
  status: string
  updatedBy: string | null
  updatedAt: string
  createdAt: string
  deletedAt: string | null
  /** Set while the owner has stopped it: no server, no jobs. */
  stoppedAt: string | null
}

type Raw = {
  id: string; name: string; title: string; icon: string | null; status: string
  updated_by: string | null; updated_at: string; created_at: string; deleted_at: string | null; stopped_at: string | null
}

const fromRaw = (r: Raw): AppletRow => ({
  id: r.id, name: r.name, title: r.title, icon: r.icon, status: r.status,
  updatedBy: r.updated_by, updatedAt: r.updated_at, createdAt: r.created_at, deletedAt: r.deleted_at, stoppedAt: r.stopped_at ?? null,
})

/** Write-through storage for the `applets` table. Rules (names, scanning) live in the registrar; this is pure storage. */
export class AppletStore {
  constructor(private readonly db: Database, private readonly now: () => Date = () => new Date()) {}

  /** Every applet that is not deleted, by name. */
  list(): AppletRow[] {
    return (this.db.query("SELECT * FROM applets WHERE deleted_at IS NULL ORDER BY name").all() as Raw[]).map(fromRaw)
  }

  byName(name: string): AppletRow | undefined {
    const r = this.db.query("SELECT * FROM applets WHERE name = ? AND deleted_at IS NULL").get(name) as Raw | null
    return r ? fromRaw(r) : undefined
  }

  byId(id: string): AppletRow | undefined {
    const r = this.db.query("SELECT * FROM applets WHERE id = ?").get(id) as Raw | null
    return r ? fromRaw(r) : undefined
  }

  insert(a: { name: string; title: string; icon?: string | null; status?: string; updatedBy?: string | null }): AppletRow {
    const ts = this.now().toISOString()
    const id = randomUUID()
    this.db.query(
      "INSERT INTO applets (id, name, title, icon, status, updated_by, updated_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ).run(id, a.name, a.title, a.icon ?? null, a.status ?? "ok", a.updatedBy ?? null, ts, ts)
    return this.byId(id)!
  }

  /** Changes what the folder says (title, icon, status). Returns whether anything changed; unchanged rows keep updatedAt. */
  update(id: string, p: { title?: string; icon?: string | null; status?: string; updatedBy?: string | null }): boolean {
    const cur = this.byId(id)
    if (!cur) return false
    const next = { title: p.title ?? cur.title, icon: p.icon !== undefined ? p.icon : cur.icon, status: p.status ?? cur.status }
    if (next.title === cur.title && next.icon === cur.icon && next.status === cur.status) return false
    this.db.query("UPDATE applets SET title = ?, icon = ?, status = ?, updated_by = ?, updated_at = ? WHERE id = ?")
      .run(next.title, next.icon, next.status, p.updatedBy !== undefined ? p.updatedBy : cur.updatedBy, this.now().toISOString(), id)
    return true
  }

  /** Stops (or starts again) the applet: host state, kept across broker restarts. */
  setStopped(id: string, stopped: boolean, updatedBy: string | null): void {
    const ts = this.now().toISOString()
    this.db.query("UPDATE applets SET stopped_at = ?, updated_by = ?, updated_at = ? WHERE id = ?").run(stopped ? ts : null, updatedBy, ts, id)
  }

  /** Records who changed the applet, now. */
  touch(id: string, updatedBy: string | null): void {
    this.db.query("UPDATE applets SET updated_by = ?, updated_at = ? WHERE id = ?").run(updatedBy, this.now().toISOString(), id)
  }

  rename(id: string, name: string, updatedBy: string | null): void {
    this.db.query("UPDATE applets SET name = ?, updated_by = ?, updated_at = ? WHERE id = ?").run(name, updatedBy, this.now().toISOString(), id)
  }

  /** Deleted applets (kept 30 days), oldest first. */
  listDeleted(): AppletRow[] {
    return (this.db.query("SELECT * FROM applets WHERE deleted_at IS NOT NULL ORDER BY deleted_at").all() as Raw[]).map(fromRaw)
  }

  undelete(id: string, updatedBy: string | null): void {
    this.db.query("UPDATE applets SET deleted_at = NULL, updated_by = ?, updated_at = ? WHERE id = ?").run(updatedBy, this.now().toISOString(), id)
  }

  /** The end of the 30 days: the row is gone for good. */
  purge(id: string): void {
    this.db.query("DELETE FROM applets WHERE id = ?").run(id)
  }

  /** Soft delete: the row (and the folder, moved aside) are kept for 30 days. */
  softDelete(id: string, updatedBy: string | null): void {
    const ts = this.now().toISOString()
    this.db.query("UPDATE applets SET deleted_at = ?, updated_by = ?, updated_at = ? WHERE id = ?").run(ts, updatedBy, ts, id)
  }
}
