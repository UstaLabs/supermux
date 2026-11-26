import { closeSync, existsSync, mkdirSync, openSync, readFileSync, renameSync, statSync, unlinkSync, writeFileSync } from "fs"
import { createServer } from "net"
import { join } from "path"
import { proc } from "@supermux/platform"
import { makeLogger } from "../../shared/log"

const log = makeLogger("applets")

/**
 * #117: keeps every applet that has a `run` command alive, with no session involved. Each server gets a loopback $PORT
 * and its $DATA_DIR, starts in its own process group, writes stdout and stderr to a log file, and is restarted with a
 * growing delay when it exits. A pid file per applet lets a restarted broker ADOPT the servers still running (the
 * broker's unit keeps its children, KillMode=process), so a broker restart is no blip for an applet and leaves no
 * second copy; a pid file whose process is gone or is someone else (checked by its start time) starts a fresh server.
 */

export type AppletRunState = "starting" | "running" | "crashed" | "stopped"

export interface AppletProcessState {
  state: AppletRunState
  pid?: number
  port?: number
  startedAt?: string
  restarts: number
  lastExit?: { code: number | null; signal: string | null; at: string }
  /** Why it is not running, in words an agent can act on. */
  error?: string
}

export interface AppletSpec {
  id: string
  name: string
  folder: string
  run: string
  dataDir: string
  /** Everything the server gets besides PORT and DATA_DIR (MUX_VIA_KEY, A2's secrets). */
  env: Record<string, string>
}

interface PidFile { pid: number; port: number; startToken: string | null; startedAt: string; run: string }

export interface SupervisorDeps {
  /** Where pid files and logs go: <state>/applets. */
  stateDir: string
  spawn?: (spec: AppletSpec, port: number, logFd: number) => { pid: number | undefined; onExit: (cb: (code: number | null, signal: string | null) => void) => void }
  isAlive?: (pid: number) => boolean
  startToken?: (pid: number) => string | null
  killTree?: (pid: number) => void
  freePort?: () => Promise<number>
  now?: () => number
  /** How often an adopted server (not our child, so no exit event) is checked. */
  adoptPollMs?: number
}

/** Restart delays after a crash: 1 s doubling to a minute; a server that ran a minute starts over at 1 s. */
export function restartDelayMs(consecutiveCrashes: number): number {
  return Math.min(60_000, 1_000 * 2 ** Math.max(0, consecutiveCrashes - 1))
}

const LOG_MAX_BYTES = 1_000_000

export function freeLoopbackPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address()
      const port = typeof addr === "object" && addr ? addr.port : 0
      srv.close(() => (port ? resolve(port) : reject(new Error("no free port"))))
    })
  })
}

/** The environment an applet starts with: a small allow-list of the broker's, never its tokens or secrets. */
export function appletBaseEnv(src: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const keep = ["PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "TZ", "SHELL", "TMPDIR", "TEMP", "TMP",
    "SYSTEMROOT", "SystemRoot", "COMSPEC", "PATHEXT", "WINDIR", "USERPROFILE", "APPDATA", "LOCALAPPDATA"]
  const env: Record<string, string> = {}
  for (const k of keep) if (src[k] !== undefined) env[k] = src[k]!
  return env
}

function defaultSpawn(spec: AppletSpec, port: number, logFd: number) {
  const env = { ...appletBaseEnv(), ...spec.env, PORT: String(port), DATA_DIR: spec.dataDir, APPLET_NAME: spec.name }
  const child = proc.spawnShellLine(spec.run, { cwd: spec.folder, env, detached: true, stdio: ["ignore", logFd, logFd] })
  child.unref()
  return {
    pid: child.pid,
    onExit: (cb: (code: number | null, signal: string | null) => void) => {
      child.on("exit", (code, signal) => cb(code, signal))
      child.on("error", (err) => { log.warn("applet.spawn_error", { name: spec.name, err: err.message }); cb(127, null) })
    },
  }
}

interface Entry {
  spec: AppletSpec
  st: AppletProcessState
  crashes: number
  timer?: ReturnType<typeof setTimeout>
  poll?: ReturnType<typeof setInterval>
  /** Bumped on every start/stop, so a late exit from an older process is ignored. */
  gen: number
}

export class AppletSupervisor {
  private entries = new Map<string, Entry>()
  /** #165: told when a server has just been started (its widget can be read as soon as it answers). */
  onStarted?: (id: string) => void
  private readonly d: Required<Omit<SupervisorDeps, "stateDir">> & { stateDir: string }

