import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"

let tmpDir: string

beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "cmux-db-")) })
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("openDb creates the file with 0o600 perms", () => {
  const db = openDb(join(tmpDir, "test.sqlite3"))
  expect(db).toBeDefined()
  db.close()
  const { statSync } = require("fs")
  const mode = statSync(join(tmpDir, "test.sqlite3")).mode & 0o777
  expect(mode).toBe(0o600)
})

test("runMigrations applies 001_init.sql and tracks schema_version", () => {
  const dbPath = join(tmpDir, "test.sqlite3")
  const db = openDb(dbPath)
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const rows = db.prepare("SELECT version FROM schema_version ORDER BY version ASC").all() as Array<{ version: number }>
  expect(rows.length).toBeGreaterThanOrEqual(1)
  expect(rows[0]!.version).toBe(1)
  db.close()
})

test("runMigrations is idempotent — re-runs apply zero new migrations", () => {
  const dbPath = join(tmpDir, "test.sqlite3")
  const db = openDb(dbPath)
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const firstCount = (db.prepare("SELECT version FROM schema_version").all() as Array<unknown>).length
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const rows = db.prepare("SELECT version FROM schema_version").all() as Array<{ version: number }>
  expect(rows.length).toBe(firstCount)
  expect(rows[0]!.version).toBe(1)
  db.close()
})

test("003 migration creates sessions table", () => {
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").all() as { name: string }[]
  const names = tables.map(t => t.name)
  expect(names).toContain("sessions")
  expect(names).toContain("chats")
  expect(names).toContain("chat_history")
  db.close()
})
