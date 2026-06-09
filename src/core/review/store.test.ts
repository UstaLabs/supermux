// src/core/review/store.test.ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { ReviewStore, type NewComment } from "./store"

function store() { const db = openDb(":memory:"); runMigrations(db, MIGRATIONS); return new ReviewStore(db) }
const base: NewComment = {
  sessionId: "s1", repo: "", path: "src/a.ts", side: "RIGHT", baseSha: "b", headBlobSha: "h",
  anchorLine: 10, anchorContext: "const x = 1", body: "rename x", author: "user", createdAt: "2026-01-01",
}

test("add + list round-trips a comment", () => {
  const s = store()
  const c = s.add(base)
  expect(c.id).toBeTruthy()
  const all = s.list("s1")
  expect(all).toHaveLength(1)
  expect(all[0]).toMatchObject({ path: "src/a.ts", anchorLine: 10, body: "rename x", status: "open" })
})

test("listOpen filters by status; update changes status", () => {
  const s = store()
  const c = s.add(base)
  s.add({ ...base, body: "second" })
  expect(s.listOpen("s1")).toHaveLength(2)
  s.update(c.id, { status: "resolved", resolvedBy: "user" })
  expect(s.listOpen("s1")).toHaveLength(1)
  expect(s.get(c.id)?.status).toBe("resolved")
})

test("delete removes a comment", () => {
  const s = store()
  const c = s.add(base)
  s.delete(c.id)
  expect(s.list("s1")).toHaveLength(0)
})
