import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"

let tmpDir: string
beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "amux-mig-")) })
afterEach(() => rmSync(tmpDir, { recursive: true, force: true }))

test("sessions table has role + is_default columns with correct defaults", () => {
  const db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const cols = (db.query("PRAGMA table_info(sessions)").all() as any[]).map(c => c.name)
  expect(cols).toContain("role")
  expect(cols).toContain("is_default")
  // Real sessions schema: id, name, status, agent, workdir, created_at are required (no defaults)
  db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at) VALUES ('x','w','active','claude','/w',datetime('now'))")
  const row = db.query("SELECT role, is_default FROM sessions WHERE id='x'").get() as any
  expect(row.role).toBe("worker")
  expect(row.is_default).toBe(0)
})

test("backfill promotes a 'dockie' row to personal_assistant default", () => {
  const db = openDb(join(tmpDir, "t2.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  db.run("INSERT INTO sessions (id,name,status,agent,workdir,created_at,role,is_default) VALUES ('d','dockie','active','claude','/h',datetime('now'),'worker',0)")
  db.run("UPDATE sessions SET role='personal_assistant', is_default=1 WHERE name='dockie' AND status != 'archived'")
  const row = db.query("SELECT role, is_default FROM sessions WHERE id='d'").get() as any
  expect(row.role).toBe("personal_assistant")
  expect(row.is_default).toBe(1)
})
