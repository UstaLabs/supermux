import type { Database } from "bun:sqlite"

/**
 * #117: an applet's secret values and settings values, the host state applet.json only describes. Secrets reach the
 * applet's server and jobs as environment variables; nothing here hands a value to an agent. They are kept out of the
 * folder and out of git, which is the point: an agent on the same Unix user could still dig one out of the database,
 * so this is not a wall.
 */

export interface SecretStatus {
  name: string
  /** set: has a value · missing: applet.json asks for it and there is none · expired: its expiry has passed. */
  state: "set" | "missing" | "expired"
  expiresAt: string | null
  updatedAt: string | null
  updatedBy: string | null
}

type SecretRaw = { name: string; value: string; expires_at: string | null; updated_by: string | null; updated_at: string }

export class AppletSecrets {
  constructor(private readonly db: Database, private readonly now: () => Date = () => new Date()) {}

  set(appletId: string, name: string, value: string, opts: { expiresAt?: string | null; updatedBy?: string | null } = {}): void {
    this.db.query(
      `INSERT INTO applet_secrets (applet_id, name, value, expires_at, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(applet_id, name) DO UPDATE SET value = excluded.value, expires_at = excluded.expires_at,
       updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
    ).run(appletId, name, value, opts.expiresAt ?? null, opts.updatedBy ?? null, this.now().toISOString())
  }

  remove(appletId: string, name: string): boolean {
    return this.db.query("DELETE FROM applet_secrets WHERE applet_id = ? AND name = ?").run(appletId, name).changes > 0
  }

  /** The environment the server and jobs get: every stored value, expired ones too (the applet may still try). */
  env(appletId: string): Record<string, string> {
    const rows = this.db.query("SELECT name, value FROM applet_secrets WHERE applet_id = ?").all(appletId) as { name: string; value: string }[]
    return Object.fromEntries(rows.map((r) => [r.name, r.value]))
  }

  /** What applet.json asks for, and what is stored besides, with no values. */
  status(appletId: string, wanted: string[]): SecretStatus[] {
    const rows = this.db.query("SELECT name, value, expires_at, updated_by, updated_at FROM applet_secrets WHERE applet_id = ?").all(appletId) as SecretRaw[]
    const byName = new Map(rows.map((r) => [r.name, r]))
    const now = this.now().getTime()
    const names = [...wanted, ...rows.map((r) => r.name).filter((n) => !wanted.includes(n)).sort()]
    return names.map((name) => {
      const r = byName.get(name)
      if (!r) return { name, state: "missing", expiresAt: null, updatedAt: null, updatedBy: null }
      const expired = r.expires_at !== null && Date.parse(r.expires_at) <= now
      return { name, state: expired ? "expired" : "set", expiresAt: r.expires_at, updatedAt: r.updated_at, updatedBy: r.updated_by }
    })
  }

  settings(appletId: string): Record<string, string> {
    const rows = this.db.query("SELECT key, value FROM applet_settings WHERE applet_id = ?").all(appletId) as { key: string; value: string }[]
    return Object.fromEntries(rows.map((r) => [r.key, r.value]))
  }

  setSetting(appletId: string, key: string, value: string, updatedBy: string | null): void {
    this.db.query(
      `INSERT INTO applet_settings (applet_id, key, value, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(applet_id, key) DO UPDATE SET value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
    ).run(appletId, key, value, updatedBy, this.now().toISOString())
  }

  /** The purge of a deleted applet: its values go with it. */
  forget(appletId: string): void {
    this.db.query("DELETE FROM applet_secrets WHERE applet_id = ?").run(appletId)
    this.db.query("DELETE FROM applet_settings WHERE applet_id = ?").run(appletId)
  }
}
