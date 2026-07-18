import { randomUUID } from "node:crypto"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { createProcessJob, type ProcessJob } from "./job-object"
import { SessionScreen } from "./screen"

export interface SessionTerminal {
  readonly eof: Promise<void>
  setDrainHandler(handler: () => void): void
  write(data: Uint8Array | string): number
  resize(cols: number, rows: number): void
  close(): void
}

export interface SessionProcess {
  readonly pid: number
  readonly exited: Promise<number>
  kill(): void
}

export type SessionSpawnOptions = {
  group: string
  name: string
  cwd: string
  argv: string[]
  env: Record<string, string>
  cols: number
  rows: number
}

export type SessionProcessFactory = (
  options: SessionSpawnOptions,
  onData: (data: Uint8Array) => void,
) => { process: SessionProcess; terminal: SessionTerminal }

export type ExitedRuntimeTarget = RuntimeTarget & {
  group: string
  exitCode: number | null
  exitedAt: number
}

type InputEntry = {
  data: Uint8Array
  offset: number
  resolve?: () => void
  reject?: (error: Error) => void
}

class TerminalInputWriter {
  private readonly queue: InputEntry[] = []
  private pendingBytes = 0
  private closed = false
  private pumping = false
  private waitingForDrain = false

  constructor(
    private readonly terminal: SessionTerminal,
    private readonly byteLimit: number,
  ) {
    terminal.setDrainHandler(() => {
      this.waitingForDrain = false
      this.pump()
    })
  }

  write(data: Uint8Array): Promise<void> {
    const snapshot = data.slice()
    if (this.closed) return Promise.reject(new Error("terminal input is closed"))
    if (this.pendingBytes + snapshot.byteLength > this.byteLimit) {
      return Promise.reject(new Error(`terminal input queue exceeds ${this.byteLimit} bytes`))
    }
    if (snapshot.byteLength === 0) return Promise.resolve()
    return new Promise<void>((resolve, reject) => {
      this.enqueue({ data: snapshot, offset: 0, resolve, reject })
    })
  }

  tryWrite(data: Uint8Array): boolean {
    if (this.closed || this.pendingBytes + data.byteLength > this.byteLimit) return false
    if (data.byteLength === 0) return true
    this.enqueue({ data: data.slice(), offset: 0 })
    return true
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.terminal.setDrainHandler(() => {})
    this.rejectAll(new Error("terminal input is closed"))
  }

  private enqueue(entry: InputEntry): void {
    this.queue.push(entry)
    this.pendingBytes += entry.data.byteLength
    this.pump()
  }

  private pump(): void {
    if (this.closed || this.pumping || this.waitingForDrain) return
    this.pumping = true
    try {
      while (!this.closed) {
        const entry = this.queue[0]
        if (!entry) return
        const remaining = entry.data.subarray(entry.offset)
        let accepted: number
        try {
          accepted = this.terminal.write(remaining)
        } catch (error) {
          this.rejectAll(asError(error, "terminal write failed"))
          return
        }
        if (!Number.isInteger(accepted) || accepted < 0 || accepted > remaining.byteLength) {
          this.rejectAll(new Error(`terminal write returned invalid accepted byte count: ${accepted}`))
          return
        }
        if (accepted === 0) {
          this.waitingForDrain = true
          return
        }
        entry.offset += accepted
        this.pendingBytes -= accepted
        if (entry.offset === entry.data.byteLength) {
          this.queue.shift()
          entry.resolve?.()
        }
      }
    } finally {
      this.pumping = false
    }
  }

  private rejectAll(error: Error): void {
    const entries = this.queue.splice(0)
    this.pendingBytes = 0
    this.waitingForDrain = false
    for (const entry of entries) entry.reject?.(error)
  }
}

type Viewer = {
  id: string
  onData: (data: Uint8Array) => void | Promise<void>
  queue: Uint8Array[]
  pendingBytes: number
  inFlightBytes: number
  delivering: boolean
  active: boolean
  detach: () => void
}

type TargetState = "alive" | "terminating" | "exiting"

