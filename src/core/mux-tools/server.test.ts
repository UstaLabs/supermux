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
import { modesFor } from "../agents/permission-modes"
import { CoreAdapter } from "../agents/core-bridge/core-adapter"
import type { AgentPhase } from "../session-manager/agent-state-store"
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
    // The real port is the app's create path, which announces the session itself.
    ports.getWebChannel()?.broadcastToAll({ type: "session_added", session: { id: row.id, name: row.name } })
    return r ?? { name: row.name, session_id: row.id }
  }
  const replies: any[] = []
  ports.outbound.onAssistantMessage = async (id, ev) => { replies.push({ id, ...ev }); return { ok: true as const, delivered: 1 } }
  const m = new SessionManager(registry, ports)
  if (overrides.kill) (m as any).kill = overrides.kill
  const unbind = bindMuxTools({ outbound: (id, op) => m.outbound(id, op), orchestration: (id, op) => m.orchestration(id, op) })
  const pa = registry.registerPA({ name: "pa", workdir: "/tmp", pid: 0, agent: "claude" } as any)
  const worker = registry.register({ name: "worker", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
  return { m, registry, frames, spawns, settled, replies, pa, worker, unbind, ports }
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

  test("council_report is listed for a council member only", async () => {
    const t = setup(); t.unbind()
    cleanups.push(bindMuxTools({ outbound: (id, op) => t.m.outbound(id, op), orchestration: (id, op) => t.m.orchestration(id, op), councilMember: (id) => id === t.worker.id }))
    const names = async (id: string) => ((await (await connect(AgentKind.Codex, id)).request("tools/list")).result.tools as Array<{ name: string }>).map((x) => x.name)
    expect(await names(t.worker.id)).toContain("council_report")
    expect(await names(t.pa.id)).not.toContain("council_report")
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
  test("a worker can list, spawn and kill sessions; mute_session, set_active and get_active stay PA-only", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const worker = await connect(AgentKind.Codex, t.worker.id)
    const listed = await call(worker, "list_sessions")
    expect(listed.isError).toBeUndefined()
    expect(JSON.parse(listed.content[0].text).map((s: any) => s.name).sort()).toEqual(["pa", "worker"])
    const spawned = JSON.parse((await call(worker, "spawn_session", { workdir: "/tmp/w", name: "kid" })).content[0].text)
    expect(t.spawns).toHaveLength(1)
    expect(await call(worker, "kill_session", { id: spawned.session_id })).toEqual({ content: [{ type: "text", text: "\"killed\"" }] })
    expect(t.registry.get(spawned.session_id)).toBeUndefined()
    const denied = { isError: true, content: [{ type: "text", text: "permission denied (can_orchestrate=false)" }] }
    expect(await call(worker, "mute_session", { name: "pa", muted: true })).toEqual(denied)
    expect(await call(worker, "set_active", { chat_id: "c", name: "pa" })).toEqual(denied)
    expect(await call(worker, "get_active", { chat_id: "c" })).toEqual(denied)
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
    expect((await call(ghost, "spawn_session", { workdir: "/tmp" })).content[0].text).toBe("unknown session")
    expect((await call(ghost, "kill_session", { id: "x" })).content[0].text).toBe("unknown session")
    expect((await call(ghost, "list_sessions")).content[0].text).toBe("unknown session")
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
      const args = name === "attach" ? { files: ["/f"] } : name === "react" ? { chat_id: "c", message_id: "m", emoji: "e" } : name === "spawn_session" ? { workdir: "/w" } : name === "kill_session" ? { id: "n" } : name === "mute_session" ? { name: "n", muted: true } : name === "get_active" ? { chat_id: "c" } : name === "resolve" ? { request_id: "r", data: {} } : name === "reject" ? { request_id: "r", error: "e" } : {}
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
    const pending = c.start("tools/call", { name: "kill_session", arguments: { id: victim.id } })
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

describe("message_session", () => {
  test("a worker can send; the sender is filled in and interrupt picks the busy mode", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const sent: any[] = []
    t.ports.getInbox = () => ({
      send: (from, to, text, busy) => { sent.push({ from, to, text, busy }); return { ids: { [to[0]!]: "m_1" } } },
    })
    const c = await connect(AgentKind.Codex, t.worker.id)
    const r = await call(c, "message_session", { to: [t.pa.id], text: "hi", interrupt: true })
    expect(r).toEqual({ content: [{ type: "text", text: JSON.stringify({ ids: { [t.pa.id]: "m_1" } }) }] })
    expect(sent).toEqual([{ from: { name: "worker", id: t.worker.id }, to: [t.pa.id], text: "hi", busy: "interrupt" }])
  })

  // The MCP SDK validates arguments against the JSON schema before the handler runs, so the
  // handler's own checks (which the external shim's socket path relies on) are driven directly.
  test("bad arguments are refused by the handler", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.getInbox = () => ({ send: () => { throw new Error("must not be called") } })
    expect(await t.m.orchestration(t.worker.id, { name: "message_session", args: { to: t.pa.id, text: "hi", interrupt: false } }))
      .toEqual({ ok: false, error: "to must be a non-empty array of session ids" })
    expect(await t.m.orchestration(t.worker.id, { name: "message_session", args: { to: [""], text: "hi", interrupt: false } }))
      .toEqual({ ok: false, error: "to must be a non-empty array of session ids" })
    expect(await t.m.orchestration(t.worker.id, { name: "message_session", args: { to: [t.pa.id], text: "hi" } }))
      .toEqual({ ok: false, error: "interrupt must be true or false" })
  })

  test("errors from the inbox (unknown ids) come back as tool errors", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.getInbox = () => ({ send: () => { throw new Error("unknown session id: nope") } })
    const c = await connect(AgentKind.Claude, t.worker.id)
    expect(await call(c, "message_session", { to: ["nope"], text: "hi", interrupt: false }))
      .toEqual({ isError: true, content: [{ type: "text", text: "unknown session id: nope" }] })
  })

  test("no inbox wired: an error, not a crash", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const c = await connect(AgentKind.Claude, t.worker.id)
    expect(await call(c, "message_session", { to: [t.pa.id], text: "hi", interrupt: false }))
      .toEqual({ isError: true, content: [{ type: "text", text: "inbox is not ready" }] })
  })

  test("interrupt:false hands the message over as queue; every recipient id is passed on as given", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const sent: any[] = []
    t.ports.getInbox = () => ({
      send: (from, to, text, busy) => { sent.push({ from, to, text, busy }); return { ids: Object.fromEntries(to.map((id) => [id, "m_2"])) } },
    })
    const c = await connect(AgentKind.Claude, t.pa.id)
    await call(c, "message_session", { to: [t.worker.id, "other-1"], text: "when you are done", interrupt: false })
    expect(sent).toEqual([{ from: { name: "pa", id: t.pa.id }, to: [t.worker.id, "other-1"], text: "when you are done", busy: "queue" }])
  })

  test("a missing text is refused by the handler, and a caller that is not a session is refused first", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.getInbox = () => ({ send: () => { throw new Error("must not be called") } })
    expect(await t.m.orchestration(t.worker.id, { name: "message_session", args: { to: [t.pa.id], interrupt: false } }))
      .toEqual({ ok: false, error: "text must be a string" })
    expect(await t.m.orchestration("no-such-session", { name: "message_session", args: { to: [t.pa.id], text: "hi", interrupt: false } }))
      .toEqual({ ok: false, error: "unknown session" })
  })

  test("an identical repeated call is folded into one send (the orchestration single-flight)", async () => {
    const t = setup(); cleanups.push(t.unbind)
    let sends = 0
    t.ports.getInbox = () => ({ send: (_from, to) => { sends++; return { ids: { [to[0]!]: `m_${sends}` } } } })
    const c = await connect(AgentKind.Codex, t.worker.id)
    const args = { to: [t.pa.id], text: "hi", interrupt: false }
    const first = await call(c, "message_session", args)
    expect(await call(c, "message_session", args)).toEqual(first)
    expect(sends).toBe(1)
    // A different text is a different call.
    await call(c, "message_session", { ...args, text: "again" })
    expect(sends).toBe(2)
  })
})

