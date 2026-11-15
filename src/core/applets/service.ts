import type { Database } from "bun:sqlite"
import { createHmac, randomBytes } from "crypto"
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "fs"
import { spawnProcessSync } from "../process/launcher"
import { dirs, files } from "@supermux/platform"
import { isAbsolute, join, relative } from "path"
import { makeLogger } from "../../shared/log"
import { isAppletName, parseManifest, type AppletManifest } from "./manifest"
import { AppletStore, type AppletRow } from "./store"
import { AppletSupervisor, appletBaseEnv, type AppletProcessState, type AppletSpec } from "./supervisor"
import { AppletSecrets, type SecretStatus } from "./secrets"
import { JobRunner, type JobRun, type JobTarget } from "./jobs"
import { AppletBackups, nightlyDue, type BackupInfo } from "./backups"

/** How long a deleted applet (row, folder, backups) is kept before it is gone for good. */
export const DELETED_KEEP_MS = 30 * 86_400_000

const log = makeLogger("applets")

const escapeHtml = (t: string) => t.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!)

/** One applet as the broker knows it: its row, what its folder says, and how its server is doing. */
export interface AppletView {
  row: AppletRow
  folder: string
  manifest?: AppletManifest
  /** Why applet.json can't be used, or that the folder is gone. */
  error?: string
  process?: AppletProcessState
}

export interface AppletServiceDeps {
  db: Database
  /** ~/.mux/applets */
  appletsDir: string
  /** <state>/applets: pid files, logs, the via key. */
  stateDir: string
  /** ~/.mux/backups/applets */
  backupsDir: string
  supervisor?: AppletSupervisor
  jobs?: JobRunner
  /** How often folders are re-read, so an applet an agent made by hand appears on its own. 0 = never. */
  rescanMs?: number
  /** How often due jobs are looked for. 0 = never (tests run jobs by hand). */
  jobTickMs?: number
  /** How often the nightly backups and the 30-day purge are checked. 0 = never. */
  maintenanceMs?: number
  now?: () => Date
  onChange?: () => void
}

export type Result<T> = ({ ok: true } & T) | { ok: false; error: string }

/**
 * #117: the applets of this host. Folders under ~/.mux/applets/ that hold an applet.json are applets; each gets a row
 * with a stable id the first time it is seen. The folder is the truth for code (applet.json), the row for host state.
 * Nothing here knows about sessions.
 */
export class AppletService {
  readonly store: AppletStore
  readonly supervisor: AppletSupervisor
  readonly secrets: AppletSecrets
  readonly jobs: JobRunner
  readonly backups: AppletBackups
  private views = new Map<string, AppletView>()
  private timers: ReturnType<typeof setInterval>[] = []
  private hostKey: Buffer
  private readonly now: () => Date

  constructor(private readonly deps: AppletServiceDeps) {
    this.now = deps.now ?? (() => new Date())
    this.store = new AppletStore(deps.db, this.now)
    mkdirSync(deps.stateDir, { recursive: true })
    this.supervisor = deps.supervisor ?? new AppletSupervisor({ stateDir: deps.stateDir })
    this.secrets = new AppletSecrets(deps.db, this.now)
    this.jobs = deps.jobs ?? new JobRunner({ db: deps.db })
    this.backups = new AppletBackups(deps.backupsDir, this.now)
    this.hostKey = this.loadHostKey()
  }

  private loadHostKey(): Buffer {
    const p = join(this.deps.stateDir, "via.key")
    if (existsSync(p)) {
      const k = Buffer.from(readFileSync(p, "utf8").trim(), "hex")
      if (k.length >= 32) return k
    }
    const k = randomBytes(32)
    files.writePrivate(p, k.toString("hex"))
    return k
  }

  /** The key an applet checks X-Mux-Via-Sig with: one per applet, so one applet can't forge another's header. */
  viaKey(id: string): string {
    return createHmac("sha256", this.hostKey).update(`applet-via:${id}`).digest("hex")
  }

