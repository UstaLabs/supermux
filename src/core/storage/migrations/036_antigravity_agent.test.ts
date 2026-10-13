import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { SessionStore } from "../../session-manager/session-store"

const COLS = ["id","name","status","agent","workdir","model","mute","can_orchestrate","tmux_target","agent_session_id","agent_home","created_at","killed_at","base_commit","base_commits","role","is_default","reasoning_level","tmux_window_id","repo_root","base_branch","session_branch","last_read_at","draft","finish_job","internal","self_renamed","user_status","sort_order","draft_payload","workspace_id","prompts","core","permission_mode","account"]

test("036 widens sessions.agent to antigravity, keeping every column, index, CHECK and FK", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 36))
  const schemaBefore = db.query("PRAGMA table_info(sessions)").all()
  db.run("INSERT INTO workspaces (id, name, workdir, layout, created_at) VALUES ('w1','w','/w','{}','t')")
  db.run(`INSERT INTO sessions (${COLS.join(",")}) VALUES ('s1','n','suspended','grok','/w','m',1,1,'tt','asid','/h','t1','t2','bc','{"a":"b"}','personal_assistant',1,'high','@1','/r','main','br','t3','dr','{"k":"fj"}',1,1,'settled',7,'{"text":"x"}','w1',1,1,'ask','acct')`)
  // Every table that FKs into sessions(id) gets a row pointing at s1.
  db.run("UPDATE workspaces SET primary_session_id='s1' WHERE id='w1'")
  db.run("INSERT INTO messages (id, session, ts, direction, channel, chat_id, session_id) VALUES ('m1','n','t','in','web','c1','s1')")
  db.run("INSERT INTO chats (chat_id, active_session_id) VALUES ('c1','s1')")
  db.run("INSERT INTO chat_history (chat_id, session_id, position) VALUES ('c1','s1',0)")
  const before = db.query("SELECT * FROM sessions WHERE id='s1'").get()

  runMigrations(db, MIGRATIONS.filter((m) => m.version === 36))

  expect(db.query("SELECT * FROM sessions WHERE id='s1'").get()).toEqual(before)
  // Same columns, order, types, NOT NULL and defaults as post-035.
  expect(db.query("PRAGMA table_info(sessions)").all()).toEqual(schemaBefore)
  expect((db.query("PRAGMA table_info(sessions)").all() as { name: string }[]).map((c) => c.name)).toEqual(COLS)

  db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at) VALUES ('a1','agy','active','antigravity','/w','t')")
  expect(() => db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at) VALUES ('g1','gem','active','gemini','/w','t')")).toThrow()
  expect(() => db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at,user_status) VALUES ('u1','u','active','claude','/w','t','bogus')")).toThrow()
  expect(() => db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at,role) VALUES ('r1','r','active','claude','/w','t','boss')")).toThrow()
  expect(() => db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at) VALUES ('x1','x','gone','claude','/w','t')")).toThrow()
  expect(() => db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at) VALUES ('d1','agy','active','claude','/w','t')")).toThrow() // unique active name

  const idx = (db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sessions' AND name NOT LIKE 'sqlite_%' ORDER BY name").all() as { name: string }[]).map((r) => r.name)
  expect(idx).toEqual(["sessions_name_active", "sessions_workspace"])
  expect(db.query("PRAGMA foreign_key_list(sessions)").all()).toEqual([expect.objectContaining({ table: "workspaces", from: "workspace_id", to: "id", on_delete: "SET NULL" })])
  expect(db.query("PRAGMA foreign_key_check").all()).toEqual([])

  // Referencing rows survive and still resolve to s1; their FKs still target sessions.
  expect(db.query("SELECT primary_session_id AS s FROM workspaces WHERE id='w1'").get()).toEqual({ s: "s1" })
  expect(db.query("SELECT session_id AS s FROM messages WHERE id='m1'").get()).toEqual({ s: "s1" })
  expect(db.query("SELECT active_session_id AS s FROM chats WHERE chat_id='c1'").get()).toEqual({ s: "s1" })
  expect(db.query("SELECT session_id AS s FROM chat_history WHERE chat_id='c1'").get()).toEqual({ s: "s1" })
  for (const [table, from] of [["messages", "session_id"], ["chats", "active_session_id"], ["chat_history", "session_id"], ["workspaces", "primary_session_id"]]) {
    expect(db.query(`PRAGMA foreign_key_list(${table})`).all()).toContainEqual(expect.objectContaining({ table: "sessions", from, to: "id" }))
  }
  // FK enforcement against the rebuilt table still works.
  expect(() => db.run("INSERT INTO chat_history (chat_id, session_id, position) VALUES ('c1','missing',1)")).toThrow()
  // workspace_id ON DELETE SET NULL still fires.
  db.run("UPDATE workspaces SET primary_session_id=NULL WHERE id='w1'")
  db.run("DELETE FROM workspaces WHERE id='w1'")
  expect(db.query("SELECT workspace_id AS w FROM sessions WHERE id='s1'").get()).toEqual({ w: null })

  const store = new SessionStore(db)
  const s = store.register({ name: "agy2", agent: "antigravity", workdir: "/w", pid: 0 })
  expect(new SessionStore(db).getById(s.id)?.agent).toBe("antigravity")
})
