/**
 * The host side of host MCP servers (slice C2): one Unix socket owned by the core. Each agent-side
 * bridge (`mcp-bridge.ts`) connects, sends a one-line hello `{token, sessionId, server}`, and from
 * then on the stream carries newline-delimited JSON-RPC. The host verifies the token, builds an
 * SDK McpServer for that (session, server) and connects it with SocketTransport.
 */
import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from "node:crypto"
import { closeSync, constants, lstatSync } from "node:fs"
import { unlink } from "node:fs/promises"
import { createConnection, createServer, type Server, type Socket } from "node:net"
import { tmpdir } from "node:os"
import { dirname, join, resolve } from "node:path"
import type { JSONRPCMessage, McpServer, Transport } from "@modelcontextprotocol/server"
import { CoreError } from "../errors.js"
import { createPrivateFile, present, readPrivateFile, realDirectoryIn, realStateDirectory, writeAll } from "../private-files.js"
import { CORE_SUBCOMMANDS, selfEntry } from "../self-entry.js"
import { SUN_PATH_MAX, privateDirectory, privateSocketPath, socketCandidates } from "../unix-socket.js"
import type { ConnectionContext, HostMcpServer } from "./server.js"

/** Env the bridge reads (see mcp-bridge.ts). */
export const BRIDGE_ENV = { socket: "SUPERMUX_MCP_SOCKET", session: "SUPERMUX_MCP_SESSION", token: "SUPERMUX_MCP_TOKEN" } as const
export const HELLO_PROTOCOL = "supermux-mcp-bridge/1"

/**
 * Where the MCP host of `stateDirectory` may listen, best first, as a keeper does (socketCandidates):
 * `<state>/mcp/mcp.sock` when it fits sun_path; otherwise `<base>/supermux-mcp-<uid>/<hash of the state
 * directory>.sock` under the OS temp dir, `/tmp`, then `$XDG_RUNTIME_DIR` last (it is deleted at logout
 * without lingering, under the bridges of detached sessions). The one `@supermux/core/mcp` exports: it only
 * works out the paths and creates or changes nothing (the host takes the first whose directory it can make
 * private, see prepareSocketPath); `invalid_options` when every candidate is longer than sun_path.
 */
export function socketPaths(stateDirectory: string, env: NodeJS.ProcessEnv = process.env, tmp: string = tmpdir()): string[] {
  const state = resolve(stateDirectory)
  const name = createHash("sha256").update(state).digest("hex").slice(0, 16)
  const paths = socketCandidates(join(state, "mcp", "mcp.sock"), "supermux-mcp", `${name}.sock`, env, tmp)
  if (!paths.length) throw new CoreError("invalid_options", `No usable MCP socket path for ${state}: every candidate is longer than ${SUN_PATH_MAX} bytes`)
  return paths
}

/**
 * The socket the MCP host of `stateDirectory` listens on: the first of socketPaths whose directory this
 * user can make private (0700, its own, not a link; made, or tightened with a chmod, when it is not there
 * or not private yet). Derived from the state directory alone (and the environment), so a restarted host
 * listens where the bridges of its detached sessions, which keep the path they were launched with,
 * reconnect. Not exported by `@supermux/core/mcp`: it has those side effects, which socketPaths has not.
 */
export function prepareSocketPath(stateDirectory: string, env: NodeJS.ProcessEnv = process.env, tmp: string = tmpdir()): { directory: string; path: string } {
  const path = privateSocketPath(socketPaths(stateDirectory, env, tmp), join(resolve(stateDirectory), "mcp", "mcp.sock"))
  return { directory: dirname(path), path }
}

/**
 * The first argument after `process.execPath` that starts the bridge: `bridge.js` next to this file in
 * dist, `bridge.ts` when running from src (bun), the `__core-bridge` subcommand inside a compiled binary.
 */
export function bridgeEntry(): string {
  return selfEntry(import.meta.url, "bridge", CORE_SUBCOMMANDS.bridge).args[0]!
}

export type HostLookup = (sessionId: string, server: string) => Promise<
  | { ok: true; server: HostMcpServer; agent: string; account?: string }
  | { ok: false; code: "unknown_session" | "unknown_server" | "not_attached"; message: string }
>

export type ToolEvent =
  | { type: "tool.called"; sessionId: string; server: string; tool: string; callId: string }
  | { type: "tool.finished"; sessionId: string; server: string; tool: string; callId: string; ok: boolean; durationMs: number }

export type McpHostOptions = {
  stateDirectory: string
  lookup: HostLookup
  onEvent(event: ToolEvent): void
  onError?(error: Error): void
}

const MAX_LINE = 64 << 20
const HELLO_TIMEOUT_MS = 10_000

