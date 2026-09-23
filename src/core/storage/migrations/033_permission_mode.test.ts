import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { Registry } from "../../session-manager/registry"

test("033 adds permission_mode and backfills from prompts", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 33))
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at, prompts) VALUES ('g1','g','active','grok','/w','t', 1)")
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at, prompts) VALUES ('c1','c','active','codex','/w','t', 1)")
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at, prompts) VALUES ('d1','d','active','claude','/w','t', 0)")
  runMigrations(db, MIGRATIONS.filter((m) => m.version === 33))
  const grok = db.query("SELECT permission_mode FROM sessions WHERE id = 'g1'").get() as { permission_mode: string | null }
  const codex = db.query("SELECT permission_mode FROM sessions WHERE id = 'c1'").get() as { permission_mode: string | null }
  const claude = db.query("SELECT permission_mode FROM sessions WHERE id = 'd1'").get() as { permission_mode: string | null }
  expect(grok.permission_mode).toBe("ask")
  expect(codex.permission_mode).toBe("on-request+workspace-write")
  expect(claude.permission_mode).toBeNull()
})

test("registry get/set permissionMode", () => {
  const r = new Registry()
  const s = r.register({ name: "ana", workdir: "/w", pid: 1, agent: "grok" })
  expect(s.permissionMode).toBeUndefined()
  r.setPermissionMode(s.id, "ask")
  expect(r.get(s.id)?.permissionMode).toBe("ask")
})
