import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { healSessionsWithoutWorkspace } from "./self-heal"

function seed() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

function insertSession(db: any, id: string, name: string, workdir: string, status = "active") {
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES (?, ?, ?, 'claude', ?, '2026-01-01T00:00:00.000Z')`,
    [id, name, status, workdir],
  )
}

test("a live session with no workspace gets one, with a chat view", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "orphan", "/w")

  const healed = healSessionsWithoutWorkspace(db, ws)
  expect(healed).toEqual(["s1"])

  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  const w = ws.getById(link.workspace_id)!
  expect(w).toMatchObject({ name: "orphan", workdir: "/w", primary_session_id: "s1" })
  expect(ws.chatSessionIds(w.id)).toEqual(["s1"])
})

test("a session that already has a workspace is left alone", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "ok", "/w")
  const w = ws.create({ name: "ok", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  db.run("UPDATE sessions SET workspace_id = ? WHERE id = 's1'", [w.id])

  expect(healSessionsWithoutWorkspace(db, ws)).toEqual([])
})

test("a session pointing at a workspace that no longer exists is healed", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "dangling", "/w")
  // sessions.workspace_id REFERENCES workspaces(id); FKs must be off to plant a
  // dangling pointer (the state self-heal repairs after hand-edits or corruption).
  db.exec("PRAGMA foreign_keys = OFF")
  db.run("UPDATE sessions SET workspace_id = 'gone' WHERE id = 's1'")
  db.exec("PRAGMA foreign_keys = ON")

  expect(healSessionsWithoutWorkspace(db, ws)).toEqual(["s1"])
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  expect(ws.getById(link.workspace_id)).toBeDefined()
})

test("archived sessions are not healed", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "old", "/w", "archived")
  expect(healSessionsWithoutWorkspace(db, ws)).toEqual([])
})
