import type { Database } from "bun:sqlite"
import { createHmac, randomBytes } from "crypto"
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync } from "fs"
import { files } from "@supermux/platform"
import { join } from "path"
import { makeLogger } from "../../shared/log"
import { isAppletName, parseManifest, type AppletManifest } from "./manifest"
import { AppletStore, type AppletRow } from "./store"
import { AppletSupervisor, type AppletProcessState, type AppletSpec } from "./supervisor"

const log = makeLogger("applets")

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
  supervisor?: AppletSupervisor
  /** How often folders are re-read, so an applet an agent made by hand appears on its own. 0 = never. */
  rescanMs?: number
  onChange?: () => void
}

/**
 * #117: the applets of this host. Folders under ~/.mux/applets/ that hold an applet.json are applets; each gets a row
 * with a stable id the first time it is seen. The folder is the truth for code (applet.json), the row for host state.
 * Nothing here knows about sessions.
 */
export class AppletService {
  readonly store: AppletStore
  readonly supervisor: AppletSupervisor
  private views = new Map<string, AppletView>()
  private timer?: ReturnType<typeof setInterval>
  private hostKey: Buffer

  constructor(private readonly deps: AppletServiceDeps) {
    this.store = new AppletStore(deps.db)
    mkdirSync(deps.stateDir, { recursive: true })
    this.supervisor = deps.supervisor ?? new AppletSupervisor({ stateDir: deps.stateDir })
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
    if (this.deps.rescanMs) this.timer = setInterval(() => void this.scan(), this.deps.rescanMs)
  }

  /** Broker shutdown: stop rescanning and leave servers running for the next broker. */
  shutdown(): void {
    if (this.timer) clearInterval(this.timer)
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

  private spec(row: AppletRow, folder: string, m: AppletManifest): AppletSpec {
    return {
      id: row.id, name: row.name, folder, run: m.run!,
      dataDir: join(folder, "data"),
      env: { MUX_VIA_KEY: this.viaKey(row.id) },
    }
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
export function appletPayload(v: AppletView) {
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
