import { Database } from "bun:sqlite"
import { closeSync, copyFileSync, existsSync, openSync, readSync, lstatSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "fs"
import { join } from "path"
import { files } from "@supermux/platform"
import { makeLogger } from "../../shared/log"

const log = makeLogger("applets")

/**
 * #117: plain, read-only copies of an applet's data. Every night each applet's data/ is copied to
 * ~/.mux/backups/applets/<name>/<date>/; `supermux applet backup <name> -m why` makes an extra one before a risky
 * change. About two weeks are kept. There is no restore feature: an agent copies back only what is missing, never a
 * whole backup over live data (that would lose everything added since). Same disk: this protects against a bad edit or
 * a broken applet, not a dead disk.
 */

export const KEEP_DAYS = 14

export interface BackupInfo {
  /** The folder name: 2026-11-15 (nightly) or 2026-11-15T10-31-07 (made on request). */
  id: string
  kind: "nightly" | "manual"
  createdAt: string
  why: string | null
  bytes: number
}

/** A SQLite database file (by its header), which is copied through SQLite so a write in progress can't tear it. */
function isSqlite(path: string): boolean {
  let fd: number | undefined
  try {
    fd = openSync(path, "r")
    const head = Buffer.alloc(16)
    return readSync(fd, head, 0, 16, 0) === 16 && head.toString("latin1") === "SQLite format 3\u0000"
  } catch {
    return false
  } finally {
    if (fd !== undefined) closeSync(fd)
  }
}

function copyTree(src: string, dest: string): number {
  let bytes = 0
  mkdirSync(dest, { recursive: true })
  for (const name of readdirSync(src)) {
    const s = join(src, name)
    const d = join(dest, name)
    const st = lstatSync(s)
    if (st.isSymbolicLink()) continue
    if (st.isDirectory()) { bytes += copyTree(s, d); continue }
    if (!st.isFile()) continue
    // The WAL and journal sidecars are folded into the copy by VACUUM INTO; copying them too would be stale.
    if (/-(wal|shm|journal)$/.test(name) && existsSync(s.replace(/-(wal|shm|journal)$/, ""))) continue
    if (isSqlite(s)) {
      try {
        const db = new Database(s, { readonly: true })
        try { db.run(`VACUUM INTO '${d.replace(/'/g, "''")}'`) } finally { db.close() }
        bytes += statSync(d).size
        continue
      } catch (err) {
        log.warn("applet.backup_sqlite_fallback", { file: s, err: (err as Error).message })
      }
    }
    copyFileSync(s, d)
    bytes += st.size
  }
  return bytes
}

function stamp(d: Date): { day: string; time: string } {
  const p = (n: number) => String(n).padStart(2, "0")
  const day = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
  return { day, time: `${day}T${p(d.getHours())}-${p(d.getMinutes())}-${p(d.getSeconds())}` }
}

export class AppletBackups {
  constructor(private readonly root: string, private readonly now: () => Date = () => new Date()) {}

  dirOf(name: string): string { return join(this.root, name) }

  /**
   * Copies [dataDir] into a new backup of applet [name]. Nightly: one per day (a second call the same day does
   * nothing). Manual: always a new one, with [why] saved beside it. Null when there is no data folder yet.
   */
  backup(name: string, dataDir: string, kind: BackupInfo["kind"], why?: string): BackupInfo | null {
    if (!existsSync(dataDir) || !statSync(dataDir).isDirectory()) return null
    const at = this.now()
    const { day, time } = stamp(at)
    let id = kind === "nightly" ? day : time
    const base = this.dirOf(name)
    if (kind === "nightly" && existsSync(join(base, id))) return this.info(name, id)
    for (let n = 2; existsSync(join(base, id)); n++) id = `${time}-${n}`
    const dest = join(base, id)
    const tmp = join(base, `.${id}.partial`)
    rmSync(tmp, { recursive: true, force: true })
    try {
      const bytes = copyTree(dataDir, join(tmp, "data"))
      writeFileSync(join(tmp, "backup.json"), JSON.stringify({ kind, createdAt: at.toISOString(), why: why?.trim() || null, bytes }, null, 2))
      files.replaceSync(tmp, dest)
    } catch (err) {
      try { files.makeWritableTree(tmp) } catch { /* not there */ }
      rmSync(tmp, { recursive: true, force: true })
      throw err
    }
    files.makeReadOnlyTree(dest)
    log.info("applet.backup", { name, id, kind })
    return this.info(name, id)
  }

  info(name: string, id: string): BackupInfo | null {
    try {
      const meta = JSON.parse(readFileSync(join(this.dirOf(name), id, "backup.json"), "utf8")) as Omit<BackupInfo, "id">
      return { id, kind: meta.kind, createdAt: meta.createdAt, why: meta.why ?? null, bytes: meta.bytes ?? 0 }
    } catch {
      return null
    }
  }

  /** Newest first. */
  list(name: string): BackupInfo[] {
    const base = this.dirOf(name)
    if (!existsSync(base)) return []
    return readdirSync(base).filter((n) => !n.startsWith("."))
      .map((id) => this.info(name, id)).filter((b): b is BackupInfo => !!b)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  }

  /** Removes backups older than [KEEP_DAYS], and any half-made copy. */
  prune(name: string): void {
    const base = this.dirOf(name)
    if (!existsSync(base)) return
    const cutoff = this.now().getTime() - KEEP_DAYS * 86_400_000
    for (const id of readdirSync(base)) {
      const p = join(base, id)
      const b = id.startsWith(".") ? null : this.info(name, id)
      if (b && Date.parse(b.createdAt) >= cutoff) continue
      if (!b && !id.startsWith(".")) continue // not ours: leave it
      this.remove(p)
    }
  }

  /** Every backup of [name] (the purge of a deleted applet). */
  removeAll(name: string): void {
    const base = this.dirOf(name)
    if (existsSync(base)) this.remove(base)
  }

  private remove(p: string): void {
    try { files.makeWritableTree(p) } catch { /* already gone */ }
    rmSync(p, { recursive: true, force: true })
  }
}

/** Whether tonight's backups are due: past 03:30 local time and none made today. */
export function nightlyDue(now: Date, lastDay: string | undefined): boolean {
  const { day } = stamp(now)
  return lastDay !== day && (now.getHours() > 3 || (now.getHours() === 3 && now.getMinutes() >= 30))
}

export function dayOf(d: Date): string { return stamp(d).day }
