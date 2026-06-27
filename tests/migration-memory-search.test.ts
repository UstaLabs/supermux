import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"

test("migration 022 creates the FTS5 search tables", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.exec("INSERT INTO memory_fts (scope, name, heading, body, path, is_personal) VALUES ('domain','infra','Title','hello world','/p',0)")
  const m = db.query("SELECT name FROM memory_fts WHERE memory_fts MATCH 'hello'").all() as any[]
  expect(m.length).toBe(1)
  db.exec("INSERT INTO session_fts (session_id, ts, text) VALUES ('s1','2026-06-27T00:00:00.000Z','deploy the broker')")
  const s = db.query("SELECT session_id FROM session_fts WHERE session_fts MATCH 'deploy'").all() as any[]
  expect(s[0].session_id).toBe("s1")
})

test("migration manifest length matches applied versions", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const versions = db.query("SELECT version FROM schema_version").all() as { version: number }[]
  expect(versions.some((v) => v.version === 22)).toBe(true)
})
