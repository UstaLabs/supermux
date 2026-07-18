import { randomUUID } from "node:crypto"
import { dirname, isAbsolute, join, resolve } from "node:path"
import { createConnection, type Socket } from "node:net"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { encodeFrame } from "../../shared/frame-codec"
import { PROTOCOL_VERSION } from "./protocol"
import { SESSIOND_MAX_BUFFER_BYTES, SessiondFrameAccumulator, SessiondFrameError } from "./framing"
import { createOrLoadSessiondSecret } from "./secret"

type SpawnSessiond = (executable: string, stateDir: string) => void | Promise<void>
type ConnectSocket = (endpoint: string) => Promise<Socket>
type WriteUntracked = (socket: Socket, frame: Buffer) => boolean

export type SessiondBackendOptions = {
  endpoint: string
  secret?: string
  stateDir: string
  platform?: NodeJS.Platform
  executable?: string
  spawnSessiond?: SpawnSessiond
  /** @internal Deterministic transport seam for connection-failure tests. */
  connectSocket?: ConnectSocket
  /** @internal Backpressure seam; the frame is accepted even when this returns false. */
  writeUntracked?: WriteUntracked
  adoptionTimeoutMs?: number
  adoptionPollMs?: number
  requestTimeoutMs?: number
  maxPendingRequests?: number
  maxOutboundBytes?: number
  viewerInboundByteLimit?: number
}

type Pending = { resolve(value: unknown): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout>; socket: Socket }
type ViewerRegistration = {
  targetId: string
  viewerId: string
  onData: (data: Uint8Array) => void | Promise<void>
  queue: Uint8Array[]
  pendingBytes: number
  delivering: boolean
  open: boolean
  requestedExit?: number
  requestedFailure?: string
  exitCode?: number
  failureReason?: string
  exitHandlers: Set<(code: number) => void>
  failureHandlers: Set<(reason: string) => void>
  resolveExited(code: number): void
}

function defaultExecutable(): string {
  return process.env.MUX_SESSIOND_EXE ?? join(dirname(process.execPath), "mux-sessiond.exe")
}

function defaultSpawn(executable: string, stateDir: string): void {
  const child = Bun.spawn([executable, "--state-dir", stateDir], {
    detached: true,
    windowsHide: true,
    stdin: "ignore",
    stdout: "ignore",
    stderr: "ignore",
  })
  child.unref()
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function validBase64(value: unknown): value is string {
  return typeof value === "string" && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
    && Buffer.from(value, "base64").toString("base64") === value
}

function target(value: unknown): RuntimeTarget {
  const pid = isRecord(value) ? value.pid : undefined
  const alive = isRecord(value) ? value.alive : undefined
  const pidValid = validPid(pid)
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.name !== "string"
    || typeof alive !== "boolean" || (alive ? !pidValid : pid !== null)) {
    throw new Error("sessiond returned an invalid runtime target")
  }
  return { id: value.id, name: value.name, pid: pid as number | null, alive: alive as boolean }
}

function validPid(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && Number.isSafeInteger(value) && value > 0
}

function expectVoid(value: unknown, operation: string): void {
  if (value !== undefined) throw new Error(`sessiond returned an invalid ${operation} response`)
}

function adoptable(error: unknown): boolean {
  const code = (error as NodeJS.ErrnoException)?.code
  return code === "ENOENT" || code === "ECONNREFUSED"
}

function defaultConnectSocket(endpoint: string): Promise<Socket> {
  return new Promise((resolveConnect, reject) => {
    const candidate = createConnection(endpoint)
    const onError = (error: Error) => { candidate.destroy(); reject(error) }
    candidate.once("error", onError)
    candidate.once("connect", () => { candidate.off("error", onError); resolveConnect(candidate) })
  })
}

function currentProcessEnvironment(overrides: Readonly<Record<string, string>>): Record<string, string> {
  const environment: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) if (typeof value === "string") environment[key] = value
  return Object.assign(environment, overrides)
}

