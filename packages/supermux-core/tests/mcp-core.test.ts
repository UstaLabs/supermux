import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs"
import { rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { z } from "zod"
import { claude } from "../src/claude/index.js"
import { contextFingerprint } from "../src/context/index.js"
import type { DriverContextSupport, LaunchContext, ResolvedContext } from "../src/context/types.js"
import { createCore, type Core } from "../src/core.js"
import { mcpServer, tool, type ToolContext } from "../src/mcp/index.js"
import type { AgentDriver, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_CLAUDE_PERMISSIONS, TEST_LIMITS } from "./helpers.js"
import { BridgeClient, until } from "./mcp-helpers.js"

setDefaultTimeout(30_000)
const dirs: string[] = []
const cores: Core[] = []
const clients: BridgeClient[] = []
afterEach(async () => {
  await Promise.all(clients.splice(0).map(client => client.close()))
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "mcp-core-")); dirs.push(dir); return dir }

const SUPPORTED = { support: "supported" as const, note: "n" }
const RELOAD_ONLY: DriverContextSupport = { capabilities: { instructions: SUPPORTED, skills: SUPPORTED, plugins: SUPPORTED, mcpServers: SUPPORTED } }
const LIST_CHANGED: DriverContextSupport = { ...RELOAD_ONLY, mcpListChanged: true }