type TargetRecord = {
  id: string
  group: string
  name: string
  pid: number
  process: SessionProcess
  terminal: SessionTerminal
  input: TerminalInputWriter
  job: ProcessJob
  screen: SessionScreen
  viewers: Map<string, Viewer>
  outputQueue: Promise<void>
  state: TargetState
  exitObserved: boolean
  exitCode: number | null
  terminalClosed: boolean
  jobClosed: boolean
  removed: boolean
  finalizePromise?: Promise<void>
  killPromise?: Promise<void>
}

export type SessionStoreOptions = {
  processFactory?: SessionProcessFactory
  jobFactory?: () => ProcessJob
  idFactory?: () => string
  rawByteLimit?: number
  inputByteLimit?: number
  viewerByteLimit?: number
  terminalEofTimeoutMs?: number
  processExitTimeoutMs?: number
  maxExitedHistory?: number
}

function productionProcessFactory(
  options: SessionSpawnOptions,
  onData: (data: Uint8Array) => void,
): { process: SessionProcess; terminal: SessionTerminal } {
  let notifyDrain = () => {}
  let resolveEof!: () => void
  const eof = new Promise<void>(resolve => { resolveEof = resolve })
  const spawned = Bun.spawn(options.argv, {
    cwd: options.cwd,
    env: options.env,
    detached: true,
    windowsHide: true,
    terminal: {
      cols: options.cols,
      rows: options.rows,
      data(_terminal, data) {
        onData(data)
      },
      drain() {
        notifyDrain()
      },
      exit() {
        resolveEof()
      },
    },
  })
  const nativeTerminal = spawned.terminal
  if (!nativeTerminal) {
    spawned.kill()
    throw new Error("Bun.spawn did not create a ConPTY terminal")
  }
  const terminal: SessionTerminal = {
    eof,
    setDrainHandler(handler) {
      notifyDrain = handler
    },
    write(data) {
      return nativeTerminal.write(data)
    },
    resize(cols, rows) {
      nativeTerminal.resize(cols, rows)
    },
    close() {
      nativeTerminal.close()
    },
  }
  return {
    process: {
      pid: spawned.pid,
      exited: spawned.exited,
      kill: () => spawned.kill(),
    },
    terminal,
  }
}

function asError(value: unknown, fallback: string): Error {
  return value instanceof Error ? value : new Error(`${fallback}: ${String(value)}`)
}

function encodeKey(key: string): Uint8Array {
  const named: Record<string, string> = {
    Enter: "\r",
    Tab: "\t",
    Escape: "\x1b",
    Esc: "\x1b",
    Backspace: "\x7f",
    Space: " ",
    Up: "\x1b[A",
    Down: "\x1b[B",
    Right: "\x1b[C",
    Left: "\x1b[D",
    Home: "\x1b[H",
    End: "\x1b[F",
    Delete: "\x1b[3~",
  }
  const known = named[key]
  if (known !== undefined) return new TextEncoder().encode(known)
  const control = /^C-(.)$/iu.exec(key)
  if (control) {
    const character = control[1]!
    if (character === "?") return new Uint8Array([0x7f])
    const code = character.toUpperCase().charCodeAt(0)
    if (code >= 0x40 && code <= 0x5f) return new Uint8Array([code & 0x1f])
  }
  const meta = /^M-(.*)$/su.exec(key)
  if (meta) {
    const suffix = new TextEncoder().encode(meta[1]!)
    const result = new Uint8Array(suffix.byteLength + 1)
    result[0] = 0x1b
    result.set(suffix, 1)
    return result
  }
  return new TextEncoder().encode(key)
}

function encodeKeys(keys: string[]): Uint8Array {
  const encoded = keys.map(encodeKey)
  const result = new Uint8Array(encoded.reduce((total, value) => total + value.byteLength, 0))
  let offset = 0
  for (const value of encoded) {
    result.set(value, offset)
    offset += value.byteLength
  }
  return result
}

function validateLimit(name: string, value: number): number {
  if (!Number.isInteger(value) || value < 0) throw new RangeError(`${name} must be a non-negative integer`)
  return value
}

function validateTimeout(name: string, value: number): number {
  if (!Number.isFinite(value) || value < 0) throw new RangeError(`${name} must be a non-negative number`)
  return value
}

