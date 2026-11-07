// The external mux-shim is a long-lived process with its own build of the tool list. After a broker
// restart or update it serves the list the broker sends in its register reply, and tells the agent
// (notifications/tools/list_changed) when that list differs from the one the agent has. Drives the
// REAL shim process over stdio, as an agent's MCP client would, against a fake broker socket.
import { afterEach, beforeEach, expect, test } from "bun:test"
import { spawn, type ChildProcess } from "child_process"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createInterface } from "readline"
import { startSocketServer, type SocketServer } from "../src/core/session-manager/socket-server"
import { listTools, shimTools } from "../src/shim/tools"
import type { ShimTool, ToolOperation } from "../src/shared/socket-frames"

type Msg = { jsonrpc: "2.0"; id?: number; method?: string; params?: any; result?: any; error?: any }

class ShimProcess {
  readonly child: ChildProcess
  readonly received: Msg[] = []
  readonly stderr: string[] = []
  private next = 1
  private readonly waiting = new Map<number, (m: Msg) => void>()

  constructor(env: Record<string, string>) {
    const base = { ...process.env }
    for (const key of ["MUX_RPC_ONLY", "MUX_CHANNEL_ONLY", "CLAUDE_SESSION_ID", "MUX_DISPLAY_NAME"]) delete base[key]
    this.child = spawn(process.execPath, ["run", join(import.meta.dirname, "../src/shim/index.ts")], { stdio: ["pipe", "pipe", "pipe"], env: { ...base, ...env } })
    createInterface({ input: this.child.stdout! }).on("line", (line) => {
      const m = JSON.parse(line) as Msg
      this.received.push(m)
      if (m.id !== undefined && m.method === undefined) { this.waiting.get(m.id)?.(m); this.waiting.delete(m.id) }
    })
    createInterface({ input: this.child.stderr! }).on("line", (line) => this.stderr.push(line))
  }

  request(method: string, params: unknown = {}): Promise<Msg> {
    const id = this.next++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${method} timed out; stderr: ${this.stderr.join(" | ")}`)), 10_000)
      this.waiting.set(id, (m) => { clearTimeout(timer); resolve(m) })
      this.child.stdin!.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n")
    })
  }

  notify(method: string): void { this.child.stdin!.write(JSON.stringify({ jsonrpc: "2.0", method }) + "\n") }

  listChanged(): number { return this.received.filter((m) => m.method === "notifications/tools/list_changed").length }

  async close(): Promise<void> {
    if (this.child.exitCode !== null || this.child.signalCode !== null) return
    const exited = new Promise((resolve) => this.child.once("exit", resolve))
    this.child.kill("SIGTERM")
    await exited
  }
}

async function until(check: () => boolean, what: string, timeoutMs = 8_000): Promise<void> {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`)
    await new Promise((r) => setTimeout(r, 20))
  }
}

const SESSION = "sess-tool-refresh"
let dir: string
let broker: SocketServer | undefined
let shim: ShimProcess | undefined
/** The tools the fake broker's register reply carries (undefined: a broker that sends none). */
let served: ShimTool[] | undefined
let registers = 0
const calls: Array<{ kind: string; op: ToolOperation }> = []

async function startBroker(): Promise<void> {
  broker = await startSocketServer({
    socketsDir: dir,
    handler: {
      onRegister: async () => { registers++; return { name: "w", session_id: SESSION, ...(served ? { tools: served } : {}) } },
      onOutbound: async (msg) => { calls.push({ kind: "outbound", op: msg.op }); return { ok: true } },
      onOrchestration: async (msg) => { calls.push({ kind: "orchestration", op: msg.op }); return { ok: true, value: "done" } },
    },
  })
  await broker.bind(SESSION)
}

/** Stops the broker and starts a new one serving `next`; resolves once the shim has re-registered with it. */
async function restartBroker(next: ShimTool[] | undefined): Promise<void> {
  await broker!.close()
  served = next
  const before = registers
  await startBroker()
  await until(() => registers > before, "the shim to re-register")
}

async function startShim(): Promise<ShimProcess> {
  shim = new ShimProcess({ MUX_SESSION_ID: SESSION, MUX_SOCKETS_DIR: dir, MUX_AGENT_KIND: "codex" })
  const init = await shim.request("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "agent", version: "1" } })
  shim.notify("notifications/initialized")
  expect(init.result.capabilities.tools).toEqual({ listChanged: true })
  return shim
}

/**
 * A tool call through the broker. Its result comes back on the same socket as, and after, the
 * re-register reply, so once it returns the shim has handled that reply (and written any
 * list_changed to stdout ahead of this answer).
 */
const throughBroker = (s: ShimProcess) => s.request("tools/call", { name: "list_sessions", arguments: {} })
const names = async (s: ShimProcess) => ((await s.request("tools/list")).result.tools as Array<{ name: string }>).map((t) => t.name)
const zap: ShimTool = { name: "zap", description: "A tool this shim's build does not have", inputSchema: { type: "object", properties: {} }, route: { kind: "orchestration", op: "zap_op" } }

beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "mux-shim-refresh-")); registers = 0; calls.length = 0; served = shimTools("mux-shim") })
afterEach(async () => {
  await shim?.close(); shim = undefined
  await broker?.close(); broker = undefined
  rmSync(dir, { recursive: true, force: true })
})

test("a restarted broker with a different list: the shim tells the agent, serves the new list and routes the new tool; the same list again sends nothing", async () => {
  await startBroker()
  const s = await startShim()
  const listed = (await s.request("tools/list")).result.tools
  expect(listed).toEqual(shimTools("mux-shim")!.map(({ route: _, ...tool }) => tool))

  await restartBroker([...shimTools("mux-shim")!, zap])
  await until(() => s.listChanged() === 1, "tools/list_changed")
  expect(await names(s)).toContain("zap")
  expect((await s.request("tools/call", { name: "zap", arguments: { n: 1 } })).result).toEqual({ content: [{ type: "text", text: '"done"' }] })
  expect(calls.at(-1)).toEqual({ kind: "orchestration", op: { name: "zap_op", args: { n: 1 } } })

  await restartBroker([...shimTools("mux-shim")!, zap])
  await throughBroker(s)
  expect(s.listChanged()).toBe(1)
}, 30_000)

test("before the agent has listed tools, a changed list is served without a notification", async () => {
  await startBroker()
  const s = await startShim()
  await restartBroker([...shimTools("mux-shim")!, zap])
  await throughBroker(s)
  expect(s.listChanged()).toBe(0)
  expect(await names(s)).toContain("zap")
}, 30_000)

test("a broker that sends no tools: the shim serves its own build's list; a broker that then sends the same list changes nothing", async () => {
  served = undefined
  await startBroker()
  const s = await startShim()
  expect(await names(s)).toEqual(listTools().map((t) => t.name))
  await restartBroker(shimTools("mux-shim"))
  await throughBroker(s)
  expect(s.listChanged()).toBe(0)
}, 30_000)
