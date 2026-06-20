import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"

let tmpDir: string, db: ReturnType<typeof openDb>
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "amux-rpc-"))
  db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
})
afterEach(() => { try { db.close() } catch {}; rmSync(tmpDir, { recursive: true, force: true }) })

test("listVisible excludes internal sessions", () => {
  const registry = new Registry(db)
  registry.register({ name: "normal", workdir: "/tmp/normal", pid: 1001 })
  registry.register({ name: "worker", workdir: "/tmp/worker", pid: 1002, internal: true })
  expect(registry.list().length).toBe(2)
  expect(registry.listVisible().length).toBe(1)
  expect(registry.listVisible()[0]!.internal).toBe(false)
})
