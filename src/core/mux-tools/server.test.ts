// C3b: mux-shim / mux-rpc as host MCP servers. Each test drives the SDK server that the core
// would serve to one (session, connection), over an in-memory JSON-RPC link, with the REAL shared
// SessionManager handlers behind it (the same ones the external shim's socket calls).
import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "../session-manager/registry"
import { SessionManager } from "../session-manager/manager"
import { fakePorts } from "../../../tests/helpers/session-manager-ports"
import { callTool, listTools, RPC_TOOLS } from "../../shim/tools"
import { AgentKind } from "../../shared/agents"
import { bindMuxTools, buildMuxToolsServer, muxRpcHostServer, muxShimHostServer, MUX_HOST_SERVERS } from "./server"

type Msg = Record<string, any>

/** A connected SDK server and a raw client end: request() resolves with the JSON-RPC response. */
async function connect(agent: string, sessionId: string, rpcOnly = false) {
  const controller = new AbortController()
  const server = await buildMuxToolsServer({ sessionId, agent, server: rpcOnly ? "mux-rpc" : "mux-shim", signal: controller.signal }, rpcOnly)
  const pending = new Map<number, (m: Msg) => void>()
  const sent: Msg[] = []
  let id = 0
  const serverSide: any = {
    async start() {}, async close() { serverSide.onclose?.() },
    async send(message: Msg) { sent.push(message); queueMicrotask(() => { if (message.id !== undefined && pending.has(message.id)) { pending.get(message.id)!(message); pending.delete(message.id) } }) },
  }
  await server.connect(serverSide)
  const deliver = (m: Msg) => queueMicrotask(() => serverSide.onmessage?.(m))
  const request = (method: string, params: unknown = {}) => new Promise<Msg>((resolve) => { const n = ++id; pending.set(n, resolve); deliver({ jsonrpc: "2.0", id: n, method, params }) })
  const start = (method: string, params: unknown = {}) => { const n = ++id; const response = new Promise<Msg>((resolve) => { pending.set(n, resolve) }); deliver({ jsonrpc: "2.0", id: n, method, params }); return { id: n, response } }
  const notify = (method: string, params: unknown = {}) => deliver({ jsonrpc: "2.0", method, params })
  await request("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test", version: "1" } })
  notify("notifications/initialized")
  return { request, start, notify, sent, close: () => server.close() }
}

const call = (c: Awaited<ReturnType<typeof connect>>, name: string, args: Record<string, unknown> = {}) =>
  c.request("tools/call", { name, arguments: args }).then(r => r.result)

function setup(overrides: { spawn?: (args: any) => Promise<any>; kill?: (id: string) => Promise<void> } = {}) {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  const frames: any[] = []
  const ports = fakePorts(db, { frames })
  const spawns: any[] = []
  const settled: Array<[string, unknown]> = []
  ports.getAgentRpc = () => ({ settle: (id: string, data: unknown) => { settled.push([id, data]) }, fail: () => {} })
  const registry = new Registry(db)
  ports.orchestration.spawnSession = async (args) => {
    spawns.push(args)
    const r = overrides.spawn ? await overrides.spawn(args) : undefined
    const row = registry.register({ name: args.requestedName ?? "spawned", workdir: args.workdir, pid: 0, agent: args.agent ?? "claude", connected: false })
    return r ?? { name: row.name, session_id: row.id }
  }
  const replies: any[] = []
  ports.outbound.onAssistantMessage = async (id, ev) => { replies.push({ id, ...ev }); return { ok: true as const, delivered: 1 } }
  const m = new SessionManager(registry, ports)
  if (overrides.kill) (m as any).kill = overrides.kill
  const unbind = bindMuxTools({ outbound: (id, op) => m.outbound(id, op), orchestration: (id, op) => m.orchestration(id, op) })
  const pa = registry.registerPA({ name: "pa", workdir: "/tmp", pid: 0, agent: "claude" } as any)
  const worker = registry.register({ name: "worker", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
  return { m, registry, frames, spawns, settled, replies, pa, worker, unbind }
}

let cleanups: Array<() => void> = []
afterEach(() => { for (const c of cleanups.splice(0)) c() })

describe("mux-shim host server: tool list", () => {
  test("the two host servers are named mux-shim / mux-rpc (tool ids stay mcp__mux-shim__* / mcp__mux-rpc__*)", () => {
    expect(muxShimHostServer.name).toBe("mux-shim")
    expect(muxRpcHostServer.name).toBe("mux-rpc")
    expect(MUX_HOST_SERVERS).toEqual([muxShimHostServer, muxRpcHostServer])
  })

  for (const agent of [AgentKind.Claude, AgentKind.Codex, AgentKind.Cursor, AgentKind.Grok, AgentKind.OpenCode]) {
    test(`${agent}: tools/list is exactly the shim's listTools(${agent}) (names, descriptions, JSON schemas)`, async () => {
      const c = await connect(agent, "s1")
      const listed = (await c.request("tools/list")).result.tools
      expect(listed).toEqual(listTools().map(t => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })))
    })
  }

  test("the rpc server lists only resolve / reject", async () => {
    const c = await connect(AgentKind.Claude, "s1", true)
    const listed = (await c.request("tools/list")).result.tools
    expect(listed).toEqual(RPC_TOOLS.map(t => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })))
  })

  test("every agent kind gets the same files-only attach tool and no reply tool", async () => {
    const claude = (await (await connect(AgentKind.Claude, "s1")).request("tools/list")).result.tools
    const codex = (await (await connect(AgentKind.Codex, "s1")).request("tools/list")).result.tools
    expect(claude).toEqual(codex)
    expect(claude.find((t: any) => t.name === "reply")).toBeUndefined()
    const attach = claude.find((t: any) => t.name === "attach")
    expect(attach.inputSchema.required).toEqual(["files"])
  })
})

