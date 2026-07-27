import { describe, it, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { TerminalManager, type TermProc, type SpawnFn } from "../src/core/terminal/manager"
import type { TmuxRunner } from "../src/core/terminal/tmux-term"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../src/core/runtime/session-backend"
import { sessiondTerminalGroup, sessiondTerminalName } from "../src/core/terminal/sessiond-term"

// Hermetic: a fake subprocess + a fake tmux "world" sharing in-memory state, so
// these tests spawn NOTHING real (no tmux, no shell, no pty-helper) and can't
// hang the runner on live process handles. Real tmux persistence is covered by
// the manual smoke test / e2e, not the unit suite.

const STATE = mkdtempSync(join(tmpdir(), "muxterm-test-"))
const flush = () => new Promise((r) => setTimeout(r, 0))

interface FakeProc extends TermProc {
  writes: Uint8Array[]
  emit(s: string): void
  end(code: number): void
}

function makeFakeProc(): FakeProc {
  let ctrl!: ReadableStreamDefaultController<Uint8Array>
  const stdout = new ReadableStream<Uint8Array>({ start(c) { ctrl = c } })
  let resolve!: (n: number) => void
  const exited = new Promise<number>((r) => { resolve = r })
  let done = false
  const finish = (code: number) => { if (done) return; done = true; try { ctrl.close() } catch {} ; resolve(code) }
  const writes: Uint8Array[] = []
  return {
    pid: 4242,
    writes,
    stdin: { write: (d) => { writes.push(typeof d === "string" ? new TextEncoder().encode(d) : d) } },
    stdout,
    exited,
    kill: () => finish(143),
    emit: (s) => { try { ctrl.enqueue(new TextEncoder().encode(s)) } catch {} },
    end: (code) => finish(code),
  }
}

/** In-memory stand-in for the muxterm tmux server: the fake spawn "creates" a
 * session (mirroring `tmux new-session -A`) and the fake runner queries it. */
function fakeTmuxWorld() {
  const sessions = new Map<string, number>()
  const procs: FakeProc[] = []
  let clock = 100
  const run: TmuxRunner = async (args) => {
    const cmd = args[0]
    const targetOf = () => args[args.indexOf("-t") + 1] ?? ""
    if (cmd === "list-sessions") {
      const stdout = [...sessions].map(([n, c]) => `${n}\t${c}`).join("\n")
      return { code: sessions.size ? 0 : 1, stdout, stderr: sessions.size ? "" : "no server running" }
    }
    if (cmd === "has-session") return { code: sessions.has(targetOf()) ? 0 : 1, stdout: "", stderr: "" }
    if (cmd === "kill-session") { sessions.delete(targetOf()); return { code: 0, stdout: "", stderr: "" } }
    return { code: 0, stdout: "", stderr: "" }
  }
  const spawn: SpawnFn = (cmd) => {
    if (cmd.includes("new-session")) {
      const name = cmd[cmd.indexOf("-s") + 1]!
      if (!sessions.has(name)) sessions.set(name, clock++)
    }
    const p = makeFakeProc()
    procs.push(p)
    return p
  }
  return { sessions, procs, run, spawn }
}

function makeMgr(world = fakeTmuxWorld()) {
  const mgr = new TerminalManager({ stateDir: STATE, socket: "test", run: world.run, spawn: world.spawn })
  return { mgr, world }
}

const baseAttach = { workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} }