  constructor(deps: SupervisorDeps) {
    this.d = {
      stateDir: deps.stateDir,
      spawn: deps.spawn ?? defaultSpawn,
      isAlive: deps.isAlive ?? ((pid) => proc.isAlive(pid)),
      startToken: deps.startToken ?? ((pid) => proc.startToken(pid)),
      killTree: deps.killTree ?? ((pid) => proc.killTree(pid)),
      freePort: deps.freePort ?? freeLoopbackPort,
      now: deps.now ?? Date.now,
      adoptPollMs: deps.adoptPollMs ?? 2_000,
    }
    mkdirSync(this.d.stateDir, { recursive: true })
  }

  private dir(id: string): string { return join(this.d.stateDir, id) }
  private pidPath(id: string): string { return join(this.dir(id), "server.pid.json") }
  logPath(id: string): string { return join(this.dir(id), "server.log") }

  state(id: string): AppletProcessState | undefined {
    return this.entries.get(id)?.st
  }

  /** The loopback port a running (or starting) server listens on. */
  port(id: string): number | undefined {
    const e = this.entries.get(id)
    return e && (e.st.state === "running" || e.st.state === "starting") ? e.st.port : undefined
  }

  /** What the server printed since it last started (its own section of the log). */
  logsSinceStart(id: string, lines = 50): string {
    const all = this.logs(id, 2000)
    let at = 0
    for (const m of all.matchAll(/^--- \S+ supermux started: /gm)) at = m.index ?? 0
    return all.slice(at).split("\n").slice(-lines).join("\n")
  }

  /** The last [lines] lines of the server's output. */
  logs(id: string, lines = 200): string {
    const p = this.logPath(id)
    if (!existsSync(p)) return ""
    const text = readFileSync(p, "utf8")
    const all = text.endsWith("\n") ? text.slice(0, -1).split("\n") : text.split("\n")
    return all.slice(-lines).join("\n")
  }

  /**
   * Makes sure [spec]'s server runs: adopts the one a previous broker left (same pid, same start, same command), else
   * starts it. A running server whose command changed is left alone until [restart] (agents restart on purpose).
   */
  async ensure(spec: AppletSpec): Promise<void> {
    const cur = this.entries.get(spec.id)
    if (cur) {
      cur.spec = spec
      if (cur.st.state !== "stopped") return
    }
    if (this.adopt(spec)) return
    await this.start(spec)
  }

  async restart(spec: AppletSpec): Promise<void> {
    this.stop(spec.id)
    const e = this.entries.get(spec.id)
    if (e) { e.crashes = 0; e.st.restarts = 0 }
    await this.start(spec)
  }

  /** Stops the server and forgets the pid file. */
  stop(id: string): void {
    const e = this.entries.get(id)
    if (!e) {
      this.killFromPidFile(id)
      return
    }
    e.gen++
    if (e.timer) clearTimeout(e.timer)
    if (e.poll) clearInterval(e.poll)
    e.timer = undefined
    e.poll = undefined
    if (e.st.pid) this.d.killTree(e.st.pid)
    try { unlinkSync(this.pidPath(id)) } catch { /* none */ }
    e.st = { state: "stopped", restarts: e.st.restarts, ...(e.st.lastExit ? { lastExit: e.st.lastExit } : {}) }
  }

  /** Stops servers whose applet no longer exists (deleted, or its run was removed). */
  stopAllExcept(ids: Set<string>): void {
    for (const id of [...this.entries.keys()]) if (!ids.has(id)) { this.stop(id); this.entries.delete(id) }
  }

  /** Broker shutdown: stop watching, leave the servers running for the next broker to adopt. */
  detach(): void {
    for (const e of this.entries.values()) {
      e.gen++
      if (e.timer) clearTimeout(e.timer)
      if (e.poll) clearInterval(e.poll)
    }
  }

  private readPidFile(id: string): PidFile | undefined {
    try {
      const p = JSON.parse(readFileSync(this.pidPath(id), "utf8")) as PidFile
      return Number.isInteger(p.pid) && Number.isInteger(p.port) ? p : undefined
    } catch {
      return undefined
    }
  }

  /** Whether [p] still names the process we started: alive, and (where the OS tells) started when we started it. */
  private isOurs(p: PidFile): boolean {
    if (!this.d.isAlive(p.pid)) return false
    if (p.startToken === null) return false
    return this.d.startToken(p.pid) === p.startToken
  }

  private killFromPidFile(id: string): void {
    const p = this.readPidFile(id)
    if (p && this.isOurs(p)) this.d.killTree(p.pid)
    try { unlinkSync(this.pidPath(id)) } catch { /* none */ }
  }