async function settleWithin<T>(promise: Promise<T>, timeoutMs: number): Promise<{ settled: true; value: T | null } | { settled: false }> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await new Promise(resolve => {
      timer = setTimeout(() => resolve({ settled: false as const }), timeoutMs)
      promise.then(
        value => resolve({ settled: true as const, value }),
        () => resolve({ settled: true as const, value: null }),
      )
    })
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export class SessionStore implements SessionBackend {
  private readonly processFactory: SessionProcessFactory
  private readonly jobFactory: () => ProcessJob
  private readonly idFactory: () => string
  private readonly rawByteLimit: number
  private readonly inputByteLimit: number
  private readonly viewerByteLimit: number
  private readonly terminalEofTimeoutMs: number
  private readonly processExitTimeoutMs: number
  private readonly maxExitedHistory: number
  private readonly targets = new Map<string, TargetRecord>()
  private readonly exitedHistory: ExitedRuntimeTarget[] = []

  constructor(options: SessionStoreOptions = {}) {
    this.processFactory = options.processFactory ?? productionProcessFactory
    this.jobFactory = options.jobFactory ?? createProcessJob
    this.idFactory = options.idFactory ?? (() => `session-${randomUUID()}`)
    this.rawByteLimit = validateLimit("rawByteLimit", options.rawByteLimit ?? 1024 * 1024)
    this.inputByteLimit = validateLimit("inputByteLimit", options.inputByteLimit ?? 1024 * 1024)
    this.viewerByteLimit = validateLimit("viewerByteLimit", options.viewerByteLimit ?? 1024 * 1024)
    this.terminalEofTimeoutMs = validateTimeout("terminalEofTimeoutMs", options.terminalEofTimeoutMs ?? 2_000)
    this.processExitTimeoutMs = validateTimeout("processExitTimeoutMs", options.processExitTimeoutMs ?? 5_000)
    this.maxExitedHistory = validateLimit("maxExitedHistory", options.maxExitedHistory ?? 100)
  }

  async create(options: {
    group: string
    name: string
    cwd: string
    argv: string[]
    env: Record<string, string>
    cols?: number
    rows?: number
  }): Promise<RuntimeTarget> {
    if ([...this.targets.values()].some(target =>
      !target.exitObserved && target.group === options.group && target.name === options.name)) {
      throw new Error(`duplicate session target in group ${options.group}: ${options.name}`)
    }

    const id = this.uniqueId()
    const cols = options.cols ?? 80
    const rows = options.rows ?? 24
    const screen = new SessionScreen(cols, rows, this.rawByteLimit)
    let job: ProcessJob
    try {
      job = this.jobFactory()
    } catch (error) {
      screen.dispose()
      throw error
    }
    const pendingOutput: Uint8Array[] = []
    let record: TargetRecord | undefined

    try {
      const spawned = this.processFactory(
        { ...options, argv: [...options.argv], env: { ...options.env }, cols, rows },
        data => {
          if (!record) pendingOutput.push(data.slice())
          else if (!record.removed) void this.enqueueOutput(record, data)
        },
      )
      try {
        job.assign(spawned.process.pid)
      } catch (error) {
        try { job.terminate(1) } catch { /* Preserve assignment failure. */ }
        try { spawned.process.kill() } catch { /* Best-effort unassigned root cleanup. */ }
        try { spawned.terminal.close() } catch { /* Preserve assignment failure. */ }
        void spawned.process.exited.catch(() => undefined)
        throw error
      }

      const input = new TerminalInputWriter(spawned.terminal, this.inputByteLimit)
      record = {
        id,
        group: options.group,
        name: options.name,
        pid: spawned.process.pid,
        process: spawned.process,
        terminal: spawned.terminal,
        input,
        job,
        screen,
        viewers: new Map(),
        outputQueue: Promise.resolve(),
        state: "alive",
        exitObserved: false,
        exitCode: null,
        terminalClosed: false,
        jobClosed: false,
        removed: false,
      }
      this.targets.set(id, record)
      for (const data of pendingOutput) void this.enqueueOutput(record, data)
      this.observeExit(record)
      return this.publicTarget(record)
    } catch (error) {
      if (!record) {
        try { job.close() } catch { /* Preserve creation failure. */ }
        screen.dispose()
      }
      throw error
    }
  }

  async list(group?: string): Promise<RuntimeTarget[]> {
    return [...this.targets.values()]
      .filter(target => !target.exitObserved && (group === undefined || target.group === group))
      .map(target => this.publicTarget(target))
  }

  listExited(group?: string): ExitedRuntimeTarget[] {
    return this.exitedHistory
      .filter(target => group === undefined || target.group === group)
      .map(target => ({ ...target }))
  }

  async resolve(group: string, name: string): Promise<string | null> {
    return [...this.targets.values()].find(target =>
      !target.exitObserved && target.group === group && target.name === name)?.id ?? null
  }

  async livePid(targetId: string): Promise<number | null> {
    const target = this.targets.get(targetId)
    return target && !target.exitObserved ? target.pid : null
  }

  async write(targetId: string, data: Uint8Array): Promise<void> {
    return this.activeTarget(targetId).input.write(data)
  }

  async sendKeys(targetId: string, keys: string[]): Promise<void> {
    return this.activeTarget(targetId).input.write(encodeKeys(keys))
  }

  async resize(targetId: string, cols: number, rows: number): Promise<void> {
    const target = this.activeTarget(targetId)
    target.screen.resize(cols, rows)
    target.terminal.resize(cols, rows)
  }

  async capture(targetId: string, raw = false): Promise<string | null> {
    const target = this.targets.get(targetId)
    if (!target || target.exitObserved) return null
    await target.outputQueue
    if (target.exitObserved) return null
    return raw ? target.screen.captureRaw() : target.screen.captureText()
  }

  async attach(
    targetId: string,
    viewerId: string,
    onData: (data: Uint8Array) => void | Promise<void>,
  ): Promise<RuntimeViewer> {
    const target = this.activeTarget(targetId)
    if (target.viewers.has(viewerId)) throw new Error(`viewer is already attached: ${viewerId}`)
    const viewer: Viewer = {
      id: viewerId,
      onData,
      queue: [],
      pendingBytes: 0,
      inFlightBytes: 0,
      delivering: false,
      active: true,
      detach: () => {},
    }
    viewer.detach = () => {
      if (!viewer.active) return
      viewer.active = false
      viewer.queue.length = 0
      viewer.pendingBytes = viewer.inFlightBytes
      viewer.detach = () => {}
      target.viewers.delete(viewer.id)
    }
    target.viewers.set(viewerId, viewer)
    let closed = false
    return {
      close: () => {
        if (closed) return
        closed = true
        viewer.detach()
      },
      write: data => !closed && target.state === "alive" && target.input.tryWrite(data),
      resize: (cols, rows) => {
        if (closed || target.state !== "alive") return false
        try {
          target.screen.resize(cols, rows)
          target.terminal.resize(cols, rows)
          return true
        } catch {
          return false
        }
      },
    }
  }

  async detach(targetId: string, viewerId: string): Promise<void> {
    const target = this.targets.get(targetId)
    const viewer = target?.viewers.get(viewerId)
    if (target && viewer) viewer.detach()
  }

  async interrupt(targetId: string): Promise<void> {
    return this.activeTarget(targetId).input.write(new Uint8Array([0x03]))
  }

  async kill(targetId: string): Promise<void> {
    const target = this.targets.get(targetId)
    if (!target) return
    if (target.killPromise) return target.killPromise
    if (target.exitObserved) return this.finalizeExit(target)

    const attempt = (async () => {
      target.state = "terminating"
      try {
        target.job.terminate(1)
      } catch (error) {
        await Promise.resolve()
        if (!target.exitObserved) target.state = "alive"
        throw error
      }

      const result = await settleWithin(target.process.exited, this.processExitTimeoutMs)
      if (!target.exitObserved) this.markExited(target, result.settled ? result.value : null)
      await this.finalizeExit(target)
    })()
    target.killPromise = attempt
    try {
      await attempt
    } finally {
      if (target.killPromise === attempt) target.killPromise = undefined
    }
  }

  /** Accept one immutable PTY output chunk. Public for native smoke tooling. */
  acceptOutput(targetId: string, data: Uint8Array): Promise<void> {
    const target = this.targets.get(targetId)
    if (!target || target.removed) return Promise.resolve()
    return this.enqueueOutput(target, data)
  }

  private uniqueId(): string {
    for (let attempt = 0; attempt < 100; attempt++) {
      const id = this.idFactory()
      if (id.length > 0 && !this.targets.has(id) && !this.exitedHistory.some(target => target.id === id)) return id
    }
    throw new Error("could not allocate a unique session target ID")
  }

  private activeTarget(targetId: string): TargetRecord {
    const target = this.targets.get(targetId)
    if (!target || target.state !== "alive" || target.exitObserved) {
      throw new Error(`live session target not found: ${targetId}`)
    }
    return target
  }

  private publicTarget(target: TargetRecord): RuntimeTarget {
    const alive = !target.exitObserved
    return { id: target.id, name: target.name, pid: alive ? target.pid : null, alive }
  }

  private enqueueOutput(target: TargetRecord, data: Uint8Array): Promise<void> {
    const snapshot = data.slice()
    target.outputQueue = target.outputQueue.then(async () => {
      if (target.removed) return
      await target.screen.write(snapshot)
      for (const viewer of [...target.viewers.values()]) this.enqueueViewer(viewer, snapshot)
    })
    return target.outputQueue
  }

  private enqueueViewer(viewer: Viewer, data: Uint8Array): void {
    if (!viewer.active) return
    if (viewer.pendingBytes + data.byteLength > this.viewerByteLimit) {
      viewer.detach()
      return
    }
    viewer.queue.push(data.slice())
    viewer.pendingBytes += data.byteLength
    this.pumpViewer(viewer)
  }

  private pumpViewer(viewer: Viewer): void {
    if (viewer.delivering || !viewer.active) return
    viewer.delivering = true
    void (async () => {
      try {
        while (viewer.active) {
          const data = viewer.queue.shift()
          if (!data) return
          viewer.inFlightBytes = data.byteLength
          try {
            await viewer.onData(data)
          } catch {
            viewer.detach()
            return
          } finally {
            viewer.pendingBytes = Math.max(0, viewer.pendingBytes - data.byteLength)
            viewer.inFlightBytes = 0
          }
        }
      } finally {
        viewer.delivering = false
        if (viewer.active && viewer.queue.length > 0) this.pumpViewer(viewer)
      }
    })()
  }

  private observeExit(target: TargetRecord): void {
    void target.process.exited.then(
      code => this.markExited(target, code),
      () => this.markExited(target, null),
    )
  }

  private markExited(target: TargetRecord, exitCode: number | null): void {
    if (target.exitObserved || target.removed) return
    target.exitObserved = true
    target.exitCode = exitCode
    target.state = "exiting"
    void this.finalizeExit(target).catch(() => {
      // A later kill/cleanup request can retry each close independently.
    })
  }

  private finalizeExit(target: TargetRecord): Promise<void> {
    if (target.removed) return Promise.resolve()
    if (target.finalizePromise) return target.finalizePromise
    const attempt = (async () => {
      await settleWithin(target.terminal.eof, this.terminalEofTimeoutMs)
      await target.outputQueue
      target.input.close()
      for (const viewer of [...target.viewers.values()]) viewer.detach()

      const errors: Error[] = []
      if (!target.terminalClosed) {
        try {
          target.terminal.close()
          target.terminalClosed = true
        } catch (error) {
          errors.push(asError(error, "terminal close failed"))
        }
      }
      if (!target.jobClosed) {
        try {
          target.job.close()
          target.jobClosed = true
        } catch (error) {
          errors.push(asError(error, "Job Object close failed"))
        }
      }
      if (errors.length > 0) {
        throw new AggregateError(errors, `session cleanup failed: ${errors.map(error => error.message).join("; ")}`)
      }

      target.screen.dispose()
      target.removed = true
      this.targets.delete(target.id)
      if (this.maxExitedHistory > 0) {
        this.exitedHistory.push({
          id: target.id,
          group: target.group,
          name: target.name,
          pid: null,
          alive: false,
          exitCode: target.exitCode,
          exitedAt: Date.now(),
        })
        while (this.exitedHistory.length > this.maxExitedHistory) this.exitedHistory.shift()
      }
    })()
    target.finalizePromise = attempt
    void attempt.finally(() => {
      if (target.finalizePromise === attempt) target.finalizePromise = undefined
    }).catch(() => undefined)
    return attempt
  }
}