describe("TerminalManager (hermetic)", () => {
  it("attach creates a tmux session; detach keeps it; re-attach reuses it", async () => {
    const { mgr } = makeMgr()
    expect((await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })).ok).toBe(true)
    expect(mgr.has("d", "s", "t1")).toBe(true)
    expect(await mgr.hasSession("s", "t1")).toBe(true)

    mgr.detach("d", "s", "t1")
    expect(mgr.has("d", "s", "t1")).toBe(false)
    expect(await mgr.hasSession("s", "t1")).toBe(true) // ← persists across detach

    expect((await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })).ok).toBe(true)
    expect(mgr.has("d", "s", "t1")).toBe(true)
  })

  it("close destroys the tmux session and viewer", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    await mgr.close("s", "t1")
    expect(mgr.has("d", "s", "t1")).toBe(false)
    expect(await mgr.hasSession("s", "t1")).toBe(false)
  })

  it("supports multiple terminals per session and lists them sorted", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach })
    const list = await mgr.listForSession("s")
    expect(list.map((l) => l.id)).toEqual(["t1", "t2"])
  })

  it("killAllForSession clears one session, leaves others", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s1", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s2", terminalId: "t1", ...baseAttach })
    await mgr.killAllForSession("s1")
    expect(await mgr.hasSession("s1", "t1")).toBe(false)
    expect(await mgr.hasSession("s2", "t1")).toBe(true)
  })

  it("a natural exit fires onExit; an intentional detach does NOT", async () => {
    const { mgr, world } = makeMgr()
    const naturalExits: number[] = []
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach, onExit: (c) => { naturalExits.push(c) } })
    world.procs[0]!.end(0) // shell/tmux ended on its own
    await flush()
    expect(naturalExits).toEqual([0])

    const detachExits: number[] = []
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach, onExit: (c) => { detachExits.push(c) } })
    mgr.detach("d", "s", "t2")
    await flush()
    expect(detachExits).toEqual([]) // detach is intentional → no exit frame
  })

  it("relays viewer output to onData", async () => {
    const { mgr, world } = makeMgr()
    let received = ""
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach, onData: (d) => { received += new TextDecoder().decode(d) } })
    world.procs[0]!.emit("hello world")
    await flush()
    expect(received).toContain("hello world")
  })

  it("backpressure: pauses reading until onData's returned promise resolves", async () => {
    const { mgr, world } = makeMgr()
    const received: string[] = []
    let release!: () => void
    mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach,
      onData: (d) => {
        received.push(new TextDecoder().decode(d))
        // First chunk only: return a pending promise to simulate a congested
        // socket. pumpOutput must not pull chunk 2 until we release().
        if (received.length === 1) return new Promise<void>((r) => { release = r })
      },
    })
    const proc = world.procs[0]!
    proc.emit("chunk-1")
    proc.emit("chunk-2")
    proc.emit("chunk-3")
    await flush()
    expect(received).toEqual(["chunk-1"]) // paused: 2 & 3 still buffered upstream

    release()
    await flush() // pumpOutput's awaited continuation resumes + drains (microtasks)
    await flush() // belt-and-suspenders macrotask margin; not load-bearing
    expect(received).toEqual(["chunk-1", "chunk-2", "chunk-3"]) // resumed, drained
  })

  it("write/resize target the viewer's stdin and reject unknown terminals", () => {
    const { mgr, world } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    expect(mgr.write("d", "s", "t1", new TextEncoder().encode("ls\n"))).toBe(true)
    expect(mgr.resize("d", "s", "t1", 120, 40)).toBe(true)
    // stdin saw the input plus the NUL-prefixed resize escape
    const all = world.procs[0]!.writes.map((w) => new TextDecoder().decode(w)).join("")
    expect(all).toContain("ls\n")
    expect(all).toContain("R120:40")
    expect(mgr.write("d", "s", "nope", new TextEncoder().encode("x"))).toBe(false)
    expect(mgr.resize("d", "s", "nope", 80, 24)).toBe(false)
  })

  it("shutdown detaches all viewers without destroying tmux sessions", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach })
    expect(mgr.count()).toBe(2)
    mgr.shutdown()
    expect(mgr.count()).toBe(0)
    expect(await mgr.hasSession("s", "t1")).toBe(true) // tmux sessions survive a broker stop
  })

  it("agent kind: attach builds a grouped-viewer argv; detach kills the viewer, not the agent", async () => {
    const { viewerSessionName } = await import("../src/core/terminal/agent-tmux")
    const spawnedArgs: string[][] = []
    const spawn = (cmd: string[]): TermProc => { spawnedArgs.push(cmd); return makeFakeProc() }
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      socket: "test",
      run: async () => ({ code: 0, stdout: "", stderr: "" }),
      spawn,
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })

    const r = await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent",
      ...baseAttach, kind: "agent", agentTarget: "mux:s",
    })
    expect(r.ok).toBe(true)

    // the spawned argv is an `sh -c` that builds the grouped viewer + attaches
    const argv = spawnedArgs.at(-1)!
    expect(argv).toContain("sh")
    const script = argv[argv.indexOf("sh") + 2]!
    expect(script).toContain("new-session -d")
    expect(script).toContain("exec tmux attach")
    expect(agentCalls.flat()).not.toContain("kill-session")

    mgr.detach("d", "s", "agent")
    await flush()
    const kills = agentCalls.filter((c) => c[0] === "kill-session")
    expect(kills.length).toBe(1)
    expect(kills[0]![2]).toBe(viewerSessionName("d", "mux:s"))
    expect(kills.flat()).not.toContain("mux:s")
  })

  it("agent kind: close destroys the grouped viewer, not the agent", async () => {
    const { viewerSessionName } = await import("../src/core/terminal/agent-tmux")
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE, socket: "test",
      run: async () => ({ code: 0, stdout: "", stderr: "" }),
      spawn: () => makeFakeProc(),
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach, kind: "agent", agentTarget: "mux:s" })
    await mgr.close("s", "agent")
    await flush()
    const kills = agentCalls.filter((c) => c[0] === "kill-session")
    expect(kills.length).toBe(1)
    expect(kills[0]![2]).toBe(viewerSessionName("d", "mux:s"))
  })

  it("scratch detach does NOT call killViewer (agent cleanup is agent-only)", async () => {
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE, socket: "test",
      run: async () => ({ code: 0, stdout: "", stderr: "" }),
      spawn: () => makeFakeProc(),
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    mgr.detach("d", "s", "t1")
    await flush()
    expect(agentCalls.flat()).not.toContain("kill-session")
  })
})