  private adopt(spec: AppletSpec): boolean {
    const p = this.readPidFile(spec.id)
    if (!p) return false
    if (!this.isOurs(p) || p.run !== spec.run) {
      if (this.isOurs(p)) this.d.killTree(p.pid)
      try { unlinkSync(this.pidPath(spec.id)) } catch { /* none */ }
      return false
    }
    const e = this.entry(spec)
    const gen = ++e.gen
    e.st = { state: "running", pid: p.pid, port: p.port, startedAt: p.startedAt, restarts: e.st.restarts }
    e.poll = setInterval(() => {
      if (e.gen !== gen) return
      if (!this.d.isAlive(p.pid)) {
        clearInterval(e.poll)
        e.poll = undefined
        this.onExit(spec.id, gen, null, null)
      }
    }, this.d.adoptPollMs)
    log.info("applet.adopted", { name: spec.name, pid: p.pid, port: p.port })
    return true
  }

  private entry(spec: AppletSpec): Entry {
    let e = this.entries.get(spec.id)
    if (!e) {
      e = { spec, st: { state: "stopped", restarts: 0 }, crashes: 0, gen: 0 }
      this.entries.set(spec.id, e)
    }
    e.spec = spec
    return e
  }

  private openLog(id: string): number {
    mkdirSync(this.dir(id), { recursive: true })
    const p = this.logPath(id)
    try {
      if (existsSync(p) && statSync(p).size > LOG_MAX_BYTES) renameSync(p, p + ".1")
    } catch { /* keep appending */ }
    return openSync(p, "a")
  }

  private async start(spec: AppletSpec): Promise<void> {
    const e = this.entry(spec)
    const gen = ++e.gen
    e.st = { ...e.st, state: "starting", error: undefined }
    let port: number
    try {
      port = await this.d.freePort()
    } catch (err) {
      e.st = { ...e.st, state: "crashed", error: `no free port: ${(err as Error).message}` }
      return
    }
    if (e.gen !== gen) return
    mkdirSync(spec.dataDir, { recursive: true })
    const fd = this.openLog(spec.id)
    const startedAt = new Date(this.d.now()).toISOString()
    try {
      writeFileSync(fd, `--- ${startedAt} supermux started: ${spec.run} (PORT=${port})\n`)
    } catch { /* the log is best effort */ }
    let child: ReturnType<NonNullable<SupervisorDeps["spawn"]>>
    try {
      child = this.d.spawn(spec, port, fd)
    } catch (err) {
      closeSync(fd)
      e.crashes++
      e.st = { ...e.st, state: "crashed", error: `could not start: ${(err as Error).message}` }
      this.scheduleRestart(spec.id, gen)
      return
    }
    closeSync(fd)
    if (!child.pid) {
      e.crashes++
      e.st = { ...e.st, state: "crashed", error: "could not start the command" }
      this.scheduleRestart(spec.id, gen)
      return
    }
    e.st = { state: "running", pid: child.pid, port, startedAt, restarts: e.st.restarts, ...(e.st.lastExit ? { lastExit: e.st.lastExit } : {}) }
    const pf: PidFile = { pid: child.pid, port, startToken: this.d.startToken(child.pid), startedAt, run: spec.run }
    writeFileSync(this.pidPath(spec.id), JSON.stringify(pf))
    child.onExit((code, signal) => this.onExit(spec.id, gen, code, signal))
    log.info("applet.started", { name: spec.name, pid: child.pid, port })
    this.onStarted?.(spec.id)
  }

  private onExit(id: string, gen: number, code: number | null, signal: string | null): void {
    const e = this.entries.get(id)
    if (!e || e.gen !== gen) return
    const at = this.d.now()
    const ranMs = e.st.startedAt ? at - Date.parse(e.st.startedAt) : 0
    if (ranMs >= 60_000) e.crashes = 0
    e.crashes++
    e.st = {
      state: "crashed", restarts: e.st.restarts, port: e.st.port,
      lastExit: { code, signal, at: new Date(at).toISOString() },
      error: `the server exited (${signal ? `signal ${signal}` : `code ${code ?? "unknown"}`}); see its logs`,
    }
    try { unlinkSync(this.pidPath(id)) } catch { /* none */ }
    log.warn("applet.exited", { name: e.spec.name, code, signal, crashes: e.crashes })
    this.scheduleRestart(id, gen)
  }

  private scheduleRestart(id: string, gen: number): void {
    const e = this.entries.get(id)
    if (!e || e.gen !== gen) return
    const delay = restartDelayMs(Math.max(1, e.crashes))
    e.timer = setTimeout(() => {
      if (e.gen !== gen) return
      e.st.restarts++
      void this.start(e.spec)
    }, delay)
  }
}