describe("mux-shim host server: calls through the shared handlers", () => {
  test("PA vs worker: the gate refuses list_sessions for a worker with the exact error, a PA gets the list", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const worker = await connect(AgentKind.Codex, t.worker.id)
    expect(await call(worker, "list_sessions")).toEqual({ isError: true, content: [{ type: "text", text: "permission denied (can_orchestrate=false)" }] })
    const pa = await connect(AgentKind.Claude, t.pa.id)
    const listed = await call(pa, "list_sessions")
    expect(listed.isError).toBeUndefined()
    expect(JSON.parse(listed.content[0].text).map((s: any) => s.name).sort()).toEqual(["pa", "worker"])
  })

  test("rename_session needs no gate: a worker renames itself", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const worker = await connect(AgentKind.Codex, t.worker.id)
    expect(await call(worker, "rename_session", { name: "Better Name" })).toEqual({ content: [{ type: "text", text: JSON.stringify({ name: "Better Name" }) }] })
    expect(t.registry.get(t.worker.id)!.name).toBe("Better Name")
  })

  test("attach: files are sent for any agent kind; a call without files is refused with the exact text", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const claude = await connect(AgentKind.Claude, t.pa.id)
    expect(await call(claude, "attach", { text: "hello", files: [] })).toEqual({ isError: true, content: [{ type: "text", text: "attach needs files[] — your normal assistant output is already your reply" }] })
    expect(await call(claude, "attach", { files: ["/tmp/a.png"], text: "cap" })).toEqual({ content: [{ type: "text", text: "sent" }] })
    const codex = await connect(AgentKind.Codex, t.worker.id)
    expect(await call(codex, "attach", { files: ["/tmp/x.png"] })).toEqual({ content: [{ type: "text", text: "sent" }] })
    expect(t.replies.map(r => [r.id, r.text, r.files])).toEqual([[t.pa.id, "cap", ["/tmp/a.png"]], [t.worker.id, "", ["/tmp/x.png"]]])
  })

  test("an unknown session: the same errors the socket path gives", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const ghost = await connect(AgentKind.Claude, "no-such-session")
    expect((await call(ghost, "rename_session", { name: "x" })).content[0].text).toBe("unknown session")
    expect((await call(ghost, "spawn_session", { workdir: "/tmp" })).content[0].text).toBe("permission denied (can_orchestrate=false)")
  })

  test("rpc: resolve settles the request through agentRpc", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const rpc = await connect(AgentKind.Claude, t.worker.id, true)
    expect(await call(rpc, "resolve", { request_id: "r1", data: { a: 1 } })).toEqual({ content: [{ type: "text", text: "\"ok\"" }] })
    expect(t.settled).toEqual([["r1", { a: 1 }]])
  })

  test("not bound to a broker: an isError result, never a hang", async () => {
    const c = await connect(AgentKind.Claude, "s1")
    expect(await call(c, "list_sessions")).toEqual({ isError: true, content: [{ type: "text", text: "supermux broker is not ready" }] })
  })
})

describe("result mapping is identical to the external shim's callTool", () => {
  const cases: Array<[string, { ok: boolean; value?: unknown; error?: string }, boolean]> = [
    ["attach", { ok: true, value: { message_id: undefined } }, false],
    ["attach", { ok: true, value: { message_id: "42" } }, false],
    ["attach", { ok: false, error: "no chat" }, false],
    ["react", { ok: true, value: { ok: 1 } }, false],
    ["list_sessions", { ok: true, value: [{ name: "a" }] }, false],
    ["kill_session", { ok: true, value: "killed" }, false],
    ["mute_session", { ok: true }, false],
    ["get_active", { ok: true, value: null }, false],
    ["spawn_session", { ok: false }, false],
    ["resolve", { ok: true, value: "ok" }, true],
    ["reject", { ok: false, error: "nope" }, true],
  ]
  for (const [name, result, rpcOnly] of cases) {
    test(`${rpcOnly ? "rpc " : ""}${name} ${JSON.stringify(result)}`, async () => {
      const ops: any[] = []
      const unbind = bindMuxTools({
        outbound: async (_id, op) => { ops.push(["outbound", op]); return result },
        orchestration: async (_id, op) => { ops.push(["orchestration", op]); return result },
      })
      cleanups.push(unbind)
      const args = name === "attach" ? { files: ["/f"] } : name === "react" ? { chat_id: "c", message_id: "m", emoji: "e" } : name === "spawn_session" ? { workdir: "/w" } : name === "kill_session" || name === "mute_session" ? { name: "n", muted: true } : name === "get_active" ? { chat_id: "c" } : name === "resolve" ? { request_id: "r", data: {} } : name === "reject" ? { request_id: "r", error: "e" } : {}
      const host = await call(await connect(AgentKind.Claude, "s1", rpcOnly), name, args)
      const shimOps: any[] = []
      const shim = await callTool({ name, arguments: args }, {
        callOutbound: async (op) => { shimOps.push(["outbound", op]); return result },
        callOrchestration: async (op) => { shimOps.push(["orchestration", op]); return result },
      }, AgentKind.Claude, rpcOnly)
      expect(host).toEqual(shim as any)
      expect(ops).toEqual(shimOps)
    })
  }
})

