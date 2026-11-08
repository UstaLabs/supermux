// The external mux-shim is a stdio MCP server: the agent that started it holds the other end of its stdin, so when
// that ends the agent is gone. The shim used to ignore it (the MCP SDK's stdio transport listens for data, not for the
// end) and, once the broker closed the session's socket, reconnect to it forever: an agent killed without killing its
// MCP servers (OpenCode on SIGTERM) left its shim running for good. Drives the REAL shim process against a fake broker.
import { afterEach, beforeEach, expect, test } from "bun:test"
import { spawn, type ChildProcess } from "child_process"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createInterface } from "readline"
import { startSocketServer, type SocketServer } from "../src/core/session-manager/socket-server"

const SESSION = "sess-shim-exit"
let dir: string
let broker: SocketServer | undefined
let shim: ChildProcess | undefined
let registers = 0

async function startBroker(): Promise<void> {
  broker = await startSocketServer({
    socketsDir: dir,
    handler: {
      onRegister: async () => { registers++; return { name: "w", session_id: SESSION } },
      onOutbound: async () => ({ ok: true }),
      onOrchestration: async () => ({ ok: true, value: "done" }),
    },
  })
  await broker.bind(SESSION)
}

async function until(check: () => boolean, what: string, timeoutMs = 8_000): Promise<void> {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`)
    await new Promise((r) => setTimeout(r, 20))
  }
}

/** The shim, initialized over stdio as an agent's MCP client does; resolves once it answered. */
async function startShim(): Promise<ChildProcess> {
  const base = { ...process.env }
  for (const key of ["MUX_RPC_ONLY", "MUX_CHANNEL_ONLY", "CLAUDE_SESSION_ID", "MUX_DISPLAY_NAME"]) delete base[key]
  const child = spawn(process.execPath, ["run", join(import.meta.dirname, "../src/shim/index.ts")], {
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...base, MUX_SESSION_ID: SESSION, MUX_SOCKETS_DIR: dir, MUX_AGENT_KIND: "opencode" },
  })
  shim = child
  const answered = new Promise<void>((resolve) => {
    createInterface({ input: child.stdout! }).on("line", (line) => { if ((JSON.parse(line) as { id?: number }).id === 1) resolve() })
  })
  child.stdin!.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "agent", version: "1" } } }) + "\n")
  await answered
  await until(() => registers > 0, "the shim to register with the broker")
  return child
}

const exited = (child: ChildProcess) => child.exitCode !== null || child.signalCode !== null

beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "mux-shim-exit-")); registers = 0 })
afterEach(async () => {
  if (shim && !exited(shim)) shim.kill("SIGKILL")
  shim = undefined
  await broker?.close(); broker = undefined
  rmSync(dir, { recursive: true, force: true })
})

test("a shim exits once its agent closes its stdin", async () => {
  await startBroker()
  const child = await startShim()
  child.stdin!.end()
  await until(() => exited(child), "the shim to exit after its stdin ended")
  expect(child.exitCode).toBe(0)
}, 20_000)

test("a shim whose broker went away stops reconnecting and exits once its stdin ends", async () => {
  await startBroker()
  const child = await startShim()
  // The broker closes the session's socket (a killed session, a broker restart): the shim starts reconnecting.
  await broker!.close(); broker = undefined
  await new Promise((r) => setTimeout(r, 300))
  child.stdin!.end()
  await until(() => exited(child), "the shim to exit after its stdin ended")
  expect(child.exitCode).toBe(0)
  // And nothing reconnects afterwards: a broker that comes back hears no register from it.
  const before = registers
  await startBroker()
  await new Promise((r) => setTimeout(r, 1_500))
  expect(registers).toBe(before)
}, 20_000)
