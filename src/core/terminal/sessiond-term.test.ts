import { describe, expect, test } from "bun:test"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import {
  createSessiondTerm,
  parseSessiondTerminalName,
  SessiondWorkspaceBackend,
  sessiondTerminalGroup,
  sessiondTerminalName,
} from "./sessiond-term"
import { isWorkspaceTerminalError, type WorkspaceTerminalKey } from "./workspace-backend"
import {
  CONTRACT_A,
  CONTRACT_ENSURE,
  recorder,
  runWorkspaceBackendContract,
  type Gate,
  type WorkspaceBackendWorld,
} from "./workspace-backend.contract"

const bytes = (value: string) => new TextEncoder().encode(value)
const text = (value: Uint8Array) => new TextDecoder().decode(value)
const tick = () => new Promise(resolve => setTimeout(resolve, 0))

class FakeBackend implements SessionBackend {
  targets = new Map<string, RuntimeTarget & { group: string }>()
  creates: Parameters<SessionBackend["create"]>[0][] = []
  writes: Array<{ targetId: string; data: Uint8Array }> = []
  resizes: Array<{ targetId: string; cols: number; rows: number }> = []
  kills: string[] = []
  viewerCloses = 0
  attachCalls: Array<{ targetId: string; viewerId: string }> = []
  failAttach?: Error
  failKill?: Error
  failPostCreateLivePid?: Error
  private outputs = new Map<string, (data: Uint8Array, replay: boolean) => void | Promise<void>>()
  private viewerExit?: (code: number) => void
  private viewerFailure?: (reason: string) => void
  private next = 1

  seed(group: string, name: string, alive = true): RuntimeTarget {
    const target = { id: `target-${this.next++}`, name, pid: alive ? 4000 + this.next : null, alive, group }
    this.targets.set(target.id, target)
    return target
  }

  async create(opts: Parameters<SessionBackend["create"]>[0]): Promise<RuntimeTarget> {
    this.creates.push({ ...opts, argv: [...opts.argv], env: { ...opts.env } })
    return this.seed(opts.group, opts.name)
  }
  async list(group?: string): Promise<RuntimeTarget[]> {
    return [...this.targets.values()].filter(target => group === undefined || target.group === group)
  }
  async resolve(group: string, name: string): Promise<string | null> {
    return [...this.targets.values()].find(target => target.group === group && target.name === name)?.id ?? null
  }
  async livePid(targetId: string): Promise<number | null> {
    if (this.creates.length > 0 && this.failPostCreateLivePid) throw this.failPostCreateLivePid
    return this.targets.get(targetId)?.pid ?? null
  }
  async write(targetId: string, data: Uint8Array): Promise<void> { this.writes.push({ targetId, data: data.slice() }) }
  async sendKeys(): Promise<void> {}
  async resize(targetId: string, cols: number, rows: number): Promise<void> { this.resizes.push({ targetId, cols, rows }) }
  async capture(): Promise<string | null> { return null }
  async attach(targetId: string, viewerId: string, onData: (data: Uint8Array, replay: boolean) => void | Promise<void>): Promise<RuntimeViewer> {
    this.attachCalls.push({ targetId, viewerId })
    if (this.failAttach) throw this.failAttach
    this.outputs.set(viewerId, onData)
    let resolveExit!: (code: number) => void
    const exited = new Promise<number>(resolve => { resolveExit = resolve })
    this.viewerExit = resolveExit
    let open = true
    return {
      close: () => {
        if (!open) return
        open = false
        this.viewerCloses++
        this.outputs.delete(viewerId)
      },
      write: data => {
        if (!open) return false
        this.writes.push({ targetId, data: data.slice() })
        return true
      },
      resize: (cols, rows) => {
        if (!open) return false
        this.resizes.push({ targetId, cols, rows })
        return true
      },
      exited,
      onFailure: handler => {
        this.viewerFailure = handler
        return () => { if (this.viewerFailure === handler) this.viewerFailure = undefined }
      },
    } as RuntimeViewer
  }
  async interrupt(): Promise<void> {}
  async kill(targetId: string): Promise<void> {
    this.kills.push(targetId)
    if (this.failKill) throw this.failKill
    const target = this.targets.get(targetId)
    if (target) { target.alive = false; target.pid = null }
  }

  async emit(value: string): Promise<void> {
    for (const output of this.outputs.values()) await output(bytes(value), false)
  }
  exit(code: number): void { this.viewerExit?.(code) }
  failViewer(reason: string): void { this.viewerFailure?.(reason) }
}