type Fake = { driver: AgentDriver; opened: DriverContext[]; releasePrompt(): void; blockPrompts: boolean; closes: number }
function fakeDriver(id: string, support: DriverContextSupport | undefined): Fake {
  const fake: Fake = { opened: [], releasePrompt: () => {}, blockPrompts: false, closes: 0, driver: undefined as never }
  let n = 0
  fake.driver = {
    id,
    ...(support ? { context: support } : {}),
    async open(context) {
      fake.opened.push(structuredClone({ ...context, signal: undefined, onUpdate: undefined, onExit: undefined, requestPermission: undefined, requestAnswers: undefined, onActivity: undefined }) as never)
      n++
      return {
        agentSessionId: context.resumeId ?? (context.forkFrom ? `fork-${n}` : `native-${n}`),
        capabilities: { resume: true, steer: false, fork: true, detach: false },
        async prompt() {
          if (fake.blockPrompts) await new Promise<void>(resolve => { fake.releasePrompt = resolve })
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() { fake.closes++ },
      }
    },
  }
  return fake
}

function core(state: string, drivers: AgentDriver[], extra: Partial<Parameters<typeof createCore>[0]> = {}) {
  const c = createCore({ stateDirectory: state, agents: drivers, limits: TEST_LIMITS, ...extra })
  cores.push(c)
  return c
}

function orders(calls: ToolContext[] = []) {
  return mcpServer({
    name: "orders",
    tools: { lookup: tool({ description: "Look up", input: z.object({ id: z.string() }), run: ({ id }, ctx) => { calls.push(ctx); return `order ${id} via ${ctx.sessionId}` } }) },
  })
}

/** A bridge started exactly as the agent would, from the launch the driver got. */
async function bridgeFor(launch: LaunchContext | undefined, name: string): Promise<BridgeClient> {
  const server = launch!.mcpServers.find(entry => entry.name === name)!
  const client = new BridgeClient({ socket: server.env.SUPERMUX_MCP_SOCKET!, session: server.env.SUPERMUX_MCP_SESSION!, token: server.env.SUPERMUX_MCP_TOKEN!, server: name })
  clients.push(client)
  await client.initialize()
  return client
}

const text = (response: any) => response.result?.content?.[0]?.text

// ------------------------------------------------------------ registry, records, launch

test("a host server in a context: registered, stored by name, launched as the bridge with socket + session token", async () => {
  const state = scratch(), cwd = scratch(), fake = fakeDriver("fake", RELOAD_ONLY)
  const calls: ToolContext[] = []
  const server = orders(calls)
  const c = core(state, [fake.driver])
  const events: CoreEvent[] = []
  c.subscribe(event => { events.push(event) })
  await c.sessions.create({ agent: "fake", cwd, id: "s1", account: undefined, context: { mcpServers: [server, { name: "github", command: "gh-mcp" }] } })
  expect(c.mcp.list()).toEqual(["orders"])
  expect((await c.sessions.get("s1"))!.context).toEqual({ mcpServers: [{ kind: "host", name: "orders" }, { name: "github", command: "gh-mcp", args: [], env: {} }] })
  const launch = fake.opened[0]!.sessionContext!
  const bridge = launch.mcpServers[0]!
  expect(bridge).toMatchObject({ name: "orders", command: process.execPath, host: { tools: ["lookup"] } })
  expect(bridge.args.slice(1)).toEqual(["--server", "orders"])
  expect(bridge.args[0]).toMatch(/mcp\/bridge\.(ts|js)$/)
  expect(bridge.env.SUPERMUX_MCP_SESSION).toBe("s1")
  expect(bridge.env.SUPERMUX_MCP_SOCKET).toBe(c.mcp.socket()!)
  expect(bridge.env.SUPERMUX_MCP_TOKEN).toMatch(/^[0-9a-f]{64}$/)
  // The token never reaches the record.
  expect(readFileSync(join(state, "sessions", "s1.json"), "utf8")).not.toContain(bridge.env.SUPERMUX_MCP_TOKEN!)

  const client = await bridgeFor(launch, "orders")
  expect(text(await client.request("tools/call", { name: "lookup", arguments: { id: "9" } }))).toBe("order 9 via s1")
  expect(calls[0]).toMatchObject({ sessionId: "s1", agent: "fake", server: "orders" })
  await until(() => events.some(event => event.type === "tool.finished"), 5000, "tool.finished")
  const called = events.find(event => event.type === "tool.called")!
  const finished = events.find(event => event.type === "tool.finished")!
  expect(called).toMatchObject({ sessionId: "s1", server: "orders", tool: "lookup" })
  expect(finished).toMatchObject({ sessionId: "s1", server: "orders", tool: "lookup", ok: true, callId: (called as any).callId })
  expect(JSON.stringify([called, finished])).not.toContain("order 9")
})

test("two different server objects with one name are invalid_context; the same object twice is fine", async () => {
  const fake = fakeDriver("fake", RELOAD_ONLY), cwd = scratch()
  const first = orders()
  const c = core(scratch(), [fake.driver], { mcpServers: [first] })
  await expect(c.sessions.create({ agent: "fake", cwd, id: "s1", context: { mcpServers: [orders()] } })).rejects.toMatchObject({ code: "invalid_context" })
  expect(fake.opened).toHaveLength(0)
  await c.sessions.create({ agent: "fake", cwd, id: "s2", context: { mcpServers: [first] } })
  expect(() => c.mcp.register(orders())).toThrow(expect.objectContaining({ code: "invalid_input" }))
  expect(() => createCore({ stateDirectory: scratch(), agents: [], limits: TEST_LIMITS, mcpServers: [orders(), orders()] })).toThrow(expect.objectContaining({ code: "invalid_options" }))
})

test("registry: a resume without the server registered is missing_mcp_servers (never dropped); registering it fixes it", async () => {
  const state = scratch(), cwd = scratch(), fake = fakeDriver("fake", RELOAD_ONLY)
  let c = core(state, [fake.driver])
  await c.sessions.create({ agent: "fake", cwd, id: "s1", context: { mcpServers: [orders()] } })
  await c.close({ agents: "shutdown" })
  c = core(state, [fake.driver])
  await expect(c.sessions.resume("s1")).rejects.toMatchObject({ code: "missing_mcp_servers", message: expect.stringContaining("orders") })
  expect(fake.opened).toHaveLength(1)
  c.mcp.register(orders())
  await c.sessions.resume("s1")
  expect(fake.opened[1]!.sessionContext!.mcpServers.map(server => server.name)).toEqual(["orders"])
  // createCore({ mcpServers }) is the same as registering.
  await c.close({ agents: "shutdown" })
  c = core(state, [fake.driver], { mcpServers: [orders()] })
  await c.sessions.resume("s1")
  // A stored reference resolves; a reference to an unknown name in a new context is missing too.
  await expect(c.sessions.create({ agent: "fake", cwd, id: "s2", context: { mcpServers: [{ kind: "host", name: "nope" }] } })).rejects.toMatchObject({ code: "missing_mcp_servers" })
  expect(c.mcp.unregister("orders")).toBe(true)
  await c.sessions.close("s1", { mode: "shutdown" })
  await expect(c.sessions.resume("s1")).rejects.toMatchObject({ code: "missing_mcp_servers" })
})

test("a core default host server reaches every session; resume, fork and updateContext take server objects too", async () => {
  const state = scratch(), cwd = scratch(), fake = fakeDriver("fake", RELOAD_ONLY)
  const shared = mcpServer({ name: "shared", tools: { ping: tool({ description: "p", run: () => "pong" }) } })
  const extra = mcpServer({ name: "extra", tools: { e: tool({ description: "e", run: () => "e" }) } })
  const c = core(state, [fake.driver], { context: { mcpServers: [shared] } })
  const session = await c.sessions.create({ agent: "fake", cwd, id: "s1" })
  expect(fake.opened[0]!.sessionContext!.mcpServers.map(server => server.name)).toEqual(["shared"])
  expect((await c.sessions.get("s1"))!.context).toBeUndefined()
  const fork = await session.fork({ id: "f1", context: { mcpServers: [extra] } })
  expect(fake.opened[1]!.sessionContext!.mcpServers.map(server => server.name)).toEqual(["shared", "extra"])
  expect((await c.sessions.get("f1"))!.context).toEqual({ mcpServers: [{ kind: "host", name: "extra" }] })
  const third = mcpServer({ name: "third", tools: {} })
  const result = await fork.updateContext({ mcpServers: { add: [third] } })
  expect(result.applied).toEqual([{ kind: "mcpServers", op: "add", item: "third", how: "reload" }])
  expect((await c.sessions.get("f1"))!.context!.mcpServers).toEqual([{ kind: "host", name: "extra" }, { kind: "host", name: "third" }])
  expect(fake.opened.at(-1)!.sessionContext!.mcpServers.map(server => server.name)).toEqual(["shared", "extra", "third"])
  await c.sessions.close("f1", { mode: "shutdown" })
  await c.sessions.resume("f1", { context: { mcpServers: [extra] } })
  expect(fake.opened.at(-1)!.sessionContext!.mcpServers.map(server => server.name)).toEqual(["shared", "extra"])
  // The session (and its default server) is attached: a bridge for "third" on f1 is now refused.
  const launch = fake.opened.at(-1)!.sessionContext!
  const client = await bridgeFor(launch, "shared")
  expect(text(await client.request("tools/call", { name: "ping", arguments: {} }))).toBe("pong")
})

test("no host server anywhere: the launch is unchanged and no socket is opened", async () => {
  const state = scratch(), cwd = scratch(), fake = fakeDriver("fake", RELOAD_ONLY)
  const c = core(state, [fake.driver], { mcpServers: [orders()] })
  await c.sessions.create({ agent: "fake", cwd, id: "s1" })
  expect(fake.opened[0]!.sessionContext).toBeUndefined()
  expect(c.mcp.socket()).toBeUndefined()
  expect(existsSync(join(state, "mcp"))).toBe(false)
})

// ------------------------------------------------------------ fingerprint

test("fingerprint: host servers by name only (no token, socket or runtime path); tool set only when it must relaunch", () => {
  const base: ResolvedContext = { skills: [], plugins: [], mcpServers: [] }
  const host = (token: string, socket: string, tools?: string[]) => ({
    ...base, mcpServers: [{ name: "orders", command: `/rt/${socket}`, args: [`/x/${socket}/bridge.js`, "--server", "orders"], env: { SUPERMUX_MCP_TOKEN: token, SUPERMUX_MCP_SOCKET: socket }, host: tools ? { tools } : {} }],
  })
  expect(contextFingerprint(host("a", "s1"), [])).toBe(contextFingerprint(host("b", "s2"), []))
  expect(contextFingerprint(host("a", "s1", ["x", "y"]), [])).toBe(contextFingerprint(host("b", "s2", ["y", "x"]), []))
  expect(contextFingerprint(host("a", "s1", ["x"]), [])).not.toBe(contextFingerprint(host("a", "s1", ["x", "y"]), []))
  // External servers still include env.
  const external = (token: string) => ({ ...base, mcpServers: [{ name: "e", command: "c", args: [], env: { T: token } }] })
  expect(contextFingerprint(external("a"), [])).not.toBe(contextFingerprint(external("b"), []))
})

test("fingerprint is stable across a core restart with host servers", async () => {
  const state = scratch(), cwd = scratch(), fake = fakeDriver("fake", RELOAD_ONLY)
  let c = core(state, [fake.driver])
  await c.sessions.create({ agent: "fake", cwd, id: "s1", context: { mcpServers: [orders()] } })
  await c.close({ agents: "shutdown" })
  c = core(state, [fake.driver], { mcpServers: [orders()] })
  await c.sessions.resume("s1")
  expect(fake.opened[1]!.sessionContext!.fingerprint).toBe(fake.opened[0]!.sessionContext!.fingerprint)
  expect(fake.opened[1]!.sessionContext!.mcpServers[0]!.env).toEqual(fake.opened[0]!.sessionContext!.mcpServers[0]!.env)
})

// ------------------------------------------------------------ tool changes

test("tool add/remove: live on a list_changed agent, a relaunch at idle on one that ignores it, nothing for live-only", async () => {
  const cwd = scratch()
  const live = fakeDriver("live", LIST_CHANGED), reload = fakeDriver("reload", RELOAD_ONLY), still = fakeDriver("still", RELOAD_ONLY)
  const server = orders()
  const quiet = mcpServer({ name: "quiet", toolChanges: "live-only", tools: {} })
  const c = core(scratch(), [live.driver, reload.driver, still.driver])
  const events: CoreEvent[] = []
  c.subscribe(event => { events.push(event) })
  await c.sessions.create({ agent: "live", cwd, id: "a", context: { mcpServers: [server] } })
  const b = await c.sessions.create({ agent: "reload", cwd, id: "b", context: { mcpServers: [server] } })
  await c.sessions.create({ agent: "still", cwd, id: "q", context: { mcpServers: [quiet] } })
  expect(c.capabilities("live").hostToolChanges).toBe("live")
  expect(c.capabilities("reload").hostToolChanges).toBe("reload")
  // b is mid-turn: the relaunch waits for the turn to end (never cancels it).
  reload.blockPrompts = true
  const receipt = await b.send({ content: [{ type: "text", text: "work" }], whenBusy: "queue" })
  await until(() => b.snapshot().state === "running", 5000, "running")
  server.add("cancel", tool({ description: "c", run: () => "ok" }))
  server.add("refund", tool({ description: "r", run: () => "ok" }))
  quiet.add("hush", tool({ description: "h", run: () => "ok" }))
  await until(() => events.filter(event => event.type === "context.updated" && event.sessionId === "a").length === 2, 5000, "live updates")
  const updated = (id: string) => events.filter((event): event is Extract<CoreEvent, { type: "context.updated" }> => event.type === "context.updated" && event.sessionId === id).flatMap(event => event.applied)
  expect(updated("a")).toEqual([
    { kind: "tools", op: "add", item: "orders/cancel", how: "live" },
    { kind: "tools", op: "add", item: "orders/refund", how: "live" },
  ])
  expect(updated("q")).toEqual([{ kind: "tools", op: "add", item: "quiet/hush", how: "unsupported", reason: expect.stringContaining("live-only") }])
  await new Promise(resolve => setTimeout(resolve, 100))
  expect(reload.opened).toHaveLength(1)
  expect(updated("b")).toEqual([])
  reload.blockPrompts = false
  reload.releasePrompt()
  expect((await receipt.completed).status).toBe("completed")
  await until(() => updated("b").length === 2, 5000, "reload update")
  // One relaunch for both changes, on the same conversation, with the new tool set.
  expect(reload.opened).toHaveLength(2)
  expect(reload.opened[1]!.resumeId).toBe("native-1")
  expect(reload.opened[1]!.sessionContext!.mcpServers[0]!.host).toEqual({ tools: ["lookup", "cancel", "refund"] })
  expect(updated("b")).toEqual([
    { kind: "tools", op: "add", item: "orders/cancel", how: "reload" },
    { kind: "tools", op: "add", item: "orders/refund", how: "reload" },
  ])
  expect(live.opened).toHaveLength(1)
  expect(still.opened).toHaveLength(1)
  // A closed core no longer reacts.
  await c.close({ agents: "shutdown" })
  server.remove("cancel")
  await new Promise(resolve => setTimeout(resolve, 50))
  expect(reload.opened).toHaveLength(2)
})

// ------------------------------------------------------------ keeper (Claude fixture): re-attach, reconnect, interrupt

const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))

