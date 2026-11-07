// Card #78 end to end: a real broker (throwaway state, MUX_TEST_BROKER=1, its own HOME) with real keepers and the
// library's echo `claude`, restarted the ways it happens:
// - SIGTERM (a plain stop, and as far as agents go a reboot: graceful shutdown stops every agent and its keeper);
// - SIGKILL (a crash or OOM kill: keepers outlive the broker, so their agents still run at the next boot);
// - a restart under systemd, as `supermux setup` installs the broker (#29): its agents run on.
// After either, only the Agent and a worker whose agent still runs have keepers; the other workers are asleep
// (suspended) and the first message to one wakes it and gets the answer.
import { afterAll, expect, test } from "bun:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { Registry } from "../src/core/session-manager/registry"
import { DeviceStore } from "../src/channels/web/device-store"

const REPO = join(import.meta.dir, "..")
const AGENT = "00000000-0000-4000-8000-0000000000a1"
const WORKER_A = "00000000-0000-4000-8000-0000000000b1"
const WORKER_B = "00000000-0000-4000-8000-0000000000b2"
const fixtures: Fixture[] = []
afterAll(async () => { for (const f of fixtures) await f.dispose() })

function freePort(): number {
  const l = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } })
  const p = l.port
  l.stop(true)
  return p
}

