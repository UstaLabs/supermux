import { afterAll, describe, expect, test } from "bun:test"
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { basename, join } from "path"
import {
  CONTRACT_A,
  CONTRACT_ENSURE,
  deferred,
  recorder,
  runWorkspaceBackendContract,
  type Gate,
  type WorkspaceBackendWorld,
} from "../workspace-backend.contract"
import {
  WorkspaceTerminalError,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
} from "../workspace-backend"
import {
  workspaceStartupEnvironment,
  WORKSPACE_ENV_ALLOWLIST,
  ZmxWorkspaceBackend,
  type ZmxBackendOptions,
  type ZmxHelperFacade,
  type ZmxProbe,
} from "./backend"
import type { HelperHandlers } from "./helper"
import { encodeName, socketBasename } from "./names"
import type { HelperCommandBody, HelperEvent } from "./protocol"

// A FAKE ZMX. Not a mock of our own backend — a stand-in for the patched DAEMON
// and the helper in front of it, faithful to the behaviours this backend has to
// cope with:
//
//   * `BrokerLease` goes to the WINNER of a focus claim and to nobody else, so
//     a superseded viewer is never told on the wire. If the fake told both,
//     the broker-side deduction this backend exists to do would be untested.
//   * A restore boundary opens on every attachment, with the DAEMON's epoch.
//   * On the owner leaving, the lease moves to the most recently attached
//     viewer, with a fresh lease of its own.
//   * A non-owner's reply is dropped by the daemon, silently.
//   * `kill` and `attach` verify `mux.target` before they touch a session.
//
// Nothing here spawns a process; Task 6 is what proves the real ones agree.

const encoder = new TextEncoder()

type FakeTarget = {
  name: string
  socket: string
  /** What `zmx list` reports: the SESSION pid, i.e. the shell. */
  pid: number
  createdAtSeconds: number
  cols: number
  rows: number
  scrollback: string
  pty: string[]
  owner: FakeHelper | null
  /** Attach order; the newest is the daemon's successor on a release. */
  viewers: FakeHelper[]
}

class FakeZmx {
  readonly targets = new Map<string, FakeTarget>()
  creates = 0
  clock = 1_700_000_000
  nextPid = 90_000
  /** A daemon busy flooding output never gets round to reading `.Kill`:
   * `cmdKill` still answers `ok`, and the target keeps running. */
  ignoreKill = false
  epoch = 0
  createGate?: Promise<void>
  attachGate?: Promise<void>
  killGate?: Promise<void>
  /** Every command any helper was asked to run, for order assertions. */
  readonly commands: HelperCommandBody[] = []
  readonly launched: FakeHelper[] = []
  /** Set to make the next probed label disagree with the key we asked for. */
  labelOverride?: string

  launch = async (handlers: HelperHandlers): Promise<ZmxHelperFacade> => {
    const helper = new FakeHelper(this, handlers)
    this.launched.push(helper)
    return helper
  }

  target(key: WorkspaceTerminalKey): FakeTarget | undefined {
    const name = encodeName(key)
    return [...this.targets.values()].find(entry => entry.name === name)
  }

  /** The target's PROCESS ended. Every viewer hears it from its own connection. */
  async exit(key: WorkspaceTerminalKey, status: { known: boolean; code: number | null; signal: number | null }): Promise<void> {
    const target = this.target(key)
    if (!target) return
    this.targets.delete(target.socket)
    for (const viewer of [...target.viewers]) {
      await viewer.deliver({ v: 1, ev: "exit", ...status })
    }
  }
}

class FakeHelper implements ZmxHelperFacade {
  #dead = false
  #attached?: FakeTarget

  constructor(private readonly world: FakeZmx, private readonly handlers: HelperHandlers) {}

  async deliver(event: HelperEvent): Promise<void> {
    await this.handlers.onEvent(event)
  }

  /** Pty bytes, the way the real helper hands them over: fire and forget.
   * Bun drains its stdout pipe whatever the broker does with them. */
  emitOutput(bytes: Uint8Array): void {
    void this.handlers.onOutput(bytes)
  }

  /** The daemon connection is gone. The real `ZmxHelper` turns a `lost` frame
   * (and a socket EOF) into exactly this, ON TOP of the event. */
  lose(message: string): void {
    this.#dead = true
    this.handlers.onFailure(new WorkspaceTerminalError("backend-unavailable", message, true))
  }

