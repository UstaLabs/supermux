import { describe, expect, test } from "bun:test"
import {
  isWorkspaceTerminalError,
  WorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalSummary,
  type WorkspaceTerminalViewer,
} from "./workspace-backend"

// A RECORDING backend: an in-memory WorkspaceTerminalBackend used ONLY here, to
// pin the invariants every real backend (zmx on POSIX, sessiond on Windows) has
// to honour. It records every call so a test can assert not just the end state
// but WHICH operations got there — "a reconnect never calls ensure" is a
// property of the call log, not of the target map.
//
// The target map lives in a `World` that OUTLIVES the backend instance, exactly
// as zmx targets outlive the broker process. Constructing a second backend over
// the same world is our stand-in for a broker restart.

type Target = {
  key: WorkspaceTerminalKey
  createdAt: number
  cols: number
  rows: number
  scrollback: Uint8Array
  input: Uint8Array[]
  replies: Uint8Array[]
  owner?: string
  viewers: Map<string, RecordingViewer>
}

type World = {
  targets: Map<string, Target>
  calls: string[]
  creates: number
  clock: number
  epoch: number
  pendingEnsure: Map<string, Promise<void>>
  hooks: { beforeCreate?: () => Promise<void>; beforeAttach?: () => Promise<void> }
  /** The target's process ended on its own. The target is GONE — nothing respawns it. */
  exit(key: WorkspaceTerminalKey, code: number): Promise<void>
}

const keyOf = (key: WorkspaceTerminalKey) => JSON.stringify([key.scope, key.terminalId])
const label = (key: WorkspaceTerminalKey) => `${key.scope}/${key.terminalId}`

function makeWorld(): World {
  const world: World = {
    targets: new Map(),
    calls: [],
    creates: 0,
    clock: 1000,
    epoch: 0,
    pendingEnsure: new Map(),
    hooks: {},
    async exit(key, code) {
      const target = world.targets.get(keyOf(key))
      if (!target) return
      world.targets.delete(keyOf(key))
      for (const viewer of [...target.viewers.values()]) {
        await viewer.deliver({ type: "exit", code })
        viewer.kill()
      }
    },
  }
  return world
}

class RecordingViewer implements WorkspaceTerminalViewer {
  detached = false
  cols = 0
  rows = 0
  constructor(
    private readonly world: World,
    private readonly target: Target,
    readonly id: string,
    private readonly emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ) {}

  /** Backend → viewer. Swallowed once the viewer is gone, never after detach. */
  async deliver(event: WorkspaceTerminalEvent): Promise<void> {
    if (this.detached) return
    await this.emit(event)
  }

  /** Drop the viewer WITHOUT touching the target (close / shutdown / detach). */
  kill(): void {
    this.detached = true
    this.target.viewers.delete(this.id)
    if (this.target.owner === this.id) this.target.owner = undefined
  }

  private live(): boolean {
    return !this.detached && this.world.targets.get(keyOf(this.target.key)) === this.target
  }

  write(bytes: Uint8Array): boolean {
    if (!this.live()) return false
    this.target.input.push(bytes)
    return true
  }

  reply(bytes: Uint8Array): boolean {
    if (!this.live()) return false
    this.target.replies.push(bytes)
    return true
  }

  async resize(cols: number, rows: number): Promise<void> {
    this.cols = cols
    this.rows = rows
    // Only the owner's geometry reaches the pty.
    if (this.live() && this.target.owner === this.id) {
      this.target.cols = cols
      this.target.rows = rows
    }
  }

  async focus(active: boolean, cols: number, rows: number): Promise<void> {
    this.world.calls.push(`focus ${label(this.target.key)} ${this.id} ${active}`)
    this.cols = cols
    this.rows = rows
    if (!this.live()) return
    if (!active) {
      if (this.target.owner !== this.id) return
      this.target.owner = undefined
      await this.deliver({ type: "owner", enabled: false })
      return
    }
    const previous = this.target.owner
    if (previous && previous !== this.id) {
      await this.target.viewers.get(previous)?.deliver({ type: "owner", enabled: false })
    }
    this.target.owner = this.id
    this.target.cols = cols
    this.target.rows = rows
    await this.deliver({ type: "owner", enabled: true })
  }

