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
import {
  CONTRACT_A,
  CONTRACT_ENSURE,
  recorder,
  runWorkspaceBackendContract,
  type Gate,
  type WorkspaceBackendWorld,
} from "./workspace-backend.contract"

// A RECORDING backend: an in-memory WorkspaceTerminalBackend used ONLY here, to
// pin the invariants every real backend (zmx on POSIX, sessiond on Windows) has
// to honour. It is the reference the shared contract suite was written against;
// zmx and sessiond run the same suite against themselves.
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
  /** Everything that reached the pty, in order. Input and replies are kept in
   * separate lists as well, because the SPLIT is part of the contract. */
  pty: string[]
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
  hooks: {
    beforeCreate?: () => Promise<void>
    beforeAttach?: () => Promise<void>
    /** Held INSIDE `close`, after it has decided and before the target goes. */
    beforeKill?: () => Promise<void>
  }
  /** The target's process ended on its own. The target is GONE — nothing respawns it. */
  exit(key: WorkspaceTerminalKey, status: Omit<Extract<WorkspaceTerminalEvent, { type: "exit" }>, "type">): Promise<void>
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
    async exit(key, status) {
      const target = world.targets.get(keyOf(key))
      if (!target) return
      world.targets.delete(keyOf(key))
      for (const viewer of [...target.viewers.values()]) {
        await viewer.deliver({ type: "exit", ...status })
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
    this.target.pty.push(new TextDecoder().decode(bytes))
    return true
  }

  reply(bytes: Uint8Array): boolean {
    if (!this.live()) return false
    // OWNER-ONLY, and it says so: a non-owner's reply is discarded, and
    // returning true would report a delivery that never happened.
    if (this.target.owner !== this.id) return false
    this.target.replies.push(bytes)
    this.target.pty.push(new TextDecoder().decode(bytes))
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
        pty: [],
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
    if (target.owner === viewerId) await viewer.deliver({ type: "owner", enabled: true })
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
    // The decision is synchronous and the destruction is not: this backend
    // takes the target out of reach FIRST, which is what makes an attach in
    // the gap answer `target-not-found` with nothing else to remember.
    this.world.targets.delete(keyOf(key))
    if (!target) return
    for (const viewer of [...target.viewers.values()]) viewer.kill()
    await this.world.hooks.beforeKill?.()
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

/** The recording backend as a contract world. */
function recordingWorld(): WorkspaceBackendWorld {
  const world = makeWorld()
  const gate = (hook: "beforeCreate" | "beforeAttach" | "beforeKill"): Gate => {
    let resolve!: () => void
    const promise = new Promise<void>(r => { resolve = r })
    world.hooks[hook] = () => promise
    return { release: () => { world.hooks[hook] = undefined; resolve() } }
  }
  return {
    backend: new RecordingBackend(world),
    restart: () => new RecordingBackend(world),
    creates: () => world.creates,
    pty: key => (world.targets.get(keyOf(key))?.pty ?? []).join(""),
    size: key => {
      const target = world.targets.get(keyOf(key))
      return { cols: target?.cols ?? 0, rows: target?.rows ?? 0 }
    },
    exit: (key, status) => world.exit(key, status),
    gateCreate: () => gate("beforeCreate"),
    gateAttach: () => gate("beforeAttach"),
    gateClose: () => gate("beforeKill"),
    dispose: async () => {},
  }
}

runWorkspaceBackendContract("recording", async () => recordingWorld())

describe("workspace terminal backend (recording specifics)", () => {
  test("input and terminal replies are kept apart on the way to the pty", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const viewer = await backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
    await viewer.focus(true, 80, 24)
    expect(viewer.write(new TextEncoder().encode("ls\r"))).toBe(true)
    expect(viewer.reply(new TextEncoder().encode("\x1b[?62;c"))).toBe(true)
    const target = world.targets.get(keyOf(CONTRACT_A))!
    expect(target.input).toHaveLength(1)
    expect(target.replies).toHaveLength(1)
  })

  test("a reconnect never calls ensure, in the call log as well as the count", async () => {
    const world = makeWorld()
    const backend = new RecordingBackend(world)
    await backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
    const first = await backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
    await first.detach()

    world.calls.length = 0
    await backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
    expect(world.calls.some(call => call.startsWith("ensure"))).toBe(false)
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
