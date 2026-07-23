import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SessionStore } from "../src/core/session-manager/session-store"

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
