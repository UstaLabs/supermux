import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { Registry } from "../../session-manager/registry"

test("031 adds prompts column default 0", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','n','active','grok','/w','t')")
  const row = db.query("SELECT prompts FROM sessions WHERE id = 's1'").get() as { prompts: number }
  expect(row.prompts).toBe(0)
})

test("registry get/set prompts", () => {
  const r = new Registry()
  const s = r.register({ name: "ana", workdir: "/w", pid: 1, agent: "grok" })
  expect(s.prompts).toBe(false)
  r.setPrompts(s.id, true)
  expect(r.get(s.id)?.prompts).toBe(true)
})
