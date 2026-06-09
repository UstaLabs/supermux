import { Database } from "bun:sqlite"
import { readFileSync, readdirSync, chmodSync, existsSync } from "fs"
import { join } from "path"

export type Db = Database

export function openDb(path: string): Db {
  const isMemory = path === ":memory:"
  const exists = isMemory || existsSync(path)
  const db = new Database(path, { create: true })
  if (!exists) chmodSync(path, 0o600)
  if (!isMemory) db.exec("PRAGMA journal_mode = WAL")
  db.exec("PRAGMA foreign_keys = ON")
  return db
}

export interface Migration {
  version: number
  name: string
  sql: string
}

// Accepts either an embedded manifest (production: travels inside the bundled
// mux-head-bundle.js) or a directory path (tests/dev: read .sql files from the
// source tree). The deployed broker MUST use the embedded form — a bundle has no
// sibling migrations dir to scandir.
export function runMigrations(db: Db, migrations: string | Migration[]): void {
  const list = typeof migrations === "string" ? readMigrationsDir(migrations) : migrations

  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_version (
      version    INTEGER PRIMARY KEY,
      applied_at TEXT NOT NULL
    )
  `)

  const applied = new Set(
    (db.prepare("SELECT version FROM schema_version").all() as Array<{ version: number }>).map((r) => r.version),
  )

  const pending = [...list].sort((a, b) => a.version - b.version).filter((m) => !applied.has(m.version))
  if (pending.length === 0) return

  // SQLite's documented table-rebuild procedure (sqlite.org/lang_altertable.html)
  // requires foreign_keys to be OFF *before* the transaction begins. A migration that
  // rebuilds a referenced table (DROP TABLE performs an implicit row-delete) otherwise
  // leaves the deferred-FK violation counter stuck and COMMIT fails with "FOREIGN KEY
  // constraint failed" even when the data is consistent. defer_foreign_keys inside the
  // txn does NOT clear that counter, and foreign_keys can only be toggled outside a
  // transaction — so we toggle it here and verify integrity with foreign_key_check after.
  const fkWasOn = (db.prepare("PRAGMA foreign_keys").get() as { foreign_keys: number }).foreign_keys === 1
  if (fkWasOn) db.exec("PRAGMA foreign_keys = OFF")
  try {
    for (const { version, sql } of pending) {
      const tx = db.transaction(() => {
        db.exec(sql)
        db.prepare("INSERT INTO schema_version(version, applied_at) VALUES (?, datetime('now'))").run(version)
      })
      tx()
    }

    const violations = db.prepare("PRAGMA foreign_key_check").all()
    if (violations.length > 0) {
      throw new Error(`migrations left ${violations.length} foreign-key violation(s): ${JSON.stringify(violations.slice(0, 5))}`)
    }
  } finally {
    if (fkWasOn) db.exec("PRAGMA foreign_keys = ON")
  }
}

function readMigrationsDir(dir: string): Migration[] {
  return readdirSync(dir)
    .filter((f) => /^\d{3}_.*\.sql$/.test(f))
    .sort()
    .map((file) => ({
      version: parseInt(file.slice(0, 3), 10),
      name: file.replace(/\.sql$/, ""),
      sql: readFileSync(join(dir, file), "utf8"),
    }))
}