  /** X-Mux-Via-Sig for [via] to applet [id]: hex HMAC-SHA256(viaKey(id), via). */
  signVia(id: string, via: string): string {
    return createHmac("sha256", this.viaKey(id)).update(via).digest("hex")
  }

  async start(): Promise<void> {
    await this.scan()
    const every = (ms: number | undefined, fn: () => void) => { if (ms) this.timers.push(setInterval(fn, ms)) }
    every(this.deps.rescanMs, () => void this.scan())
    every(this.deps.jobTickMs, () => this.jobs.tick(this.jobTargets()))
    every(this.deps.maintenanceMs, () => this.maintenance())
    if (this.deps.jobTickMs) this.jobs.tick(this.jobTargets())
    if (this.deps.maintenanceMs) this.maintenance()
  }

  /** Broker shutdown: stop the timers and leave servers running for the next broker. */
  shutdown(): void {
    for (const t of this.timers) clearInterval(t)
    this.timers = []
    this.supervisor.detach()
  }

  list(): AppletView[] {
    return [...this.views.values()].map((v) => this.withProcess(v)).sort((a, b) => a.row.name.localeCompare(b.row.name))
  }

  get(name: string): AppletView | undefined {
    const v = [...this.views.values()].find((x) => x.row.name === name)
    return v ? this.withProcess(v) : undefined
  }

  private withProcess(v: AppletView): AppletView {
    const p = this.supervisor.state(v.row.id)
    return p ? { ...v, process: p } : v
  }

  folderOf(name: string): string { return join(this.deps.appletsDir, name) }

  /** Restarts the server after a change (re-reading applet.json first). A static applet needs nothing: false. */
  async restart(name: string): Promise<{ ok: true; server: boolean } | { ok: false; error: string }> {
    await this.scan()
    const v = this.get(name)
    if (!v) return { ok: false, error: `no applet named "${name}"` }
    if (!v.manifest) return { ok: false, error: v.error ?? "applet.json can't be read" }
    if (!v.manifest.run) return { ok: true, server: false }
    await this.supervisor.restart(this.spec(v.row, v.folder, v.manifest))
    return { ok: true, server: true }
  }

  /** $DATA_DIR: <folder>/data, or an adopted applet's own `data` folder (~ and relative paths allowed). */
  dataDir(folder: string, m?: AppletManifest): string {
    const d = m?.data
    if (!d) return join(folder, "data")
    if (d === "~" || /^~[\\/]/.test(d)) return join(dirs.home(), d.slice(2))
    return isAbsolute(d) ? d : join(folder, d)
  }

  /** What the server and its jobs get besides PORT/DATA_DIR: the via key, the Settings values, the secrets. */
  private env(row: AppletRow, m: AppletManifest): Record<string, string> {
    const settings: Record<string, unknown> = {}
    for (const f of m.config) if (f.default !== undefined) settings[f.key] = f.default
    Object.assign(settings, this.secrets.settings(row.id))
    return { MUX_VIA_KEY: this.viaKey(row.id), APPLET_SETTINGS: JSON.stringify(settings), ...this.secrets.env(row.id) }
  }

  private spec(row: AppletRow, folder: string, m: AppletManifest): AppletSpec {
    return { id: row.id, name: row.name, folder, run: m.run!, dataDir: this.dataDir(folder, m), env: this.env(row, m) }
  }

  private jobTarget(v: AppletView): JobTarget | undefined {
    const m = v.manifest
    if (!m || !m.jobs.length) return undefined
    return {
      appletId: v.row.id, name: v.row.name, folder: v.folder, jobs: m.jobs,
      env: () => {
        const port = this.supervisor.port(v.row.id)
        const dataDir = this.dataDir(v.folder, m)
        mkdirSync(dataDir, { recursive: true })
        return { ...appletBaseEnv(), ...this.env(v.row, m), DATA_DIR: dataDir, APPLET_NAME: v.row.name, ...(port ? { PORT: String(port) } : {}) }
      },
    }
  }