class ManagerBackend implements SessionBackend {
  targets = new Map<string, RuntimeTarget & { group: string }>()
  creates = 0
  kills: string[] = []
  viewerCloses = 0
  writes: string[] = []
  resizes: Array<[number, number]> = []
  attachGate?: Promise<void>
  killGate?: Promise<void>
  failKill?: Error
  failResolve?: Error
  failList?: Error
  viewerFailure?: (reason: string) => void
  private next = 1

  seed(group: string, name: string): RuntimeTarget {
    const target = { id: `win-${this.next++}`, name, pid: 9000 + this.next, alive: true, group }
    this.targets.set(target.id, target)
    return target
  }
  async create(opts: Parameters<SessionBackend["create"]>[0]): Promise<RuntimeTarget> {
    this.creates++
    return this.seed(opts.group, opts.name)
  }
  async list(group?: string): Promise<RuntimeTarget[]> {
    if (this.failList) throw this.failList
    return [...this.targets.values()].filter(target => target.alive && (group === undefined || target.group === group))
  }
  async resolve(group: string, name: string): Promise<string | null> {
    if (this.failResolve) throw this.failResolve
    return [...this.targets.values()].find(target => target.alive && target.group === group && target.name === name)?.id ?? null
  }
  async livePid(targetId: string): Promise<number | null> { return this.targets.get(targetId)?.pid ?? null }
  async write(_targetId: string, data: Uint8Array): Promise<void> { this.writes.push(new TextDecoder().decode(data)) }
  async sendKeys(): Promise<void> {}
  async resize(_targetId: string, cols: number, rows: number): Promise<void> { this.resizes.push([cols, rows]) }
  async capture(): Promise<string | null> { return null }
  async attach(targetId: string, _viewerId: string, _onData: (data: Uint8Array) => void | Promise<void>): Promise<RuntimeViewer> {
    await this.attachGate
    let open = true
    return {
      close: () => { if (open) { open = false; this.viewerCloses++ } },
      write: data => { if (!open) return false; this.writes.push(new TextDecoder().decode(data)); return true },
      resize: (cols, rows) => { if (!open) return false; this.resizes.push([cols, rows]); return true },
      onFailure: handler => {
        this.viewerFailure = handler
        return () => { if (this.viewerFailure === handler) this.viewerFailure = undefined }
      },
    }
  }
  async interrupt(): Promise<void> {}
  async kill(targetId: string): Promise<void> {
    this.kills.push(targetId)
    if (this.failKill) throw this.failKill
    await this.killGate
    const target = this.targets.get(targetId)
    if (target) { target.alive = false; target.pid = null }
  }
  failViewer(reason: string): void { this.viewerFailure?.(reason) }
}