function pidAlive(pid: number | undefined): boolean {
  if (!pid) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function until<T>(what: string, probe: () => Promise<T | undefined> | T | undefined, ms = 60_000): Promise<T> {
  const deadline = Date.now() + ms
  for (;;) {
    const value = await probe()
    if (value !== undefined && value !== false) return value
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`)
    await Bun.sleep(200)
  }
}

class Fixture {
  readonly dir = mkdtempSync(join(tmpdir(), "mux-boot-agents-e2e-"))
  readonly state = join(this.dir, "state")
  readonly port = freePort()
  readonly base = `http://127.0.0.1:${this.port}`
  readonly token: string
  private broker: ReturnType<typeof Bun.spawn> | undefined
  private boots = 0

  constructor() {
    fixtures.push(this)
    const workdir = join(this.dir, "workdir")
    mkdirSync(this.state, { recursive: true, mode: 0o700 })
    mkdirSync(workdir, { recursive: true })
    mkdirSync(join(this.dir, "home"), { recursive: true })
    const db = openDb(join(this.state, "db.sqlite3"))
    runMigrations(db, MIGRATIONS)
    const registry = new Registry(db)
    registry.register({ id: AGENT, name: "the-agent", agent: "claude", workdir, pid: 0, core: true, role: "personal_assistant" })
    registry.register({ id: WORKER_A, name: "worker-a", agent: "claude", workdir, pid: 0, core: true })
    registry.register({ id: WORKER_B, name: "worker-b", agent: "claude", workdir, pid: 0, core: true })
    db.close()
    this.token = new DeviceStore(join(this.state, "devices.json")).mint("boot-e2e").token
    // `claude` is the library's echo fixture, as in scripts/test-broker.sh: a turn really runs behind a keeper.
    const stub = join(this.dir, "stubbin")
    mkdirSync(stub, { recursive: true })
    writeFileSync(join(stub, "claude"), `#!/bin/sh\nMODE=echo EXPECT_DEFAULT_DENY=0 exec bun "${REPO}/packages/supermux-core/tests/fixtures/claude-agent.mjs" "$@"\n`)
    writeFileSync(join(stub, "tmux"), `#!/bin/sh\ncase "$1" in -V) echo "tmux 3.4";; esac\nexit 0\n`)
    chmodSync(join(stub, "claude"), 0o755)
    chmodSync(join(stub, "tmux"), 0o755)
  }

  /**
   * Nothing from the session that runs the test (MUX_*, CLAUDE*, provider keys), and a HOME of its own, so the broker
   * can't write the real ~/.claude.json.
   */
  private brokerEnv(): Record<string, string> {
    return {
      PATH: `${join(this.dir, "stubbin")}:${process.env.PATH}`,
      HOME: join(this.dir, "home"),
      MUX_TEST_BROKER: "1",
      MUX_HOME: this.dir,
      MUX_STATE_DIR: this.state,
      MUX_SOCKETS_DIR: join(this.state, "sockets"),
      MUX_WEB_PORT: String(this.port),
      MUX_WEB_PUBLIC_URL: this.base,
      MUX_UPDATE_CHECK: "0",
      MUX_CURATOR_ENABLED: "0",
      DO_NOT_TRACK: "1",
    }
  }

  async start(extraEnv: Record<string, string> = {}): Promise<void> {
    const log = join(this.dir, `broker-${++this.boots}.log`)
    this.broker = Bun.spawn(["bun", "src/main.ts"], { cwd: REPO, stdout: Bun.file(log), stderr: Bun.file(log), env: { ...this.brokerEnv(), ...extraEnv } })
    await this.answers()
  }

  /** The running broker catches SIGTERM: its graceful-shutdown listener is registered (/proc/<pid>/status SigCgt). */
  catchesSigterm(): boolean {
    const caught = readFileSync(`/proc/${this.broker!.pid}/status`, "utf8").match(/^SigCgt:\s*([0-9a-f]+)$/m)?.[1]
    return caught !== undefined && ((BigInt(`0x${caught}`) >> 14n) & 1n) === 1n
  }

  /** The web port opens after the boot resume. */
  private async answers(): Promise<void> {
    await until("the broker to answer", async () => (await fetch(`${this.base}/me`).then((r) => r.status).catch(() => 0)) === 401 || undefined)
  }

  /** The transient systemd user service the broker runs as, once [startUnit] started it. */
  private unit: string | undefined

  /** The broker as a systemd user service, as `supermux setup` installs it: the default KillMode=control-group. */
  async startUnit(): Promise<void> {
    this.unit = `supermux-e2e-${this.port}`
    const log = join(this.dir, "broker-unit.log")
    const env = Object.entries(this.brokerEnv()).map(([k, v]) => `--setenv=${k}=${v}`)
    const run = Bun.spawnSync(["systemd-run", "--user", "--quiet", "--collect", `--unit=${this.unit}`, `--working-directory=${REPO}`,
      "-p", `StandardOutput=append:${log}`, "-p", `StandardError=append:${log}`, ...env, process.execPath, "src/main.ts"])
    expect(run.exitCode, run.stderr.toString()).toBe(0)
    await this.answers()
  }

  systemctl(verb: "restart" | "stop"): void {
    expect(Bun.spawnSync(["systemctl", "--user", verb, this.unit!]).exitCode).toBe(0)
  }

  /** The unit's broker process; 0 when none runs. */
  mainPid(): number {
    return Number(Bun.spawnSync(["systemctl", "--user", "show", "-p", "MainPID", "--value", this.unit!]).stdout.toString().trim())
  }

  /** Waits for the broker after [before] (its process) to answer: a new process, with its web port open. */
  async restarted(before: number): Promise<void> {
    await until("a new broker process", () => { const now = this.mainPid(); return (now !== 0 && now !== before) || undefined })
    await this.answers()
  }

  /** POST /internal/<what> with the per-boot token, as the desktop app and `supermux connect --restart` send it. */
  async internal(what: "restart" | "expect-restart"): Promise<number> {
    const token = readFileSync(join(this.state, "stop-token"), "utf8").trim()
    return (await fetch(`${this.base}/internal/${what}`, { method: "POST", headers: { "x-supermux-stop-token": token } })).status
  }

  async stop(signal: "SIGTERM" | "SIGKILL"): Promise<void> {
    this.broker?.kill(signal)
    await this.broker?.exited
    this.broker = undefined
  }

  /** Session ids with a live keeper process, read from /proc (the keeper's SUPERMUX_KEEPER_SESSION_DIR). */
  keepers(): Map<string, number> {
    const root = join(this.state, "core", "claude", "keepers") + "/"
    const live = new Map<string, number>()
    for (const pid of readdirSync("/proc").filter((p) => /^\d+$/.test(p))) {
      let environ = ""
      try { environ = readFileSync(`/proc/${pid}/environ`, "utf8") } catch { continue }
      const dir = environ.split("\0").find((v) => v.startsWith("SUPERMUX_KEEPER_SESSION_DIR="))?.split("=")[1]
      if (dir?.startsWith(root)) live.set(dir.slice(root.length).replace(/\/$/, ""), Number(pid))
    }
    return live
  }

  /**
   * The session's keeper process, from its status.json, while it runs. The broker's own unit runs its processes with
   * this user's primary group, and Linux lets only a process with the same uid and gid read their /proc/<pid>/environ,
   * which [keepers] reads: a test run from a shell with another group (`sg`) cannot.
   */
  keeperPid(id: string): number | undefined {
    const file = join(this.state, "core", "claude", "keepers", id, "status.json")
    if (!existsSync(file)) return undefined
    const pid = (JSON.parse(readFileSync(file, "utf8")) as { keeperPid?: number }).keeperPid
    return pidAlive(pid) ? pid : undefined
  }

  /** The session's agent process runs (its keeper's status.json). */
  agentRuns(id: string): boolean {
    const file = join(this.state, "core", "claude", "keepers", id, "status.json")
    if (!existsSync(file)) return false
    const status = JSON.parse(readFileSync(file, "utf8")) as { agentPid?: number; agentExited?: number | null }
    return status.agentExited === undefined && pidAlive(status.agentPid)
  }

  async sessions(): Promise<Map<string, { status: string; phase?: string }>> {
    const rows = await fetch(`${this.base}/sessions`, { headers: { Authorization: `Bearer ${this.token}` } }).then((r) => r.json()) as Array<{ id: string; status: string; phase?: string }>
    return new Map(rows.map((r) => [r.id, r]))
  }

  /** Sends `text` to `id` as the app does and returns the agent's answer. */
  async converse(id: string, text: string): Promise<string> {
    const ws = await new Promise<WebSocket>((resolve, reject) => {
      const w = new WebSocket(`${this.base.replace("http", "ws")}/ws?clientSurface=web`, { headers: { Authorization: `Bearer ${this.token}` } } as never)
      w.onopen = () => resolve(w)
      w.onerror = (e) => reject(e)
    })
    try {
      ws.send(JSON.stringify({ type: "send", session: id, op: "reply", args: { text } }))
      return await until(`an answer from ${id}`, async () => {
        const log = await fetch(`${this.base}/sessions/${id}/messages`, { headers: { Authorization: `Bearer ${this.token}` } }).then((r) => r.json()) as Array<{ direction?: string; text?: string }>
        return log.find((m) => m.direction === "outbound" && m.text?.startsWith("Fixture reply:") && m.text.includes(text))?.text
      })
    } finally { ws.close() }
  }

  async dispose(): Promise<void> {
    if (this.broker) await this.stop("SIGTERM")
    if (this.unit) Bun.spawnSync(["systemctl", "--user", "stop", this.unit])
    // A unit's keepers (in scopes of their own) and their agents, by the pids their status records, for a test that
    // failed before its plain stop.
    for (const id of [AGENT, WORKER_A, WORKER_B]) {
      const file = join(this.state, "core", "claude", "keepers", id, "status.json")
      if (!existsSync(file)) continue
      const status = JSON.parse(readFileSync(file, "utf8")) as { keeperPid?: number; agentPid?: number }
      for (const pid of [status.keeperPid, status.agentPid]) if (pidAlive(pid)) { try { process.kill(pid!, "SIGKILL") } catch { /* gone */ } }
    }
    // Whatever a crash left running in this fixture.
    for (const pid of this.keepers().values()) { try { process.kill(pid, "SIGKILL") } catch { /* gone */ } }
    if (process.env.MUX_TEST_KEEP_FIXTURE === "1") console.log("fixture kept:", this.dir)
    else rmSync(this.dir, { recursive: true, force: true })
  }
}

test("after a restart only the Agent runs; a message wakes a worker and it answers", async () => {
  const f = new Fixture()
  await f.start()
  // First boot: the Agent is resumed, the workers are asleep.
  await until("the Agent's keeper", () => f.keepers().has(AGENT) || undefined)
  expect([...f.keepers().keys()]).toEqual([AGENT])
  // Both workers wake on their first message and answer.
  expect(await f.converse(WORKER_A, "hello a")).toContain("hello a")
  expect(await f.converse(WORKER_B, "hello b")).toContain("hello b")
  expect([...f.keepers().keys()].sort()).toEqual([AGENT, WORKER_A, WORKER_B].sort())

  // A restart (systemd's SIGTERM): graceful shutdown stops every agent, as a reboot does.
  await f.stop("SIGTERM")
  await until("every keeper to exit", () => f.keepers().size === 0 || undefined, 20_000)
  await f.start()
  await until("the Agent's keeper", () => f.keepers().has(AGENT) || undefined)
  expect([...f.keepers().keys()]).toEqual([AGENT])
  expect(f.agentRuns(AGENT)).toBe(true)
  expect(f.agentRuns(WORKER_A)).toBe(false)
  expect(f.agentRuns(WORKER_B)).toBe(false)
  // Every client lists them asleep (suspended), not crashed.
  const rows = await f.sessions()
  expect(rows.get(AGENT)?.status).toBe("active")
  for (const id of [WORKER_A, WORKER_B]) {
    expect(rows.get(id)?.status).toBe("suspended")
    expect(rows.get(id)?.phase).not.toBe("dead")
  }
  // Asleep, it still opens: its chat is there before anything wakes it.
  const asleep = await fetch(`${f.base}/sessions/${WORKER_A}/messages`, { headers: { Authorization: `Bearer ${f.token}` } })
  expect(asleep.status).toBe(200)
  expect(((await asleep.json()) as Array<{ text?: string }>).some((m) => m.text?.startsWith("Fixture reply:") && m.text.includes("hello a"))).toBe(true)
  expect(f.agentRuns(WORKER_A)).toBe(false)
  // The first message wakes the worker; it answers with its conversation intact.
  expect(await f.converse(WORKER_A, "after the restart")).toContain("after the restart")
  expect(f.agentRuns(WORKER_A)).toBe(true)
  expect((await f.sessions()).get(WORKER_A)?.status).toBe("active")
  expect(f.agentRuns(WORKER_B)).toBe(false)
}, 240_000)

test("after a crash a worker whose agent still runs is re-attached, not started again", async () => {
  const f = new Fixture()
  await f.start()
  expect(await f.converse(WORKER_A, "before the crash")).toContain("before the crash")
  const keeperBefore = f.keepers().get(WORKER_A)
  expect(keeperBefore).toBeDefined()

  // A crash (or an OOM kill): the broker dies, its keepers and their agents don't.
  await f.stop("SIGKILL")
  expect(f.keepers().get(WORKER_A)).toBe(keeperBefore!)
  await f.start()
  const rows = await f.sessions()
  expect(rows.get(WORKER_A)?.status).toBe("active")
  expect(rows.get(WORKER_B)?.status).toBe("suspended")
  // The same keeper: boot re-attached it and started no new process.
  expect(f.keepers().get(WORKER_A)).toBe(keeperBefore!)
  expect(f.keepers().has(WORKER_B)).toBe(false)
  expect(await f.converse(WORKER_A, "after the crash")).toContain("after the crash")
  expect(f.keepers().get(WORKER_A)).toBe(keeperBefore!)
}, 240_000)

/** This user's systemd answers `systemd-run --user` (a desktop, a server with lingering; not a container or CI). */
const userSystemd = process.platform === "linux"
  && Bun.spawnSync(["systemd-run", "--user", "--scope", "--quiet", "--collect", "--", "true"], { stdout: "ignore", stderr: "ignore" }).exitCode === 0

// #29: a restart must not take the agents down. Under the unit `supermux setup` installs (KillMode=control-group), a
// restart killed every keeper in the unit's cgroup, and the broker's own graceful shutdown stopped them as well: the
// turn in flight was lost on every restart. Keepers now run in scopes of their own, and a restart the broker expects
// (it scheduled it, or the desktop app said so first) leaves them to the broker that comes back.
test.skipIf(!userSystemd)("under systemd a restart keeps the agents running; a plain stop still stops them", async () => {
  const f = new Fixture()
  await f.startUnit()
  expect(await f.converse(WORKER_A, "before the restart")).toContain("before the restart")
  const keeper = f.keeperPid(WORKER_A)
  expect(keeper).toBeDefined()
  const agentKeeper = f.keeperPid(AGENT)

  // Restart broker, `supermux connect --restart`, `supermux update`: the broker restarts itself under its unit.
  let before = f.mainPid()
  expect(await f.internal("restart")).toBe(202)
  await f.restarted(before)
  expect(f.keeperPid(WORKER_A)).toBe(keeper!)
  expect(f.agentRuns(WORKER_A)).toBe(true)
  expect((await f.sessions()).get(WORKER_A)?.status).toBe("active")
  expect(await f.converse(WORKER_A, "after the restart")).toContain("after the restart")
  expect(f.keeperPid(WORKER_A)).toBe(keeper!)

  // The desktop app's Restart: it says so first, then restarts the unit itself.
  before = f.mainPid()
  expect(await f.internal("expect-restart")).toBe(204)
  f.systemctl("restart")
  await f.restarted(before)
  expect(f.keeperPid(WORKER_A)).toBe(keeper!)
  expect(await f.converse(WORKER_A, "after the app's restart")).toContain("after the app's restart")

  // A plain stop (`systemctl --user stop`, an uninstall): the agents stop with the broker.
  f.systemctl("stop")
  await until("every keeper to exit", () => (f.keeperPid(WORKER_A) === undefined && f.keeperPid(AGENT) === undefined) || undefined, 20_000)
  expect(agentKeeper === undefined || !f.agentRuns(AGENT)).toBe(true)
  expect(f.agentRuns(WORKER_A)).toBe(false)
}, 240_000)

/** A Telegram Bot API for a broker configured with a bot token: getMe, a long poll that never has updates, ok to the rest. */
function stubTelegram(): { root: string; polled: () => boolean; stop: () => void } {
  let polled = false
  const server = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    async fetch(req) {
      const method = new URL(req.url).pathname.split("/").pop()
      if (method === "getMe") {
        return Response.json({ ok: true, result: { id: 1, is_bot: true, first_name: "stub", username: "stub_bot", can_join_groups: false, can_read_all_group_messages: false, supports_inline_queries: false } })
      }
      if (method === "getUpdates") {
        polled = true
        await Bun.sleep(500)
        return Response.json({ ok: true, result: [] })
      }
      return Response.json({ ok: true, result: true })
    },
  })
  return { root: `http://127.0.0.1:${server.port}`, polled: () => polled, stop: () => server.stop(true) }
}