export class McpHost {
  readonly socket: string
  private readonly directory: string
  private readonly secretPath: string
  private secret?: Buffer
  private listener?: Server
  private readonly connections = new Set<Connection>()
  /** Every accepted socket (also those still in the hello), destroyed on close. */
  private readonly sockets = new Set<Socket>()
  private started?: Promise<void>
  private closed = false

  constructor(private readonly options: McpHostOptions) {
    const { directory, path } = prepareSocketPath(options.stateDirectory)
    this.directory = directory
    this.socket = path
    this.secretPath = join(resolve(options.stateDirectory), "mcp", "secret")
  }

  /** Starts listening (once). Refuses when another live process owns the socket. */
  start(): Promise<void> {
    if (this.closed) return Promise.reject(new CoreError("core_closed", "Core is closing or closed"))
    this.started ??= this.listen().catch(error => { this.started = undefined; throw error })
    return this.started
  }

  /** HMAC(secret, sessionId + server): stable across launches and host restarts, so a detached session's bridge still authenticates. */
  async token(sessionId: string, server: string): Promise<string> {
    const secret = await this.loadSecret()
    return createHmac("sha256", secret).update(`${sessionId}\0${server}`).digest("hex")
  }

  /** Aborts every in-flight tool call of a session (a session interrupt). */
  interrupt(sessionId: string): void {
    for (const connection of this.connections) if (connection.sessionId === sessionId) connection.cancelAll("The session was interrupted")
  }

  /** Closes the connections of one server (it was unregistered); their bridges reconnect and are refused until it is back. */
  disconnectServer(name: string): void {
    for (const connection of [...this.connections]) if (connection.server === name) connection.destroy()
  }

  async close(): Promise<void> {
    this.closed = true
    await this.started?.catch(() => {})
    for (const connection of [...this.connections]) connection.destroy()
    for (const socket of [...this.sockets]) socket.destroy()
    const listener = this.listener
    this.listener = undefined
    if (listener) {
      await new Promise<void>(resolve => listener.close(() => resolve()))
      await unlink(this.socket).catch(() => {})
    }
  }

  /**
   * The secret in `<state>/mcp/secret`, made on first use. Never read or written through a symbolic link
   * (`state_unsafe`, see private-files.ts): a planted one could hand the host a secret its planter knows.
   */
  private async loadSecret(): Promise<Buffer> {
    if (this.secret) return this.secret
    // `<state>/mcp`: a directory of this user's that no link leads to, 0700.
    privateDirectory(realDirectoryIn(realStateDirectory(this.options.stateDirectory), "mcp"))
    let text = readPrivateFile(this.secretPath)
    if (text === undefined) {
      const fresh = randomBytes(32)
      // O_EXCL: two cores racing to create it keep the first one's secret.
      let fd: number | undefined
      try { fd = createPrivateFile(this.secretPath, constants.O_WRONLY) } catch (error) { if (!present(this.secretPath)) throw error }
      if (fd !== undefined) {
        try { writeAll(fd, fresh.toString("hex") + "\n") } finally { closeSync(fd) }
        return this.secret = fresh
      }
      text = readPrivateFile(this.secretPath)
    }
    const hex = text?.trim() ?? ""
    if (!/^[0-9a-f]{64}$/.test(hex)) throw new CoreError("invalid_options", `${this.secretPath} is not a supermux MCP secret`)
    return this.secret = Buffer.from(hex, "hex")
  }

  private async listen(): Promise<void> {
    await this.loadSecret()
    privateDirectory(this.directory)
    if (present(this.socket)) {
      // Never probed through a link (it could lead to anyone's socket).
      if (lstatSync(this.socket).isSymbolicLink()) throw new CoreError("state_unsafe", `Refusing ${this.socket}: it is a symbolic link`)
      if (await reachable(this.socket)) throw new CoreError("mcp_socket_in_use", `Another live core owns the MCP socket ${this.socket}`)
      await unlink(this.socket).catch(() => {})
    }
    const listener = createServer(socket => this.accept(socket))
    await new Promise<void>((resolve, reject) => {
      listener.once("error", reject)
      // The socket is made 0600 as listen() binds it (a keeper's is too), not chmod'ed by its path afterwards: that would
      // follow a link planted there in between. A socket is made 0777 less the umask, hence 0o177 (not 0o077, which leaves 0700).
      underUmask(0o177, () => listener.listen(this.socket, () => { listener.off("error", reject); resolve() }))
    })
    listener.on("error", error => this.options.onError?.(error))
    if (this.closed) {
      await new Promise<void>(resolve => listener.close(() => resolve()))
      await unlink(this.socket).catch(() => {})
      throw new CoreError("core_closed", "Core is closing or closed")
    }
    this.listener = listener
  }