export class SessiondBackend implements SessionBackend {
  private socket?: Socket
  private ready = false
  private closed = false
  private connectionAttempt?: Promise<void>
  private frames = new SessiondFrameAccumulator()
  private sequence = 0
  private readonly pending = new Map<string, Pending>()
  private readonly viewers = new Map<string, ViewerRegistration>()
  private readonly endpoint: string
  private secret?: string
  private readonly stateDir: string
  private readonly platform: NodeJS.Platform
  private readonly executable: string
  private readonly spawnSessiond: SpawnSessiond
  private readonly connectSocket: ConnectSocket
  private readonly writeUntrackedFrame: WriteUntracked
  private readonly adoptionTimeoutMs: number
  private readonly adoptionPollMs: number
  private readonly requestTimeoutMs: number
  private readonly maxPendingRequests: number
  private readonly maxOutboundBytes: number
  private readonly viewerInboundByteLimit: number

  constructor(options: SessiondBackendOptions) {
    this.endpoint = options.endpoint
    this.secret = options.secret
    this.stateDir = isAbsolute(options.stateDir) ? options.stateDir : resolve(options.stateDir)
    this.platform = options.platform ?? process.platform
    this.executable = options.executable ?? defaultExecutable()
    this.spawnSessiond = options.spawnSessiond ?? defaultSpawn
    this.connectSocket = options.connectSocket ?? defaultConnectSocket
    this.writeUntrackedFrame = options.writeUntracked ?? ((socket, frame) => socket.write(frame))
    this.adoptionTimeoutMs = options.adoptionTimeoutMs ?? 10_000
    this.adoptionPollMs = options.adoptionPollMs ?? 100
    this.requestTimeoutMs = options.requestTimeoutMs ?? 10_000
    this.maxPendingRequests = options.maxPendingRequests ?? 1024
    this.maxOutboundBytes = options.maxOutboundBytes ?? SESSIOND_MAX_BUFFER_BYTES
    this.viewerInboundByteLimit = options.viewerInboundByteLimit ?? 1024 * 1024
    if (!Number.isInteger(this.viewerInboundByteLimit) || this.viewerInboundByteLimit < 0) {
      throw new RangeError("viewerInboundByteLimit must be a non-negative integer")
    }
  }

  async ensureConnected(): Promise<void> {
    if (this.closed) throw new Error("sessiond client is closed")
    if (this.ready && this.socket?.writable && !this.socket.destroyed) return
    if (this.connectionAttempt) return this.connectionAttempt
    const attempt = (async () => {
      this.secret ??= await createOrLoadSessiondSecret(this.stateDir)
      await this.connectWithAdoption()
    })()
    this.connectionAttempt = attempt
    try { await attempt } finally { if (this.connectionAttempt === attempt) this.connectionAttempt = undefined }
  }

  async hello(): Promise<{ version: number; healthy: boolean }> {
    const value = await this.request("hello", {})
    if (!isRecord(value) || value.version !== PROTOCOL_VERSION || value.healthy !== true) throw new Error("invalid sessiond hello response")
    return { version: value.version, healthy: true }
  }

  async create(options: { group: string; name: string; cwd: string; argv: string[]; env: Record<string, string>; cols?: number; rows?: number }): Promise<RuntimeTarget> {
    return target(await this.request("create", { ...options, env: currentProcessEnvironment(options.env) }))
  }

  async list(group?: string): Promise<RuntimeTarget[]> {
    const value = await this.request("list", { ...(group === undefined ? {} : { group }) })
    if (!Array.isArray(value)) throw new Error("sessiond returned an invalid target list")
    return value.map(target)
  }

  async resolve(group: string, name: string): Promise<string | null> {
    const value = await this.request("resolve", { group, name })
    if (!(typeof value === "string" || value === null)) throw new Error("sessiond returned an invalid target ID")
    return value
  }

  async livePid(targetId: string): Promise<number | null> {
    const value = await this.request("livePid", { targetId })
    if (!(validPid(value) || value === null)) throw new Error("sessiond returned an invalid PID")
    return value
  }

  async write(targetId: string, data: Uint8Array): Promise<void> {
    expectVoid(await this.request("write", { targetId, dataBase64: Buffer.from(data).toString("base64") }), "write")
  }

  async sendKeys(targetId: string, keys: string[]): Promise<void> { expectVoid(await this.request("sendKeys", { targetId, keys }), "sendKeys") }
  async resize(targetId: string, cols: number, rows: number): Promise<void> { expectVoid(await this.request("resize", { targetId, cols, rows }), "resize") }

  async capture(targetId: string, raw?: boolean): Promise<string | null> {
    const value = await this.request("capture", { targetId, ...(raw === undefined ? {} : { raw }) })
    if (!(typeof value === "string" || value === null)) throw new Error("sessiond returned invalid capture data")
    return value
  }