  async detach(): Promise<void> {
    this.world.calls.push(`detach ${label(this.target.key)} ${this.id}`)
    this.kill()
  }
}

class RecordingBackend implements WorkspaceTerminalBackend {
  constructor(private readonly world: World) {}

  async ensure(key: WorkspaceTerminalKey, options: {
    cwd: string; shell: string; env: Record<string, string>; cols: number; rows: number
  }): Promise<void> {
    this.world.calls.push(`ensure ${label(key)}`)
    const k = keyOf(key)
    const pending = this.world.pendingEnsure.get(k)
    if (pending) return pending
    if (this.world.targets.has(k)) return
    const create = (async () => {
      await this.world.hooks.beforeCreate?.()
      if (this.world.targets.has(k)) return
      this.world.creates++
      this.world.targets.set(k, {
        key,
        createdAt: this.world.clock++,
        cols: options.cols,
        rows: options.rows,
        scrollback: new TextEncoder().encode(`${options.cwd}$ `),
        input: [],
        replies: [],
        viewers: new Map(),
      })
    })()
    this.world.pendingEnsure.set(k, create)
    try {
      await create
    } finally {
      if (this.world.pendingEnsure.get(k) === create) this.world.pendingEnsure.delete(k)
    }
  }

  async attachExisting(
    key: WorkspaceTerminalKey,
    viewerId: string,
    emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ): Promise<WorkspaceTerminalViewer> {
    this.world.calls.push(`attach ${label(key)} ${viewerId}`)
    const missing = () =>
      new WorkspaceTerminalError("target-not-found", `no workspace terminal ${label(key)}`)
    if (!this.world.targets.has(keyOf(key))) throw missing()
    // Attaching is not instant. Anything may happen to the target while we are
    // in flight — including an explicit close — so re-check afterwards.
    await this.world.hooks.beforeAttach?.()
    const target = this.world.targets.get(keyOf(key))
    if (!target) throw missing()

    const epoch = `epoch-${++this.world.epoch}`
    const viewer = new RecordingViewer(this.world, target, viewerId, emit)
    target.viewers.set(viewerId, viewer)
    await viewer.deliver({ type: "reset", epoch })
    await viewer.deliver({ type: "replay-start", epoch })
    if (target.scrollback.length > 0) await viewer.deliver({ type: "output", bytes: target.scrollback })
    await viewer.deliver({ type: "replay-end", epoch })
    await viewer.deliver({ type: "owner", enabled: target.owner === viewerId })
    return viewer
  }

  async list(scope: string): Promise<WorkspaceTerminalSummary[]> {
    this.world.calls.push(`list ${scope}`)
    return [...this.world.targets.values()]
      .filter(t => t.key.scope === scope)
      .map(t => ({ scope: t.key.scope, terminalId: t.key.terminalId, createdAt: t.createdAt }))
      .sort((a, b) => a.createdAt - b.createdAt)
  }

  async exists(key: WorkspaceTerminalKey): Promise<boolean> {
    return this.world.targets.has(keyOf(key))
  }

  async close(key: WorkspaceTerminalKey): Promise<void> {
    this.world.calls.push(`close ${label(key)}`)
    const target = this.world.targets.get(keyOf(key))
    this.world.targets.delete(keyOf(key))
    if (!target) return
    for (const viewer of [...target.viewers.values()]) viewer.kill()
  }

  async closeScope(scope: string): Promise<void> {
    this.world.calls.push(`closeScope ${scope}`)
    for (const target of [...this.world.targets.values()]) {
      // EXACT scope, never a prefix: "w:a" must not take "w:ab" with it.
      if (target.key.scope === scope) await this.close(target.key)
    }
  }