describe("orchestration de-dup and cancellation", () => {
  test("two identical spawn_session calls (two connections) run the spawn ONCE and both get its result", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const t = setup({ spawn: async () => { await gate } }); cleanups.push(t.unbind)
    const a = await connect(AgentKind.Claude, t.pa.id), b = await connect(AgentKind.Claude, t.pa.id)
    const ra = call(a, "spawn_session", { workdir: "/tmp/x", name: "kid" }), rb = call(b, "spawn_session", { workdir: "/tmp/x", name: "kid" })
    await new Promise((r) => setTimeout(r, 20)); release()
    const [x, y] = await Promise.all([ra, rb])
    expect(t.spawns).toHaveLength(1)
    expect(y).toEqual(x)
    // Different args are a different call.
    await call(a, "spawn_session", { workdir: "/tmp/x", name: "other" })
    expect(t.spawns).toHaveLength(2)
  })

  test("the socket path and the host server share one de-dup (a stray external mux-shim next to the host one)", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const host = await connect(AgentKind.Claude, t.pa.id)
    const viaHost = await call(host, "spawn_session", { workdir: "/tmp/y", name: "twice" })
    const viaSocket = await t.m.handleOrchestration({ kind: "orchestration", call_id: "c1", session_id: t.pa.id, op: { name: "spawn_session", args: { workdir: "/tmp/y", name: "twice" } } } as any)
    expect(t.spawns).toHaveLength(1)
    expect(JSON.stringify(viaSocket.value)).toBe(viaHost.content[0].text)
  })

  test("spawn_session cancelled mid-flight (notifications/cancelled): the spawn finishes, the row and broadcast exist, a retry gets the same session", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const t = setup({ spawn: async () => { await gate } }); cleanups.push(t.unbind)
    const c = await connect(AgentKind.Claude, t.pa.id)
    const pending = c.start("tools/call", { name: "spawn_session", arguments: { workdir: "/tmp/z", name: "cancelled-kid" } })
    await new Promise((r) => setTimeout(r, 20))
    c.notify("notifications/cancelled", { requestId: pending.id, reason: "interrupted" })
    await new Promise((r) => setTimeout(r, 20))
    release()
    await new Promise((r) => setTimeout(r, 50))
    // The SDK sends nothing for a cancelled request; the mutation still completed, entirely.
    expect(c.sent.some(m => m.id === pending.id)).toBe(false)
    expect(t.spawns).toHaveLength(1)
    const row = t.registry.resolveName("cancelled-kid")
    expect(row).toBeTruthy()
    expect(t.frames.some(f => f.type === "session_added" && f.session.id === row!.id)).toBe(true)
    // The agent retries (it never saw an answer): the same session, no second spawn.
    const retry = await call(c, "spawn_session", { workdir: "/tmp/z", name: "cancelled-kid" })
    expect(JSON.parse(retry.content[0].text)).toEqual({ name: "cancelled-kid", session_id: row!.id })
    expect(t.spawns).toHaveLength(1)
  })

  test("kill_session cancelled mid-flight: the kill, the unregister and the session_removed broadcast all happen", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const killed: string[] = []
    const t = setup({ kill: async (id) => { await gate; killed.push(id) } }); cleanups.push(t.unbind)
    const victim = t.registry.register({ name: "victim", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
    const c = await connect(AgentKind.Claude, t.pa.id)
    const pending = c.start("tools/call", { name: "kill_session", arguments: { name: "victim" } })
    await new Promise((r) => setTimeout(r, 20))
    c.notify("notifications/cancelled", { requestId: pending.id, reason: "interrupted" })
    await new Promise((r) => setTimeout(r, 20))
    release()
    await new Promise((r) => setTimeout(r, 50))
    expect(killed).toEqual([victim.id])
    expect(t.registry.get(victim.id)).toBeUndefined()
    expect(t.frames.some(f => f.type === "session_removed" && f.id === victim.id)).toBe(true)
  })
})
