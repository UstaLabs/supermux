import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"

// Regression: migration 013 rebuilds the sessions table (DROP + rename) to widen a
// CHECK constraint. With foreign_keys ON, DROP TABLE's implicit row-delete bumps
// SQLite's deferred-FK violation counter for child rows in messages/chats/chat_history,
// and nothing clears it — so COMMIT failed with "FOREIGN KEY constraint failed" even
// though the data is consistent (foreign_key_check reports zero violations).
// defer_foreign_keys can't fix this; the runner must set foreign_keys=OFF before BEGIN.
test("rebuilding sessions (013) succeeds while child rows reference it", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version <= 12))

  db.prepare(
    "INSERT INTO sessions(id,name,status,agent,workdir,created_at) VALUES('s1','sess','active','claude','/tmp',datetime('now'))",
  ).run()
  db.prepare(
    "INSERT INTO messages(id,session,ts,direction,channel,chat_id,session_id) VALUES('m1','sess',datetime('now'),'in','web','c1','s1')",
  ).run()
  db.prepare("INSERT INTO chats(chat_id,active_session_id) VALUES('c1','s1')").run()
  db.prepare("INSERT INTO chat_history(chat_id,session_id,position) VALUES('c1','s1',0)").run()

  expect(() => runMigrations(db, MIGRATIONS)).not.toThrow()
  const latest = Math.max(...MIGRATIONS.map((m) => m.version))
  expect((db.prepare("SELECT MAX(version) v FROM schema_version").get() as { v: number }).v).toBe(latest)

  // Child rows survived and still resolve to the rebuilt session.
  expect((db.prepare("SELECT COUNT(*) c FROM messages WHERE session_id='s1'").get() as { c: number }).c).toBe(1)
  expect((db.prepare("SELECT COUNT(*) c FROM chat_history WHERE session_id='s1'").get() as { c: number }).c).toBe(1)

  // The widened CHECK now accepts opencode.
  db.prepare(
    "INSERT INTO sessions(id,name,status,agent,workdir,created_at) VALUES('s2','o','active','opencode','/tmp',datetime('now'))",
  ).run()
  expect((db.prepare("SELECT agent FROM sessions WHERE id='s2'").get() as { agent: string }).agent).toBe("opencode")
})
