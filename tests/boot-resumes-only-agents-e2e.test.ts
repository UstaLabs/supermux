// Card #78 end to end: a real broker (throwaway state, MUX_TEST_BROKER=1, its own HOME) with real keepers and the
// library's echo `claude`, restarted the two ways it happens on his box:
// - SIGTERM (a restart, and as far as agents go a reboot: graceful shutdown stops every agent and its keeper);
// - SIGKILL (a crash or OOM kill: keepers outlive the broker, so their agents still run at the next boot).
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

  async start(): Promise<void> {
    const log = join(this.dir, `broker-${++this.boots}.log`)
    // Nothing from the session that runs the test (MUX_*, CLAUDE*, provider keys), and a HOME of its own, so the
    // broker can't write the real ~/.claude.json.
    this.broker = Bun.spawn(["bun", "src/main.ts"], {
      cwd: REPO,
      stdout: Bun.file(log),
      stderr: Bun.file(log),
      env: {
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
      },
    })
    // The web port opens after the boot resume.
    await until("the broker to answer", async () => (await fetch(`${this.base}/me`).then((r) => r.status).catch(() => 0)) === 401 || undefined)
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
