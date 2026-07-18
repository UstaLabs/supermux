import { chmod, lstat, mkdir, unlink } from "node:fs/promises"
import { dirname } from "node:path"
import { createConnection, createServer, type Server, type Socket } from "node:net"
import type { RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { decodeFrames, encodeFrame } from "../../shared/frame-codec"
import { parseRequest, PROTOCOL_VERSION, type SessiondRequest, type SessiondResponse } from "./protocol"
import { timingSafeSecretEqual } from "./secret"

export const SESSIOND_MAX_FRAME_BYTES = 1024 * 1024
export const SESSIOND_MAX_BUFFER_BYTES = SESSIOND_MAX_FRAME_BYTES + 4

export type SessiondServerOptions = {
  endpoint: string
  secret: string
  backend: SessionBackend
  platform?: NodeJS.Platform
  maxViewersPerConnection?: number
  maxViewersPerTarget?: number
}

export type SessiondServer = {
  readonly endpoint: string
  close(): Promise<void>
  closeConnections(): void
}

function isPipe(endpoint: string): boolean {
  return endpoint.startsWith("\\\\.\\pipe\\")
}

function send(socket: Socket, value: unknown): Promise<void> {
  if (socket.destroyed || !socket.writable) return Promise.reject(new Error("connection is closed"))
  const frame = encodeFrame(value)
  return new Promise((resolve, reject) => socket.write(frame, error => error ? reject(error) : resolve()))
}

function response(id: string, ok: boolean, value?: unknown, error?: string): SessiondResponse {
  return { id, ok, ...(value === undefined ? {} : { value }), ...(error === undefined ? {} : { error }) }
}

function requestId(input: unknown): string {
  if (typeof input === "object" && input !== null && !Array.isArray(input) && typeof (input as { id?: unknown }).id === "string") {
    return (input as { id: string }).id.slice(0, 256)
  }
  return ""
}

function safeError(error: unknown, secret: string): string {
  const message = error instanceof Error ? error.message : "operation failed"
  return message.replaceAll(secret, "[redacted]").slice(0, 500)
}

async function endpointIsLive(endpoint: string): Promise<boolean> {
  return await new Promise(resolve => {
    const socket = createConnection(endpoint)
    const done = (live: boolean) => { socket.destroy(); resolve(live) }
    socket.once("connect", () => done(true))
    socket.once("error", () => done(false))
  })
}

async function preparePosixEndpoint(endpoint: string): Promise<void> {
  await mkdir(dirname(endpoint), { recursive: true, mode: 0o700 })
  let existing: Awaited<ReturnType<typeof lstat>>
  try {
    existing = await lstat(endpoint)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return
    throw error
  }
  if (!existing.isSocket()) throw new Error(`refusing to replace non-socket sessiond endpoint: ${endpoint}`)
  if (await endpointIsLive(endpoint)) throw Object.assign(new Error(`sessiond endpoint is already live: ${endpoint}`), { code: "EADDRINUSE" })
  await unlink(endpoint)
}

function listen(server: Server, endpoint: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const onError = (error: Error) => { server.off("listening", onListening); reject(error) }
    const onListening = () => { server.off("error", onError); resolve() }
    server.once("error", onError)
    server.once("listening", onListening)
    server.listen({ path: endpoint, exclusive: true, readableAll: false, writableAll: false })
  })
}