  private accept(socket: Socket): void {
    if (this.closed) { socket.destroy(); return }
    this.sockets.add(socket)
    socket.once("close", () => this.sockets.delete(socket))
    let buffer = ""
    const timer = setTimeout(() => socket.destroy(), HELLO_TIMEOUT_MS)
    const reject = (code: string, message: string) => {
      clearTimeout(timer)
      socket.end(JSON.stringify({ ok: false, code, message }) + "\n", () => socket.destroy())
    }
    const onData = (chunk: Buffer) => {
      buffer += chunk.toString("utf8")
      const newline = buffer.indexOf("\n")
      if (newline < 0) { if (buffer.length > 64 << 10) socket.destroy(); return }
      socket.off("data", onData)
      socket.pause()
      const line = buffer.slice(0, newline), rest = buffer.slice(newline + 1)
      void this.hello(socket, line, rest, reject).finally(() => clearTimeout(timer)).catch(error => {
        this.options.onError?.(error instanceof Error ? error : new Error(String(error)))
        socket.destroy()
      })
    }
    socket.on("data", onData)
    socket.on("error", () => {})
  }

  private async hello(socket: Socket, line: string, rest: string, reject: (code: string, message: string) => void): Promise<void> {
    let hello: { protocol?: unknown; token?: unknown; sessionId?: unknown; server?: unknown }
    try { hello = JSON.parse(line) } catch { return reject("bad_hello", "hello is not JSON") }
    if (hello.protocol !== HELLO_PROTOCOL || typeof hello.token !== "string" || typeof hello.sessionId !== "string" || typeof hello.server !== "string") return reject("bad_hello", "malformed hello")
    const expected = Buffer.from(await this.token(hello.sessionId, hello.server), "hex")
    const given = /^[0-9a-f]{64}$/.test(hello.token) ? Buffer.from(hello.token, "hex") : Buffer.alloc(0)
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) return reject("unauthorized", "bad token")
    const found = await this.options.lookup(hello.sessionId, hello.server)
    if (!found.ok) return reject(found.code, found.message)
    if (this.closed || socket.destroyed) { socket.destroy(); return }
    const abort = new AbortController()
    const ctx: ConnectionContext = {
      sessionId: hello.sessionId, agent: found.agent, ...(found.account !== undefined ? { account: found.account } : {}),
      server: hello.server, signal: abort.signal,
    }
    let built: Awaited<ReturnType<HostMcpServer["build"]>>
    try { built = await found.server.build(ctx) }
    catch (error) { abort.abort(); return reject("server_failed", error instanceof Error ? error.message : String(error)) }
    socket.write(JSON.stringify({ ok: true }) + "\n")
    const connection = new Connection(socket, hello.sessionId, hello.server, built.server, this.options.onEvent, () => {
      this.connections.delete(connection)
      abort.abort()
      built.dispose()
    })
    this.connections.add(connection)
    await connection.start(rest)
  }
}

/**
 * Runs `body` (which makes a file) under umask `mask`, then puts the umask back. A worker thread cannot change the umask:
 * there `body` runs under the one it has, and its socket's directory (0700, this user's) is what keeps the others out.
 */
function underUmask<T>(mask: number, body: () => T): T {
  let before: number | undefined
  try { before = process.umask(mask) } catch { /* not settable here */ }
  try { return body() } finally { if (before !== undefined) process.umask(before) }
}

function reachable(path: string): Promise<boolean> {
  return new Promise(resolve => {
    const probe = createConnection(path)
    const done = (value: boolean) => { probe.destroy(); resolve(value) }
    probe.once("connect", () => done(true))
    probe.once("error", () => done(false))
    setTimeout(() => done(false), 2000).unref?.()
  })
}

type Inflight = { tool: string; callId: string; started: number }

/** One bridge connection: the socket, its SDK server and the in-flight tool calls (for events and interrupts). */
class Connection {
  private readonly transport: SocketTransport
  private readonly inflight = new Map<string, Inflight>()
  /** Calls answered by cancelAll: a late SDK response for one is dropped. */
  private readonly cancelled = new Set<string>()
  private finished = false

  constructor(
    private readonly socket: Socket,
    readonly sessionId: string,
    readonly server: string,
    private readonly mcp: McpServer,
    private readonly emit: (event: ToolEvent) => void,
    private readonly onClosed: () => void,
  ) {
    this.transport = new SocketTransport(socket, {
      inbound: message => this.inbound(message),
      outbound: message => this.outbound(message),
    })
    this.transport.onclosed = () => this.closed()
  }

  async start(rest: string): Promise<void> {
    await this.mcp.connect(this.transport)
    this.transport.begin(rest)
  }