  jobTargets(): JobTarget[] {
    return [...this.views.values()].map((v) => this.jobTarget(v)).filter((t): t is JobTarget => !!t)
  }

  /** Runs [job] now and waits for it: its exit code and output (`supermux applet run`, "Run now"). */
  async runJob(name: string, job: string): Promise<Result<{ run: JobRun }>> {
    await this.scan()
    const v = this.get(name)
    if (!v) return { ok: false, error: `no applet named "${name}"` }
    const t = this.jobTarget(v)
    const j = t?.jobs.find((x) => x.name === job)
    if (!t || !j) {
      const names = v.manifest?.jobs.map((x) => x.name) ?? []
      return { ok: false, error: names.length ? `"${name}" has no job "${job}" (its jobs: ${names.join(", ")})` : `"${name}" has no jobs` }
    }
    return { ok: true, run: await this.jobs.run(t, j, "manual") }
  }

  /** An extra read-only copy of the applet's data, labelled with why (`supermux applet backup`). */
  backup(name: string, why?: string): Result<{ backup: BackupInfo }> {
    const v = this.get(name)
    if (!v) return { ok: false, error: `no applet named "${name}"` }
    try {
      const b = this.backups.backup(name, this.dataDir(v.folder, v.manifest), "manual", why)
      return b ? { ok: true, backup: b } : { ok: false, error: `"${name}" has no data folder yet, so there is nothing to back up` }
    } catch (err) {
      return { ok: false, error: `the backup failed: ${(err as Error).message}` }
    }
  }

  /** The secrets applet.json asks for that have no value or have expired: the "Needs you" of its page. */
  needsYou(v: AppletView): SecretStatus[] {
    return this.secrets.status(v.row.id, v.manifest?.secrets ?? []).filter((s) => s.state !== "set" && (v.manifest?.secrets ?? []).includes(s.name))
  }

  /** Owner only: stores a secret's value and restarts the server so it sees it. */
  async setSecret(name: string, secret: string, value: string, opts: { expiresAt?: string | null; by: string }): Promise<Result<{}>> {
    const v = this.get(name)
    if (!v?.manifest) return { ok: false, error: v ? v.error ?? "applet.json can't be read" : `no applet named "${name}"` }
    if (!v.manifest.secrets.includes(secret)) return { ok: false, error: `"${name}" doesn't ask for a secret named ${secret}` }
    if (!value) return { ok: false, error: "the value is empty" }
    if (opts.expiresAt && Number.isNaN(Date.parse(opts.expiresAt))) return { ok: false, error: "expiresAt is not a date" }
    this.secrets.set(v.row.id, secret, value, { expiresAt: opts.expiresAt ?? null, updatedBy: opts.by })
    await this.restartIfServer(v)
    this.deps.onChange?.()
    return { ok: true }
  }

  async removeSecret(name: string, secret: string): Promise<Result<{}>> {
    const v = this.get(name)
    if (!v) return { ok: false, error: `no applet named "${name}"` }
    if (!this.secrets.remove(v.row.id, secret)) return { ok: false, error: `${secret} has no value` }
    await this.restartIfServer(v)
    this.deps.onChange?.()
    return { ok: true }
  }

  /** Owner only: a value for one of applet.json's `config` fields; the server restarts to read APPLET_SETTINGS. */
  async setSetting(name: string, key: string, value: string, by: string): Promise<Result<{}>> {
    const v = this.get(name)
    if (!v?.manifest) return { ok: false, error: v ? v.error ?? "applet.json can't be read" : `no applet named "${name}"` }
    const field = v.manifest.config.find((f) => f.key === key)
    if (!field) return { ok: false, error: `"${name}" has no setting "${key}"` }
    if (field.options && !field.options.includes(value)) return { ok: false, error: `"${key}" must be one of: ${field.options.join(", ")}` }
    this.secrets.setSetting(v.row.id, key, value, by)
    await this.restartIfServer(v)
    this.deps.onChange?.()
    return { ok: true }
  }

