import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { Registry } from "../../session-manager/registry"

test("032 adds core column default 0", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','n','active','claude','/w','t')")
  const row = db.query("SELECT core FROM sessions WHERE id = 's1'").get() as { core: number }
  expect(row.core).toBe(0)
})

test("registry register core flag", () => {
  const r = new Registry()
  const s = r.register({ name: "ana", workdir: "/w", pid: 0, agent: "claude", core: true })
  expect(s.core).toBe(true)
  const t = r.register({ name: "bob", workdir: "/w", pid: 1, agent: "claude" })
  expect(t.core).toBe(false)
})