test("create_agent is a tool with name and provider", () => {
  const t = listTools().find(x => x.name === "create_agent")
  expect(t).toBeDefined()
  expect((t!.inputSchema as any).required).toEqual(["name"])
  expect(Object.keys((t!.inputSchema as any).properties)).toEqual(["name", "provider", "model"])
})

describe("list_models", () => {
  test("any session can call it; you is the caller's own agent, model, effort and access", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.config.agentStatuses = () => [{ kind: "codex", installed: true, authed: true, usableWithoutAuth: false }]
    t.ports.config.lookupModels = (a) => (a === "codex" ? [{ id: "gpt-6", displayName: "GPT-6", agent: "codex", reasoningLevels: [{ id: "low" }] }] : [])
    t.registry.setPermissionMode(t.worker.id, "auto")
    const c = await connect(AgentKind.Codex, t.worker.id)
    const r = await call(c, "list_models", { agent: "codex" })
    expect(r.isError).toBeUndefined()
    expect(JSON.parse(r.content[0].text)).toEqual({
      you: { agent: "codex", model: null, effort: null, access: "auto" },
      agents: [{
        agent: "codex",
        ready: true,
        access: modesFor("codex").map((m) => ({ id: m.id, label: m.label, level: m.level })),
        models: [{ id: "gpt-6", label: "GPT-6", efforts: ["low"], ready: true }],
      }],
    })
  })

  test("a caller with no permission mode set gets its agent's default access, resolved rather than raw", async () => {
    const t = setup(); cleanups.push(t.unbind)
    expect(t.registry.get(t.worker.id)!.permissionMode).toBeUndefined()
    const c = await connect(AgentKind.Codex, t.worker.id)
    const r = await call(c, "list_models", { agent: "codex" })
    expect(r.isError).toBeUndefined()
    expect(JSON.parse(r.content[0].text).you).toEqual({ agent: "codex", model: null, effort: null, access: "full-access" })
  })

  test("an unknown agent kind and an unknown caller are errors", async () => {
    const t = setup(); cleanups.push(t.unbind)
    expect(await t.m.orchestration(t.worker.id, { name: "list_models", args: { agent: "gemini" } }))
      .toEqual({ ok: false, error: "unknown agent kind: gemini" })
    expect(await t.m.orchestration("no-such-session", { name: "list_models", args: {} }))
      .toEqual({ ok: false, error: "unknown session" })
  })
})

