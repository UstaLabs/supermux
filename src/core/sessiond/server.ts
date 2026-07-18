import { chmod, lstat, mkdir, unlink } from "node:fs/promises"
import { dirname } from "node:path"
import { createConnection, createServer, type Server, type Socket } from "node:net"
import type { RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import { encodeFrame } from "../../shared/frame-codec"
import { parseRequest, PROTOCOL_VERSION, type SessiondRequest, type SessiondResponse } from "./protocol"
import { timingSafeSecretEqual } from "./secret"
import { SESSIOND_MAX_FRAME_BYTES, SessiondFrameAccumulator, SessiondFrameError } from "./framing"
export { SESSIOND_MAX_BUFFER_BYTES, SESSIOND_MAX_FRAME_BYTES } from "./framing"

export type SessiondServerOptions = {
  endpoint: string
  secret: string
  backend: SessionBackend
  platform?: NodeJS.Platform
  maxViewersPerConnection?: number
  maxViewersPerTarget?: number
  maxConnections?: number
  handshakeTimeoutMs?: number
  /** @internal Failure seam exercised after secure POSIX ownership setup. */
  postListenSetup?: () => void | Promise<void>
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
  if (frame.byteLength > SESSIOND_MAX_FRAME_BYTES + 4) return Promise.reject(new Error("sessiond frame exceeds protocol limit"))
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
    let settled = false
    const timer = setTimeout(() => done(false), 250)
    const done = (live: boolean) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket.destroy()
      resolve(live)
    }
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
  const maxConnections = options.maxConnections ?? 128
  const handshakeTimeoutMs = options.handshakeTimeoutMs ?? 5_000
  const targetViewerCounts = new Map<string, number>()
  let nextConnectionId = 0

  const server = createServer(socket => {
    if (sockets.size >= maxConnections) { socket.destroy(); return }
    sockets.add(socket)
    const connectionId = `connection-${++nextConnectionId}`
    const frames = new SessiondFrameAccumulator()
    let dispatch = Promise.resolve()
    let closing = false
    let authenticated = false
    const handshakeTimer = setTimeout(() => { if (!authenticated) socket.destroy() }, handshakeTimeoutMs)
    const viewers = new Map<string, { viewer: RuntimeViewer; targetId: string }>()
    const reservations = new Set<{ targetId: string; active: boolean }>()
    const requestIds = new Set<string>()
    const requestIdOrder: string[] = []

    const closeViewers = () => {
      for (const key of [...viewers.keys()]) releaseViewer(key)
      for (const reservation of [...reservations]) releaseReservation(reservation)
    }
    const releaseReservation = (reservation: { targetId: string; active: boolean }) => {
      if (!reservation.active) return
      reservation.active = false
      reservations.delete(reservation)
      const count = targetViewerCounts.get(reservation.targetId) ?? 0
      if (count <= 1) targetViewerCounts.delete(reservation.targetId)
      else targetViewerCounts.set(reservation.targetId, count - 1)
    }
    const releaseViewer = (key: string) => {
      const entry = viewers.get(key)
      if (!entry) return
      viewers.delete(key)
      entry.viewer.close()
      const count = targetViewerCounts.get(entry.targetId) ?? 0
      if (count <= 1) targetViewerCounts.delete(entry.targetId)
      else targetViewerCounts.set(entry.targetId, count - 1)
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
          const targetCount = targetViewerCounts.get(request.args.targetId) ?? 0
          if (targetCount >= maxViewersPerTarget) throw new Error("target viewer limit exceeded")
          targetViewerCounts.set(request.args.targetId, targetCount + 1)
          const reservation = { targetId: request.args.targetId, active: true }
          reservations.add(reservation)
          let viewer: RuntimeViewer | undefined
          try {
            viewer = await backend.attach(request.args.targetId, `${connectionId}:${request.args.viewerId}`, async data => {
              try {
                await send(socket, {
                  event: "data",
                  targetId: request.args.targetId,
                  viewerId: request.args.viewerId,
                  dataBase64: Buffer.from(data).toString("base64"),
                })
              } catch (error) {
                releaseViewer(key)
                throw error
              }
            })
            if (socket.destroyed || closing) throw new Error("connection is closed")
            reservation.active = false
            reservations.delete(reservation)
            viewers.set(key, { viewer, targetId: request.args.targetId })
          } catch (error) {
            viewer?.close()
            releaseReservation(reservation)
            throw error
          }
          return { attached: true }
        }
        case "detach": {
          const key = `${request.args.targetId}\0${request.args.viewerId}`
          releaseViewer(key)
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
      if (!authenticated) { authenticated = true; clearTimeout(handshakeTimer) }
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
      try {
        for (const message of frames.push(bytes)) dispatch = dispatch.then(() => accept(message))
      } catch (error) {
        failAndClose("", error instanceof SessiondFrameError && error.reason === "oversized" ? "frame too large" : "malformed frame")
      }
    })
    socket.on("error", () => {})
    socket.on("close", () => { clearTimeout(handshakeTimer); sockets.delete(socket); closeViewers() })
  })

  await listen(server, options.endpoint)
  let ownedIdentity: { dev: bigint; ino: bigint } | undefined
  try {
    if (filesystemEndpoint) {
      const before = await lstat(options.endpoint, { bigint: true })
      if (!before.isSocket()) throw new Error("sessiond listener endpoint is not a socket")
      ownedIdentity = { dev: before.dev, ino: before.ino }
      await chmod(options.endpoint, 0o600)
      const after = await lstat(options.endpoint, { bigint: true })
      if (!after.isSocket() || after.dev !== before.dev || after.ino !== before.ino) {
        throw new Error("sessiond listener endpoint ownership changed during setup")
      }
    }
    await options.postListenSetup?.()
  } catch (error) {
    for (const socket of sockets) socket.destroy()
    await new Promise<void>(resolveClose => server.close(() => resolveClose()))
    if (filesystemEndpoint && ownedIdentity) {
      try {
        const current = await lstat(options.endpoint, { bigint: true })
        if (current.isSocket() && current.dev === ownedIdentity.dev && current.ino === ownedIdentity.ino) await unlink(options.endpoint)
      } catch (cleanupError) {
        if ((cleanupError as NodeJS.ErrnoException).code !== "ENOENT") {
          throw new AggregateError([error, cleanupError], "sessiond post-listen setup and cleanup failed")
        }
      }
    }
    throw error
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