  async attach(targetId: string, viewerId: string, onData: (data: Uint8Array) => void | Promise<void>): Promise<RuntimeViewer> {
    const key = `${targetId}\0${viewerId}`
    let resolveExited!: (code: number) => void
    const exited = new Promise<number>(resolve => { resolveExited = resolve })
    const registration: ViewerRegistration = {
      targetId,
      viewerId,
      onData,
      queue: [],
      pendingBytes: 0,
      delivering: false,
      open: true,
      exitHandlers: new Set(),
      failureHandlers: new Set(),
      resolveExited,
    }
    if (this.viewers.has(key)) throw new Error(`viewer is already attached: ${viewerId}`)
    this.viewers.set(key, registration)
    try {
      const value = await this.request("attach", { targetId, viewerId })
      if (!isRecord(value) || value.attached !== true) throw new Error("sessiond returned an invalid attach response")
    } catch (error) { this.viewers.delete(key); registration.open = false; throw error }
    return {
      exited,
      onExit: handler => {
        if (registration.exitCode !== undefined) { handler(registration.exitCode); return () => {} }
        if (!registration.open) return () => {}
        registration.exitHandlers.add(handler)
        return () => { registration.exitHandlers.delete(handler) }
      },
      onFailure: handler => {
        if (registration.failureReason !== undefined) { handler(registration.failureReason); return () => {} }
        if (!registration.open) return () => {}
        registration.failureHandlers.add(handler)
        return () => { registration.failureHandlers.delete(handler) }
      },
      close: () => {
        this.detachViewer(key, registration, true)
      },
      write: data => registration.open && this.sendUntracked("write", { targetId, dataBase64: Buffer.from(data).toString("base64") }),
      resize: (cols, rows) => registration.open && this.sendUntracked("resize", { targetId, cols, rows }),
    }
  }

