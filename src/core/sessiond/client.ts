import { randomUUID } from "node:crypto"
import { dirname, isAbsolute, join, resolve } from "node:path"
import { createConnection, type Socket } from "node:net"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { decodeFrames, encodeFrame } from "../../shared/frame-codec"
import { PROTOCOL_VERSION } from "./protocol"
import { SESSIOND_MAX_BUFFER_BYTES, SESSIOND_MAX_FRAME_BYTES } from "./server"
import { createOrLoadSessiondSecret } from "./secret"

type SpawnSessiond = (executable: string, stateDir: string) => void | Promise<void>

export type SessiondBackendOptions = {
  endpoint: string
  secret?: string
  stateDir: string
  platform?: NodeJS.Platform
  executable?: string
  spawnSessiond?: SpawnSessiond
  adoptionTimeoutMs?: number
  adoptionPollMs?: number
  requestTimeoutMs?: number
  maxPendingRequests?: number
  maxOutboundBytes?: number
}

type Pending = { resolve(value: unknown): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }
type ViewerRegistration = {
  onData: (data: Uint8Array) => void | Promise<void>
  closeLocal(): void
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
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.name !== "string"
    || !(typeof value.pid === "number" || value.pid === null) || typeof value.alive !== "boolean") {
    throw new Error("sessiond returned an invalid runtime target")
  }
  return { id: value.id, name: value.name, pid: value.pid, alive: value.alive }
}

function adoptable(error: unknown): boolean {
  const code = (error as NodeJS.ErrnoException)?.code
  return code === "ENOENT" || code === "ECONNREFUSED" || code === "EPIPE"
}

export class SessiondBackend implements SessionBackend {
  private socket?: Socket
  private ready = false
  private closed = false
  private connectionAttempt?: Promise<void>
  private buffer: Buffer = Buffer.alloc(0)
  private sequence = 0
  private readonly pending = new Map<string, Pending>()
  private readonly viewers = new Map<string, ViewerRegistration>()
  private readonly endpoint: string
  private secret?: string
  private readonly stateDir: string
  private readonly platform: NodeJS.Platform
  private readonly executable: string
  private readonly spawnSessiond: SpawnSessiond
  private readonly adoptionTimeoutMs: number
  private readonly adoptionPollMs: number
  private readonly requestTimeoutMs: number
  private readonly maxPendingRequests: number
  private readonly maxOutboundBytes: number

  constructor(options: SessiondBackendOptions) {
    this.endpoint = options.endpoint
    this.secret = options.secret
    this.stateDir = isAbsolute(options.stateDir) ? options.stateDir : resolve(options.stateDir)
    this.platform = options.platform ?? process.platform
    this.executable = options.executable ?? defaultExecutable()
    this.spawnSessiond = options.spawnSessiond ?? defaultSpawn
    this.adoptionTimeoutMs = options.adoptionTimeoutMs ?? 10_000
    this.adoptionPollMs = options.adoptionPollMs ?? 100
    this.requestTimeoutMs = options.requestTimeoutMs ?? 10_000
    this.maxPendingRequests = options.maxPendingRequests ?? 1024
    this.maxOutboundBytes = options.maxOutboundBytes ?? 4 * 1024 * 1024
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
    return target(await this.request("create", options))
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
    if (!(typeof value === "number" || value === null)) throw new Error("sessiond returned an invalid PID")
    return value
  }

  async write(targetId: string, data: Uint8Array): Promise<void> {
    await this.request("write", { targetId, dataBase64: Buffer.from(data).toString("base64") })
  }

  async sendKeys(targetId: string, keys: string[]): Promise<void> { await this.request("sendKeys", { targetId, keys }) }
  async resize(targetId: string, cols: number, rows: number): Promise<void> { await this.request("resize", { targetId, cols, rows }) }

  async capture(targetId: string, raw?: boolean): Promise<string | null> {
    const value = await this.request("capture", { targetId, ...(raw === undefined ? {} : { raw }) })
    if (!(typeof value === "string" || value === null)) throw new Error("sessiond returned invalid capture data")
    return value
  }

