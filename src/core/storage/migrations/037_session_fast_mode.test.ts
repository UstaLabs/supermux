import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { SessionStore } from "../../session-manager/session-store"

test("037 adds sessions.fast_mode and the store round-trips it", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ name: "fast", agent: "claude", workdir: "/w", pid: 0 })
  expect(s.fastMode).toBe(false)
  store.setFastMode(s.id, true)
  expect(new SessionStore(db).getById(s.id)?.fastMode).toBe(true)
  store.setFastMode(s.id, false)
  expect(new SessionStore(db).getById(s.id)?.fastMode).toBe(false)
})
