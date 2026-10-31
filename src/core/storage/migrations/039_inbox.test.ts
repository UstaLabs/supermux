import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("039 adds inbox_delivery and messages.from_session_id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(inbox_delivery)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(["session_id", "last_delivered_id", "updated_at"])
  const msgCols = (db.query("PRAGMA table_info(messages)").all() as { name: string }[]).map((c) => c.name)
  expect(msgCols).toContain("from_session_id")
})
