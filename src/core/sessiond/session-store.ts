import { randomUUID } from "node:crypto"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { createProcessJob, type ProcessJob } from "./job-object"
import { SessionScreen } from "./screen"

export interface SessionTerminal {
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

type Viewer = {
  onData: (data: Uint8Array) => void | Promise<void>
}

type TargetState = "alive" | "killing" | "exited"

type TargetRecord = {
  id: string
  group: string
  name: string
  pid: number
  process: SessionProcess
  terminal: SessionTerminal
  job: ProcessJob
  screen: SessionScreen
  viewers: Map<string, Viewer>
  outputQueue: Promise<void>
  state: TargetState
  terminalClosed: boolean
  jobClosed: boolean
  finalizePromise?: Promise<void>
  killPromise?: Promise<void>
}

export type SessionStoreOptions = {
  processFactory?: SessionProcessFactory
  jobFactory?: () => ProcessJob
  idFactory?: () => string
  rawByteLimit?: number
}

function productionProcessFactory(
  options: SessionSpawnOptions,
  onData: (data: Uint8Array) => void,
): { process: SessionProcess; terminal: SessionTerminal } {
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
    },
  })
  const terminal = spawned.terminal
  if (!terminal) {
    spawned.kill()
    throw new Error("Bun.spawn did not create a ConPTY terminal")
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
  const size = encoded.reduce((total, value) => total + value.byteLength, 0)
  const result = new Uint8Array(size)
  let offset = 0
  for (const value of encoded) {
    result.set(value, offset)
    offset += value.byteLength
  }
  return result
}

export class SessionStore implements SessionBackend {
  private readonly processFactory: SessionProcessFactory
  private readonly jobFactory: () => ProcessJob
  private readonly idFactory: () => string
  private readonly rawByteLimit: number
  private readonly targets = new Map<string, TargetRecord>()

  constructor(options: SessionStoreOptions = {}) {
    this.processFactory = options.processFactory ?? productionProcessFactory
    this.jobFactory = options.jobFactory ?? createProcessJob
    this.idFactory = options.idFactory ?? (() => `session-${randomUUID()}`)
    this.rawByteLimit = options.rawByteLimit ?? 1024 * 1024
    if (!Number.isInteger(this.rawByteLimit) || this.rawByteLimit < 0) {
      throw new RangeError("rawByteLimit must be a non-negative integer")
    }
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
    if ([...this.targets.values()].some(target => target.group === options.group && target.name === options.name)) {
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
          else if (record.state !== "exited") void this.enqueueOutput(record, data)
        },
      )
      try {
        job.assign(spawned.process.pid)
      } catch (error) {
        try {
          spawned.process.kill()
        } catch {
          // Assignment failed, so the process is not protected by the job. A
          // best-effort direct kill is the only remaining cleanup route.
        }
        try {
          spawned.terminal.close()
        } catch {
          // Preserve the assignment error.
        }
        void spawned.process.exited.catch(() => undefined)
        throw error
      }

