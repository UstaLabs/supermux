import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"; import { tmpdir } from "os"; import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"

let tmpDir: string, db: ReturnType<typeof openDb>
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "amux-internal-"))
  db = openDb(join(tmpDir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
})
afterEach(() => { try { db.close() } catch {}; rmSync(tmpDir, { recursive: true, force: true }) })

test("sessions has an internal column defaulting to 0", () => {
  const cols = db.query("PRAGMA table_info(sessions)").all() as Array<{ name: string }>
  expect(cols.some(c => c.name === "internal")).toBe(true)
})