function claudeDriver(root: string, pidFile: string) {
  return claude({
    id: "claude", command: process.execPath, args: [fixture("claude-agent.mjs")], env: { PID_FILE: pidFile, FIXTURE_MCP: "1" }, inheritEnv: true, tools: [], permissionPrompts: "none",
    permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false, setupTimeoutMs: 5000, shutdownTimeoutMs: 500, maxFrameBytes: 16 << 20, requestTimeoutMs: 3000,
    keeper: { stateDirectory: join(root, "keeper"), limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } },
  })
}

async function reply(session: { send: (input: any) => Promise<any> }, events: CoreEvent[], prompt: string): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "reject" })
  await receipt.completed
  await new Promise(resolve => setTimeout(resolve, 50))
  return events.slice(start).flatMap(event => event.type === "session.event" && event.event.kind === "assistant-message" ? [event.event.text] : []).join("\n")
}

test("keeper: a detached Claude session with host servers is RE-ATTACHED after a core restart and calls through the reconnected bridge", async () => {
  const root = scratch(), cwd = join(root, "work"), state = join(root, "state"), pidFile = join(root, "pid")
  mkdirSync(cwd)
  const driver = claudeDriver(root, pidFile)
  const calls: ToolContext[] = []
  const servers = () => [orders(calls), mcpServer({ name: "calendar", tools: { today: tool({ description: "t", run: (_args, ctx) => `today for ${ctx.sessionId}` }) } })]
  const events: CoreEvent[] = []
  const open = (registered: ReturnType<typeof servers>) => {
    const c = core(state, [driver], { mcpServers: registered })
    c.subscribe(event => { events.push(event) })
    return c
  }
  const pid = () => Number(readFileSync(pidFile, "utf8"))
  const alive = (n: number) => { try { process.kill(n, 0); return true } catch { return false } }
  let first = servers()
  let c = open(first)
  let session = await c.sessions.create({ agent: "claude", cwd, id: "kp", context: { mcpServers: [first[0]!, { kind: "host", name: "calendar" }] } })
  const agentPid = pid()
  expect(await reply(session, events, `mcp-call orders lookup {"id":"1"}`)).toBe("MCP order 1 via kp")
  expect(await reply(session, events, "mcp-call calendar today {}")).toBe("MCP today for kp")
  await c.close({ agents: "detach" })
  expect(alive(agentPid)).toBe(true)
  first = servers()
  c = open(first)
  session = await c.sessions.resume("kp")
  expect(pid()).toBe(agentPid)
  await until(async () => (await reply(session, events, `mcp-call orders lookup {"id":"2"}`)) === "MCP order 2 via kp", 10_000, "reconnected call")
  expect(await reply(session, events, "mcp-call calendar today {}")).toBe("MCP today for kp")
  expect(calls.every(ctx => ctx.sessionId === "kp" && ctx.agent === "claude")).toBe(true)
  // A changed tool set does not matter for Claude (list_changed): still the same process on the next restart.
  first[0]!.add("cancel", tool({ description: "c", run: () => "cancelled" }))
  expect(await reply(session, events, "mcp-call orders cancel {}")).toBe("MCP cancelled")
  await c.close({ agents: "detach" })
  c = open(servers())
  session = await c.sessions.resume("kp")
  expect(pid()).toBe(agentPid)
  await c.close({ agents: "shutdown" })
  expect(alive(agentPid)).toBe(false)
})