  async shutdownViewers(): Promise<void> {
    this.world.calls.push("shutdownViewers")
    for (const target of this.world.targets.values()) {
      for (const viewer of [...target.viewers.values()]) viewer.kill()
    }
  }
}

const ENSURE = { cwd: "/w", shell: "/bin/bash", env: {}, cols: 80, rows: 24 }
const A: WorkspaceTerminalKey = { scope: "w:alpha", terminalId: "main" }
const B: WorkspaceTerminalKey = { scope: "w:alpha", terminalId: "second" }

function recorder() {
  const events: WorkspaceTerminalEvent[] = []
  return { events, emit: async (e: WorkspaceTerminalEvent) => { events.push(e) } }
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>(r => { resolve = r })
  return { promise, resolve }
}

describe("workspace terminal backend contract", () => {
  test("ensure creates once; a repeat ensure is a no-op", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    await backend.ensure(A, ENSURE)
    expect(world.creates).toBe(1)
    expect(await backend.exists(A)).toBe(true)
  })

  test("two simultaneous first creates yield one target", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    const gate = deferred()
    world.hooks.beforeCreate = () => gate.promise
    const both = Promise.all([backend.ensure(A, ENSURE), backend.ensure(A, ENSURE)])
    gate.resolve()
    await both
    expect(world.creates).toBe(1)
    expect(await backend.list("w:alpha")).toHaveLength(1)
  })

  test("attach opens ONE epoch and marks the replay boundary", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(A, "v1", emit)
    expect(events.map(e => e.type)).toEqual([
      "reset", "replay-start", "output", "replay-end", "owner",
    ])
    const epochs = events.filter(e => "epoch" in e).map(e => (e as { epoch: string }).epoch)
    expect(new Set(epochs).size).toBe(1)
    // The replayed bytes are INSIDE the boundary, never before `reset`.
    expect(events.findIndex(e => e.type === "output"))
      .toBeGreaterThan(events.findIndex(e => e.type === "replay-start"))
  })

  test("a reconnect attaches only — it never calls ensure", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const first = await backend.attachExisting(A, "v1", recorder().emit)
    await first.detach()

    world.calls.length = 0
    const again = await backend.attachExisting(A, "v1", recorder().emit)
    expect(again.write(new Uint8Array([0x6c]))).toBe(true)
    expect(world.calls.some(c => c.startsWith("ensure"))).toBe(false)
  })

  test("attach to a missing target rejects with target-not-found", async () => {
    const backend = new RecordingBackend(makeWorld())
    const error = await backend.attachExisting(A, "v1", recorder().emit).then(
      () => null,
      (e: unknown) => e,
    )
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
  })

  test("a target that EXITED is reported and never resurrected by attach", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const { events, emit } = recorder()
    await backend.attachExisting(A, "v1", emit)

    await world.exit(A, 0)
    expect(events.at(-1)).toEqual({ type: "exit", code: 0 })
    expect(await backend.exists(A)).toBe(false)

    // `tmux new-session -A` would hand back a brand-new shell here. We must not.
    const error = await backend.attachExisting(A, "v2", recorder().emit).then(() => null, (e: unknown) => e)
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
    expect(world.creates).toBe(1)
  })

  test("detach preserves the target", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const viewer = await backend.attachExisting(A, "v1", recorder().emit)
    await viewer.detach()

    expect(viewer.write(new Uint8Array([1]))).toBe(false)
    expect(await backend.exists(A)).toBe(true)
    expect(await backend.list("w:alpha")).toEqual([
      { scope: "w:alpha", terminalId: "main", createdAt: 1000 },
    ])
    await viewer.detach() // idempotent
  })

  test("close deletes the target and its viewers", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const viewer = await backend.attachExisting(A, "v1", recorder().emit)

    await backend.close(A)
    expect(await backend.exists(A)).toBe(false)
    expect(await backend.list("w:alpha")).toEqual([])
    expect(viewer.write(new Uint8Array([1]))).toBe(false)
    expect(viewer.reply(new Uint8Array([1]))).toBe(false)
    await backend.close(A) // idempotent
  })

  test("close during an IN-FLIGHT attach rejects and leaves no viewer behind", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const target = world.targets.get(keyOf(A))!

    const gate = deferred()
    world.hooks.beforeAttach = () => gate.promise
    const attaching = backend.attachExisting(A, "v1", recorder().emit)
    await backend.close(A)
    gate.resolve()

    const error = await attaching.then(() => null, (e: unknown) => e)
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
    expect(target.viewers.size).toBe(0)
    expect(await backend.exists(A)).toBe(false)
  })

  test("shutdownViewers closes viewers ONLY — targets keep running", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    await backend.ensure(B, ENSURE)
    const one = await backend.attachExisting(A, "v1", recorder().emit)
    const two = await backend.attachExisting(B, "v2", recorder().emit)

    await backend.shutdownViewers()
    expect(one.write(new Uint8Array([1]))).toBe(false)
    expect(two.write(new Uint8Array([1]))).toBe(false)
    expect(await backend.exists(A)).toBe(true)
    expect(await backend.exists(B)).toBe(true)
  })

  test("list survives a reconstructed manager", async () => {
    const world = makeWorld()
    const before = new RecordingBackend(world)
    await before.ensure(A, ENSURE)
    await before.ensure(B, ENSURE)
    await before.attachExisting(A, "v1", recorder().emit)
    await before.shutdownViewers()

    // Broker restart: brand-new backend object, same running targets.
    const after = new RecordingBackend(world)
    expect(await after.list("w:alpha")).toEqual([
      { scope: "w:alpha", terminalId: "main", createdAt: 1000 },
      { scope: "w:alpha", terminalId: "second", createdAt: 1001 },
    ])
    // And a reconnect after that restart still only ATTACHES.
    world.calls.length = 0
    await after.attachExisting(A, "v1", recorder().emit)
    expect(world.calls.some(c => c.startsWith("ensure"))).toBe(false)
  })

  test("closeScope touches exactly its own scope, not a neighbouring one", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    const neighbour: WorkspaceTerminalKey = { scope: "w:alphabet", terminalId: "main" }
    await backend.ensure(A, ENSURE)
    await backend.ensure(B, ENSURE)
    await backend.ensure(neighbour, ENSURE)

    await backend.closeScope("w:alpha")
    expect(await backend.list("w:alpha")).toEqual([])
    expect(await backend.list("w:alphabet")).toHaveLength(1)
  })

  test("size ownership follows the newest focus claim", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const first = recorder()
    const second = recorder()
    const one = await backend.attachExisting(A, "v1", first.emit)
    const two = await backend.attachExisting(A, "v2", second.emit)

    await one.focus(true, 100, 40)
    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: true })
    expect(world.targets.get(keyOf(A))!.cols).toBe(100)

    await two.focus(true, 120, 50)
    expect(first.events.at(-1)).toEqual({ type: "owner", enabled: false })
    expect(second.events.at(-1)).toEqual({ type: "owner", enabled: true })
    expect(world.targets.get(keyOf(A))!.cols).toBe(120)

    // A background viewer may keep reporting layout; it must NOT resize the pty.
    await one.resize(10, 10)
    expect(world.targets.get(keyOf(A))!.cols).toBe(120)
  })

  test("input and terminal replies are kept apart", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(A, ENSURE)
    const viewer = await backend.attachExisting(A, "v1", recorder().emit)
    expect(viewer.write(new TextEncoder().encode("ls\r"))).toBe(true)
    expect(viewer.reply(new TextEncoder().encode("\x1b[?62;c"))).toBe(true)
    const target = world.targets.get(keyOf(A))!
    expect(target.input).toHaveLength(1)
    expect(target.replies).toHaveLength(1)
  })

  test("a failure error converts to the failure event verbatim", () => {
    const error = new WorkspaceTerminalError("backend-unavailable", "zmx helper not running", true)
    expect(error.toEvent()).toEqual({
      type: "failure",
      code: "backend-unavailable",
      recoverable: true,
      message: "zmx helper not running",
    })
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(false)
  })
})
