import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("041 adds the council tables and the council tag columns on messages", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version <= 41))
  const cols = (t: string) => (db.query(`PRAGMA table_info(${t})`).all() as { name: string }[]).map((c) => c.name)
  expect(cols("councils")).toEqual(["id", "name", "caller_session_id", "question", "current_round", "status", "call_id", "created_at"])
  expect(cols("council_members")).toEqual(["council_id", "session_id", "letter"])
  expect(cols("council_rounds")).toEqual(["council_id", "round", "focus", "started_at", "completed_at"])
  expect(cols("council_reports")).toEqual(["id", "council_id", "round", "member_session_id", "seq", "text", "at"])
  expect(cols("council_member_status")).toEqual(["council_id", "round", "member_session_id", "status", "at"])
  expect(cols("messages")).toEqual(expect.arrayContaining(["council_id", "council_round", "council_letter"]))
})
