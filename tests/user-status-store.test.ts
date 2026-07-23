import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { MIGRATIONS } from "../src/core/storage/migrations"

function migratedDb(upTo: number = Infinity): Database {
  const db = new Database(":memory:")
  db.exec("PRAGMA foreign_keys = ON")
  for (const m of MIGRATIONS.filter((m) => m.version <= upTo)) db.exec(m.sql)
  return db
}

test("migration 026 adds user_status/sort_order/draft_payload with defaults", () => {
  const db = migratedDb()
  const cols = (db.query("PRAGMA table_info(sessions)").all() as Array<{ name: string }>).map((c) => c.name)
  expect(cols).toContain("user_status")
  expect(cols).toContain("sort_order")
  expect(cols).toContain("draft_payload")
})

test("migration 026 CHECK rejects an invalid user_status", () => {
  const db = migratedDb()
  expect(() =>
    db.run(
      "INSERT INTO sessions (id,name,status,agent,workdir,created_at,user_status) VALUES ('c1','x','active','claude','/tmp','2026-01-01T00:00:00Z','done')"
    )
  ).toThrow()
})

test("migration 026 backfills existing archived rows to settled", () => {
  const db = migratedDb(25)
  db.run(
    "INSERT INTO sessions (id, name, status, agent, workdir, created_at, killed_at) VALUES ('a1','old','archived','claude','/tmp','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z')"
  )
  const m026 = MIGRATIONS.find((m) => m.version === 26)!
  db.exec(m026.sql)
  const row = db.query("SELECT user_status FROM sessions WHERE id='a1'").get() as { user_status: string }
  expect(row.user_status).toBe("settled")
})

test("migration 026 defaults non-archived rows to in_progress", () => {
  const db = migratedDb(25)
  db.run(
    "INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('b1','live','active','claude','/tmp','2026-01-01T00:00:00Z')"
  )
  const m026 = MIGRATIONS.find((m) => m.version === 26)!
  db.exec(m026.sql)
  const row = db.query("SELECT user_status FROM sessions WHERE id='b1'").get() as { user_status: string }
  expect(row.user_status).toBe("in_progress")
})
