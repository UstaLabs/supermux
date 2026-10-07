/**
 * supermux-core MCP bridge: the agent runs this as an ordinary stdio MCP server
 * (`<js runtime> bridge.js --server <name>`). It has no dependencies and no MCP library: it pipes
 * newline-delimited JSON-RPC between stdio and the core's Unix socket, after a one-line hello
 * `{protocol, token, sessionId, server}`. Env: SUPERMUX_MCP_SOCKET, SUPERMUX_MCP_SESSION,
 * SUPERMUX_MCP_TOKEN (SUPERMUX_MCP_RETRY_MAX_MS caps the reconnect backoff, default 5000).
 *
 * If the host goes away (restart, detached session) the bridge keeps running and reconnects with
 * backoff. While it is disconnected every JSON-RPC request from the agent gets an immediate error
 * `{code: -32000, message: "host unavailable"}`; notifications are dropped. A new host-side server
 * is uninitialized while the agent already initialized, so on every reconnect the bridge replays
 * the agent's original `initialize` (its response is swallowed) and `notifications/initialized`
 * before it forwards anything else.
 *
 * Self-contained on purpose (node:* only): keep it that way.
 */
import { createConnection, type Socket } from "node:net"

type Message = { jsonrpc?: string; id?: string | number | null; method?: string; params?: unknown; result?: unknown; error?: unknown }

const PROTOCOL = "supermux-mcp-bridge/1"
const HOST_UNAVAILABLE = -32000
/** Hello refusals after which retrying cannot help: the bridge exits. */
const FATAL = new Set(["unauthorized", "bad_hello"])

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : undefined
}

const server = arg("--server")
const socketPath = process.env.SUPERMUX_MCP_SOCKET
const sessionId = process.env.SUPERMUX_MCP_SESSION
const token = process.env.SUPERMUX_MCP_TOKEN
const retryMax = Number(process.env.SUPERMUX_MCP_RETRY_MAX_MS) > 0 ? Number(process.env.SUPERMUX_MCP_RETRY_MAX_MS) : 5000

function log(text: string): void { process.stderr.write(`[supermux-mcp-bridge ${server ?? "?"}] ${text}\n`) }

if (!server || !socketPath || !sessionId || !token) {
  log("usage: bridge --server <name> with SUPERMUX_MCP_SOCKET, SUPERMUX_MCP_SESSION and SUPERMUX_MCP_TOKEN set")
  process.exit(2)
}

type State = "connecting" | "replaying" | "open" | "down"
let state: State = "connecting"
let socket: Socket | undefined
/** Agent messages held while connecting or replaying (forwarded in order once open). */
let held: Message[] = []
let initialize: Message | undefined
let replayId: string | undefined
let replays = 0
let delay = 100
let stopping = false

function toAgent(message: Message): void { process.stdout.write(JSON.stringify(message) + "\n") }
function toHost(message: Message): void { socket?.write(JSON.stringify(message) + "\n") }

function unavailable(message: Message): void {
  // Requests get an answer right away; notifications and responses are dropped.
  if (message.method !== undefined && message.id !== undefined && message.id !== null) {
    toAgent({ jsonrpc: "2.0", id: message.id, error: { code: HOST_UNAVAILABLE, message: "host unavailable" } })
  }
}

function fromAgent(message: Message): void {
  if (state === "connecting" || state === "replaying") { held.push(message); return }
  if (state === "down") { unavailable(message); return }
  if (message.method === "initialize" && message.id !== undefined && initialize === undefined) initialize = message
  toHost(message)
}

function fromHost(message: Message): void {
  if (state === "replaying" && replayId !== undefined && message.id === replayId && message.method === undefined) {
    replayId = undefined
    if (message.error !== undefined) { log(`replayed initialize failed: ${JSON.stringify(message.error)}`); socket?.destroy(); return }
    toHost({ jsonrpc: "2.0", method: "notifications/initialized" })
    open()
    return
  }
  toAgent(message)
}

function open(): void {
  state = "open"
  delay = 100
  const queue = held
  held = []
  for (const message of queue) fromAgent(message)
}

function down(): void {
  socket = undefined
  replayId = undefined
  if (stopping) return
  state = "down"
  const queue = held
  held = []
  for (const message of queue) unavailable(message)
  const wait = delay
  delay = Math.min(delay * 2, retryMax)
  setTimeout(connect, wait).unref?.()
}

function connect(): void {
  if (stopping) return
  const attempt = createConnection(socketPath!)
  let buffer = ""
  let greeted = false
  attempt.setEncoding("utf8")
  attempt.once("connect", () => {
    attempt.write(JSON.stringify({ protocol: PROTOCOL, token, sessionId, server }) + "\n")
  })
  attempt.on("data", (chunk: string) => {
    buffer += chunk
    for (let newline = buffer.indexOf("\n"); newline >= 0; newline = buffer.indexOf("\n")) {
      const line = buffer.slice(0, newline).trim()
      buffer = buffer.slice(newline + 1)
      if (!line) continue
      let message: Message & { ok?: boolean; code?: string }
      try { message = JSON.parse(line) } catch { log("malformed line from the host"); continue }
      if (!greeted) {
        greeted = true
        if (message.ok !== true) {
          log(`host refused the bridge: ${message.code ?? "?"} ${(message as { message?: string }).message ?? ""}`)
          if (message.code && FATAL.has(message.code)) { stopping = true; process.exit(1) }
          attempt.destroy()
          return
        }
        welcome(attempt)
        continue
      }
      fromHost(message)
    }
  })
  attempt.on("error", () => {})
  attempt.on("close", down)
}

function welcome(connected: Socket): void {
  socket = connected
  if (initialize !== undefined) {
    // A host-side server that never saw this agent: replay its initialize, swallow the answer.
    state = "replaying"
    replayId = `supermux-bridge-replay-${++replays}`
    toHost({ ...initialize, id: replayId })
  } else open()
}

let input = ""
process.stdin.setEncoding("utf8")
process.stdin.on("data", (chunk: string) => {
  input += chunk
  for (let newline = input.indexOf("\n"); newline >= 0; newline = input.indexOf("\n")) {
    const line = input.slice(0, newline).trim()
    input = input.slice(newline + 1)
    if (!line) continue
    let message: Message
    try { message = JSON.parse(line) } catch { log("malformed line from the agent"); continue }
    fromAgent(message)
  }
})
process.stdin.on("end", () => { stopping = true; socket?.end(); process.exit(0) })
process.on("SIGTERM", () => { stopping = true; socket?.destroy(); process.exit(0) })
connect()