export async function startSessiondServer(options: SessiondServerOptions): Promise<SessiondServer> {
  const platform = options.platform ?? process.platform
  const filesystemEndpoint = platform !== "win32" && !isPipe(options.endpoint)
  if (filesystemEndpoint) await preparePosixEndpoint(options.endpoint)

  const sockets = new Set<Socket>()
  const maxViewers = options.maxViewersPerConnection ?? 64
  const maxViewersPerTarget = options.maxViewersPerTarget ?? 8

  const server = createServer(socket => {
    sockets.add(socket)
    let buffer: Buffer = Buffer.alloc(0)
    let dispatch = Promise.resolve()
    let closing = false
    const viewers = new Map<string, RuntimeViewer>()
    const requestIds = new Set<string>()
    const requestIdOrder: string[] = []

    const closeViewers = () => {
      for (const viewer of viewers.values()) viewer.close()
      viewers.clear()
    }
    const failAndClose = (id: string, error: string) => {
      if (closing) return
      closing = true
      void send(socket, response(id, false, undefined, error)).catch(() => undefined).finally(() => socket.end())
    }

    const dispatchRequest = async (request: SessiondRequest): Promise<unknown> => {
      const { backend } = options
      switch (request.op) {
        case "hello": return { version: PROTOCOL_VERSION, healthy: true }
        case "create": return backend.create(request.args)
        case "list": return backend.list(request.args.group)
        case "resolve": return backend.resolve(request.args.group, request.args.name)
        case "livePid": return backend.livePid(request.args.targetId)
        case "write": return backend.write(request.args.targetId, Buffer.from(request.args.dataBase64, "base64"))
        case "sendKeys": return backend.sendKeys(request.args.targetId, request.args.keys)
        case "resize": return backend.resize(request.args.targetId, request.args.cols, request.args.rows)
        case "capture": return backend.capture(request.args.targetId, request.args.raw)
        case "attach": {
          const key = `${request.args.targetId}\0${request.args.viewerId}`
          if (viewers.has(key)) throw new Error("viewer is already attached")
          if (viewers.size >= maxViewers) throw new Error("connection viewer limit exceeded")
          const targetCount = [...viewers.keys()].filter(entry => entry.startsWith(`${request.args.targetId}\0`)).length
          if (targetCount >= maxViewersPerTarget) throw new Error("target viewer limit exceeded")
          const viewer = await backend.attach(request.args.targetId, request.args.viewerId, async data => {
            await send(socket, {
              event: "data",
              targetId: request.args.targetId,
              viewerId: request.args.viewerId,
              dataBase64: Buffer.from(data).toString("base64"),
            })
          })
          if (socket.destroyed || closing) { viewer.close(); throw new Error("connection is closed") }
          viewers.set(key, viewer)
          return { attached: true }
        }
        case "detach": {
          const key = `${request.args.targetId}\0${request.args.viewerId}`
          viewers.get(key)?.close()
          viewers.delete(key)
          return undefined
        }
        case "interrupt": return backend.interrupt(request.args.targetId)
        case "kill": return backend.kill(request.args.targetId)
      }
    }

    const accept = async (input: unknown) => {
      if (closing) return
      const id = requestId(input)
      if (typeof input !== "object" || input === null || Array.isArray(input)) {
        failAndClose(id, "invalid request")
        return
      }
      const envelope = input as Record<string, unknown>
      if (typeof envelope.version !== "number") {
        failAndClose(id, "invalid request")
        return
      }
      if (envelope.version !== PROTOCOL_VERSION) {
        failAndClose(id, "unsupported protocol version")
        return
      }
      if (typeof envelope.secret !== "string" || !timingSafeSecretEqual(options.secret, envelope.secret)) {
        failAndClose(id, "authentication failed")
        return
      }
      let request: SessiondRequest
      try { request = parseRequest(input) } catch { failAndClose(id, "invalid request"); return }
      if (request.id.length > 256) { failAndClose(id, "invalid request"); return }
      if (requestIds.has(request.id)) { failAndClose(id, "duplicate request ID"); return }
      requestIds.add(request.id)
      requestIdOrder.push(request.id)
      if (requestIdOrder.length > 4096) requestIds.delete(requestIdOrder.shift()!)
      try {
        const value = await dispatchRequest(request)
        await send(socket, response(request.id, true, value))
      } catch (error) {
        await send(socket, response(request.id, false, undefined, safeError(error, options.secret))).catch(() => undefined)
      }
    }

    socket.on("data", chunk => {
      if (closing) return
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      buffer = Buffer.concat([buffer, bytes])
      try {
        while (buffer.length >= 4) {
          const length = buffer.readUInt32BE(0)
          if (length > SESSIOND_MAX_FRAME_BYTES) { failAndClose("", "frame too large"); return }
          if (buffer.length < length + 4) break
          const framed = buffer.subarray(0, length + 4)
          buffer = buffer.subarray(length + 4)
          const decoded = decodeFrames(framed)
          for (const message of decoded.messages) dispatch = dispatch.then(() => accept(message))
        }
        if (buffer.length > SESSIOND_MAX_BUFFER_BYTES) failAndClose("", "frame buffer too large")
      } catch {
        failAndClose("", "malformed frame")
      }
    })
    socket.on("error", () => {})
    socket.on("close", () => { sockets.delete(socket); closeViewers() })
  })

  await listen(server, options.endpoint)
  let ownedIdentity: { dev: bigint; ino: bigint } | undefined
  if (filesystemEndpoint) {
    await chmod(options.endpoint, 0o600)
    const stat = await lstat(options.endpoint, { bigint: true })
    ownedIdentity = { dev: stat.dev, ino: stat.ino }
  }
  let closed = false
  return {
    endpoint: options.endpoint,
    closeConnections() { for (const socket of sockets) socket.destroy() },
    async close() {
      if (closed) return
      closed = true
      for (const socket of sockets) socket.destroy()
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
      if (filesystemEndpoint && ownedIdentity) {
        try {
          const stat = await lstat(options.endpoint, { bigint: true })
          if (stat.dev === ownedIdentity.dev && stat.ino === ownedIdentity.ino) await unlink(options.endpoint)
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error
        }
      }
    },
  }
}