      record = {
        id,
        group: options.group,
        name: options.name,
        pid: spawned.process.pid,
        process: spawned.process,
        terminal: spawned.terminal,
        job,
        screen,
        viewers: new Map(),
        outputQueue: Promise.resolve(),
        state: "alive",
        terminalClosed: false,
        jobClosed: false,
      }
      this.targets.set(id, record)
      for (const data of pendingOutput) void this.enqueueOutput(record, data)
      this.observeExit(record)
      return this.publicTarget(record)
    } catch (error) {
      if (!record) {
        try {
          job.close()
        } catch {
          // Preserve the spawn/assignment error that made creation fail.
        }
        screen.dispose()
      }
      throw error
    }
  }

  async list(group?: string): Promise<RuntimeTarget[]> {
    return [...this.targets.values()]
      .filter(target => group === undefined || target.group === group)
      .map(target => this.publicTarget(target))
  }

  async resolve(group: string, name: string): Promise<string | null> {
    return [...this.targets.values()].find(target => target.group === group && target.name === name)?.id ?? null
  }

  async livePid(targetId: string): Promise<number | null> {
    const target = this.targets.get(targetId)
    return target?.state === "alive" ? target.pid : null
  }

  async write(targetId: string, data: Uint8Array): Promise<void> {
    this.activeTarget(targetId).terminal.write(data)
  }

  async sendKeys(targetId: string, keys: string[]): Promise<void> {
    this.activeTarget(targetId).terminal.write(encodeKeys(keys))
  }

  async resize(targetId: string, cols: number, rows: number): Promise<void> {
    const target = this.activeTarget(targetId)
    target.terminal.resize(cols, rows)
    target.screen.resize(cols, rows)
  }

  async capture(targetId: string, raw = false): Promise<string | null> {
    const target = this.targets.get(targetId)
    if (!target) return null
    await target.outputQueue
    return raw ? target.screen.captureRaw() : target.screen.captureText()
  }

  async attach(
    targetId: string,
    viewerId: string,
    onData: (data: Uint8Array) => void | Promise<void>,
  ): Promise<RuntimeViewer> {
    const target = this.activeTarget(targetId)
    if (target.viewers.has(viewerId)) throw new Error(`viewer is already attached: ${viewerId}`)
    target.viewers.set(viewerId, { onData })
    let closed = false
    return {
      close: () => {
        if (closed) return
        closed = true
        target.viewers.delete(viewerId)
      },
      write: data => {
        if (closed || target.state !== "alive") return false
        try {
          target.terminal.write(data)
          return true
        } catch {
          return false
        }
      },
      resize: (cols, rows) => {
        if (closed || target.state !== "alive") return false
        try {
          target.terminal.resize(cols, rows)
          target.screen.resize(cols, rows)
          return true
        } catch {
          return false
        }
      },
    }
  }

  async detach(targetId: string, viewerId: string): Promise<void> {
    this.targets.get(targetId)?.viewers.delete(viewerId)
  }

  async interrupt(targetId: string): Promise<void> {
    this.activeTarget(targetId).terminal.write(new Uint8Array([0x03]))
  }

  async kill(targetId: string): Promise<void> {
    const target = this.targets.get(targetId)
    if (!target) return
    if (target.killPromise) return target.killPromise
    if (target.state === "exited") {
      await target.finalizePromise
      return
    }

    target.killPromise = (async () => {
      target.state = "killing"
      target.job.terminate(1)
      this.closeTerminal(target)
      let exitCode: number | null = null
      try {
        exitCode = await target.process.exited
      } catch {
        // A rejected wait still means this process can no longer be observed as
        // live. Cleanup is deterministic and the rejection is contained.
      }
      await this.finalizeExit(target, exitCode)
    })()
    return target.killPromise
  }

  /** Accept one immutable PTY output chunk. Public for native smoke tooling. */
  acceptOutput(targetId: string, data: Uint8Array): Promise<void> {
    const target = this.targets.get(targetId)
    if (!target || target.state === "exited") return Promise.resolve()
    return this.enqueueOutput(target, data)
  }

  private uniqueId(): string {
    for (let attempt = 0; attempt < 100; attempt++) {
      const id = this.idFactory()
      if (id.length > 0 && !this.targets.has(id)) return id
    }
    throw new Error("could not allocate a unique session target ID")
  }

  private activeTarget(targetId: string): TargetRecord {
    const target = this.targets.get(targetId)
    if (!target || target.state !== "alive") throw new Error(`live session target not found: ${targetId}`)
    return target
  }

  private publicTarget(target: TargetRecord): RuntimeTarget {
    const alive = target.state === "alive"
    return { id: target.id, name: target.name, pid: alive ? target.pid : null, alive }
  }

  private enqueueOutput(target: TargetRecord, data: Uint8Array): Promise<void> {
    const snapshot = data.slice()
    target.outputQueue = target.outputQueue.then(async () => {
      await target.screen.write(snapshot)
      for (const viewer of [...target.viewers.values()]) {
        try {
          await viewer.onData(snapshot.slice())
        } catch {
          // One disconnected viewer must not poison terminal capture or other
          // viewers, and callback rejections must never become unhandled.
        }
      }
    })
    return target.outputQueue
  }

  private observeExit(target: TargetRecord): void {
    void target.process.exited.then(
      code => this.finalizeExit(target, code),
      () => this.finalizeExit(target, null),
    ).catch(() => {
      // Natural-exit cleanup has no request to report through. Native close
      // failures are contained here; explicit kill still reports them to its
      // caller through killPromise.
    })
  }

  private finalizeExit(target: TargetRecord, _exitCode: number | null): Promise<void> {
    if (target.finalizePromise) return target.finalizePromise
    target.state = "exited"
    target.finalizePromise = (async () => {
      await target.outputQueue
      this.closeTerminal(target)
      this.closeJob(target)
      target.viewers.clear()
    })()
    return target.finalizePromise
  }

  private closeTerminal(target: TargetRecord): void {
    if (target.terminalClosed) return
    target.terminalClosed = true
    target.terminal.close()
  }

  private closeJob(target: TargetRecord): void {
    if (target.jobClosed) return
    target.jobClosed = true
    target.job.close()
  }
}
