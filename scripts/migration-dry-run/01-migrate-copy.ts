/**
 * Migration dry run, step 1: run THIS branch's migrations on a COPY of the live DB.
 *
 *   sqlite3 -readonly ~/.mux/state/db.sqlite3 ".backup '<dir>/db-live-snapshot.sqlite3'"
 *   bun scripts/migration-dry-run/01-migrate-copy.ts <dir>
 *
 * Copies <dir>/db-live-snapshot.sqlite3 to <dir>/db.sqlite3 (the snapshot itself stays untouched),
 * prints row counts by agent / status / core, runs the broker's own runMigrations(MIGRATIONS)
 * (the embedded manifest main.ts uses), prints the counts again, the applied versions, and what
 * the schema stamp check would say for the live stamp. Never opens anything under ~/.mux/state.
 */
import { copyFileSync, existsSync, readFileSync, rmSync } from "node:fs"
import { join, resolve } from "node:path"
import { homedir } from "node:os"
import { Database } from "bun:sqlite"
import { openDb, runMigrations } from "../../src/core/storage/db"
import { MIGRATIONS } from "../../src/core/storage/migrations"

const dir = resolve(process.argv[2] ?? join(homedir(), ".cache", "migration-dry"))
const liveState = join(homedir(), ".mux", "state")
if (dir.startsWith(liveState)) throw new Error("refusing to work inside ~/.mux/state")
const snapshot = join(dir, "db-live-snapshot.sqlite3")
const work = join(dir, "db.sqlite3")
if (!existsSync(snapshot)) throw new Error(`no snapshot at ${snapshot}`)
for (const f of [work, `${work}-wal`, `${work}-shm`]) rmSync(f, { force: true })
copyFileSync(snapshot, work)

function counts(db: Database): Array<Record<string, unknown>> {
  const hasCore = (db.prepare("PRAGMA table_info(sessions)").all() as Array<{ name: string }>).some((c) => c.name === "core")
  return db.prepare(
    `SELECT agent, status, ${hasCore ? "core" : "'(no column)'"} AS core, count(*) AS n FROM sessions GROUP BY 1, 2, 3 ORDER BY 1, 2, 3`,
  ).all() as Array<Record<string, unknown>>
}
function versions(db: Database): number[] {
  return (db.prepare("SELECT version FROM schema_version ORDER BY version").all() as Array<{ version: number }>).map((r) => r.version)
}
function sessionColumns(db: Database): string[] {
  return (db.prepare("PRAGMA table_info(sessions)").all() as Array<{ name: string }>).map((c) => c.name)
}
function fingerprint(db: Database): string {
  // Every pre-existing column of every session row, to prove the migration changed no existing value.
  const rows = db.prepare("SELECT id, name, status, agent, agent_session_id, agent_home, tmux_window_id, user_status, workdir, role FROM sessions ORDER BY id").all()
  return String(Bun.hash(JSON.stringify(rows)))
}

const before = new Database(work)
const beforeCounts = counts(before)
const beforeVersions = versions(before)
const beforeCols = sessionColumns(before)
const beforeFp = fingerprint(before)
const beforeMessages = (before.prepare("SELECT count(*) AS n FROM messages").get() as { n: number }).n
before.close()

const t0 = performance.now()
const db = openDb(work)
let error: string | undefined
try { runMigrations(db, MIGRATIONS) } catch (err) { error = String(err) }
const ms = Math.round(performance.now() - t0)
const afterCounts = counts(db)
const afterVersions = versions(db)
const afterCols = sessionColumns(db)
const afterFp = fingerprint(db)
const afterMessages = (db.prepare("SELECT count(*) AS n FROM messages").get() as { n: number }).n
const integrity = (db.prepare("PRAGMA integrity_check").get() as { integrity_check: string }).integrity_check
const permissionModes = db.prepare("SELECT agent, permission_mode, count(*) AS n FROM sessions GROUP BY 1, 2").all()
db.close()

let liveStamp: string | undefined
try { liveStamp = readFileSync(join(liveState, "schema-version"), "utf8").trim() } catch {}

const report = {
  snapshot,
  work,
  migrated: !error,
  error,
  ms,
  versionsBefore: { max: Math.max(...beforeVersions), count: beforeVersions.length },
  versionsAfter: { max: Math.max(...afterVersions), count: afterVersions.length, applied: afterVersions.filter((v) => !beforeVersions.includes(v)) },
  manifest: { count: MIGRATIONS.length, max: Math.max(...MIGRATIONS.map((m) => m.version)) },
  liveStamp,
  stampAfterMigration: MIGRATIONS.length,
  newSessionColumns: afterCols.filter((c) => !beforeCols.includes(c)),
  existingValuesUnchanged: beforeFp === afterFp,
  messages: { before: beforeMessages, after: afterMessages },
  integrity,
  permissionModes,
  countsBefore: beforeCounts,
  countsAfter: afterCounts,
}
console.log(JSON.stringify(report, null, 2))
if (error) process.exit(1)