  private async restartIfServer(v: AppletView): Promise<void> {
    if (v.manifest?.run) await this.supervisor.restart(this.spec(v.row, v.folder, v.manifest))
  }

  /**
   * `supermux applet new`: makes the folder (an empty static applet, or a copy of [from], git history included), with
   * a data/ folder git ignores and a repo-local git identity ("supermux applet") so agents never commit as the user,
   * commits it, and registers it at once.
   */
  async create(name: string, opts: { from?: string; title?: string }, by: string): Promise<Result<{ view: AppletView }>> {
    if (!isAppletName(name)) return { ok: false, error: `"${name}" can't be an applet name: use lowercase letters, digits and dashes, starting with a letter (e.g. job-tracker)` }
    const folder = this.folderOf(name)
    if (existsSync(folder) || this.store.byName(name)) return { ok: false, error: `an applet named "${name}" exists already (supermux applet info ${name})` }
    if (opts.from) {
      if (!existsSync(opts.from) || !statSync(opts.from).isDirectory()) return { ok: false, error: `--from ${opts.from} is not a folder` }
      const up = relative(opts.from, folder)
      if (!up || (!up.startsWith("..") && !isAbsolute(up))) return { ok: false, error: "--from can't be a folder that contains the applets folder" }
    }
    const title = opts.title?.trim() || name
    try {
      mkdirSync(this.deps.appletsDir, { recursive: true })
      if (opts.from) files.copyTreeSync(opts.from, folder)
      else {
        mkdirSync(join(folder, "public"), { recursive: true })
        writeFileSync(join(folder, "public", "index.html"), `<!doctype html>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width,initial-scale=1">\n<title>${escapeHtml(title)}</title>\n<h1>${escapeHtml(title)}</h1>\n`)
      }
      if (!existsSync(join(folder, "applet.json"))) writeFileSync(join(folder, "applet.json"), JSON.stringify({ title }, null, 2) + "\n")
      mkdirSync(join(folder, "data"), { recursive: true })
      const gi = join(folder, ".gitignore")
      const cur = existsSync(gi) ? readFileSync(gi, "utf8") : ""
      if (!cur.split(/\r?\n/).some((l) => ["data", "data/", "/data", "/data/"].includes(l.trim()))) {
        writeFileSync(gi, cur + (cur && !cur.endsWith("\n") ? "\n" : "") + "# the applet's live data: backed up by supermux, never committed\ndata/\n")
      }
      const git = (...args: string[]) => {
        const r = spawnProcessSync(["git", ...args], { cwd: folder, stdout: "pipe", stderr: "pipe" })
        if (r.exitCode !== 0) throw new Error(`git ${args[0]}: ${r.stderr.toString().trim()}`)
      }
      if (!existsSync(join(folder, ".git"))) git("init", "-q")
      git("config", "user.name", "supermux applet")
      git("config", "user.email", "applet@supermux.invalid")
      git("add", "-A")
      const staged = spawnProcessSync(["git", "diff", "--cached", "--quiet"], { cwd: folder })
      if (staged.exitCode !== 0) git("commit", "-q", "-m", opts.from ? `applet created from ${opts.from}` : "applet created")
    } catch (err) {
      return { ok: false, error: `couldn't create the applet: ${(err as Error).message}` }
    }
    await this.scan()
    const v = this.get(name)
    if (!v) return { ok: false, error: "the folder was made but isn't listed; check applet.json" }
    this.touchBy(v.row.id, by)
    return { ok: true, view: this.get(name)! }
  }

  /** Records that [by] changed the applet (a restart, a backup) without changing what its folder says. */
  touchBy(id: string, by: string): void {
    this.store.touch(id, by)
    const v = this.views.get(id)
    if (v) this.views.set(id, { ...v, row: this.store.byId(id)! })
  }

  private deletedDir(): string { return join(this.deps.appletsDir, ".deleted") }
  private deletedBackupsDir(): string { return join(this.deps.backupsDir, ".deleted") }

