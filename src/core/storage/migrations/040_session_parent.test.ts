import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("040 adds sessions.parent_session_id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(sessions)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toContain("parent_session_id")
})
