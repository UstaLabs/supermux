import { describe, it, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { TerminalManager, type TermProc, type SpawnFn } from "../src/core/terminal/manager"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../src/core/runtime/session-backend"
import { sessiondTerminalGroup, sessiondTerminalName } from "../src/core/terminal/sessiond-term"
import {
  WorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalSummary,
  type WorkspaceTerminalViewer,
} from "../src/core/terminal/workspace-backend"

// Hermetic: these tests spawn NOTHING real (no zmx, no tmux, no shell, no
// pty-helper) and cannot hang the runner on live process handles.
//
// WORKSPACE terminals are tested against an injected WorkspaceTerminalBackend,
// because that is now the whole of what the manager does with them: what is
// worth pinning here is the WIRING — which backend call a UI action turns into,
// which one a reconnect must NOT turn into, and who the events reach. Whether a
// backend honours those calls correctly is the shared contract suite's job
// (src/core/terminal/workspace-backend.contract.ts), run by each backend
// against itself.
//
// AGENT terminals still ride the agent tmux server through pty-helper, so they
// keep the fake subprocess below.

const STATE = mkdtempSync(join(tmpdir(), "muxterm-test-"))
const flush = () => new Promise((r) => setTimeout(r, 0))
const utf8 = (value: Uint8Array) => new TextDecoder().decode(value)

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

// ---------------------------------------------------------------------------
//  A recording workspace backend: what the manager asked for, and when
// ---------------------------------------------------------------------------

const keyOf = (key: WorkspaceTerminalKey) => `${key.scope}/${key.terminalId}`

class FakeWorkspaceViewer implements WorkspaceTerminalViewer {
  writes: string[] = []
  replies: string[] = []
  resizes: Array<[number, number]> = []
  focuses: Array<[boolean, number, number]> = []
  detached = false
  constructor(
    readonly key: WorkspaceTerminalKey,
    readonly viewerId: string,
    readonly emit: (event: WorkspaceTerminalEvent) => Promise<void>,
    private readonly world: FakeWorkspaceBackend,
  ) {}
  write(bytes: Uint8Array): boolean {
    if (this.detached) return false
    this.writes.push(utf8(bytes))
    return true
  }
  reply(bytes: Uint8Array): boolean {
    if (this.detached) return false
    this.replies.push(utf8(bytes))
    return true
  }
  async resize(cols: number, rows: number): Promise<void> { this.resizes.push([cols, rows]) }
  async focus(active: boolean, cols: number, rows: number): Promise<void> {
    this.focuses.push([active, cols, rows])
  }
  async detach(): Promise<void> {
    this.detached = true
    this.world.calls.push(`detach ${keyOf(this.key)} ${this.viewerId}`)
    this.world.live.delete(this)
  }
}

class FakeWorkspaceBackend implements WorkspaceTerminalBackend {
  calls: string[] = []
  targets = new Map<string, number>()
  live = new Set<FakeWorkspaceViewer>()
  creates = 0
  clock = 100
  attachGate?: Promise<void>

  async ensure(key: WorkspaceTerminalKey): Promise<void> {
    this.calls.push(`ensure ${keyOf(key)}`)
    if (this.targets.has(keyOf(key))) return
    this.creates++
    this.targets.set(keyOf(key), this.clock++)
  }
  async attachExisting(
    key: WorkspaceTerminalKey,
    viewerId: string,
    emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ): Promise<WorkspaceTerminalViewer> {
    this.calls.push(`attach ${keyOf(key)} ${viewerId}`)
    await this.attachGate
    if (!this.targets.has(keyOf(key))) {
      throw new WorkspaceTerminalError("target-not-found", `no workspace terminal ${keyOf(key)}`)
    }
    const viewer = new FakeWorkspaceViewer(key, viewerId, emit, this)
    this.live.add(viewer)
    return viewer
  }
  async list(scope: string): Promise<WorkspaceTerminalSummary[]> {
    this.calls.push(`list ${scope}`)
    return [...this.targets]
      .filter(([id]) => id.startsWith(`${scope}/`))
      .map(([id, createdAt]) => ({ scope, terminalId: id.slice(scope.length + 1), createdAt }))
      .sort((a, b) => a.createdAt - b.createdAt)
  }
  async exists(key: WorkspaceTerminalKey): Promise<boolean> {
    return this.targets.has(keyOf(key))
  }
  async close(key: WorkspaceTerminalKey): Promise<void> {
    this.calls.push(`close ${keyOf(key)}`)
    this.targets.delete(keyOf(key))
    for (const viewer of [...this.live]) {
      if (keyOf(viewer.key) === keyOf(key)) { viewer.detached = true; this.live.delete(viewer) }
    }
  }
  async closeScope(scope: string): Promise<void> {
    this.calls.push(`closeScope ${scope}`)
    for (const id of [...this.targets.keys()]) {
      if (id.startsWith(`${scope}/`)) this.targets.delete(id)
    }
  }
  async shutdownViewers(): Promise<void> {
    this.calls.push("shutdownViewers")
    for (const viewer of [...this.live]) { viewer.detached = true; this.live.delete(viewer) }
  }

  viewerFor(scope: string, terminalId: string, viewerId?: string): FakeWorkspaceViewer {
    const found = [...this.live].find(viewer =>
      keyOf(viewer.key) === `${scope}/${terminalId}` && (viewerId === undefined || viewer.viewerId === viewerId))
    if (!found) throw new Error(`no viewer for ${scope}/${terminalId}`)
    return found
  }
}

function makeMgr(workspaceBackend = new FakeWorkspaceBackend()) {
  const spawned: string[][] = []
  const mgr = new TerminalManager({
    stateDir: STATE,
    workspaceBackend,
    spawn: (cmd) => { spawned.push(cmd); return makeFakeProc() },
  })
  return { mgr, backend: workspaceBackend, spawned }
}

const baseAttach = { workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} }

