// Card #143-follow-up (release blocker on v0.18.3): a broker restarted after a crash met "database is locked" and
// exited 1. A process still holding db.sqlite3 (a git credential helper, `supermux connect`, WAL recovery) must make
// boot wait, not fail.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "./db"
import { initStorage, isLockedError } from "./open"

const dirs: string[] = []
// Windows releases a closed SQLite file (and its -wal/-shm) a moment late: EBUSY on an immediate rm.
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 }) })
const tmp = () => { const d = mkdtempSync(join(tmpdir(), "storage-open-")); dirs.push(d); return d }

const locked = () => Object.assign(new Error("database is locked"), { code: "SQLITE_BUSY" })

test("a locked database is retried with a growing wait, each retry reported, until it opens", () => {
  let clock = 0
  const waits: number[] = []
  const retries: number[] = []
  let attempts = 0
  const value = initStorage(() => { if (++attempts < 4) throw locked(); return "db" }, {
    now: () => clock,
    sleep: (ms) => { waits.push(ms); clock += ms },
    onRetry: (attempt) => retries.push(attempt),
  })
  expect(value).toBe("db")
  expect(retries).toEqual([1, 2, 3])
  expect(waits).toEqual([250, 500, 1000])
})

test("it gives up once the budget is spent, with the lock error", () => {
  let clock = 0
  expect(() => initStorage(() => { throw locked() }, { now: () => clock, sleep: (ms) => { clock += ms }, onRetry: () => {}, budgetMs: 3_000 }))
    .toThrow("database is locked")
  expect(clock).toBeLessThanOrEqual(3_000 + 5_000)
})

test("any other error fails at once", () => {
  let sleeps = 0
  expect(() => initStorage(() => { throw new Error("malformed") }, { now: () => 0, sleep: () => { sleeps++ }, onRetry: () => {} })).toThrow("malformed")
  expect(sleeps).toBe(0)
})

test("isLockedError knows SQLite's busy and locked errors", () => {
  expect(isLockedError(locked())).toBe(true)
  expect(isLockedError(Object.assign(new Error("x"), { code: "SQLITE_LOCKED" }))).toBe(true)
  expect(isLockedError(new Error("no such table"))).toBe(false)
})

test("openDb waits for another process's write lock instead of failing", async () => {
  const path = join(tmp(), "db.sqlite3")
  openDb(path).close()
  // Another process takes the write lock for 700 ms.
  const holder = Bun.spawn(["bun", "-e", `const { Database } = require("bun:sqlite"); const d = new Database(${JSON.stringify(path)}); d.exec("BEGIN EXCLUSIVE"); console.log("held"); setTimeout(() => { d.exec("COMMIT"); d.close() }, 700)`], { stdout: "pipe" })
  const reader = holder.stdout.getReader()
  await reader.read()
  const db = openDb(path)
  runMigrations(db, [{ version: 1, name: "t", sql: "CREATE TABLE t (x INTEGER)" }])
  expect((db.prepare("SELECT count(*) AS n FROM schema_version").get() as { n: number }).n).toBe(1)
  db.close()
  await holder.exited
})
