import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { propagateSessionRename, repointPrimarySession } from "./name"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

/** primary_session_id REFERENCES sessions(id); seed the named session(s) first. */
function seedSessions(db: ReturnType<typeof openDb>, ...ids: string[]) {
  for (const id of ids) {
    db.run(
      `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
       VALUES (?, ?, 'active', 'claude', '/w', '2026-01-01T00:00:00.000Z')`,
      [id, id],
    )
  }
}

test("renaming the primary session renames the workspace and reports the id", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s1", "New Title")).toBe(w.id)
  expect(ws.getById(w.id)!.name).toBe("New Title")
})

test("renaming a session that is not primary changes nothing", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s2", "New Title")).toBeUndefined()
  expect(ws.getById(w.id)!.name).toBe("old")
})

test("a locked name is never propagated", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })
  ws.rename(w.id, "user chose this", { byUser: true })

  expect(propagateSessionRename(ws, "s1", "agent tried")).toBeUndefined()
  expect(ws.getById(w.id)!.name).toBe("user chose this")
})

test("an equal name writes nothing — this is the loop guard", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "same", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s1", "same")).toBeUndefined()
})

test("propagation does not set name_locked", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })
  propagateSessionRename(ws, "s1", "agent name")

  expect(ws.getById(w.id)!.name_locked).toBe(false)
})

test("repointPrimarySession moves the pointer to the oldest remaining chat session", () => {
  const { db, ws } = store()
  seedSessions(db, "s1", "s2", "s3")
  const w = ws.create({ name: "n", workdir: "/w", primary_session_id: "s1" })
  const v2 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })
  const v3 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s3" } })
  // Force distinct created_at so listViews order is insertion order even when
  // both addView calls land in the same millisecond (ORDER BY created_at, id).
  db.run("UPDATE views SET created_at = '2026-01-01T00:00:01.000Z' WHERE id = ?", [v2.id])
  db.run("UPDATE views SET created_at = '2026-01-01T00:00:02.000Z' WHERE id = ?", [v3.id])

  expect(repointPrimarySession(ws, w.id)).toBe("s2")
  expect(ws.getById(w.id)!.primary_session_id).toBe("s2")
})

test("repointPrimarySession does not change the name", () => {
  const { db, ws } = store()
  seedSessions(db, "s1", "s2")
  const w = ws.create({ name: "keep me", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })

  repointPrimarySession(ws, w.id)
  expect(ws.getById(w.id)!.name).toBe("keep me")
})

test("repointPrimarySession clears the pointer when no chat view is left", () => {
  const { db, ws } = store()
  seedSessions(db, "s1")
  const w = ws.create({ name: "n", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t" } })

  expect(repointPrimarySession(ws, w.id)).toBeUndefined()
  expect(ws.getById(w.id)!.primary_session_id).toBeUndefined()
})