describe("TerminalManager (workspace terminals)", () => {
  it("a UI 'new terminal' creates; a reconnect attaches and NEVER creates", async () => {
    const { mgr, backend } = makeMgr()
    expect((await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
    })).ok).toBe(true)
    expect(backend.calls).toEqual(["ensure w:one/t1", "attach w:one/t1 d"])

    mgr.detach("d", "w:one", "t1")
    backend.calls.length = 0
    // `tmux new-session -A` could not tell these apart. This must.
    expect((await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "attach",
    })).ok).toBe(true)
    expect(backend.calls).toEqual(["attach w:one/t1 d"])
    expect(backend.creates).toBe(1)
  })

  it("a reconnect to a terminal whose shell exited is refused, not resurrected", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    // The shell ended and the target is gone.
    await backend.close({ scope: "w:one", terminalId: "t1" })

    const result = await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "attach",
    })
    expect(result).toEqual({ ok: false, error: "no workspace terminal w:one/t1" })
    expect(backend.creates).toBe(1)
    expect(mgr.has("d", "w:one", "t1")).toBe(false)
  })

  it("the legacy intent attaches first and only creates when there is nothing there", async () => {
    const { mgr, backend } = makeMgr()
    // No `intent`: what every client sends today.
    expect((await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach })).ok).toBe(true)
    expect(backend.calls).toEqual(["attach w:one/t1 d", "ensure w:one/t1", "attach w:one/t1 d"])

    // A LIVE terminal is attached to, never re-created.
    mgr.detach("d", "w:one", "t1")
    await flush()
    backend.calls.length = 0
    expect((await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach })).ok).toBe(true)
    expect(backend.calls).toEqual(["attach w:one/t1 d"])
    expect(backend.creates).toBe(1)
  })

  it("detach keeps the target; close destroys it", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    expect(mgr.has("d", "w:one", "t1")).toBe(true)
    expect(await mgr.hasSession("w:one", "t1")).toBe(true)

    mgr.detach("d", "w:one", "t1")
    expect(mgr.has("d", "w:one", "t1")).toBe(false)
    expect(await mgr.hasSession("w:one", "t1")).toBe(true) // ← persists across detach

    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach })
    await mgr.close("w:one", "t1")
    expect(mgr.has("d", "w:one", "t1")).toBe(false)
    expect(await mgr.hasSession("w:one", "t1")).toBe(false)
  })

  it("lists a scope's terminals from the backend, oldest first", async () => {
    const { mgr } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t2", ...baseAttach, intent: "create" })
    expect((await mgr.listForSession("w:one")).map(entry => entry.id)).toEqual(["t1", "t2"])
  })

  it("killAllForSession closes exactly that scope", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    await mgr.attach({ deviceName: "d", sessionName: "w:two", terminalId: "t1", ...baseAttach, intent: "create" })
    await mgr.killAllForSession("w:one")
    expect(backend.calls.at(-1)).toBe("closeScope w:one")
    expect(await mgr.hasSession("w:one", "t1")).toBe(false)
    expect(await mgr.hasSession("w:two", "t1")).toBe(true)
  })

  it("a target exit fires onExit with what the backend knew; a detach does NOT", async () => {
    const { mgr, backend } = makeMgr()
    const exits: Array<[number, unknown]> = []
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
      onExit: (code, detail) => { exits.push([code, detail]) },
    })
    await backend.viewerFor("w:one", "t1").emit({ type: "exit", known: true, code: 3, signal: null })
    expect(exits).toEqual([[3, { known: true, code: 3, signal: null }]])
    expect(mgr.has("d", "w:one", "t1")).toBe(false)

    const detachExits: number[] = []
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t2", ...baseAttach, intent: "create",
      onExit: (code) => { detachExits.push(code) },
    })
    const viewer = backend.viewerFor("w:one", "t2")
    mgr.detach("d", "w:one", "t2")
    await viewer.emit({ type: "exit", known: true, code: 0, signal: null })
    expect(detachExits).toEqual([]) // detach is intentional → no exit frame
  })

  it("an unreaped exit reports its uncertainty rather than inventing a clean one", async () => {
    const { mgr, backend } = makeMgr()
    const exits: Array<[number, unknown]> = []
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
      onExit: (code, detail) => { exits.push([code, detail]) },
    })
    await backend.viewerFor("w:one", "t1").emit({ type: "exit", known: false, code: null, signal: null })
    expect(exits).toEqual([[0, { known: false, code: null, signal: null }]])
  })

  it("a backend failure reaches onFailure and is never reported as an exit", async () => {
    const { mgr, backend } = makeMgr()
    const exits: number[] = []
    const failures: string[] = []
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
      onExit: (code) => { exits.push(code) },
      onFailure: (reason) => { failures.push(reason) },
    })
    await backend.viewerFor("w:one", "t1").emit({
      type: "failure", code: "backend-unavailable", recoverable: true, message: "zmx helper exited",
    })
    expect(failures).toEqual(["zmx helper exited"])
    expect(exits).toEqual([])
    expect(await mgr.hasSession("w:one", "t1")).toBe(true)
  })

  it("a re-synchronising target tells the client to drop what it drew", async () => {
    const { mgr, backend } = makeMgr()
    const resets: number[] = []
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
      onReset: () => { resets.push(1) },
    })
    const viewer = backend.viewerFor("w:one", "t1")
    await viewer.emit({ type: "reset", epoch: "1" })
    await viewer.emit({ type: "replay-start", epoch: "1" })
    await viewer.emit({ type: "replay-end", epoch: "1" })
    await viewer.emit({ type: "reset", epoch: "2" })
    expect(resets).toHaveLength(2)
  })

  it("relays backend output to onData, and onData's promise is the backpressure path", async () => {
    const { mgr, backend } = makeMgr()
    const received: string[] = []
    let release!: () => void
    await mgr.attach({
      deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create",
      onData: (d) => {
        received.push(utf8(d))
        if (received.length === 1) return new Promise<void>((r) => { release = r })
      },
    })
    const viewer = backend.viewerFor("w:one", "t1")
    const first = viewer.emit({ type: "output", bytes: new TextEncoder().encode("chunk-1") })
    let firstDone = false
    void first.then(() => { firstDone = true })
    await flush()
    expect(received).toEqual(["chunk-1"])
    // The backend is told to wait: emitting is what it awaits before reading more.
    expect(firstDone).toBe(false)
    release()
    await first
    await viewer.emit({ type: "output", bytes: new TextEncoder().encode("chunk-2") })
    expect(received).toEqual(["chunk-1", "chunk-2"])
  })

  it("write, reply and resize route to the viewer and reject unknown terminals", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    const viewer = backend.viewerFor("w:one", "t1")
    expect(mgr.write("d", "w:one", "t1", new TextEncoder().encode("ls\n"))).toBe(true)
    expect(mgr.reply("d", "w:one", "t1", new TextEncoder().encode("\x1b[?62c"))).toBe(true)
    expect(mgr.resize("d", "w:one", "t1", 120, 40)).toBe(true)
    await flush()
    // Typing and a query answer stay apart: only the second is owner-gated.
    expect(viewer.writes).toEqual(["ls\n"])
    expect(viewer.replies).toEqual(["\x1b[?62c"])
    expect(viewer.resizes).toEqual([[120, 40]])

    expect(mgr.write("d", "w:one", "nope", new TextEncoder().encode("x"))).toBe(false)
    expect(mgr.reply("d", "w:one", "nope", new TextEncoder().encode("x"))).toBe(false)
    expect(mgr.resize("d", "w:one", "nope", 80, 24)).toBe(false)
  })

  it("focus is the BACKEND's to arbitrate: every claim goes to it, in order", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "phone", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "create" })
    await mgr.attach({ deviceName: "desktop", sessionName: "w:one", terminalId: "t", ...baseAttach })

    expect(mgr.focus("phone", "w:one", "t", true, 52, 24)).toBe(true)
    expect(mgr.focus("desktop", "w:one", "t", true, 118, 42)).toBe(true)
    expect(mgr.focus("phone", "w:one", "t", false)).toBe(true)
    await flush()

    expect(backend.viewerFor("w:one", "t", "phone").focuses).toEqual([[true, 52, 24], [false, 0, 0]])
    expect(backend.viewerFor("w:one", "t", "desktop").focuses).toEqual([[true, 118, 42]])

    // A background client may keep reporting layout; whether it lands is the
    // backend's lease decision, not a second arbitration here.
    expect(mgr.resize("phone", "w:one", "t", 60, 26)).toBe(true)
    await flush()
    expect(backend.viewerFor("w:one", "t", "phone").resizes).toEqual([[60, 26]])
  })

  it("a focus claim with no usable geometry is refused", async () => {
    const { mgr } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "create" })
    expect(mgr.focus("d", "w:one", "t", true)).toBe(false)
    expect(mgr.focus("d", "w:one", "t", true, 0, 24)).toBe(false)
  })

  it("a second attach for one key replaces the first viewer", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "create" })
    const first = backend.viewerFor("w:one", "t")
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach })
    await flush()
    expect(first.detached).toBe(true)
    expect(mgr.has("d", "w:one", "t")).toBe(true)
    expect(backend.live.size).toBe(1)
  })

  it("a superseded in-flight attach drops its late viewer and reports the replacement", async () => {
    const backend = new FakeWorkspaceBackend()
    await backend.ensure({ scope: "w:one", terminalId: "t" })
    const { mgr } = makeMgr(backend)
    let release!: () => void
    backend.attachGate = new Promise<void>(resolve => { release = resolve })

    const first = mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "attach" })
    const second = mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "attach" })
    release()
    const [firstResult, secondResult] = await Promise.all([first, second])
    expect(firstResult.ok).toBe(false)
    expect(secondResult.ok).toBe(true)
    expect(backend.live.size).toBe(1)
    expect(mgr.has("d", "w:one", "t")).toBe(true)
  })

  it("a close racing an attach leaves no viewer and no target", async () => {
    const backend = new FakeWorkspaceBackend()
    await backend.ensure({ scope: "w:one", terminalId: "t" })
    const { mgr } = makeMgr(backend)
    let release!: () => void
    backend.attachGate = new Promise<void>(resolve => { release = resolve })

    const attaching = mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "attach" })
    await flush()
    await mgr.close("w:one", "t")
    release()
    expect((await attaching).ok).toBe(false)
    expect(mgr.has("d", "w:one", "t")).toBe(false)
    expect(backend.live.size).toBe(0)
    expect(await mgr.hasSession("w:one", "t")).toBe(false)
  })

  it("shutdown detaches every viewer and destroys nothing", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t2", ...baseAttach, intent: "create" })
    expect(mgr.count()).toBe(2)
    mgr.shutdown()
    await flush()
    expect(mgr.count()).toBe(0)
    expect(backend.live.size).toBe(0)
    // The targets outlive the broker; that is the whole point of them.
    expect(await mgr.hasSession("w:one", "t1")).toBe(true)
  })

  it("a device disconnect detaches only its own viewers", async () => {
    const { mgr, backend } = makeMgr()
    await mgr.attach({ deviceName: "phone", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "create" })
    await mgr.attach({ deviceName: "desktop", sessionName: "w:one", terminalId: "t", ...baseAttach })
    mgr.detachAllForDevice("phone")
    await flush()
    expect(mgr.has("phone", "w:one", "t")).toBe(false)
    expect(mgr.has("desktop", "w:one", "t")).toBe(true)
    expect(await mgr.hasSession("w:one", "t")).toBe(true)
  })

  it("a workspace terminal never spawns a broker child", async () => {
    const { mgr, spawned } = makeMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t", ...baseAttach, intent: "create" })
    expect(spawned).toEqual([])
  })
})