  /**
   * Owner only: the applet disappears from every device. Its server stops; its folder and backups move aside (out of
   * the scanner's sight) and are kept with its row for 30 days, then purged.
   */
  async delete(name: string, by: string): Promise<Result<{ id: string }>> {
    const v = this.get(name)
    if (!v) return { ok: false, error: `no applet named "${name}"` }
    this.supervisor.stop(v.row.id)
    const aside = `${name}-${v.row.id}`
    try {
      mkdirSync(this.deletedDir(), { recursive: true })
      if (existsSync(v.folder)) files.replaceSync(v.folder, join(this.deletedDir(), aside))
      const b = this.backups.dirOf(name)
      if (existsSync(b)) {
        mkdirSync(this.deletedBackupsDir(), { recursive: true })
        files.replaceSync(b, join(this.deletedBackupsDir(), aside))
      }
    } catch (err) {
      await this.scan()
      return { ok: false, error: `couldn't move the applet's folder aside: ${(err as Error).message}` }
    }
    this.store.softDelete(v.row.id, by)
    this.views.delete(v.row.id)
    log.info("applet.deleted", { name, id: v.row.id, by })
    this.deps.onChange?.()
    return { ok: true, id: v.row.id }
  }

  /** Owner only, within the 30 days: puts a deleted applet back under its old name, with its id, secrets and runs. */
  async undelete(id: string, by: string): Promise<Result<{ name: string }>> {
    const row = this.store.byId(id)
    if (!row?.deletedAt) return { ok: false, error: "no deleted applet with that id" }
    if (this.store.byName(row.name) || existsSync(this.folderOf(row.name))) {
      return { ok: false, error: `an applet named "${row.name}" exists again; rename it first` }
    }
    const aside = `${row.name}-${row.id}`
    const folder = join(this.deletedDir(), aside)
    if (!existsSync(folder)) return { ok: false, error: "its folder is gone" }
    files.replaceSync(folder, this.folderOf(row.name))
    const b = join(this.deletedBackupsDir(), aside)
    if (existsSync(b) && !existsSync(this.backups.dirOf(row.name))) files.replaceSync(b, this.backups.dirOf(row.name))
    this.store.undelete(id, by)
    await this.scan()
    return { ok: true, name: row.name }
  }

  /** The 30-day end of deleted applets: row, secrets, job runs, folder and backups. */
  purgeDeleted(): void {
    const cutoff = this.now().getTime() - DELETED_KEEP_MS
    for (const row of this.store.listDeleted()) {
      if (Date.parse(row.deletedAt!) > cutoff) continue
      const aside = `${row.name}-${row.id}`
      for (const p of [join(this.deletedDir(), aside), join(this.deletedBackupsDir(), aside)]) {
        if (!existsSync(p)) continue
        try { files.makeWritableTree(p) } catch { /* best effort */ }
        try { rmSync(p, { recursive: true, force: true }) } catch (err) { log.warn("applet.purge_failed", { path: p, err: (err as Error).message }) }
      }
      this.secrets.forget(row.id)
      this.jobs.forget(row.id)
      this.store.purge(row.id)
      log.info("applet.purged", { name: row.name, id: row.id })
    }
  }

  /** Nightly backups (once a day after 03:30, or at the first check after it), their two-week pruning, the purge. */
  maintenance(): void {
    const now = this.now()
    for (const v of this.views.values()) {
      if (!v.manifest) continue
      if (nightlyDue(now, this.backups.list(v.row.name).find((b) => b.kind === "nightly")?.id)) {
        try {
          this.backups.backup(v.row.name, this.dataDir(v.folder, v.manifest), "nightly")
        } catch (err) {
          log.warn("applet.nightly_backup_failed", { name: v.row.name, err: (err as Error).message })
        }
      }
      try { this.backups.prune(v.row.name) } catch (err) { log.warn("applet.prune_failed", { name: v.row.name, err: (err as Error).message }) }
    }
    this.purgeDeleted()
  }