describe("kill_session", () => {
  test("takes an id: the session is killed and archived, its workspace retired, its reply target forgotten", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const retired: string[] = [], forgotten: string[] = []
    t.ports.cleanup.retireWorkspaceIfEmpty = (w) => { retired.push(w) }
    t.ports.cleanup.forgetReplyTarget = (id) => { forgotten.push(id) }
    const ws = t.registry.workspaces.create({ name: "w", workdir: "/tmp" })
    const victim = t.registry.register({ name: "victim", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
    t.registry.db.run("UPDATE sessions SET workspace_id = ? WHERE id = ?", [ws.id, victim.id])
    const c = await connect(AgentKind.Claude, t.pa.id)
    expect(await call(c, "kill_session", { id: victim.id })).toEqual({ content: [{ type: "text", text: "\"killed\"" }] })
    expect(t.registry.sessions.getById(victim.id)?.status).toBe("archived")
    expect(retired).toEqual([ws.id])
    expect(forgotten).toEqual([victim.id])
    expect(t.frames.filter((f) => f.type === "session_removed").map((f) => f.id)).toEqual([victim.id])
  })

  test("killing a parent kills its children", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const parent = t.registry.register({ name: "parent", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
    const child = t.registry.register({ name: "child", workdir: "/tmp", pid: 0, agent: "grok", connected: false })
    t.registry.sessions.setParent(child.id, parent.id)
    const c = await connect(AgentKind.Claude, t.pa.id)
    await call(c, "kill_session", { id: parent.id })
    expect(t.registry.get(parent.id)).toBeUndefined()
    expect(t.registry.get(child.id)).toBeUndefined()
    expect(t.frames.filter((f) => f.type === "session_removed").map((f) => f.id)).toEqual([child.id, parent.id])
  })

  test("an unknown id and an archived session are errors, and nothing is killed", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const gone = t.registry.register({ name: "gone", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
    t.registry.unregister(gone.id)
    expect(await t.m.orchestration(t.pa.id, { name: "kill_session", args: { id: "nope" } }))
      .toEqual({ ok: false, error: "unknown session id: nope" })
    expect(await t.m.orchestration(t.pa.id, { name: "kill_session", args: { id: gone.id } }))
      .toEqual({ ok: false, error: `session ${gone.id} is already archived` })
    expect(t.frames.filter((f) => f.type === "session_removed")).toEqual([])
  })
})

describe("list_sessions", () => {
  test("every non-internal session, with id, agent, model, state, parent and children", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const phases: Record<string, AgentPhase> = {}
    t.ports.agentState.get = (id) => ({ phase: phases[id] ?? "idle", since: 0 })
    const busy = t.registry.register({ name: "busy", workdir: "/w", pid: 0, agent: "grok", model: "grok-5", connected: false })
    const child = t.registry.register({ name: "child", workdir: "/w", pid: 0, agent: "cursor", connected: false })
    const dead = t.registry.register({ name: "dead", workdir: "/w", pid: 0, agent: "opencode", connected: false })
    const failed = t.registry.register({ name: "failed", workdir: "/w", pid: 0, agent: "codex", connected: false })
    const asleep = t.registry.register({ name: "asleep", workdir: "/w", pid: 0, agent: "claude", connected: false })
    t.registry.register({ name: "rpc-x", workdir: "/w", pid: 0, internal: true, connected: false })
    t.registry.sessions.setParent(child.id, busy.id)
    t.registry.sessions.suspend(asleep.id)
    phases[busy.id] = "running"
    phases[dead.id] = "dead"
    // A core session whose runtime has failed reads as crashed even before any agent-state event.
    const failedAdapter = Object.assign(Object.create(CoreAdapter.prototype), { kind: "codex", isAlive: () => false })
    t.m.registerCodexRuntime(failed.id, "failed", failedAdapter)
    const c = await connect(AgentKind.Claude, t.pa.id)
    const listed: any[] = JSON.parse((await call(c, "list_sessions")).content[0].text)
    const byName = Object.fromEntries(listed.map((s) => [s.name, s]))
    expect(byName["rpc-x"]).toBeUndefined()
    expect(byName.busy).toEqual({ id: busy.id, name: "busy", agent: "grok", model: "grok-5", workdir: "/w", mute: false, state: "working", children: [child.id] })
    expect(byName.child).toEqual({ id: child.id, name: "child", agent: "cursor", model: null, workdir: "/w", mute: false, state: "idle", parent: busy.id, children: [] })
    expect(byName.dead.state).toBe("crashed")
    expect(byName.failed.state).toBe("crashed")
    expect(byName.asleep.state).toBe("suspended")
  })
})

describe("drafts are not sessions to the tools", () => {
  test("list_sessions leaves drafts out; kill_session and message_session refuse a draft's id", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const sent: unknown[] = []
    t.ports.getInbox = () => ({ send: (...args) => { sent.push(args); return { ids: {} } } })
    const draft = t.registry.sessions.register({ name: "draft", agent: "claude", workdir: "/w", pid: 0, user_status: "draft" })
    const c = await connect(AgentKind.Codex, t.worker.id)
    const listed: any[] = JSON.parse((await call(c, "list_sessions")).content[0].text)
    expect(listed.map((x) => x.name).sort()).toEqual(["pa", "worker"])
    expect(await t.m.orchestration(t.worker.id, { name: "kill_session", args: { id: draft.id } }))
      .toEqual({ ok: false, error: `session ${draft.id} is a draft (a draft has no agent until its first message is sent)` })
    expect(t.registry.get(draft.id)).toBeDefined()
    expect(await t.m.orchestration(t.worker.id, { name: "message_session", args: { to: [t.pa.id, draft.id], text: "hi", interrupt: false } }))
      .toEqual({ ok: false, error: `draft session id: ${draft.id} (a draft has no agent until its first message is sent)` })
    expect(sent).toEqual([])
  })
})

test("kill_session by the child's parent ends it as 'parent'; by another session, with no endedBy", async () => {
  const archivedChildren: any[] = []
  const t = setup(); cleanups.push(t.unbind)
  t.ports.children.archived = (s, how) => { archivedChildren.push({ id: s.id, ...how }) }
  const kid1 = t.registry.register({ name: "kid1", workdir: "/tmp", pid: 0, agent: "codex", connected: false, parent_session_id: t.pa.id })
  const kid2 = t.registry.register({ name: "kid2", workdir: "/tmp", pid: 0, agent: "codex", connected: false, parent_session_id: t.pa.id })
  await t.m.orchestration(t.pa.id, { name: "kill_session", args: { id: kid1.id } })
  await t.m.orchestration(t.worker.id, { name: "kill_session", args: { id: kid2.id } })
  expect(archivedChildren).toEqual([{ id: kid1.id, endedBy: "parent" }, { id: kid2.id }])
})

describe("delegate", () => {
  const ok = { task: "Review the auth changes", agent: "codex", access: "auto", checkout: "shared" }

  test("any session can delegate; the args and the Claude tool-call id reach the port", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const calls: any[] = []
    t.ports.orchestration.delegate = async (a) => { calls.push(a); return { session: "kid-1", agent: "codex", model: null, access: "auto" } }
    const c = await connect(AgentKind.Codex, t.worker.id)
    const r = await c.request("tools/call", { name: "delegate", arguments: { ...ok, title: "Auth review" }, _meta: { "claudecode/toolUseId": "toolu_9" } })
    expect(JSON.parse(r.result.content[0].text)).toEqual({ session: "kid-1", agent: "codex", model: null, access: "auto" })
    expect(calls).toEqual([{ parentId: t.worker.id, task: "Review the auth changes", agent: "codex", model: undefined, effort: undefined, access: "auto", checkout: "shared", title: "Auth review", callId: "toolu_9" }])
  })

  test("bad arguments are refused before anything starts", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.orchestration.delegate = async () => { throw new Error("must not be called") }
    const call = (args: Record<string, unknown>) => t.m.orchestration(t.worker.id, { name: "delegate", args })
    expect(await call({ ...ok, agent: "gemini" })).toEqual({ ok: false, error: "unknown agent kind: gemini" })
    expect(await call({ ...ok, access: "bypass" })).toEqual({ ok: false, error: "access must be one of full-access, auto, ask, read-only for codex" })
    expect(await call({ ...ok, checkout: "elsewhere" })).toEqual({ ok: false, error: 'checkout must be "shared" or "worktree"' })
    expect(await call({ ...ok, task: "   " })).toEqual({ ok: false, error: "task must not be empty" })
    expect(await t.m.orchestration("no-such-session", { name: "delegate", args: ok })).toEqual({ ok: false, error: "unknown session" })
  })

  test("a failure to start is the tool's error", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.orchestration.delegate = async () => { throw new Error("/w is not a single git repository, so no worktree can be made") }
    expect(await t.m.orchestration(t.worker.id, { name: "delegate", args: { ...ok, checkout: "worktree" } }))
      .toEqual({ ok: false, error: "/w is not a single git repository, so no worktree can be made" })
  })
})