describe("TerminalManager (agent terminals on tmux)", () => {
  function makeAgentMgr() {
    const spawnedArgs: string[][] = []
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      workspaceBackend: new FakeWorkspaceBackend(),
      spawn: (cmd: string[]): TermProc => { spawnedArgs.push(cmd); return makeFakeProc() },
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })
    return { mgr, spawnedArgs, agentCalls }
  }

  it("attach builds a grouped-viewer argv; detach kills the viewer, not the agent", async () => {
    const { viewerSessionName } = await import("../src/core/terminal/agent-tmux")
    const { mgr, spawnedArgs, agentCalls } = makeAgentMgr()

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

  it("close destroys the grouped viewer, not the agent", async () => {
    const { viewerSessionName } = await import("../src/core/terminal/agent-tmux")
    const { mgr, agentCalls } = makeAgentMgr()
    await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s",
    })
    await mgr.close("s", "agent")
    await flush()
    const kills = agentCalls.filter((c) => c[0] === "kill-session")
    expect(kills.length).toBe(1)
    expect(kills[0]![2]).toBe(viewerSessionName("d", "mux:s"))
  })

  it("a missing agentTarget is refused rather than guessed at", async () => {
    const { mgr } = makeAgentMgr()
    expect(await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach, kind: "agent",
    })).toEqual({ ok: false, error: "agentTarget is required for kind=agent" })
  })

  it("a workspace detach does NOT call killViewer (agent cleanup is agent-only)", async () => {
    const { mgr, agentCalls } = makeAgentMgr()
    await mgr.attach({ deviceName: "d", sessionName: "w:one", terminalId: "t1", ...baseAttach, intent: "create" })
    mgr.detach("d", "w:one", "t1")
    await flush()
    expect(agentCalls.flat()).not.toContain("kill-session")
  })

  it("focus arbitration is the manager's for agents: the newest claim sizes the window", async () => {
    const { mgr, agentCalls } = makeAgentMgr()
    await mgr.attach({
      deviceName: "phone", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s",
    })
    await mgr.attach({
      deviceName: "desktop", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s",
    })
    expect(mgr.focus("phone", "s", "agent", true, 52, 24)).toBe(true)
    expect(mgr.focus("desktop", "s", "agent", true, 118, 42)).toBe(true)
    await flush()
    await flush()

    const forced = agentCalls.filter(call => call[0] === "resize-window")
    expect(forced.slice(-2).map(call => [call[4], call[6]])).toEqual([["52", "24"], ["118", "42"]])

    // A still-connected phone may reflow in the background, but it cannot steal
    // the window from the newer desktop focus claim.
    const before = agentCalls.length
    expect(mgr.resize("phone", "s", "agent", 60, 26)).toBe(true)
    await flush()
    expect(agentCalls.length).toBe(before)

    // Once desktop disappears, the phone's remembered latest geometry resumes.
    mgr.detach("desktop", "s", "agent")
    await flush()
    await flush()
    const fallback = agentCalls.filter(call => call[0] === "resize-window").at(-1)!
    expect([fallback[4], fallback[6]]).toEqual(["60", "26"])

    mgr.detach("phone", "s", "agent")
    await flush()
    await flush()
    expect(agentCalls.filter(call => call[0] === "set-window-option").at(-1)).toEqual([
      "set-window-option", "-t", expect.any(String), "window-size", "latest",
    ])
  })

  it("a natural agent viewer exit fires onExit; an intentional detach does NOT", async () => {
    const procs: FakeProc[] = []
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      workspaceBackend: new FakeWorkspaceBackend(),
      spawn: () => { const p = makeFakeProc(); procs.push(p); return p },
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })
    const naturalExits: number[] = []
    await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s", onExit: (c) => { naturalExits.push(c) },
    })
    procs[0]!.end(0)
    await flush()
    expect(naturalExits).toEqual([0])
  })

  it("relays agent viewer output to onData", async () => {
    const procs: FakeProc[] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      workspaceBackend: new FakeWorkspaceBackend(),
      spawn: () => { const p = makeFakeProc(); procs.push(p); return p },
      agentRun: async () => ({ code: 0, stdout: "", stderr: "" }),
    })
    let received = ""
    await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s", onData: (d) => { received += utf8(d) },
    })
    procs[0]!.emit("hello world")
    await flush()
    expect(received).toContain("hello world")
  })

  it("agent writes and resizes reach the viewer process stdin", async () => {
    const procs: FakeProc[] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      workspaceBackend: new FakeWorkspaceBackend(),
      spawn: () => { const p = makeFakeProc(); procs.push(p); return p },
      agentRun: async () => ({ code: 0, stdout: "", stderr: "" }),
    })
    await mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent", ...baseAttach,
      kind: "agent", agentTarget: "mux:s",
    })
    expect(mgr.write("d", "s", "agent", new TextEncoder().encode("ls\n"))).toBe(true)
    expect(mgr.resize("d", "s", "agent", 120, 40)).toBe(true)
    const all = procs[0]!.writes.map(utf8).join("")
    expect(all).toContain("ls\n")
    expect(all).toContain("R120:40")
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

// Windows lifecycle regressions. A workspace terminal here goes through
// SessiondWorkspaceBackend (ConPTY + @xterm/headless, NOT zmx); an agent pane
// still goes through createSessiondTerm, because an agent target belongs to
// whoever started it. None of this is runtime-verified: there is no Windows
// host in this environment, so these pin the contract, not the platform.
describe("TerminalManager (Windows sessiond)", () => {
  it("runs a workspace terminal on sessiond without touching pty-helper, and detaches without killing it", async () => {
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