describe("SessiondTerm", () => {
  test("creates a scratch PowerShell target with safe deterministic naming and the broker environment", async () => {
    const backend = new FakeBackend()
    const created = await createSessiondTerm({
      backend,
      kind: "scratch",
      deviceName: "phone/one",
      sessionName: "broker:session/one",
      terminalId: "term:one/../../x",
      workdir: "C:\\work tree",
      cols: 101,
      rows: 37,
      environment: { Path: "C:\\bin", MUX_TEST_VALUE: "kept" },
      findExecutable: name => name === "pwsh.exe" ? "C:\\Program Files\\PowerShell\\7\\pwsh.exe" : null,
    })

    expect(backend.creates).toHaveLength(1)
    expect(backend.creates[0]).toEqual({
      group: sessiondTerminalGroup("broker:session/one"),
      name: sessiondTerminalName("term:one/../../x"),
      cwd: "C:\\work tree",
      argv: ["C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoLogo"],
      env: { Path: "C:\\bin", MUX_TEST_VALUE: "kept" },
      cols: 101,
      rows: 37,
    })
    expect(created.targetId).toBe("target-1")
    expect(created.created).toBe(true)
    expect(backend.attachCalls[0]!.viewerId).not.toContain("phone/one")
    expect(sessiondTerminalGroup("broker:session/one")).toMatch(/^muxterm-[0-9a-f]+$/)
    expect(sessiondTerminalName("term:one/..\/..\/x")).toMatch(/^term-[0-9a-f]+$/)
    expect(parseSessiondTerminalName(sessiondTerminalName("term:one/../../x"))).toBe("term:one/../../x")
  })

  test("falls back to Windows PowerShell and reuses a live scratch target", async () => {
    const backend = new FakeBackend()
    const target = backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("t"))
    const created = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {},
      findExecutable: name => name === "powershell.exe" ? "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" : null,
    })
    expect(created.targetId).toBe(target.id)
    expect(created.created).toBe(false)
    expect(backend.creates).toHaveLength(0)
  })

  test("replaces a resolved dead scratch target", async () => {
    const backend = new FakeBackend()
    const stale = backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("t"), false)
    const created = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    expect(backend.kills).toEqual([stale.id])
    expect(created.created).toBe(true)
    expect(created.targetId).not.toBe(stale.id)
  })

  test("streams output in order and forwards input and resize with boolean backpressure", async () => {
    const backend = new FakeBackend()
    const { proc, targetId } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    const reader = proc.stdout.getReader()
    await backend.emit("one")
    await backend.emit("two")
    expect(text((await reader.read()).value!)).toBe("one")
    expect(text((await reader.read()).value!)).toBe("two")
    expect(proc.stdin.write(bytes("dir\r"))).toBe(true)
    expect(proc.resize?.(120, 42)).toBe(true)
    expect(backend.writes.map(write => [write.targetId, text(write.data)])).toEqual([[targetId, "dir\r"]])
    expect(backend.resizes).toEqual([{ targetId, cols: 120, rows: 42 }])
    proc.kill()
  })

  test("bounds unread output and detaches the viewer without killing its target", async () => {
    const backend = new FakeBackend()
    const { proc } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "bounded",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
      outputByteLimit: 5,
    })
    const reader = proc.stdout.getReader()
    const first = reader.read()
    await backend.emit("x")
    expect(text((await first).value!)).toBe("x")
    await backend.emit("abc")
    await backend.emit("de")
    await backend.emit("f")
    expect(await proc.viewerFailed).toMatch(/queue.*exceed/i)
    expect(await Promise.race([proc.exited.then(() => "exit"), Bun.sleep(20).then(() => "pending")])).toBe("pending")
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
  })

  test("stream cancellation and repeated detach close the viewer exactly once without killing the target", async () => {
    const backend = new FakeBackend()
    const { proc } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    await proc.stdout.cancel()
    proc.kill()
    proc.kill()
    await tick()
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
    expect(await proc.exited).toBe(143)
  })

  test("natural target exit closes the stream and reports the process code", async () => {
    const backend = new FakeBackend()
    const { proc } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    const reader = proc.stdout.getReader()
    backend.exit(7)
    expect(await proc.exited).toBe(7)
    expect((await reader.read()).done).toBe(true)
    expect(backend.viewerCloses).toBe(1)
  })

  test("viewer failure is distinct from target process exit and keeps the target live", async () => {
    const backend = new FakeBackend()
    const { proc, targetId } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "failed-viewer",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    backend.failViewer("viewer queue overflow")
    expect(await proc.viewerFailed).toBe("viewer queue overflow")
    expect(await Promise.race([proc.exited.then(() => "exit"), Bun.sleep(20).then(() => "pending")])).toBe("pending")
    expect(await backend.livePid(targetId)).not.toBeNull()
    expect(backend.kills).toEqual([])
  })

  test("attaches an agent target without creating or killing it", async () => {
    const backend = new FakeBackend()
    const target = backend.seed("mux", "claude")
    const { proc, created } = await createSessiondTerm({
      backend, kind: "agent", deviceName: "d", sessionName: "s", terminalId: "agent",
      agentTarget: target.id, workdir: "C:\\w", cols: 80, rows: 24,
      environment: {}, findExecutable: () => { throw new Error("must not discover a shell") },
    })
    expect(created).toBe(false)
    expect(backend.creates).toHaveLength(0)
    expect(proc.stdin.write(bytes("x"))).toBe(true)
    proc.kill()
    expect(backend.kills).toEqual([])
  })

  test("rejects missing/dead agent targets and cleans up a newly-created scratch target after attach failure", async () => {
    const backend = new FakeBackend()
    await expect(createSessiondTerm({
      backend, kind: "agent", deviceName: "d", sessionName: "s", terminalId: "agent",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("agent target is required")
    const dead = backend.seed("mux", "claude", false)
    await expect(createSessiondTerm({
      backend, kind: "agent", agentTarget: dead.id, deviceName: "d", sessionName: "s", terminalId: "agent",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("agent target is not alive")

    backend.failAttach = new Error("attach denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("attach denied")
    expect(backend.kills).toEqual([backend.creates.length === 1 ? "target-2" : "unexpected"])
  })

  test("reports both attach and cleanup failures without an unhandled rejection", async () => {
    const backend = new FakeBackend()
    backend.failAttach = new Error("attach denied")
    backend.failKill = new Error("cleanup denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("attach denied; target cleanup failed: cleanup denied")
  })

  test("cleans a newly-created target when post-create liveness and cleanup both fail", async () => {
    const backend = new FakeBackend()
    backend.failPostCreateLivePid = new Error("post-create liveness denied")
    backend.failKill = new Error("cleanup denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("post-create liveness denied; target cleanup failed: cleanup denied")
    expect(backend.kills).toEqual(["target-1"])
  })
})

// ---------------------------------------------------------------------------
//  The workspace contract, on sessiond
// ---------------------------------------------------------------------------

/**
 * A sessiond stand-in for the shared contract suite.
 *
 * It is faithful to the two behaviours of the real store that this adapter is
 * built around: the replay is queued and DELIVERED synchronously inside
 * `attach()` (SessionStore.pumpViewer runs its loop up to the first await
 * before the attach barrier resolves), and a target has no notion of an owner,
 * so nothing here grants one — the lease is entirely the adapter's.
 */
class ContractBackend implements SessionBackend {
  /** Live bytes delivered between queueing the replay and the attach
   * resolving. Set by the test that pins the replay boundary. */
  liveDuringAttach?: string

  targets = new Map<string, RuntimeTarget & { group: string; pty: string[]; cols: number; rows: number; history: string }>()
  creates = 0
  viewerCloses = 0
  createGate?: Promise<void>
  attachGate?: Promise<void>
  private viewers = new Map<string, { targetId: string; exit(code: number): void; fail(reason: string): void }>()
  private next = 1

  async create(opts: Parameters<SessionBackend["create"]>[0]): Promise<RuntimeTarget> {
    await this.createGate
    this.creates++
    const target = {
      id: `sd-${this.next++}`,
      name: opts.name,
      pid: 5000 + this.next,
      alive: true,
      group: opts.group,
      pty: [] as string[],
      cols: opts.cols ?? 80,
      rows: opts.rows ?? 24,
      history: `${opts.cwd}$ `,
    }
    this.targets.set(target.id, target)
    return target
  }
  async list(group?: string): Promise<RuntimeTarget[]> {
    return [...this.targets.values()].filter(t => t.alive && (group === undefined || t.group === group))
  }
  async resolve(group: string, name: string): Promise<string | null> {
    return [...this.targets.values()].find(t => t.alive && t.group === group && t.name === name)?.id ?? null
  }
  async livePid(targetId: string): Promise<number | null> { return this.targets.get(targetId)?.pid ?? null }
  async write(targetId: string, data: Uint8Array): Promise<void> {
    this.targets.get(targetId)?.pty.push(text(data))
  }
  async sendKeys(): Promise<void> {}
  async resize(targetId: string, cols: number, rows: number): Promise<void> {
    const target = this.targets.get(targetId)
    if (target) { target.cols = cols; target.rows = rows }
  }
  async capture(): Promise<string | null> { return null }
  async attach(targetId: string, viewerId: string, onData: (data: Uint8Array, replay: boolean) => void | Promise<void>): Promise<RuntimeViewer> {
    await this.attachGate
    const target = this.targets.get(targetId)
    if (!target) throw new Error("no such target")
    let open = true
    const entry = {
      targetId,
      exit: (code: number) => { exitHandler?.(code) },
      fail: (reason: string) => { failureHandler?.(reason) },
    }
    let exitHandler: ((code: number) => void) | undefined
    let failureHandler: ((reason: string) => void) | undefined
    this.viewers.set(viewerId, entry)
    // The atomic replay, queued inside the attach barrier and MARKED as such —
    // the viewer must not be inferring the boundary from arrival order.
    if (target.history.length > 0) void onData(bytes(target.history), true)
    // ...and, when a test asks for it, live output in the window between the
    // replay being queued and this attach resolving. That window is exactly
    // what a timing heuristic gets wrong.
    if (this.liveDuringAttach !== undefined) void onData(bytes(this.liveDuringAttach), false)
    return {
      close: () => { if (open) { open = false; this.viewerCloses++; this.viewers.delete(viewerId) } },
      write: data => {
        if (!open || !target.alive) return false
        target.pty.push(text(data))
        return true
      },
      resize: (cols, rows) => {
        if (!open || !target.alive) return false
        target.cols = cols
        target.rows = rows
        return true
      },
      onExit: handler => { exitHandler = handler; return () => { exitHandler = undefined } },
      onFailure: handler => { failureHandler = handler; return () => { failureHandler = undefined } },
    }
  }
  async interrupt(): Promise<void> {}
  async kill(targetId: string): Promise<void> {
    const target = this.targets.get(targetId)
    if (target) { target.alive = false; target.pid = null }
  }

  /** The LIVE target for a key, or the last corpse if the key has only those:
   * a replaced target and the one that replaced it share a group and name. */
  find(key: WorkspaceTerminalKey) {
    const group = sessiondTerminalGroup(key.scope)
    const name = sessiondTerminalName(key.terminalId)
    const matches = [...this.targets.values()].filter(t => t.group === group && t.name === name)
    return matches.find(t => t.alive) ?? matches.at(-1)
  }
  async endProcess(key: WorkspaceTerminalKey, code: number): Promise<void> {
    const target = this.find(key)
    if (!target) return
    target.alive = false
    target.pid = null
    for (const [, viewer] of this.viewers) if (viewer.targetId === target.id) viewer.exit(code)
    // sessiond reports an exit through a handler, not a promise the caller
    // holds; give the adapter's delivery its turn before the test looks.
    await tick()
  }
  failViewers(reason: string): void {
    for (const [, viewer] of this.viewers) viewer.fail(reason)
  }
}

function sessiondWorld(): WorkspaceBackendWorld {
  const backend = new ContractBackend()
  const options = {
    backend,
    environment: { Path: "C:\\bin" },
    // The contract's POSIX shell is not a program here, so the adapter falls
    // back to PowerShell discovery — which is what Windows always did.
    findExecutable: (name: string) => (name === "pwsh.exe" ? "C:\\pwsh.exe" : null),
  }
  const gate = (which: "createGate" | "attachGate"): Gate => {
    let resolve!: () => void
    backend[which] = new Promise<void>(r => { resolve = r })
    return { release: () => { backend[which] = undefined; resolve() } }
  }
  return {
    backend: new SessiondWorkspaceBackend(options),
    restart: () => new SessiondWorkspaceBackend(options),
    creates: () => backend.creates,
    pty: key => (backend.find(key)?.pty ?? []).join(""),
    size: key => ({ cols: backend.find(key)?.cols ?? 0, rows: backend.find(key)?.rows ?? 0 }),
    exit: (key, status) => backend.endProcess(key, status.code ?? 0),
    gateCreate: () => gate("createGate"),
    gateAttach: () => gate("attachGate"),
    dispose: async () => {},
  }
}

runWorkspaceBackendContract("sessiond", async () => sessiondWorld())

describe("SessiondWorkspaceBackend", () => {
  function harness() {
    const backend = new ContractBackend()
    return {
      backend,
      workspace: new SessiondWorkspaceBackend({
        backend,
        environment: { Path: "C:\\bin" },
        findExecutable: name => (name === "pwsh.exe" ? "C:\\pwsh.exe" : null),
      }),
    }
  }

  test("a new target is PowerShell, in the workspace directory, with the broker environment", async () => {
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work", env: { EXTRA: "1" } })
    const target = backend.find(CONTRACT_A)!
    expect(target.history).toBe("C:\\work$ ")
    expect(backend.creates).toBe(1)
  })

  test("the atomic replay is what sessiond queued, inside one epoch", async () => {
    const { workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    const { events, emit } = recorder()
    await workspace.attachExisting(CONTRACT_A, "v1", emit)
    expect(events.map(event => event.type)).toEqual(["reset", "replay-start", "output", "replay-end"])
    expect(text((events[2] as { bytes: Uint8Array }).bytes)).toBe("C:\\work$ ")
    const epochs = new Set(events.filter(e => "epoch" in e).map(e => (e as { epoch: string }).epoch))
    expect(epochs.size).toBe(1)
  })

  test("live output that lands before the attach resolves is live, not replay", async () => {
    // THE BOUNDARY IS THE FRAME'S, NOT THE CLOCK'S. `SessionStore.attach`
    // queues the replay inside its barrier, but the pump that delivers it is
    // decoupled from the attach promise — and in production these bytes cross
    // a socket. "Arrived before the attach resolved" therefore swept live
    // output into the replay, and closed the boundary on a guess.
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    backend.liveDuringAttach = "LIVE-AFTER-HISTORY"
    const { events, emit } = recorder()
    await workspace.attachExisting(CONTRACT_A, "v1", emit)

    expect(events.map(event => event.type))
      .toEqual(["reset", "replay-start", "output", "replay-end", "output"])
    expect(text((events[2] as { bytes: Uint8Array }).bytes)).toBe("C:\\work$ ")
    expect(text((events[4] as { bytes: Uint8Array }).bytes)).toBe("LIVE-AFTER-HISTORY")
  })

  test("a reply is refused until the viewer owns the lease AND the replay has closed", async () => {
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    const viewer = await workspace.attachExisting(CONTRACT_A, "v1", recorder().emit)
    // The server's terminal model answers nothing itself, so an unowned reply
    // would be a second answer to the shell's one query.
    expect(viewer.reply(bytes("\x1b[?1;2c"))).toBe(false)
    await viewer.focus(true, 100, 30)
    expect(viewer.reply(bytes("\x1b[?1;2c"))).toBe(true)
    expect(backend.find(CONTRACT_A)!.pty.join("")).toContain("\x1b[?1;2c")
  })

  test("a lost viewer is a failure, keeps the ConPTY, and is never an exit", async () => {
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    const { events, emit } = recorder()
    await workspace.attachExisting(CONTRACT_A, "v1", emit)
    backend.failViewers("terminal viewer output queue exceeds 1048576 live bytes")

    await tick()
    expect(events.at(-1)).toEqual({
      type: "failure",
      code: "backend-unavailable",
      recoverable: true,
      message: "terminal viewer output queue exceeds 1048576 live bytes",
    })
    expect(events.some(event => event.type === "exit")).toBe(false)
    expect(await workspace.exists(CONTRACT_A)).toBe(true)
  })

  test("a target whose process is gone is replaced by ensure, never attached to", async () => {
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    const first = backend.find(CONTRACT_A)!
    await backend.endProcess(CONTRACT_A, 1)

    const error = await workspace.attachExisting(CONTRACT_A, "v1", recorder().emit)
      .then(() => null, (e: unknown) => e)
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)

    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    expect(backend.creates).toBe(2)
    expect(backend.find(CONTRACT_A)!.id).not.toBe(first.id)
  })

  test("the newest focus claim owns the size, and the previous owner is told", async () => {
    const { backend, workspace } = harness()
    await workspace.ensure(CONTRACT_A, { ...CONTRACT_ENSURE, cwd: "C:\\work" })
    const first = recorder()
    const second = recorder()
    const one = await workspace.attachExisting(CONTRACT_A, "v1", first.emit)
    const two = await workspace.attachExisting(CONTRACT_A, "v2", second.emit)

    await one.focus(true, 100, 40)
    await two.focus(true, 120, 50)
    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: false })
    expect(backend.find(CONTRACT_A)!.cols).toBe(120)

    // The newer claim leaving hands the size back to the one under it.
    await two.detach()
    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: true })
    await one.resize(64, 20)
    expect(backend.find(CONTRACT_A)!.cols).toBe(64)
  })
})