function makeWindowsMgr(backend = new ManagerBackend(), spawn?: SpawnFn) {
  return {
    backend,
    mgr: new TerminalManager({
      platform: "win32",
      sessionBackend: backend,
      spawn: spawn ?? (() => { throw new Error("Windows must not touch pty-helper spawn") }),
      environment: { MUX_ENV: "yes" },
      findExecutable: () => "powershell.exe",
    }),
  }
}

describe("TerminalManager (Windows sessiond)", () => {
  it("selects SessiondTerm without touching pty-helper and detaches without killing scratch", async () => {
    let spawnCalls = 0
    const { mgr, backend } = makeWindowsMgr(undefined, () => { spawnCalls++; throw new Error("must not spawn") })
    expect((await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })).ok).toBe(true)
    expect(spawnCalls).toBe(0)
    expect(mgr.write("d", "s", "t", new TextEncoder().encode("dir\r"))).toBe(true)
    expect(mgr.resize("d", "s", "t", 120, 40)).toBe(true)
    mgr.detach("d", "s", "t")
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
    expect(await mgr.hasSession("s", "t")).toBe(true)
  })

  it("explicit scratch close kills its persistent target exactly once, including after detach", async () => {
    const { mgr, backend } = makeWindowsMgr()
    await mgr.attach({ deviceName: "d1", sessionName: "s", terminalId: "t", ...baseAttach })
    await mgr.attach({ deviceName: "d2", sessionName: "s", terminalId: "t", ...baseAttach })
    mgr.detach("d1", "s", "t")
    await mgr.close("s", "t")
    expect(backend.kills).toEqual(["win-1"])
    await mgr.close("s", "t")
    expect(backend.kills).toEqual(["win-1"])
  })

  it("agent attach/close only controls viewers and never creates or kills the Claude target", async () => {
    const backend = new ManagerBackend()
    const agent = backend.seed("mux", "claude")
    const { mgr } = makeWindowsMgr(backend)
    const result = await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: agent.id,
    })
    expect(result.ok).toBe(true)
    expect(backend.creates).toBe(0)
    await mgr.close("s", "agent")
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
  })

  it("lists, resolves, and removes only scratch targets owned by one broker session", async () => {
    const { mgr, backend } = makeWindowsMgr()
    backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("b"))
    backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("a"))
    backend.seed(sessiondTerminalGroup("other"), sessiondTerminalName("z"))
    expect((await mgr.listForSession("s")).map(term => term.id)).toEqual(["a", "b"])
    expect(await mgr.hasSession("s", "a")).toBe(true)
    await mgr.killAllForSession("s")
    expect(backend.kills.sort()).toEqual(["win-1", "win-2"])
    expect(await mgr.hasSession("other", "z")).toBe(true)
  })

  it("a superseded concurrent attach closes its late viewer exactly once", async () => {
    let release!: () => void
    const backend = new ManagerBackend()
    backend.attachGate = new Promise<void>(resolve => { release = resolve })
    const { mgr } = makeWindowsMgr(backend)
    const first = mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })
    const second = mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })
    release()
    const [firstResult, secondResult] = await Promise.all([first, second])
    expect(firstResult.ok).toBe(false)
    expect(secondResult.ok).toBe(true)
    expect(backend.viewerCloses).toBe(1)
    expect(mgr.has("d", "s", "t")).toBe(true)
  })

  it("explicit close racing an attach detaches the late viewer and leaves no scratch target", async () => {
    let release!: () => void
    const backend = new ManagerBackend()
    backend.attachGate = new Promise<void>(resolve => { release = resolve })
    const { mgr } = makeWindowsMgr(backend)
    const attaching = mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })
    await flush()
    const closing = mgr.close("s", "t")
    release()
    await closing
    expect((await attaching).ok).toBe(false)
    expect(mgr.has("d", "s", "t")).toBe(false)
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual(["win-1"])
  })

  it("surfaces a scratch target kill failure after closing its viewer", async () => {
    const { mgr, backend } = makeWindowsMgr()
    await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })
    backend.failKill = new Error("job termination denied")
    await expect(mgr.close("s", "t")).rejects.toThrow("job termination denied")
    expect(backend.viewerCloses).toBe(1)
    expect(mgr.has("d", "s", "t")).toBe(false)
  })

  it("surfaces resolve failure when closing a detached Windows scratch terminal", async () => {
    const { mgr, backend } = makeWindowsMgr()
    await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })
    mgr.detach("d", "s", "t")
    backend.failResolve = new Error("resolve unavailable")
    await expect(mgr.close("s", "t")).rejects.toThrow("resolve unavailable")
  })

  it("surfaces Windows session deletion list failures", async () => {
    const { mgr, backend } = makeWindowsMgr()
    backend.failList = new Error("sessiond list unavailable")
    await expect(mgr.killAllForSession("s")).rejects.toThrow("sessiond list unavailable")
  })

  it("surfaces Windows session deletion kill failures", async () => {
    const { mgr, backend } = makeWindowsMgr()
    backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("t"))
    backend.failKill = new Error("session cleanup kill denied")
    await expect(mgr.killAllForSession("s")).rejects.toThrow("session cleanup kill denied")
  })

  it("reports Windows viewer failure without reporting target exit or killing the target", async () => {
    const { mgr, backend } = makeWindowsMgr()
    const exits: number[] = []
    const failures: string[] = []
    await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach,
      onExit: code => { exits.push(code) },
      onFailure: reason => { failures.push(reason) },
    })
    backend.failViewer("viewer queue overflow")
    await flush()
    expect(failures).toEqual(["viewer queue overflow"])
    expect(exits).toEqual([])
    expect(backend.kills).toEqual([])
    expect(await mgr.hasSession("s", "t")).toBe(true)
  })

  it("explicit close racing an agent attach never kills the Claude target", async () => {
    let release!: () => void
    const backend = new ManagerBackend()
    const agent = backend.seed("mux", "claude")
    backend.attachGate = new Promise<void>(resolve => { release = resolve })
    const { mgr } = makeWindowsMgr(backend)
    const attaching = mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: agent.id,
    })
    await flush()
    await mgr.close("s", "agent")
    release()
    expect((await attaching).ok).toBe(false)
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
    expect(await backend.livePid(agent.id)).not.toBeNull()
  })

  it("serializes concurrent explicit scratch closes and kills the target once", async () => {
    let releaseKill!: () => void
    const backend = new ManagerBackend()
    backend.killGate = new Promise<void>(resolve => { releaseKill = resolve })
    const { mgr } = makeWindowsMgr(backend)
    await mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t", ...baseAttach })

    const first = mgr.close("s", "t")
    await flush()
    const second = mgr.close("s", "t")
    await flush()
    expect(backend.kills).toEqual(["win-1"])
    releaseKill()
    await Promise.all([first, second])
    expect(backend.kills).toEqual(["win-1"])
  })
})