test("while the host is gone, the detached agent's tool call gets 'host unavailable' at once", async () => {
  const root = scratch(), cwd = join(root, "work"), state = join(root, "state"), pidFile = join(root, "pid")
  mkdirSync(cwd)
  const driver = claudeDriver(root, pidFile)
  const events: CoreEvent[] = []
  let c = core(state, [driver], { mcpServers: [orders()] })
  c.subscribe(event => { events.push(event) })
  await c.sessions.create({ agent: "claude", cwd, id: "kd", context: { mcpServers: [{ kind: "host", name: "orders" }] } })
  // Close only the MCP host (the agent and keeper stay): unregistering closes its connections, and the
  // bridge is refused (retryable) while the server is gone.
  c.mcp.unregister("orders")
  const session = c.sessions.live("kd")!
  expect(await reply(session, events, `mcp-call orders lookup {"id":"1"}`)).toBe("MCP error -32000: host unavailable")
  c.mcp.register(orders())
  await until(async () => (await reply(session, events, `mcp-call orders lookup {"id":"3"}`)) === "MCP order 3 via kd", 10_000, "back")
})

test("a session interrupt aborts its in-flight host tool call (ctx.signal) and the agent gets an isError result", async () => {
  const root = scratch(), cwd = join(root, "work"), state = join(root, "state"), pidFile = join(root, "pid")
  mkdirSync(cwd)
  let aborted = false, started = false
  const slow = mcpServer({
    name: "slow",
    tools: { wait: tool({ description: "w", run: (_args, ctx) => new Promise((_, reject) => { started = true; ctx.signal.addEventListener("abort", () => { aborted = true; reject(new Error("stopped")) }) }) }) },
  })
  const events: CoreEvent[] = []
  const c = core(state, [claudeDriver(root, pidFile)])
  c.subscribe(event => { events.push(event) })
  const session = await c.sessions.create({ agent: "claude", cwd, id: "ki", context: { mcpServers: [slow] } })
  const receipt = await session.send({ content: [{ type: "text", text: "mcp-call slow wait {}" }], whenBusy: "reject" })
  await until(() => started, 10_000, "call started")
  await session.interrupt({ pending: "keep" })
  await until(() => aborted, 5000, "aborted")
  await receipt.completed
  await until(() => events.some(event => event.type === "tool.finished"), 5000, "finished")
  expect(events.find(event => event.type === "tool.finished")).toMatchObject({ server: "slow", tool: "wait", ok: false, sessionId: "ki" })
})