  async interrupt(targetId: string): Promise<void> { expectVoid(await this.request("interrupt", { targetId }), "interrupt") }
  async kill(targetId: string): Promise<void> { expectVoid(await this.request("kill", { targetId }), "kill") }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.ready = false
    this.socket?.destroy()
    this.socket = undefined
    this.rejectPending(new Error("sessiond client is closed"))
    this.clearViewers()
  }

  private async connectWithAdoption(): Promise<void> {
    try {
      await this.openAndAuthenticate()
      return
    } catch (error) {
      if (this.closed || this.platform !== "win32" || !adoptable(error)) throw error
    }
    await this.spawnSessiond(this.executable, this.stateDir)
    const deadline = Date.now() + this.adoptionTimeoutMs
    let lastError: unknown = new Error("sessiond endpoint did not appear")
    while (!this.closed && Date.now() <= deadline) {
      try { await this.openAndAuthenticate(); return } catch (error) {
        lastError = error
        if (!adoptable(error)) throw error
        await new Promise(resolve => setTimeout(resolve, this.adoptionPollMs))
      }
    }
    throw lastError
  }

  private async openAndAuthenticate(): Promise<void> {
    this.destroyCurrent(new Error("sessiond connection replaced"))
    const socket = await this.connectSocket(this.endpoint)
    if (this.closed) { socket.destroy(); throw new Error("sessiond client is closed") }
    this.socket = socket
    this.frames = new SessiondFrameAccumulator()
    this.wire(socket)
    try {
      const value = await this.rawRequest("hello", {})
      if (!isRecord(value) || value.version !== PROTOCOL_VERSION || value.healthy !== true) throw new Error("invalid sessiond hello response")
      this.ready = true
    } catch (error) {
      socket.destroy()
      if (this.socket === socket) this.socket = undefined
      throw error
    }
  }

  private wire(socket: Socket): void {
    socket.on("data", chunk => {
      if (this.socket !== socket) return
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      try {
        for (const message of this.frames.push(bytes)) this.acceptMessage(message)
      } catch (error) {
        socket.destroy(error instanceof SessiondFrameError ? error : new Error("malformed sessiond frame"))
      }
    })
    socket.on("error", () => {})
    socket.on("close", () => {
      if (this.socket !== socket) return
      this.socket = undefined
      this.ready = false
      this.frames = new SessiondFrameAccumulator()
      this.rejectPending(new Error("sessiond disconnected"))
      this.clearViewers()
    })
  }

  private acceptMessage(input: unknown): void {
    if (!isRecord(input)) return
    if (typeof input.id === "string" && typeof input.ok === "boolean") {
      const pending = this.pending.get(input.id)
      if (!pending) return
      this.pending.delete(input.id); clearTimeout(pending.timer)
      if (input.ok) pending.resolve(input.value)
      else pending.reject(new Error(typeof input.error === "string" ? input.error.slice(0, 500) : "sessiond request failed"))
      return
    }
    if (typeof input.targetId !== "string") return
    if (input.event === "exit" && typeof input.code === "number" && Number.isSafeInteger(input.code)) {
      if (input.viewerId === undefined) {
        for (const viewer of [...this.viewers.values()]) {
          if (viewer.targetId === input.targetId) this.requestViewerExit(viewer, input.code)
        }
      } else if (typeof input.viewerId === "string") {
        const viewer = this.viewers.get(`${input.targetId}\0${input.viewerId}`)
        if (viewer) this.requestViewerExit(viewer, input.code)
      }
      return
    }
    if (input.event === "viewerFailure" && typeof input.viewerId === "string" && typeof input.reason === "string") {
      const viewer = this.viewers.get(`${input.targetId}\0${input.viewerId}`)
      if (viewer) this.requestViewerFailure(viewer, input.reason.slice(0, 500))
      return
    }
    if (typeof input.viewerId !== "string") return
    const viewer = this.viewers.get(`${input.targetId}\0${input.viewerId}`)
    if (!viewer) return
    if (input.event === "data" && validBase64(input.dataBase64)) {
      this.enqueueViewerData(viewer, Buffer.from(input.dataBase64, "base64"))
    }
  }

  private enqueueViewerData(viewer: ViewerRegistration, data: Uint8Array): void {
    if (!viewer.open || viewer.requestedExit !== undefined || viewer.requestedFailure !== undefined || data.byteLength === 0) return
    const key = `${viewer.targetId}\0${viewer.viewerId}`
    if (viewer.pendingBytes + data.byteLength > this.viewerInboundByteLimit) {
      this.failViewer(key, viewer, `sessiond viewer delivery queue exceeds ${this.viewerInboundByteLimit} bytes`, true)
      return
    }
    viewer.queue.push(data.slice())
    viewer.pendingBytes += data.byteLength
    this.pumpViewerData(viewer)
  }

  private pumpViewerData(viewer: ViewerRegistration): void {
    if (viewer.delivering || !viewer.open) return
    const key = `${viewer.targetId}\0${viewer.viewerId}`
    viewer.delivering = true
    void (async () => {
      try {
        while (viewer.open) {
          const chunk = viewer.queue.shift()
          if (!chunk) break
          try {
            await viewer.onData(chunk)
          } catch {
            this.failViewer(key, viewer, "sessiond viewer data handler failed", true)
            return
          } finally {
            viewer.pendingBytes = Math.max(0, viewer.pendingBytes - chunk.byteLength)
          }
        }
      } finally {
        viewer.delivering = false
        if (viewer.open && viewer.queue.length > 0) this.pumpViewerData(viewer)
        else if (viewer.open && viewer.requestedFailure !== undefined) this.failViewer(key, viewer, viewer.requestedFailure, false)
        else if (viewer.open && viewer.requestedExit !== undefined) this.finishViewerExit(key, viewer, viewer.requestedExit)
      }
    })()
  }

  private requestViewerExit(viewer: ViewerRegistration, code: number): void {
    if (!viewer.open) return
    viewer.requestedExit ??= code
    if (!viewer.delivering && viewer.queue.length === 0) {
      this.finishViewerExit(`${viewer.targetId}\0${viewer.viewerId}`, viewer, viewer.requestedExit)
    }
  }

  private requestViewerFailure(viewer: ViewerRegistration, reason: string): void {
    if (!viewer.open) return
    viewer.requestedFailure ??= reason
    if (!viewer.delivering && viewer.queue.length === 0) {
      this.failViewer(`${viewer.targetId}\0${viewer.viewerId}`, viewer, viewer.requestedFailure, false)
    }
  }

  private finishViewerExit(key: string, viewer: ViewerRegistration, code: number): void {
    if (!viewer.open) return
    viewer.open = false
    viewer.exitCode = code
    viewer.queue.length = 0
    viewer.pendingBytes = 0
    this.viewers.delete(key)
    viewer.resolveExited(code)
    for (const handler of [...viewer.exitHandlers]) { try { handler(code) } catch {} }
    viewer.exitHandlers.clear()
    viewer.failureHandlers.clear()
  }

  private failViewer(key: string, viewer: ViewerRegistration, reason: string, detach: boolean): void {
    if (!viewer.open) return
    viewer.open = false
    viewer.failureReason = reason
    viewer.queue.length = 0
    viewer.pendingBytes = 0
    this.viewers.delete(key)
    for (const handler of [...viewer.failureHandlers]) { try { handler(reason) } catch {} }
    viewer.failureHandlers.clear()
    viewer.exitHandlers.clear()
    if (detach) this.sendUntracked("detach", { targetId: viewer.targetId, viewerId: viewer.viewerId })
  }

  private detachViewer(key: string, viewer: ViewerRegistration, detach: boolean): void {
    if (!viewer.open) return
    viewer.open = false
    viewer.queue.length = 0
    viewer.pendingBytes = 0
    viewer.exitHandlers.clear()
    viewer.failureHandlers.clear()
    this.viewers.delete(key)
    if (detach) this.sendUntracked("detach", { targetId: viewer.targetId, viewerId: viewer.viewerId })
  }

  private async request(op: string, args: unknown): Promise<unknown> {
    await this.ensureConnected()
    return this.rawRequest(op, args)
  }

  private rawRequest(op: string, args: unknown): Promise<unknown> {
    if (this.pending.size >= this.maxPendingRequests) return Promise.reject(new Error("sessiond pending request limit exceeded"))
    const id = `rpc-${process.pid}-${++this.sequence}-${randomUUID()}`
    const frame = encodeFrame({ id, version: PROTOCOL_VERSION, secret: this.requiredSecret(), op, args })
    if (frame.byteLength > SESSIOND_MAX_BUFFER_BYTES) return Promise.reject(new Error("sessiond request frame exceeds protocol limit"))
    const socket = this.socket
    if (!socket || socket.destroyed || !socket.writable) return Promise.reject(new Error("sessiond is disconnected"))
    if (socket.writableLength + frame.byteLength > this.maxOutboundBytes) return Promise.reject(new Error("sessiond outbound buffer limit exceeded"))
    return new Promise((resolveRequest, reject) => {
      let entry: Pending
      const timer = setTimeout(() => {
        if (this.pending.get(id) !== entry) return
        this.pending.delete(id)
        const error = new Error(`sessiond request timed out after ${this.requestTimeoutMs}ms`)
        if (this.socket === socket) socket.destroy(error)
        reject(error)
      }, this.requestTimeoutMs)
      entry = { resolve: resolveRequest, reject, timer, socket }
      this.pending.set(id, entry)
      socket.write(frame, error => {
        if (!error) return
        const pending = this.pending.get(id)
        if (!pending) return
        this.pending.delete(id); clearTimeout(pending.timer); pending.reject(error)
      })
    })
  }

  private sendUntracked(op: string, args: unknown): boolean {
    if (!this.ready) return false
    const socket = this.socket
    if (!socket || socket.destroyed || !socket.writable) return false
    const id = `viewer-${process.pid}-${++this.sequence}`
    const frame = encodeFrame({ id, version: PROTOCOL_VERSION, secret: this.requiredSecret(), op, args })
    if (frame.byteLength > SESSIOND_MAX_BUFFER_BYTES) return false
    if (socket.writableLength + frame.byteLength > this.maxOutboundBytes) return false
    try { this.writeUntrackedFrame(socket, frame); return true } catch { return false }
  }

  private rejectPending(error: Error): void {
    for (const [id, pending] of this.pending) {
      this.pending.delete(id); clearTimeout(pending.timer); pending.reject(error)
    }
  }

  private destroyCurrent(error: Error): void {
    this.ready = false
    this.socket?.destroy()
    this.socket = undefined
    this.rejectPending(error)
    this.clearViewers()
  }

  private requiredSecret(): string {
    if (!this.secret) throw new Error("sessiond secret is not initialized")
    return this.secret
  }

  private clearViewers(): void {
    for (const viewer of [...this.viewers.values()]) {
      this.failViewer(`${viewer.targetId}\0${viewer.viewerId}`, viewer, "sessiond connection closed", false)
    }
    this.viewers.clear()
  }
}
