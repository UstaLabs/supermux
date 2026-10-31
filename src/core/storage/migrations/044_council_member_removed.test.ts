import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("044 adds council_members.removed_at", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(council_members)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toContain("removed_at")
})
