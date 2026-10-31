import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("043 adds ended_by, verdict and ended_at, and ends every council from before", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 43))
  db.run("INSERT INTO councils (id, name, caller_session_id, question, current_round, status, created_at) VALUES ('c1', 'council-1', 's1', 'Q', 1, 'open', '2026-10-30T00:00:00.000Z')")
  runMigrations(db, MIGRATIONS)
  const row = db.query("SELECT status, ended_by, verdict, ended_at FROM councils WHERE id = 'c1'").get() as Record<string, string | null>
  expect(row).toMatchObject({ status: "ended", ended_by: "empty", verdict: null })
  expect(row.ended_at).toMatch(/^\d{4}-\d\d-\d\dT/)
})