describe("council tools", () => {
  const member = { agent: "codex", access: "full-access" }

  test("any session can call council; the args and the Claude tool-call id reach the port", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const calls: any[] = []
    t.ports.orchestration.council = async (a) => { calls.push(a); return { council: "c1", name: "council-1", members: [{ letter: "A", session: "m1", agent: "codex", model: null }] } }
    const c = await connect(AgentKind.Codex, t.worker.id)
    const r = await c.request("tools/call", { name: "council", arguments: { question: "Q?", members: [member], checkout: "shared" }, _meta: { "claudecode/toolUseId": "toolu_c" } })
    expect(JSON.parse(r.result.content[0].text)).toEqual({ council: "c1", name: "council-1", members: [{ letter: "A", session: "m1", agent: "codex", model: null }] })
    expect(calls).toEqual([{ callerId: t.worker.id, question: "Q?", members: [{ agent: "codex", model: undefined, effort: undefined, access: "full-access" }], checkout: "shared", callId: "toolu_c" }])
  })

  test("council refuses bad arguments before anything starts", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.orchestration.council = async () => { throw new Error("must not be called") }
    const call = (args: Record<string, unknown>) => t.m.orchestration(t.worker.id, { name: "council", args })
    expect(await call({ question: " ", members: [member], checkout: "shared" })).toEqual({ ok: false, error: "question must not be empty" })
    expect(await call({ question: "Q", members: [], checkout: "shared" })).toEqual({ ok: false, error: "members must be a non-empty array" })
    expect(await call({ question: "Q", members: [{ agent: "gemini", access: "x" }], checkout: "shared" })).toEqual({ ok: false, error: "member A: unknown agent kind: gemini" })
    expect(await call({ question: "Q", members: [member, { agent: "codex", access: "bypass" }], checkout: "shared" })).toEqual({ ok: false, error: "member B: access must be one of full-access, auto, ask, read-only for codex" })
    expect(await call({ question: "Q", members: [member], checkout: "x" })).toEqual({ ok: false, error: 'checkout must be "shared" or "worktree"' })
  })

  test("council_round and council_report reach their ports with the caller", async () => {
    const t = setup(); cleanups.push(t.unbind)
    const rounds: any[] = [], reports: any[] = []
    t.ports.orchestration.councilRound = async (a) => { rounds.push(a); return { round: 2 } }
    t.ports.orchestration.councilReport = async (a) => { reports.push(a); return { id: "r1" } }
    expect(await t.m.orchestration(t.pa.id, { name: "council_round", args: { council: "c1", focus: "Expiry" } })).toEqual({ ok: true, value: { round: 2 } })
    expect(await t.m.orchestration(t.worker.id, { name: "council_report", args: { text: "Yes." } })).toEqual({ ok: true, value: { id: "r1" } })
    expect(rounds).toEqual([{ callerId: t.pa.id, council: "c1", focus: "Expiry" }])
    expect(reports).toEqual([{ memberId: t.worker.id, text: "Yes." }])
    expect(await t.m.orchestration(t.pa.id, { name: "council_round", args: { council: "c1", focus: " " } })).toEqual({ ok: false, error: "focus must not be empty" })
    expect(await t.m.orchestration(t.worker.id, { name: "council_report", args: { text: "" } })).toEqual({ ok: false, error: "text must not be empty" })
  })

  test("list_sessions shows a member's council and letter", async () => {
    const t = setup(); cleanups.push(t.unbind)
    t.ports.councils.membership = (id) => (id === t.worker.id ? { councilId: "c1", letter: "B" } : undefined)
    const listed: any[] = JSON.parse((await call(await connect(AgentKind.Claude, t.pa.id), "list_sessions")).content[0].text)
    expect(listed.find((s) => s.id === t.worker.id).council).toEqual({ id: "c1", letter: "B" })
    expect(listed.find((s) => s.id === t.pa.id).council).toBeUndefined()
  })
})
