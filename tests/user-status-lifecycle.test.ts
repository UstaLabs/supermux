import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SessionStore } from "../src/core/session-manager/session-store"
import { isDraftSession } from "../src/core/session-manager/supervisor"

function store(): SessionStore {
  const db = new Database(":memory:")
  db.exec("PRAGMA foreign_keys = ON")
  for (const m of MIGRATIONS) db.exec(m.sql)
  return new SessionStore(db)
}

test("archive marks the row settled", () => {
  const s = store()
  const sess = s.register({ name: "l1", agent: "claude", workdir: "/tmp", pid: 5 })
  s.archive(sess.id)
  const rec = s.getById(sess.id)!
  expect(rec.status).toBe("archived")
  expect(rec.user_status).toBe("settled")
})

test("resume restores user_status to in_progress", () => {
  const s = store()
  const sess = s.register({ name: "l2", agent: "claude", workdir: "/tmp", pid: 5 })
  s.archive(sess.id)
  s.resume(sess.id, "l2", 99)
  const rec = s.getById(sess.id)!
  expect(rec.status).toBe("active")
  expect(rec.user_status).toBe("in_progress")
})

test("isDraftSession identifies drafts the reconcile loop must skip", () => {
  const s = store()
  const draft = s.register({ name: "sd1", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft" })
  const live = s.register({ name: "sd2", agent: "claude", workdir: "/tmp", pid: 7 })
  expect(isDraftSession(s.getById(draft.id)!)).toBe(true)
  expect(isDraftSession(s.getById(live.id)!)).toBe(false)
})

test("deleteById removes a draft row entirely (no archived ghost)", () => {
  const s = store()
  const d = s.register({ name: "m1", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft", draft_payload: { text: "go" } })
  s.deleteById(d.id)
  expect(s.getById(d.id)).toBeUndefined()
})