  async send<T = unknown>(command: HelperCommandBody): Promise<T> {
    this.world.commands.push(command)
    if (this.#dead) throw new WorkspaceTerminalError("backend-unavailable", "zmx helper is gone", true)
    switch (command.op) {
      case "create": return this.#create(command) as T
      case "attach": return this.#attach(command) as T
      case "list": return this.#list(command.dir) as T
      case "focus": return this.#focus(command) as T
      case "resize": return this.#resize(command) as T
      case "kill": return this.#kill(command) as T
      case "detach": {
        this.kill()
        return undefined as T
      }
    }
  }

  #verify(target: FakeTarget, name: string): void {
    const label = this.world.labelOverride ?? target.name
    if (label !== name) {
      throw new WorkspaceTerminalError("protocol", `zmx session is ${label}, not ${name}`)
    }
  }

  async #create(command: Extract<HelperCommandBody, { op: "create" }>): Promise<void> {
    await this.world.createGate
    const existing = this.world.targets.get(command.socket)
    if (existing) {
      // Adopting an existing session is still identity-checked.
      this.#verify(existing, command.name)
      return
    }
    this.world.creates++
    this.world.targets.set(command.socket, {
      name: command.name,
      socket: command.socket,
      pid: ++this.world.nextPid,
      createdAtSeconds: this.world.clock++,
      cols: command.cols,
      rows: command.rows,
      scrollback: `${command.cwd}$ `,
      pty: [],
      owner: null,
      viewers: [],
    })
  }

  async #attach(command: Extract<HelperCommandBody, { op: "attach" }>): Promise<void> {
    await this.world.attachGate
    const target = this.world.targets.get(command.socket)
    if (!target) throw new WorkspaceTerminalError("target-not-found", "ConnectionRefused")
    this.#verify(target, command.name)
    this.#attached = target
    target.viewers.push(this)
    // The daemon's restore boundary. Every broker attachment opens one, even
    // an empty one — a boundary is not the same as a missing one.
    const epoch = String(++this.world.epoch)
    await this.handlers.onEvent({ v: 1, ev: "welcome", version: 1, leaseGen: "7", pendingMax: 1 << 20, snapshotMax: 1 << 22 })
    await this.handlers.onEvent({ v: 1, ev: "replay-start", epoch, bytes: target.scrollback.length })
    if (target.scrollback.length > 0) await this.handlers.onOutput(encoder.encode(target.scrollback))
    await this.handlers.onEvent({ v: 1, ev: "replay-end", epoch, bytes: target.scrollback.length })
  }

  #list(dir: string): Array<Record<string, unknown>> {
    return [...this.world.targets.values()]
      .filter(target => target.socket.startsWith(`${dir}/`))
      .map(target => ({
        socket: basename(target.socket),
        name: target.name,
        pid: target.pid,
        createdAt: target.createdAtSeconds,
        clients: target.viewers.length,
      }))
  }

  async #focus(command: Extract<HelperCommandBody, { op: "focus" }>): Promise<void> {
    const target = this.#attached
    if (!target) throw new WorkspaceTerminalError("protocol", "not attached")
    if (command.active) {
      target.owner = this
      target.cols = command.cols
      target.rows = command.rows
      // ONLY the winner is told. This is the gap the backend deduces around.
      await this.deliver({ v: 1, ev: "lease", gen: String(++this.world.epoch), cols: command.cols, rows: command.rows })
      return
    }
    if (target.owner !== this) return
    target.owner = null
    await this.deliver({ v: 1, ev: "blur" })
    await this.#promote(target)
  }

  /** The daemon's successor policy: the most recently attached usable viewer. */
  async #promote(target: FakeTarget): Promise<void> {
    const successor = [...target.viewers].reverse().find(viewer => viewer !== this && !viewer.#dead)
    if (!successor) return
    target.owner = successor
    await successor.deliver({
      v: 1, ev: "lease", gen: String(++this.world.epoch), cols: target.cols, rows: target.rows,
    })
  }

  #resize(command: Extract<HelperCommandBody, { op: "resize" }>): void {
    const target = this.#attached
    if (!target || target.owner !== this) return // silently dropped, as the daemon does
    target.cols = command.cols
    target.rows = command.rows
  }

  async #kill(command: Extract<HelperCommandBody, { op: "kill" }>): Promise<void> {
    await this.world.killGate
    const target = this.world.targets.get(command.socket)
    if (!target) return // already gone is success
    this.#verify(target, command.name)
    // ANSWERED, NOT ACTED ON. `cmdKill` resolves when the daemon has been SENT
    // `.Kill`; whether it reads it is the daemon's business.
    if (this.world.ignoreKill) return
    this.world.targets.delete(command.socket)
    for (const viewer of [...target.viewers]) {
      // The daemon hangs up; a helper sees EOF and reports a LOST target,
      // never an exit.
      viewer.#dead = true
      viewer.handlers.onFailure(new WorkspaceTerminalError("backend-unavailable", "zmx session socket closed", true))
    }
  }

  write(data: Uint8Array): boolean {
    if (this.#dead || !this.#attached) return false
    if (!this.world.targets.has(this.#attached.socket)) return false
    this.#attached.pty.push(new TextDecoder().decode(data))
    return true
  }

  reply(data: Uint8Array): boolean {
    if (this.#dead || !this.#attached) return false
    // The daemon drops a non-owner's reply. The helper still accepted the
    // bytes, which is why the BACKEND is where a non-owner is told "no".
    if (this.#attached.owner !== this) return true
    this.#attached.pty.push(new TextDecoder().decode(data))
    return true
  }

  async close(): Promise<void> {
    this.kill()
  }

  kill(): void {
    if (this.#dead) return
    this.#dead = true
    const target = this.#attached
    this.#attached = undefined
    if (!target) return
    target.viewers = target.viewers.filter(viewer => viewer !== this)
    if (target.owner === this) {
      target.owner = null
      void this.#promote(target)
    }
  }
}

const dirs: string[] = []
function makeSocketDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "zmx-backend-test-"))
  dirs.push(dir)
  return dir
}
afterAll(() => {
  for (const dir of dirs) { try { rmSync(dir, { recursive: true, force: true }) } catch {} }
})

/** cwd/shell answers for a box that has neither of the contract's paths. */
const probe: ZmxProbe = {
  isDirectory: path => path === "/w",
  isExecutableFile: path => path === "/bin/bash" || path === "/usr/bin/fish" || path === "/opt/my shell/run",
  which: name => (name === "bash" ? "/bin/bash" : null),
}

const HOST_ENV = { HOME: "/home/tester", PATH: "/usr/bin", MUX_TOKEN: "secret", TERM: undefined }

function zmxWorld(): WorkspaceBackendWorld {
  const fake = new FakeZmx()
  const socketDir = makeSocketDir()
  const options: ZmxBackendOptions = { socketDir, launch: fake.launch, probe, hostEnv: HOST_ENV }
  const gate = (which: "createGate" | "attachGate" | "killGate"): Gate => {
    let resolve!: () => void
    fake[which] = new Promise<void>(r => { resolve = r })
    return { release: () => { fake[which] = undefined; resolve() } }
  }
  return {
    backend: new ZmxWorkspaceBackend(options),
    restart: () => new ZmxWorkspaceBackend(options),
    creates: () => fake.creates,
    pty: key => (fake.target(key)?.pty ?? []).join(""),
    size: key => ({ cols: fake.target(key)?.cols ?? 0, rows: fake.target(key)?.rows ?? 0 }),
    exit: (key, status) => fake.exit(key, status),
    gateCreate: () => gate("createGate"),
    gateAttach: () => gate("attachGate"),
    gateClose: () => gate("killGate"),
    dispose: async () => {},
  }
}

runWorkspaceBackendContract("zmx", async () => zmxWorld())

describe("ZmxWorkspaceBackend", () => {
  function harness(extra: Partial<ZmxBackendOptions> = {}) {
    const fake = new FakeZmx()
    const socketDir = makeSocketDir()
    const options: ZmxBackendOptions = { socketDir, launch: fake.launch, probe, hostEnv: HOST_ENV, ...extra }
    return { fake, socketDir, backend: new ZmxWorkspaceBackend(options), options }
  }

  test("create names the socket by key and carries the reversible label", async () => {
    const { fake, backend, socketDir } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const create = fake.commands.find(command => command.op === "create")!
    expect(create).toMatchObject({
      op: "create",
      name: encodeName(CONTRACT_A),
      socket: join(socketDir, socketBasename(CONTRACT_A)),
      cwd: "/w",
      cols: 80,
      rows: 24,
    })
  })

  test("the shell is an argv, resolved through PATH, and a login shell where it has one", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, shell: "bash" })
    expect((fake.commands[0] as { argv: string[] }).argv).toEqual(["/bin/bash", "-l"])

    const other = harness()
    // A path with a space must stay ONE argument; a shell string would split it.
    await other.backend.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, shell: "/opt/my shell/run" })
    expect((other.fake.commands[0] as { argv: string[] }).argv).toEqual(["/opt/my shell/run"])
  })

  test("a missing cwd, a missing shell and an unfindable shell all fail typed, creating nothing", async () => {
    const { fake, backend } = harness()
    for (const options of [
      { ...CONTRACT_ENSURE, cwd: "/gone" },
      { ...CONTRACT_ENSURE, cwd: "relative" },
      { ...CONTRACT_ENSURE, shell: "" },
      { ...CONTRACT_ENSURE, shell: "nosuchshell" },
      { ...CONTRACT_ENSURE, shell: "/bin/not-executable" },
    ]) {
      const error = await backend.ensure(CONTRACT_A, options).then(() => null, (e: unknown) => e)
      expect(error).toBeInstanceOf(WorkspaceTerminalError)
      expect((error as WorkspaceTerminalError).code).toBe("backend-unavailable")
    }
    expect(fake.creates).toBe(0)
  })

  test("a name that cannot fit is refused BEFORE anything is created", async () => {
    const { fake, backend } = harness()
    const huge: WorkspaceTerminalKey = { scope: "w:alpha", terminalId: "x".repeat(300) }
    const error = await backend.ensure(huge, CONTRACT_ENSURE).then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(WorkspaceTerminalError)
    expect((error as WorkspaceTerminalError).code).toBe("name-too-long")
    expect(fake.creates).toBe(0)
  })

  test("the startup environment is the allowlist, not the broker's environ", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, env: { EXTRA: "yes" } })
    const env = (fake.commands[0] as { env: Record<string, string> }).env
    expect(env.HOME).toBe("/home/tester")
    expect(env.PATH).toBe("/usr/bin")
    expect(env.EXTRA).toBe("yes")
    expect(env.SHELL).toBe("/bin/bash")
    expect(env.PWD).toBe("/w")
    // The broker's own configuration is not the shell's business.
    expect(env.MUX_TOKEN).toBeUndefined()
    // A terminal description the client's emulator actually implements.
    expect(env.TERM).toBe("xterm-256color")
    expect(WORKSPACE_ENV_ALLOWLIST).not.toContain("MUX_TOKEN")
  })

  test("the broker's own TERM is never inherited; an overlay still wins", () => {
    // The broker may well be running under `TERM=dumb` or none at all. What
    // the shell needs to know is what the CLIENT renders, which is neither.
    expect(workspaceStartupEnvironment({ TERM: "dumb" }).TERM).toBe("xterm-256color")
    expect(workspaceStartupEnvironment({ TERM: "dumb" }, { TERM: "xterm" }).TERM).toBe("xterm")
    expect(workspaceStartupEnvironment({}).COLORTERM).toBe("truecolor")
  })

  test("kill is identity-checked: a mismatched label refuses rather than destroying a stranger", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    // A 20-hex socket basename can collide. The label is what tells the two
    // apart, and a kill that cannot match it must not proceed.
    fake.labelOverride = encodeName({ scope: "w:someone-else", terminalId: "main" })
    const error = await backend.close(CONTRACT_A).then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(WorkspaceTerminalError)
    expect((error as WorkspaceTerminalError).code).toBe("protocol")
    fake.labelOverride = undefined
    expect(await backend.exists(CONTRACT_A)).toBe(true)
    const kill = fake.commands.find(command => command.op === "kill")!
    expect(kill).toMatchObject({ name: encodeName(CONTRACT_A) })
  })

  // ---- close means CLOSED -------------------------------------------------
  //
  // `cmdKill` answers when the daemon has been sent `.Kill`, not when it has
  // died, and a daemon busy flooding output measurably never acts on it (about
  // one run in six — vendor/zmx/VERIFICATION.md §6). A workspace whose delete
  // left a shell running is the failure; these pin the confirmation and the
  // escalation that now stand between the two.

  /** A stand-in process table: which pids exist, who their parent is, and what
   * a SIGKILL does to them. No real process is ever signalled. */
  class FakeProcesses {
    readonly killed: number[] = []
    readonly parents = new Map<number, number>()
    readonly dead = new Set<number>()
    /** What dying means for the world outside the process table. */
    onKill: (pid: number) => void = () => {}

    /** `list` reports the shell; the daemon is its parent. */
    daemonOf(sessionPid: number): number {
      const daemon = sessionPid - 1
      this.parents.set(sessionPid, daemon)
      return daemon
    }

    /** Whether this stand-in host has procfs. True by default: a fake without
     * it could not tell "that pid is gone" from "nothing here is knowable",
     * which is the distinction the escalation turns on. */
    parentageReadable = true

    readonly control = {
      daemonPidOf: (sessionPid: number) => this.parents.get(sessionPid) ?? null,
      canReadParentage: () => this.parentageReadable,
      isAlive: (pid: number) => !this.dead.has(pid),
      kill: (pid: number) => {
        this.killed.push(pid)
        this.dead.add(pid)
        this.onKill(pid)
      },
    }
  }

  test("close waits for the target to be GONE, not for the kill to be delivered", async () => {
    const processes = new FakeProcesses()
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 500 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const target = fake.target(CONTRACT_A)!
    const daemon = processes.daemonOf(target.pid)
    // The socket file a killed daemon never gets to unlink.
    writeFileSync(target.socket, "")

    fake.ignoreKill = true // the flooding daemon: answers `ok`, keeps running
    processes.onKill = pid => {
      // Killing the daemon is what actually ends the session.
      if (pid === daemon) fake.targets.delete(target.socket)
    }

    await backend.close(CONTRACT_A)

    // The daemon first — it owns the socket and the pty — and only pids the
    // DAEMON reported for our own label.
    expect(processes.killed[0]).toBe(daemon)
    expect(processes.killed).not.toContain(process.pid)
    expect(await backend.exists(CONTRACT_A)).toBe(false)
    // A killed daemon runs no teardown, so the socket it bound is ours to clear.
    expect(existsSync(target.socket)).toBe(false)
  })

  test("a session pid with no readable parent is NOT signalled", async () => {
    // THE PID-REUSE CASE, AND THE ONE THE OLD CHECK TRUSTED MOST. `list`
    // reports the shell; its parent is the daemon. A shell whose parent cannot
    // be read on a host that CAN read parents is a shell that has already
    // exited — so whatever answers to that pid now is either nothing or
    // somebody else's process, and it is the one pid we must not SIGKILL.
    // `sessionIsOurs` used to read that same absence as "ours".
    const processes = new FakeProcesses()
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 100 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const target = fake.target(CONTRACT_A)!
    // Deliberately NOT `processes.daemonOf(target.pid)`: no parent is recorded,
    // which is what an exited shell looks like through procfs.
    fake.ignoreKill = true

    await backend.close(CONTRACT_A).catch(() => {})

    expect(processes.killed).not.toContain(target.pid)
    expect(processes.killed).toEqual([])
  })

  test("where parentage cannot be read AT ALL, the shell pid is still signalled", async () => {
    // macOS has no procfs, so `daemonPidOf` is null for every pid and the
    // shell is all we have. Refusing to act there would turn the escalation
    // off on that platform rather than make it safer — a deleted workspace
    // leaving a live shell is the failure it exists for.
    const processes = new FakeProcesses()
    processes.parentageReadable = false
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 100 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const target = fake.target(CONTRACT_A)!
    fake.ignoreKill = true
    processes.onKill = pid => { if (pid === target.pid) fake.targets.delete(target.socket) }

    await backend.close(CONTRACT_A)

    expect(processes.killed).toEqual([target.pid])
  })

  test("a target that will not die is a typed failure, never a quiet success", async () => {
    const processes = new FakeProcesses()
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 100 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    processes.daemonOf(fake.target(CONTRACT_A)!.pid)
    fake.ignoreKill = true // nothing, including SIGKILL, makes it go

    const error = await backend.close(CONTRACT_A).then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(WorkspaceTerminalError)
    expect((error as WorkspaceTerminalError).code).toBe("backend-unavailable")
    expect((error as WorkspaceTerminalError).message).toContain("still running")
    expect(await backend.exists(CONTRACT_A)).toBe(true)
  })

  test("a target that dies on the Kill is never signalled", async () => {
    const processes = new FakeProcesses()
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 5_000 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    processes.daemonOf(fake.target(CONTRACT_A)!.pid)

    const started = Date.now()
    await backend.close(CONTRACT_A)

    expect(processes.killed).toEqual([])
    // Confirmed from the listing, which is the same authority `exists` uses —
    // and confirmed at once, not after the budget.
    expect(Date.now() - started).toBeLessThan(1_000)
    const ops = fake.commands.map(command => command.op)
    expect(ops.slice(ops.indexOf("kill"))).toEqual(["kill", "list"])
  })

  test("closeScope attempts every target even when one refuses to die", async () => {
    const processes = new FakeProcesses()
    const { fake, backend } = harness({ processes: processes.control, closeConfirmMs: 100 })
    const stubborn = { scope: "w:scope", terminalId: "flooding" }
    const quiet = { scope: "w:scope", terminalId: "quiet" }
    await backend.ensure(stubborn, CONTRACT_ENSURE)
    await backend.ensure(quiet, CONTRACT_ENSURE)
    // Only the flooded one ignores the kill; its neighbour must still go.
    fake.ignoreKill = true
    processes.daemonOf(fake.target(stubborn)!.pid)
    const quietDaemon = processes.daemonOf(fake.target(quiet)!.pid)
    const quietSocket = fake.target(quiet)!.socket
    processes.onKill = pid => { if (pid === quietDaemon) fake.targets.delete(quietSocket) }

    const error = await backend.closeScope("w:scope").then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(WorkspaceTerminalError)
    expect(await backend.exists(quiet)).toBe(false)
    expect(await backend.exists(stubborn)).toBe(true)
  })

  test("attach refuses a session whose label is not ours", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    fake.labelOverride = encodeName({ scope: "w:someone-else", terminalId: "main" })
    const error = await backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      .then(() => null, (e: unknown) => e)
    expect((error as WorkspaceTerminalError).code).toBe("protocol")
  })

  test("the reset carries the DAEMON's epoch, and a re-sync opens a new one", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(CONTRACT_A, "v1", emit)

    const first = (events[0] as { epoch: string }).epoch
    expect(events.map(event => event.type)).toEqual(["reset", "replay-start", "output", "replay-end"])

    // A mid-stream re-sync: the daemon opens another boundary, which the client
    // must be told to drop what it had for.
    const helper = fake.launched.at(-1)!
    await helper.deliver({ v: 1, ev: "replay-start", epoch: "99", bytes: 0 })
    await helper.deliver({ v: 1, ev: "replay-end", epoch: "99", bytes: 0 })
    expect(events.slice(4).map(event => event.type)).toEqual(["reset", "replay-start", "replay-end"])
    expect((events[4] as { epoch: string }).epoch).toBe("99")
    expect((events[4] as { epoch: string }).epoch).not.toBe(first)
  })

  test("a reply produced while the replay is still drawing never reaches the pty", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const viewer = await backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
    await viewer.focus(true, 80, 24)

    const helper = fake.launched.at(-1)!
    await helper.deliver({ v: 1, ev: "replay-start", epoch: "5", bytes: 0 })
    // The replay is drawing HISTORY. A query in it was answered once already;
    // this answer is keystrokes as far as the shell is concerned.
    expect(viewer.reply(encoder.encode("\x1b[?62c"))).toBe(false)
    await helper.deliver({ v: 1, ev: "replay-end", epoch: "5", bytes: 0 })
    expect(viewer.reply(encoder.encode("\x1b[?62c"))).toBe(true)
    expect(fake.target(CONTRACT_A)!.pty.join("")).toBe("\x1b[?62c")
  })

  test("losing the lease to another viewer is reported even though the daemon says nothing", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const first = recorder()
    const one = await backend.attachExisting(CONTRACT_A, "v1", first.emit)
    const two = await backend.attachExisting(CONTRACT_A, "v2", recorder().emit)
    await one.focus(true, 100, 40)
    await two.focus(true, 120, 50)

    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: false })
    // And the loser stops sending geometry the daemon would drop on the floor.
    await one.resize(60, 20)
    expect(fake.target(CONTRACT_A)!.cols).toBe(120)
    expect(fake.commands.filter(command => command.op === "resize")).toHaveLength(0)
  })

  test("releasing focus hands the lease to the daemon's successor, who is told", async () => {
    const { backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const first = recorder()
    const second = recorder()
    const one = await backend.attachExisting(CONTRACT_A, "v1", first.emit)
    const two = await backend.attachExisting(CONTRACT_A, "v2", second.emit)
    await two.focus(true, 120, 50)
    void one
    await two.focus(false, 120, 50)

    expect(second.events.at(-1)).toEqual({ type: "owner", enabled: false })
    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: true })
  })

  test("a lost target is a recoverable failure, never an exit", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(CONTRACT_A, "v1", emit)

    const helper = fake.launched.at(-1)!
    await helper.deliver({ v: 1, ev: "lost", message: "socket closed" })
    // The helper's own failure path is what the backend reports; a lost socket
    // says nothing about whether the shell is still running.
    helper.kill()
    expect(events.some(event => event.type === "exit")).toBe(false)
  })

  test("a viewer lost DURING its restore is told that much, since the daemon cannot tell it why", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(CONTRACT_A, "v1", emit)

    const helper = fake.launched.at(-1)!
    await helper.deliver({ v: 1, ev: "replay-start", epoch: "9", bytes: 4096 })
    // The daemon queued its `BrokerDetach{resync_required}` on the socket this
    // viewer had stopped reading, so what the helper sees is a closed
    // connection. The reason is gone; the moment is not.
    helper.lose("daemon closed the connection")
    await Bun.sleep(1)

    const failure = events.find(event => event.type === "failure")!
    expect(failure).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    expect((failure as { message: string }).message).toContain("daemon closed the connection")
    expect((failure as { message: string }).message).toContain("restore was still streaming")
    expect(events.some(event => event.type === "exit")).toBe(false)
  })

  test("a close the broker asked for is not reported to the client as a failure", async () => {
    const { backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(CONTRACT_A, "v1", emit)
    const before = events.length
    await backend.close(CONTRACT_A)
    expect(events.slice(before).filter(event => event.type === "failure")).toEqual([])
  })

  test("a viewer whose emit falls behind is dropped recoverably, and its queue is dropped with it", async () => {
    // AWAITING `emit` IS NOT BACKPRESSURE (vendor/zmx/VERIFICATION.md §5): Bun
    // drains the helper's pipe eagerly, so a slow callback does not slow the
    // daemon down, it only decides where the backlog piles up. This is the
    // bound that makes the answer "nowhere".
    const { fake, backend } = harness({ viewerPendingMax: 1024 })
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)

    const events: WorkspaceTerminalEvent[] = []
    const stall = deferred()
    let slow = false
    const viewer = await backend.attachExisting(CONTRACT_A, "slow", async event => {
      events.push(event)
      if (slow && event.type === "output") await stall.promise
    })
    const other = recorder()
    await backend.attachExisting(CONTRACT_A, "reader", other.emit)
    const helper = fake.launched.at(-2)!
    const outputs = () => events.filter(event => event.type === "output").length
    const delivered = outputs()

    slow = true
    helper.emitOutput(new Uint8Array(512)) // taken; `emit` blocks on it
    await Bun.sleep(1) // ...and really has, before anything queues behind it
    helper.emitOutput(new Uint8Array(512)) // queued: exactly at the cap
    expect(events.some(event => event.type === "failure")).toBe(false)
    helper.emitOutput(new Uint8Array(1)) // one byte over, and that is that
    stall.resolve()
    await Bun.sleep(5)

    const failure = events.find(event => event.type === "failure")
    expect(failure).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    expect((failure as { message: string }).message).toContain("resync_required")
    // The one chunk `emit` had already taken arrived; the 513 bytes behind it
    // did NOT. They would have been drawn on top of the next epoch.
    expect(outputs()).toBe(delivered + 1)
    expect(viewer.write(new Uint8Array([0x61]))).toBe(false)

    // The other viewer, and the target, never noticed.
    fake.launched.at(-1)!.emitOutput(new Uint8Array(64))
    await Bun.sleep(5)
    expect(other.events.some(event => event.type === "failure")).toBe(false)
    expect(other.events.filter(event => event.type === "output").length).toBeGreaterThan(1)
    expect(await backend.exists(CONTRACT_A)).toBe(true)
  })

  test("list reports milliseconds from the daemon's own clock, oldest first", async () => {
    const { fake, backend } = harness()
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    await backend.ensure({ scope: "w:alpha", terminalId: "second" }, CONTRACT_ENSURE)
    const listed = await backend.list("w:alpha")
    expect(listed.map(entry => entry.terminalId)).toEqual(["main", "second"])
    expect(listed[0]!.createdAt).toBe(fake.target(CONTRACT_A)!.createdAtSeconds * 1000)
  })
})