  async attach(targetId: string, viewerId: string, onData: (data: Uint8Array) => void | Promise<void>): Promise<RuntimeViewer> {
    const key = `${targetId}\0${viewerId}`
    let open = true
    const registration: ViewerRegistration = { onData, closeLocal: () => { open = false } }
    if (this.viewers.has(key)) throw new Error(`viewer is already attached: ${viewerId}`)
    this.viewers.set(key, registration)
    try { await this.request("attach", { targetId, viewerId }) } catch (error) { this.viewers.delete(key); open = false; throw error }
    return {
      close: () => {
        if (!open) return
        open = false
        this.viewers.delete(key)
        this.sendUntracked("detach", { targetId, viewerId })
      },
      write: data => open && this.sendUntracked("write", { targetId, dataBase64: Buffer.from(data).toString("base64") }),
      resize: (cols, rows) => open && this.sendUntracked("resize", { targetId, cols, rows }),
    }
  }

  async interrupt(targetId: string): Promise<void> { await this.request("interrupt", { targetId }) }
  async kill(targetId: string): Promise<void> { await this.request("kill", { targetId }) }

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
    const socket = await new Promise<Socket>((resolveConnect, reject) => {
      const candidate = createConnection(this.endpoint)
      const onError = (error: Error) => { candidate.destroy(); reject(error) }
      candidate.once("error", onError)
      candidate.once("connect", () => { candidate.off("error", onError); resolveConnect(candidate) })
    })
    if (this.closed) { socket.destroy(); throw new Error("sessiond client is closed") }
    this.socket = socket
    this.buffer = Buffer.alloc(0)
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
      this.buffer = Buffer.concat([this.buffer, bytes])
      try {
        while (this.buffer.length >= 4) {
          const length = this.buffer.readUInt32BE(0)
          if (length > SESSIOND_MAX_FRAME_BYTES) { socket.destroy(new Error("sessiond frame too large")); return }
          if (this.buffer.length < length + 4) break
          const framed = this.buffer.subarray(0, length + 4)
          this.buffer = this.buffer.subarray(length + 4)
          const decoded = decodeFrames(framed)
          for (const message of decoded.messages) this.acceptMessage(message)
        }
        if (this.buffer.length > SESSIOND_MAX_BUFFER_BYTES) socket.destroy(new Error("sessiond frame buffer too large"))
      } catch { socket.destroy(new Error("malformed sessiond frame")) }
    })
    socket.on("error", () => {})
    socket.on("close", () => {
      if (this.socket !== socket) return
      this.socket = undefined
      this.ready = false
      this.buffer = Buffer.alloc(0)
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
    if (input.event !== "data" || typeof input.targetId !== "string" || typeof input.viewerId !== "string" || !validBase64(input.dataBase64)) return
    const viewer = this.viewers.get(`${input.targetId}\0${input.viewerId}`)
    if (viewer) void Promise.resolve(viewer.onData(Buffer.from(input.dataBase64, "base64"))).catch(() => undefined)
  }

  private async request(op: string, args: unknown): Promise<unknown> {
    await this.ensureConnected()
    return this.rawRequest(op, args)
  }

  private rawRequest(op: string, args: unknown): Promise<unknown> {
    if (this.pending.size >= this.maxPendingRequests) return Promise.reject(new Error("sessiond pending request limit exceeded"))
    const id = `rpc-${process.pid}-${++this.sequence}-${randomUUID()}`
    const frame = encodeFrame({ id, version: PROTOCOL_VERSION, secret: this.requiredSecret(), op, args })
    const socket = this.socket
    if (!socket || socket.destroyed || !socket.writable) return Promise.reject(new Error("sessiond is disconnected"))
    if (socket.writableLength + frame.byteLength > this.maxOutboundBytes) return Promise.reject(new Error("sessiond outbound buffer limit exceeded"))
    return new Promise((resolveRequest, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`sessiond request timed out after ${this.requestTimeoutMs}ms`))
      }, this.requestTimeoutMs)
      this.pending.set(id, { resolve: resolveRequest, reject, timer })
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
    if (socket.writableLength + frame.byteLength > this.maxOutboundBytes) return false
    try { return socket.write(frame) } catch { return false }
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
    for (const viewer of this.viewers.values()) viewer.closeLocal()
    this.viewers.clear()
  }
}
