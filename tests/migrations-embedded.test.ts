import { test, expect } from "bun:test"
import { join } from "path"
import { readdirSync } from "fs"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"

const DIR = join(import.meta.dir, "../src/core/storage/migrations")

function tableNames(db: ReturnType<typeof openDb>): string[] {
  return (db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all() as Array<{ name: string }>)
    .map((r) => r.name)
}

// Regression: the deployed broker runs as a single bundled file (mux-head-bundle.js).
// Migrations must travel *inside* the bundle, not be read from a sibling dir via
// import.meta.dir — that path doesn't exist next to the bundle and crashed boot
// with storage_init_failed (ENOENT scandir core/storage/migrations).
test("MIGRATIONS is an embedded manifest carrying SQL text, not filesystem paths", () => {
  expect(Array.isArray(MIGRATIONS)).toBe(true)
  expect(MIGRATIONS.length).toBeGreaterThan(0)
  for (const m of MIGRATIONS) {
    expect(typeof m.version).toBe("number")
    expect(m.sql.length).toBeGreaterThan(0)
    expect(m.sql).toMatch(/\b(CREATE|ALTER|INSERT|UPDATE|PRAGMA)\b/i) // actual SQL, not a path
  }
})

test("embedded manifest covers every .sql file on disk", () => {
  const onDisk = readdirSync(DIR).filter((f) => /^\d{3}_.*\.sql$/.test(f)).length
  expect(MIGRATIONS.length).toBe(onDisk)
})

test("running embedded migrations produces the same schema as reading from the dir", () => {
  const embedded = openDb(":memory:")
  runMigrations(embedded, MIGRATIONS)

  const fromDir = openDb(":memory:")
  runMigrations(fromDir, DIR)

  expect(tableNames(embedded)).toEqual(tableNames(fromDir))

  const maxVersion = Math.max(...MIGRATIONS.map((m) => m.version))
  const applied = (embedded.prepare("SELECT MAX(version) AS v FROM schema_version").get() as { v: number }).v
  expect(applied).toBe(maxVersion)
})
