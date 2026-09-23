// The Windows half of a workspace terminal.
//
// Windows does NOT run zmx. It runs sessiond, a broker-side service that owns
// ConPTY handles and a headless terminal model per target (core/sessiond/), and
// this file is the adapter between that and the platform-neutral
// WorkspaceTerminalBackend contract. Nothing here speaks the zmx protocol and
// nothing here should be read as if it did.
//
// What sessiond already gives us, and what it does not:
//
//   * ATOMIC REPLAY — yes, but only because the FRAME says so. `SessionStore
//     .attach` queues the target screen's raw history as one chunk INSIDE an
//     output-order barrier, so a viewer never sees a half-consumed escape
//     sequence and never misses a byte that was broadcast while it was
//     attaching. What the barrier does NOT give is a boundary the viewer can
//     observe: the pump that delivers those bytes is decoupled from the promise
//     `attach()` returns, and in production (win32 always uses
//     `SessiondBackend`) they cross a socket as separate trips. Treating
//     "arrived before the attach resolved" as the replay was therefore a timing
//     heuristic — and one that closes early lets a query answer for content
//     that scrolled past reach the pty as keystrokes. So every data frame
//     carries `replay` (core/sessiond/protocol.ts), and
//     `SessiondWorkspaceBackend` wraps exactly the chunks that say so in the
//     contract's `reset`/`replay-start`/`replay-end` boundary; it does not
//     re-implement or re-order them.
//
//   * A FOCUS LEASE — no. sessiond has no notion of one owner among several
//     viewers, so the lease lives HERE: the adapter tracks focus claims per
//     target, applies only the owner's geometry, and emits the `owner` event
//     itself. On zmx that authority is the daemon's; on Windows it is ours,
//     and the contract is what makes the two look the same to a client.
//
//   * QUERY ANSWERS — no, and that is the point. The server's terminal model
//     (core/sessiond/screen.ts, @xterm/headless) CONSUMES device-attribute and
//     status queries and its own generated answers go nowhere: nothing
//     subscribes to `Terminal.onData`. So the shell's query is answered by
//     viewers and by nobody else, and every answer past the first is read by
//     the shell as typed input. Two rules keep it to one: a reply must come
//     from the viewer that owns the lease, and it must come after the replay
//     boundary has closed — a replay hands a viewer the program's own earlier
//     bytes, queries among them, and an answer to one of those is not an
//     answer, it is keystrokes.
import { randomUUID } from "node:crypto"
import type { RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import {
  WorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalSummary,
  type WorkspaceTerminalViewer,
} from "./workspace-backend"

export type SessiondTerminalKind = "scratch" | "agent"
export type FindExecutable = (name: string) => string | null

export type SessiondTermOptions = {
  backend: SessionBackend
  kind: SessiondTerminalKind
  deviceName: string
  sessionName: string
  terminalId: string
  agentTarget?: string
  workdir: string
  cols: number
  rows: number
  environment?: Readonly<Record<string, string>>
  findExecutable?: FindExecutable
  outputByteLimit?: number
}

const encoder = new TextEncoder()

function hex(value: string): string {
  return Buffer.from(value, "utf8").toString("hex")
}

export function sessiondTerminalGroup(sessionName: string): string {
  return `muxterm-${hex(sessionName)}`
}

export function sessiondTerminalName(terminalId: string): string {
  return `term-${hex(terminalId)}`
}

export function parseSessiondTerminalName(name: string): string | null {
  if (!name.startsWith("term-")) return null
  const encoded = name.slice("term-".length)
  if (encoded.length === 0 || encoded.length % 2 !== 0 || !/^[0-9a-f]+$/.test(encoded)) return null
  try {
    const decoded = Buffer.from(encoded, "hex")
    if (decoded.toString("hex") !== encoded) return null
    return decoded.toString("utf8")
  } catch {
    return null
  }
}

function processEnvironment(source: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const environment: Record<string, string> = {}
  for (const [key, value] of Object.entries(source)) if (typeof value === "string") environment[key] = value
  return environment
}

function defaultFindExecutable(name: string): string | null {
  try {
    return Bun.which(name)
  } catch {
    return null
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function findPowerShell(findExecutable: FindExecutable = defaultFindExecutable): string {
  for (const name of ["pwsh.exe", "powershell.exe"]) {
    const found = findExecutable(name)
    if (found) return found
  }
  throw new Error("PowerShell was not found (tried pwsh.exe and powershell.exe)")
}

export class SessiondTerm {
  readonly pid?: number
  readonly stdout: ReadableStream<Uint8Array>
  readonly exited: Promise<number>
  readonly viewerFailed: Promise<string>
  readonly stdin: { write(data: Uint8Array | string): boolean }

  private viewer?: RuntimeViewer
  private controller?: ReadableStreamDefaultController<Uint8Array>
  private readonly outputQueue: Uint8Array[] = []
  private pendingOutputBytes = 0
  private waitingForPull = false
  private resolveExited!: (code: number) => void
  private resolveViewerFailed!: (reason: string) => void
  private unsubscribeExit?: () => void
  private unsubscribeFailure?: () => void
  private closed = false

  constructor(pid?: number, private readonly outputByteLimit = 1024 * 1024) {
    if (!Number.isInteger(outputByteLimit) || outputByteLimit < 0) {
      throw new RangeError("outputByteLimit must be a non-negative integer")
    }
    this.pid = pid
    this.exited = new Promise(resolve => { this.resolveExited = resolve })
    this.viewerFailed = new Promise(resolve => { this.resolveViewerFailed = resolve })
    this.stdout = new ReadableStream<Uint8Array>({
      start: controller => { this.controller = controller },
      pull: controller => {
        const data = this.outputQueue.shift()
        if (!data) {
          this.waitingForPull = true
          return
        }
        this.pendingOutputBytes -= data.byteLength
        controller.enqueue(data)
      },
      cancel: () => { this.detach(143) },
    }, { highWaterMark: 0 })
    this.stdin = {
      write: data => {
        if (this.closed || !this.viewer) return false
        const value = typeof data === "string" ? encoder.encode(data) : data
        try {
          return this.viewer.write(value)
        } catch {
          return false
        }
      },
    }
  }

  accept(data: Uint8Array): void {
    if (this.closed) return
    if (data.byteLength === 0) return
    if (data.byteLength > this.outputByteLimit) {
      this.fail(`terminal viewer output queue exceeds ${this.outputByteLimit} bytes`)
      return
    }
    const snapshot = data.slice()
    if (this.waitingForPull) {
      this.waitingForPull = false
      try { this.controller?.enqueue(snapshot) } catch { this.fail("terminal viewer output stream failed") }
      return
    }
    if (this.pendingOutputBytes + data.byteLength > this.outputByteLimit) {
      this.fail(`terminal viewer output queue exceeds ${this.outputByteLimit} bytes`)
      return
    }
    this.outputQueue.push(snapshot)
    this.pendingOutputBytes += data.byteLength
  }

  bind(viewer: RuntimeViewer): void {
    if (this.closed) {
      viewer.close()
      return
    }
    this.viewer = viewer
    if (viewer.onExit) this.unsubscribeExit = viewer.onExit(code => { this.detach(code) })
    else void viewer.exited?.then(code => { this.detach(code) }, () => { this.fail("viewer exit subscription failed") })
    this.unsubscribeFailure = viewer.onFailure?.(reason => { this.fail(reason) })
  }

  resize(cols: number, rows: number): boolean {
    if (this.closed || !this.viewer) return false
    try {
      return this.viewer.resize(cols, rows)
    } catch {
      return false
    }
  }

  kill(): void {
    this.detach(143)
  }

  private detach(code: number): void {
    if (this.closed) return
    this.closed = true
    const viewer = this.viewer
    this.viewer = undefined
    this.unsubscribeExit?.()
    this.unsubscribeExit = undefined
    this.unsubscribeFailure?.()
    this.unsubscribeFailure = undefined
    this.outputQueue.length = 0
    this.pendingOutputBytes = 0
    this.waitingForPull = false
    try { viewer?.close() } catch {}
    try { this.controller?.close() } catch {}
    this.resolveExited(code)
  }

  private fail(reason: string): void {
    if (this.closed) return
    this.closed = true
    const viewer = this.viewer
    this.viewer = undefined
    this.unsubscribeExit?.()
    this.unsubscribeExit = undefined
    this.unsubscribeFailure?.()
    this.unsubscribeFailure = undefined
    this.outputQueue.length = 0
    this.pendingOutputBytes = 0
    this.waitingForPull = false
    try { viewer?.close() } catch {}
    try { this.controller?.close() } catch {}
    this.resolveViewerFailed(reason)
  }
}

export async function createSessiondTerm(options: SessiondTermOptions): Promise<{
  proc: SessiondTerm
  targetId: string
  created: boolean
}> {
  const { backend } = options
  let targetId: string | undefined
  let created = false
  let proc: SessiondTerm | undefined

  try {
    if (options.kind === "agent") {
      if (!options.agentTarget) throw new Error("agent target is required")
      targetId = options.agentTarget
      if (await backend.livePid(targetId) === null) throw new Error("agent target is not alive")
    } else {
      const group = sessiondTerminalGroup(options.sessionName)
      const name = sessiondTerminalName(options.terminalId)
      const resolved = await backend.resolve(group, name)
      if (resolved && await backend.livePid(resolved) !== null) {
        targetId = resolved
      } else {
        if (resolved) await backend.kill(resolved)
        const shell = findPowerShell(options.findExecutable)
        const environment = options.environment ? { ...options.environment } : processEnvironment()
        const target = await backend.create({
          group,
          name,
          cwd: options.workdir,
          argv: [shell, "-NoLogo"],
          env: environment,
          cols: options.cols,
          rows: options.rows,
        })
        targetId = target.id
        created = true
      }
    }

    const activeProc = new SessiondTerm(await backend.livePid(targetId) ?? undefined, options.outputByteLimit)
    proc = activeProc
    const viewerId = `terminal-viewer-${randomUUID().replaceAll("-", "")}`
    const viewer = await backend.attach(targetId, viewerId, data => { activeProc.accept(data) })
    activeProc.bind(viewer)
    return { proc: activeProc, targetId, created }
  } catch (error) {
    proc?.kill()
    if (created && targetId) {
      try {
        await backend.kill(targetId)
      } catch (cleanupError) {
        throw new Error(
          `${errorMessage(error)}; target cleanup failed: ${errorMessage(cleanupError)}`,
          { cause: new AggregateError([error, cleanupError]) },
        )
      }
    }
    throw error
  }
}

// ---------------------------------------------------------------------------
//  The workspace contract, on sessiond
// ---------------------------------------------------------------------------

export interface SessiondWorkspaceOptions {
  backend: SessionBackend
  /** The environment a new target starts with. Defaults to the broker's own. */
  environment?: Readonly<Record<string, string>>
  findExecutable?: FindExecutable
}

/** Per-target state sessiond does not hold for us: who is watching, and which
 * of them owns the size. */
type SessiondTargetState = {
  key: WorkspaceTerminalKey
  viewers: Set<SessiondWorkspaceViewer>
  /** Focus claims, oldest first. The newest owns the size; when it leaves, the
   * one under it takes over. */
  claims: SessiondWorkspaceViewer[]
  owner: SessiondWorkspaceViewer | null
  /** True from the moment `close` decides to kill, so the viewer teardown that
   * follows is not reported to a client as "I lost your terminal". */
  closing: boolean
}

const keyId = (key: WorkspaceTerminalKey) => `${sessiondTerminalGroup(key.scope)}\0${sessiondTerminalName(key.terminalId)}`

export class SessiondWorkspaceBackend implements WorkspaceTerminalBackend {
  readonly #backend: SessionBackend
  readonly #environment?: Readonly<Record<string, string>>
  readonly #findExecutable?: FindExecutable
  readonly #targets = new Map<string, SessiondTargetState>()
  /** create/close serialisation, per logical target. */
  readonly #chain = new Map<string, Promise<unknown>>()
  /**
   * Targets whose close has been DECIDED but whose kill has not run yet.
   *
   * `close` forgets the state the moment it decides (it discards the viewers,
   * and a state with no viewers is dropped), so `closing` on that object
   * protects nothing afterwards: a concurrent `attachExisting` would allocate
   * a fresh state with `closing:false`, `resolve` would still find the target
   * -- the kill is behind the serialisation chain -- and a viewer would be
   * attached to a ConPTY that is about to be terminated. This map is the part
   * of the decision that survives the forget.
   */
  readonly #closing = new Map<string, Promise<void>>()

  constructor(options: SessiondWorkspaceOptions) {
    this.#backend = options.backend
    this.#environment = options.environment
    this.#findExecutable = options.findExecutable
  }

  #serialize<T>(key: WorkspaceTerminalKey, run: () => Promise<T>): Promise<T> {
    const id = keyId(key)
    const previous = this.#chain.get(id) ?? Promise.resolve()
    const next = previous.catch(() => undefined).then(run)
    // The chain link never rejects — one failed create must not poison every
    // later operation on that target — but the CALLER still sees the failure.
    const tail = next.then(() => undefined, () => undefined)
    this.#chain.set(id, tail)
    void tail.finally(() => { if (this.#chain.get(id) === tail) this.#chain.delete(id) })
    return next
  }

  #target(key: WorkspaceTerminalKey): SessiondTargetState {
    const id = keyId(key)
    let target = this.#targets.get(id)
    if (!target) {
      target = { key, viewers: new Set(), claims: [], owner: null, closing: false }
      this.#targets.set(id, target)
    }
    return target
  }

  #forget(target: SessiondTargetState): void {
    if (target.viewers.size > 0) return
    const id = keyId(target.key)
    if (this.#targets.get(id) === target) this.#targets.delete(id)
  }

  /**
   * The shell a new target runs.
   *
   * A caller's `shell` is honoured when this host can actually find it, which
   * on Windows it usually cannot — the contract's POSIX shell path is not a
   * program here. PowerShell discovery is the fallback, which is what a
   * Windows workspace terminal has always been.
   */
  #shellArgv(shell: string): string[] {
    const find = this.#findExecutable ?? defaultFindExecutable
    const resolved = shell ? find(shell) : null
    if (resolved) return [resolved]
    return [findPowerShell(this.#findExecutable), "-NoLogo"]
  }

  async ensure(key: WorkspaceTerminalKey, options: {
    cwd: string
    shell: string
    env: Record<string, string>
    cols: number
    rows: number
  }): Promise<void> {
    const group = sessiondTerminalGroup(key.scope)
    const name = sessiondTerminalName(key.terminalId)
    await this.#serialize(key, async () => {
      const resolved = await this.#backend.resolve(group, name)
      if (resolved) {
        if (await this.#backend.livePid(resolved) !== null) return
        // A resolved target with no live process is a corpse, not a terminal.
        // It is replaced, never attached to, and never silently reused.
        await this.#backend.kill(resolved)
      }
      const argv = this.#shellArgv(options.shell)
      const env = { ...(this.#environment ? { ...this.#environment } : processEnvironment()), ...options.env }
      await this.#backend.create({
        group, name, cwd: options.cwd, argv, env, cols: options.cols, rows: options.rows,
      })
    })
  }

  async attachExisting(
    key: WorkspaceTerminalKey,
    viewerId: string,
    emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ): Promise<WorkspaceTerminalViewer> {
    const group = sessiondTerminalGroup(key.scope)
    const name = sessiondTerminalName(key.terminalId)
    const missing = () =>
      new WorkspaceTerminalError("target-not-found", `no workspace terminal ${key.scope}/${key.terminalId}`)

    // A close that has been decided but not finished is a target that is going
    // away, whatever `resolve` still says about it.
    if (this.#closing.has(keyId(key))) throw missing()
    const target = this.#target(key)
    if (target.closing) { this.#forget(target); throw missing() }
    const targetId = await this.#backend.resolve(group, name)
    if (!targetId || await this.#backend.livePid(targetId) === null) {
      this.#forget(target)
      throw missing()
    }

    const viewer = new SessiondWorkspaceViewer(this, target, `${viewerId}-${randomUUID().slice(0, 8)}`, emit)
    let runtime: RuntimeViewer
    try {
      runtime = await this.#backend.attach(targetId, viewer.id, (data, replay) => viewer.accept(data, replay))
    } catch (error) {
      this.#forget(target)
      throw error
    }
    // Attaching is not instant, and the replay was queued INSIDE it. A close
    // may have landed meanwhile: the target is gone and this viewer, which
    // sessiond has already registered, must not outlive it.
    if (target.closing || this.#closing.has(keyId(key)) || this.#targets.get(keyId(key)) !== target) {
      try { runtime.close() } catch {}
      this.#forget(target)
      throw missing()
    }
    target.viewers.add(viewer)
    await viewer.bind(runtime)
    return viewer
  }

  async list(scope: string): Promise<WorkspaceTerminalSummary[]> {
    const targets = await this.#backend.list(sessiondTerminalGroup(scope))
    return targets
      .map(target => parseSessiondTerminalName(target.name))
      .filter((id): id is string => id !== null)
      .map(id => ({ scope, terminalId: id, createdAt: 0 }))
      // sessiond does not record a creation time, so the order is the stable
      // one a client can rebuild a tab strip from rather than an invented age.
      .sort((a, b) => a.terminalId.localeCompare(b.terminalId))
  }

  async exists(key: WorkspaceTerminalKey): Promise<boolean> {
    const targetId = await this.#backend.resolve(
      sessiondTerminalGroup(key.scope), sessiondTerminalName(key.terminalId))
    return targetId !== null && await this.#backend.livePid(targetId) !== null
  }

  async close(key: WorkspaceTerminalKey): Promise<void> {
    const group = sessiondTerminalGroup(key.scope)
    const name = sessiondTerminalName(key.terminalId)
    const id = keyId(key)
    const target = this.#target(key)
    target.closing = true
    // Viewers first: a close we asked for must not reach a client as a failure.
    for (const viewer of [...target.viewers]) viewer.discard()
    this.#forget(target)
    const killed = this.#serialize(key, async () => {
      const targetId = await this.#backend.resolve(group, name)
      if (targetId) await this.#backend.kill(targetId)
    })
    // Registered SYNCHRONOUSLY, in the same turn as the decision: an attach
    // that runs before the kill does must not find a target to attach to.
    // After it, `resolve`/`livePid` answer for themselves.
    const marker = killed.then(() => undefined, () => undefined)
    this.#closing.set(id, marker)
    try {
      await killed
    } finally {
      if (this.#closing.get(id) === marker) this.#closing.delete(id)
    }
  }

  async closeScope(scope: string): Promise<void> {
    const group = sessiondTerminalGroup(scope)
    // EXACTLY this scope: the group name is a hex encoding of it, so a
    // neighbouring scope can never share the group of this one.
    const targets = await this.#backend.list(group)
    for (const target of targets) {
      const terminalId = parseSessiondTerminalName(target.name)
      if (terminalId === null) continue
      await this.close({ scope, terminalId })
    }
  }

  async shutdownViewers(): Promise<void> {
    const targets = [...this.#targets.values()]
    this.#targets.clear()
    await Promise.all(targets.flatMap(target => [...target.viewers].map(viewer => viewer.detach())))
  }

  // ---- what viewers call back into ---------------------------------------

  /** A focus claim. The newest wins; the loser is told, because on Windows
   * there is no daemon to tell it. */
  async claimFocus(target: SessiondTargetState, viewer: SessiondWorkspaceViewer): Promise<void> {
    target.claims = target.claims.filter(claim => claim !== viewer)
    target.claims.push(viewer)
    const previous = target.owner
    target.owner = viewer
    if (previous && previous !== viewer) await previous.noteOwner(false)
    await viewer.noteOwner(true)
    await viewer.applyGeometry()
  }

  /** A release, a detach or a lost viewer. The size goes to the claim under
   * the one that left, or nowhere. */
  async releaseFocus(target: SessiondTargetState, viewer: SessiondWorkspaceViewer): Promise<void> {
    target.claims = target.claims.filter(claim => claim !== viewer)
    if (target.owner !== viewer) return
    target.owner = null
    await viewer.noteOwner(false)
    const successor = [...target.claims].reverse().find(claim => claim.live)
    if (!successor) return
    target.owner = successor
    await successor.noteOwner(true)
    await successor.applyGeometry()
  }

  noteGone(target: SessiondTargetState, viewer: SessiondWorkspaceViewer): void {
    target.viewers.delete(viewer)
    target.claims = target.claims.filter(claim => claim !== viewer)
    if (target.owner === viewer) target.owner = null
    this.#forget(target)
  }

  /** The target's process ended. It is GONE; nothing attaches to it again. */
  noteExit(target: SessiondTargetState): void {
    target.closing = true
    this.#forget(target)
  }
}

/**
 * One viewer of a Windows workspace terminal.
 *
 * THE REPLAY BOUNDARY IS THE FRAME'S, NOT THE CLOCK'S. `SessionStore.attach`
 * queues the history inside its output-order barrier, but the pump that
 * delivers it is decoupled from the promise `attach()` returns — and in
 * production (win32 always uses `SessiondBackend`) the bytes cross a socket,
 * where the pump, the attach response and the data frames are three
 * independent trips. So "everything that arrived before `attach()` resolved"
 * was a timing heuristic, and a heuristic that closes the boundary early lets
 * a viewer answer a query for content that scrolled past — which the shell
 * reads as typing.
 *
 * Each data chunk now carries `replay` (core/sessiond/protocol.ts), and the
 * boundary is the last consecutive run of chunks that say so. The first live
 * chunk closes it, whenever it arrives.
 */
class SessiondWorkspaceViewer implements WorkspaceTerminalViewer {
  #runtime?: RuntimeViewer
  #dead = false
  /** True while we are deliberately dropping this viewer, so the teardown that
   * follows is never reported as a failure. */
  #discarding = false
  /** Chunks the wire MARKED as replay, held until the boundary is emitted. */
  #replay: Uint8Array[] = []
  /** Live chunks that arrived before `bind` opened the boundary. Kept apart so
   * the replay cannot swallow them and they cannot overtake it. */
  #beforeBind: Uint8Array[] = []
  /** True once a chunk said it was live: the replay run is over for good. */
  #replayEnded = false
  /** True once `bind` has emitted the boundary and drained `#beforeBind`. */
  #bound = false
  #replayClosed = false
  #owner = false
  #cols = 80
  #rows = 24
  #unsubscribeExit?: () => void
  #unsubscribeFailure?: () => void
  #tail: Promise<void> = Promise.resolve()

  constructor(
    private readonly backend: SessiondWorkspaceBackend,
    private readonly target: SessiondTargetState,
    readonly id: string,
    private readonly emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ) {}

  get live(): boolean {
    return !this.#dead
  }

  /**
   * Output from sessiond. `replay` is the wire's own statement about which
   * side of the attach boundary this chunk is on; a chunk that does not say
   * so is live, and ends the replay run whatever the clock says.
   */
  accept(data: Uint8Array, replay = false): void | Promise<void> {
    if (this.#dead) return
    if (replay && !this.#replayEnded) { this.#replay.push(data.slice()); return }
    this.#replayEnded = true
    if (!this.#bound) { this.#beforeBind.push(data.slice()); return }
    return this.#deliver({ type: "output", bytes: data })
  }

  /** Emit the replay boundary and start reporting exits and failures. */
  async bind(runtime: RuntimeViewer): Promise<void> {
    this.#runtime = runtime
    if (this.#dead) { try { runtime.close() } catch {} ; return }

    const replay = this.#replay
    this.#replay = []
    this.#replayEnded = true
    // One epoch, ours: sessiond has no epoch of its own to carry, and the
    // boundary is what tells a client "everything before this is history".
    const epoch = `sessiond-${this.id}`
    await this.#deliver({ type: "reset", epoch })
    await this.#deliver({ type: "replay-start", epoch })
    for (const bytes of replay) await this.#deliver({ type: "output", bytes })
    await this.#deliver({ type: "replay-end", epoch })
    this.#replayClosed = true
    // Live bytes that landed while the boundary was being emitted go out in
    // arrival order, and only then does `accept` start delivering directly —
    // draining in a loop, because more can arrive across these awaits.
    while (this.#beforeBind.length > 0) {
      await this.#deliver({ type: "output", bytes: this.#beforeBind.shift()! })
    }
    this.#bound = true

    if (runtime.onExit) this.#unsubscribeExit = runtime.onExit(code => { void this.#onExit(code) })
    else void runtime.exited?.then(code => { void this.#onExit(code) }, () => undefined)
    this.#unsubscribeFailure = runtime.onFailure?.(reason => { void this.#onFailure(reason) })
  }

  write(bytes: Uint8Array): boolean {
    if (this.#dead || !this.#runtime) return false
    try { return this.#runtime.write(bytes) } catch { return false }
  }

  reply(bytes: Uint8Array): boolean {
    if (this.#dead || !this.#runtime) return false
    // Owner-only, and after the replay. Both rules exist because the server's
    // terminal model answers nothing itself: every answer a viewer sends
    // reaches the shell, and the second one is typing.
    if (!this.#owner || !this.#replayClosed) return false
    try { return this.#runtime.write(bytes) } catch { return false }
  }

  async resize(cols: number, rows: number): Promise<void> {
    if (Number.isInteger(cols) && cols > 0) this.#cols = cols
    if (Number.isInteger(rows) && rows > 0) this.#rows = rows
    // A background viewer may keep reporting layout; only the owner's reaches
    // the ConPTY.
    if (!this.#owner) return
    await this.applyGeometry()
  }

  async focus(active: boolean, cols: number, rows: number): Promise<void> {
    if (Number.isInteger(cols) && cols > 0) this.#cols = cols
    if (Number.isInteger(rows) && rows > 0) this.#rows = rows
    if (this.#dead) return
    if (active) await this.backend.claimFocus(this.target, this)
    else await this.backend.releaseFocus(this.target, this)
  }

  async applyGeometry(): Promise<void> {
    if (this.#dead || !this.#runtime) return
    try { this.#runtime.resize(this.#cols, this.#rows) } catch {}
  }

  async noteOwner(enabled: boolean): Promise<void> {
    if (this.#owner === enabled) return
    this.#owner = enabled
    await this.#deliver({ type: "owner", enabled })
  }

  async detach(): Promise<void> {
    if (this.#dead) return
    this.#dead = true
    this.#discarding = true
    this.#teardown()
    await this.backend.releaseFocus(this.target, this)
    this.backend.noteGone(this.target, this)
  }

  /** Drop this viewer NOW, with no client event: the target is going away. */
  discard(): void {
    if (this.#dead) return
    this.#dead = true
    this.#discarding = true
    this.#teardown()
    this.backend.noteGone(this.target, this)
  }

  #teardown(): void {
    this.#unsubscribeExit?.()
    this.#unsubscribeExit = undefined
    this.#unsubscribeFailure?.()
    this.#unsubscribeFailure = undefined
    const runtime = this.#runtime
    this.#runtime = undefined
    try { runtime?.close() } catch {}
  }

  async #onExit(code: number): Promise<void> {
    if (this.#dead) return
    this.#dead = true
    this.#discarding = true
    this.backend.noteExit(this.target)
    this.backend.noteGone(this.target, this)
    this.#teardown()
    // sessiond reports a process exit CODE and nothing else; there is no
    // signal on Windows and no "we could not reap it" state to represent.
    await this.#deliver({ type: "exit", known: true, code, signal: null })
  }

  async #onFailure(reason: string): Promise<void> {
    if (this.#dead || this.#discarding) return
    this.#dead = true
    this.backend.noteGone(this.target, this)
    this.#teardown()
    // A lost VIEWER, never the target: the ConPTY and its shell are untouched,
    // and re-attaching is how a client recovers.
    await this.#deliver({ type: "failure", code: "backend-unavailable", recoverable: true, message: reason })
  }

  #deliver(event: WorkspaceTerminalEvent): Promise<void> {
    const next = this.#tail.catch(() => undefined).then(() => this.emit(event))
    this.#tail = next.catch(() => undefined)
    return next
  }
}
