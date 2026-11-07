import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("042 adds messages.from_session_name and from_agent; a row from before reads them as null", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 42))
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal) VALUES ('s1', 's1', 'active', 'claude', '/w', 0, 0, '2026-10-30T00:00:00.000Z', 'worker', 0, 0)")
  db.run("INSERT INTO messages (id, session, session_id, ts, direction, channel, chat_id, from_session_id) VALUES ('in:inbox:m1', '', 's1', '2026-10-30T00:00:00.000Z', 'inbound', 'inbox', '', 's0')")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(messages)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(expect.arrayContaining(["from_session_name", "from_agent"]))
  expect(db.query("SELECT from_session_name, from_agent FROM messages WHERE id = 'in:inbox:m1'").get()).toEqual({ from_session_name: null, from_agent: null })
})