  /** Cancels every in-flight call: the SDK aborts its handler (ctx.signal) and the agent gets an isError result right away. */
  cancelAll(reason: string): void {
    for (const key of [...this.inflight.keys()]) {
      const id = JSON.parse(key) as string | number
      this.finish(key, false)
      this.cancelled.add(key)
      this.transport.inject({ jsonrpc: "2.0", method: "notifications/cancelled", params: { requestId: id, reason } })
      void this.transport.write({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: `Cancelled: ${reason}` }], isError: true } })
    }
  }

  destroy(): void { this.socket.destroy() }

  private inbound(message: JSONRPCMessage): void {
    const request = message as { id?: string | number; method?: string; params?: { name?: unknown; requestId?: string | number } }
    if (request.method === "notifications/cancelled" && request.params?.requestId !== undefined) {
      // The agent cancelled its call: the SDK aborts the handler and sends no answer.
      const key = JSON.stringify(request.params.requestId)
      if (this.inflight.has(key)) { this.finish(key, false); this.cancelled.add(key) }
      return
    }
    if (request.method === "tools/call" && request.id !== undefined) {
      const tool = typeof request.params?.name === "string" ? request.params.name : ""
      const call: Inflight = { tool, callId: randomUUID(), started: Date.now() }
      this.cancelled.delete(JSON.stringify(request.id))
      this.inflight.set(JSON.stringify(request.id), call)
      this.emit({ type: "tool.called", sessionId: this.sessionId, server: this.server, tool, callId: call.callId })
    }
  }

  /** false: drop the message (a response to a call that was already answered by cancelAll). */
  private outbound(message: JSONRPCMessage): boolean {
    const response = message as { id?: string | number; method?: string; result?: { isError?: unknown }; error?: unknown }
    if (response.method !== undefined || response.id === undefined) return true
    const key = JSON.stringify(response.id)
    if (this.cancelled.delete(key)) return false
    if (!this.inflight.has(key)) return true
    this.finish(key, response.error === undefined && response.result?.isError !== true)
    return true
  }

  private finish(key: string, ok: boolean): void {
    const call = this.inflight.get(key)
    if (!call) return
    this.inflight.delete(key)
    this.emit({ type: "tool.finished", sessionId: this.sessionId, server: this.server, tool: call.tool, callId: call.callId, ok, durationMs: Date.now() - call.started })
  }

  private closed(): void {
    if (this.finished) return
    this.finished = true
    for (const key of [...this.inflight.keys()]) this.finish(key, false)
    void this.mcp.close().catch(() => {})
    this.onClosed()
  }
}

/** An SDK Transport over the bridge socket: newline-delimited JSON-RPC. */
class SocketTransport implements Transport {
  onclose?: () => void
  onerror?: (error: Error) => void
  onmessage?: <T extends JSONRPCMessage>(message: T) => void
  onclosed?: () => void
  private buffer = ""
  private closing = false

  constructor(private readonly socket: Socket, private readonly hooks: { inbound(message: JSONRPCMessage): void; outbound(message: JSONRPCMessage): boolean }) {}

  async start(): Promise<void> {}

  /** Starts reading (after the SDK connected), with the bytes that followed the hello. */
  begin(rest: string): void {
    this.socket.on("data", chunk => this.data(chunk.toString("utf8")))
    this.socket.on("close", () => this.finish())
    this.socket.on("error", error => this.onerror?.(error))
    if (rest) this.data(rest)
    this.socket.resume()
  }

  /** Delivers a message to the SDK as if it came from the agent. */
  inject(message: JSONRPCMessage): void { this.onmessage?.(message) }

  async send(message: JSONRPCMessage): Promise<void> {
    if (!this.hooks.outbound(message)) return
    await this.write(message)
  }

  write(message: JSONRPCMessage): Promise<void> {
    if (this.closing || this.socket.destroyed) return Promise.resolve()
    return new Promise(resolve => { this.socket.write(JSON.stringify(message) + "\n", () => resolve()) })
  }

  async close(): Promise<void> {
    this.socket.end()
    this.finish()
  }

  private data(text: string): void {
    this.buffer += text
    if (this.buffer.length > MAX_LINE) { this.onerror?.(new Error("MCP message too large")); this.socket.destroy(); return }
    for (let newline = this.buffer.indexOf("\n"); newline >= 0; newline = this.buffer.indexOf("\n")) {
      const line = this.buffer.slice(0, newline).trim()
      this.buffer = this.buffer.slice(newline + 1)
      if (!line) continue
      let message: JSONRPCMessage
      try { message = JSON.parse(line) } catch { this.onerror?.(new Error("Malformed JSON-RPC line from the bridge")); continue }
      this.hooks.inbound(message)
      this.onmessage?.(message)
    }
  }

  private finish(): void {
    if (this.closing) return
    this.closing = true
    this.onclose?.()
    this.onclosed?.()
  }
}