// #29: with Telegram configured, the broker's last boot step is grammy's long poll, which returns only at shutdown, and
// main.ts registered its SIGTERM/SIGINT listeners after it, so they never were. Every SIGTERM was a hard kill: no
// graceful shutdown, broker.pid left behind, the agents left to their parked deadline, and a restart could not be told
// from a stop. The listeners now come before the channels start.
test("with Telegram on, SIGTERM still runs the graceful shutdown: an announced restart keeps the agents, a plain stop stops them", async () => {
  const telegram = stubTelegram()
  const f = new Fixture()
  try {
    const env = { MUX_TELEGRAM_BOT_TOKEN: "123456:stub-token", MUX_TELEGRAM_API_ROOT: telegram.root }
    await f.start(env)
    await until("the broker's Telegram long poll", () => telegram.polled() || undefined)
    expect(f.catchesSigterm()).toBe(true)

    // An announced restart (the desktop app's Restart, a deploy that says so first): the agents stay for the next broker.
    expect(await f.converse(WORKER_A, "before the restart")).toContain("before the restart")
    const keeper = f.keeperPid(WORKER_A)
    expect(keeper).toBeDefined()
    expect(await f.internal("expect-restart")).toBe(204)
    await f.stop("SIGTERM")
    expect(f.keeperPid(WORKER_A)).toBe(keeper!)
    await f.start(env)
    expect(await f.converse(WORKER_A, "after the restart")).toContain("after the restart")
    expect(f.keeperPid(WORKER_A)).toBe(keeper!)

    // A plain stop: the graceful shutdown runs, and the agents stop with the broker.
    await f.stop("SIGTERM")
    await until("every keeper to exit", () => (f.keeperPid(WORKER_A) === undefined && f.keeperPid(AGENT) === undefined) || undefined, 20_000)
  } finally {
    telegram.stop()
  }
}, 240_000)