  private readFolder(name: string): { manifest?: AppletManifest; error?: string } | undefined {
    const folder = this.folderOf(name)
    const mf = join(folder, "applet.json")
    if (!existsSync(mf)) return undefined
    const r = parseManifest(readFileSync(mf, "utf8"))
    return r.ok ? { manifest: r.manifest } : { error: r.error }
  }

  private scanning?: Promise<void>

  /** Re-reads the folders: new ones get a row, changed ones update it, gone ones turn "missing". Servers follow. */
  scan(): Promise<void> {
    this.scanning ??= this.scanOnce().finally(() => { this.scanning = undefined })
    return this.scanning
  }

  private async scanOnce(): Promise<void> {
    let names: string[] = []
    try {
      mkdirSync(this.deps.appletsDir, { recursive: true })
      names = readdirSync(this.deps.appletsDir).filter((n) => {
        if (!isAppletName(n)) return false
        try { return statSync(join(this.deps.appletsDir, n)).isDirectory() } catch { return false }
      })
    } catch (err) {
      log.warn("applets.scan_failed", { err: (err as Error).message })
      return
    }

    let changed = false
    const seen = new Set<string>()
    for (const name of names) {
      const read = this.readFolder(name)
      if (!read) continue
      const status = read.manifest ? "ok" : "invalid"
      let row = this.store.byName(name)
      if (!row) {
        row = this.store.insert({ name, title: read.manifest?.title ?? name, icon: read.manifest?.icon ?? null, status, updatedBy: "host" })
        log.info("applet.registered", { name, id: row.id })
        changed = true
      } else if (this.store.update(row.id, { title: read.manifest?.title, icon: read.manifest ? read.manifest.icon ?? null : undefined, status })) {
        row = this.store.byId(row.id)!
        changed = true
      }
      seen.add(row.id)
      const prev = this.views.get(row.id)
      const view: AppletView = { row, folder: this.folderOf(name), ...read }
      if (!prev || prev.error !== view.error || prev.row.updatedAt !== row.updatedAt) changed = true
      this.views.set(row.id, view)
    }

    for (const row of this.store.list()) {
      if (seen.has(row.id)) continue
      if (row.status !== "missing") {
        this.store.update(row.id, { status: "missing", updatedBy: "host" })
        changed = true
      }
      this.views.set(row.id, { row: this.store.byId(row.id)!, folder: this.folderOf(row.name), error: "the applet's folder is gone" })
    }
    for (const id of [...this.views.keys()]) if (!this.store.byId(id) || this.store.byId(id)!.deletedAt) this.views.delete(id)

    const running = new Set<string>()
    for (const v of this.views.values()) {
      if (v.manifest?.run) {
        running.add(v.row.id)
        await this.supervisor.ensure(this.spec(v.row, v.folder, v.manifest))
      }
    }
    this.supervisor.stopAllExcept(running)
    if (changed) this.deps.onChange?.()
  }
}

/** What a device sees of one applet (GET /applets): no paths on the host, no keys. */
export function appletPayload(v: AppletView, needsYou: SecretStatus[] = []) {
  const m = v.manifest
  return {
    id: v.row.id,
    name: v.row.name,
    title: v.row.title,
    url: `/a/${v.row.name}/`,
    iconUrl: v.row.icon ? `/applets/${v.row.name}/icon` : null,
    status: v.row.status,
    kind: m?.run ? "server" : "static",
    ...(v.error ? { error: v.error } : {}),
    needsYou: needsYou.map((s) => ({ secret: s.name, state: s.state })),
    ...(m ? { jobs: m.jobs.map((j) => ({ name: j.name, every: j.every })), secrets: m.secrets, widget: m.widget ?? null } : {}),
    ...(v.process ? {
      process: {
        state: v.process.state, restarts: v.process.restarts, startedAt: v.process.startedAt ?? null,
        lastExit: v.process.lastExit ?? null, error: v.process.error ?? null,
      },
    } : {}),
    updatedBy: v.row.updatedBy,
    updatedAt: v.row.updatedAt,
    createdAt: v.row.createdAt,
  }
}
