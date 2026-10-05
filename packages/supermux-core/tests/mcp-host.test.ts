import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, mkdtempSync, statSync, writeFileSync } from "node:fs"
import { rm } from "node:fs/promises"
import { createServer, type Socket } from "node:net"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { McpServer } from "@modelcontextprotocol/server"
import { z } from "zod"
import { McpHost, socketPath, type HostLookup, type ToolEvent } from "../src/mcp/host.js"
import { mcpServer, tool, type HostMcpServer, type ToolContext } from "../src/mcp/index.js"
import { BridgeClient, until } from "./mcp-helpers.js"

setDefaultTimeout(30_000)
const dirs: string[] = []
const hosts: McpHost[] = []
const clients: BridgeClient[] = []
afterEach(async () => {
  await Promise.all(clients.splice(0).map(client => client.close()))
  await Promise.all(hosts.splice(0).map(host => host.close()))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "mcp-host-")); dirs.push(dir); return dir }

function lookupFor(servers: HostMcpServer[], sessions: Record<string, { agent: string; account?: string; servers: string[] }>): HostLookup {
  return async (sessionId, name) => {
    const session = sessions[sessionId]
    if (!session) return { ok: false, code: "unknown_session", message: sessionId }
    const server = servers.find(candidate => candidate.name === name)
    if (!server) return { ok: false, code: "unknown_server", message: name }
    if (!session.servers.includes(name)) return { ok: false, code: "not_attached", message: name }
    return { ok: true, server, agent: session.agent, ...(session.account ? { account: session.account } : {}) }
  }
}

async function startHost(state: string, servers: HostMcpServer[], events: ToolEvent[] = []) {
  const host = new McpHost({
    stateDirectory: state,
    lookup: lookupFor(servers, { s1: { agent: "claude", account: "claude:work", servers: servers.map(server => server.name) }, s2: { agent: "codex", servers: [] } }),
    onEvent: event => { events.push(event) },
  })
  hosts.push(host)
  await host.start()
  return host
}

async function bridge(host: McpHost, server: string, session = "s1", token?: string) {
  const client = new BridgeClient({ socket: host.socket, session, server, token: token ?? await host.token(session, server) })
  clients.push(client)
  return client
}

const text = (response: any) => response.result?.content?.[0]?.text

function orders(calls: ToolContext[] = []) {
  return mcpServer({
    name: "orders",
    instructions: "Order lookup",
    tools: {
      lookup: tool({
        description: "Look up an order",
        input: z.object({ id: z.string() }),
        run: async ({ id }, ctx) => { calls.push(ctx); return `order ${id} for ${ctx.sessionId}` },
      }),
      json: tool({ description: "JSON result", run: () => ({ total: 3, items: ["a"] }) }),
      boom: tool({ description: "Throws", run: () => { throw new Error("the warehouse is on fire") } }),
    },
  })
}

// ------------------------------------------------------------ socket path

test("socket path: <state>/mcp/mcp.sock when short; a hashed path under the runtime dir when too long", async () => {
  const short = scratch()
  expect(socketPath(short).path).toBe(join(short, "mcp", "mcp.sock"))
  const long = join(scratch(), "x".repeat(120))
  const fallback = socketPath(long, { XDG_RUNTIME_DIR: tmpdir() })
  expect(Buffer.byteLength(fallback.path)).toBeLessThanOrEqual(107)
  expect(fallback.path.startsWith(join(tmpdir(), "supermux-mcp-"))).toBe(true)
  expect(socketPath(long, { XDG_RUNTIME_DIR: tmpdir() }).path).toBe(fallback.path)
  expect(socketPath(long + "y", { XDG_RUNTIME_DIR: tmpdir() }).path).not.toBe(fallback.path)
  // The host really listens there, in a 0700 directory, socket 0600; secret stays in the state dir.
  const host = await startHost(long, [orders()])
  dirs.push(fallback.directory)
  expect(host.socket).toBe(socketPath(long).path)
  expect(statSync(socketPath(long).directory).mode & 0o777).toBe(0o700)
  expect(statSync(host.socket).mode & 0o777).toBe(0o600)
  expect(statSync(join(long, "mcp", "secret")).mode & 0o777).toBe(0o600)
  const client = await bridge(host, "orders")
  await client.initialize()
  expect(text(await client.request("tools/call", { name: "lookup", arguments: { id: "7" } }))).toBe("order 7 for s1")
})

test("a stale socket is removed on start; a live owner makes the second host refuse", async () => {
  const state = scratch()
  const first = await startHost(state, [orders()])
  const second = new McpHost({ stateDirectory: state, lookup: lookupFor([], {}), onEvent() {} })
  await expect(second.start()).rejects.toMatchObject({ code: "mcp_socket_in_use" })
  await first.close()
  // A leftover file nobody listens on.
  writeFileSync(first.socket, "")
  const third = await startHost(state, [orders()])
  const client = await bridge(third, "orders")
  await client.initialize()
})

test("the token is deterministic per (session, server) and survives a host restart (secret in the state dir)", async () => {
  const state = scratch()
  const a = await startHost(state, [orders()])
  const token = await a.token("s1", "orders")
  expect(token).toMatch(/^[0-9a-f]{64}$/)
  expect(await a.token("s1", "other")).not.toBe(token)
  expect(await a.token("s2", "orders")).not.toBe(token)
  await a.close()
  const b = await startHost(state, [orders()])
  expect(await b.token("s1", "orders")).toBe(token)
})

// ------------------------------------------------------------ auth

test("auth failure: a wrong token is refused and the bridge exits", async () => {
  const host = await startHost(scratch(), [orders()])
  const client = await bridge(host, "orders", "s1", "0".repeat(64))
  expect(await client.exited).toBe(1)
  expect(client.stderr.join("\n")).toContain("unauthorized")
})

test("a server the session does not have is refused (retryable): requests get host unavailable", async () => {
  const host = await startHost(scratch(), [orders()])
  const client = await bridge(host, "orders", "s2")
  const response = await client.request("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } })
  expect(response.error).toEqual({ code: -32000, message: "host unavailable" })
  expect(client.child.exitCode).toBe(null)
})

// ------------------------------------------------------------ tools

test("initialize, tools/list, a call with ctx, JSON results, isError on throw, zod validation errors", async () => {
  const calls: ToolContext[] = []
  const events: ToolEvent[] = []
  const host = await startHost(scratch(), [orders(calls)], events)
  const client = await bridge(host, "orders")
  const init = await client.initialize()
  expect(init.result.serverInfo.name).toBe("orders")
  expect(init.result.instructions).toBe("Order lookup")
  expect(init.result.capabilities.tools.listChanged).toBe(true)
  const list = await client.request("tools/list")
  expect(list.result.tools.map((entry: any) => entry.name).sort()).toEqual(["boom", "json", "lookup"])
  expect(list.result.tools.find((entry: any) => entry.name === "lookup").inputSchema.properties.id.type).toBe("string")

  expect(text(await client.request("tools/call", { name: "lookup", arguments: { id: "42" } }))).toBe("order 42 for s1")
  expect(calls[0]).toMatchObject({ sessionId: "s1", agent: "claude", account: "claude:work", server: "orders" })
  expect(calls[0]!.signal).toBeInstanceOf(AbortSignal)
  expect(JSON.parse(text(await client.request("tools/call", { name: "json", arguments: {} })))).toEqual({ total: 3, items: ["a"] })

  const boom = await client.request("tools/call", { name: "boom", arguments: {} })
  expect(boom.result.isError).toBe(true)
  expect(text(boom)).toContain("the warehouse is on fire")

  const invalid = await client.request("tools/call", { name: "lookup", arguments: { id: 5 } })
  expect(invalid.result.isError).toBe(true)
  expect(text(invalid)).toContain("Input validation error")

  await until(() => events.filter(event => event.type === "tool.finished").length === 4, 5000, "four finished events")
  const finished = events.filter((event): event is Extract<ToolEvent, { type: "tool.finished" }> => event.type === "tool.finished")
  expect(finished.map(event => [event.tool, event.ok])).toEqual([["lookup", true], ["json", true], ["boom", false], ["lookup", false]])
  const called = events.filter(event => event.type === "tool.called")
  expect(called.map(event => event.callId)).toEqual(finished.map(event => event.callId))
  for (const event of events) {
    expect(Object.keys(event).sort()).toEqual(event.type === "tool.called" ? ["callId", "server", "sessionId", "tool", "type"] : ["callId", "durationMs", "ok", "server", "sessionId", "tool", "type"])
  }
})

test("output schema: a structured result is sent as structuredContent and validated", async () => {
  const server = mcpServer({
    name: "typed",
    tools: {
      total: tool({ description: "t", output: z.object({ total: z.number() }), run: () => ({ total: 9 }) }),
      wrong: tool({ description: "w", output: z.object({ total: z.number() }), run: () => ({ total: "nine" }) as never }),
    },
  })
  const host = await startHost(scratch(), [server])
  const client = await bridge(host, "typed")
  await client.initialize()
  const ok = await client.request("tools/call", { name: "total", arguments: {} })
  expect(ok.result.structuredContent).toEqual({ total: 9 })
  const wrong = await client.request("tools/call", { name: "wrong", arguments: {} })
  expect(wrong.result.isError).toBe(true)
})

test("list_changed on add/remove: every live connection re-lists the new tool set", async () => {
  const server = orders()
  const host = await startHost(scratch(), [server])
  const a = await bridge(host, "orders"), b = await bridge(host, "orders")
  await a.initialize(); await b.initialize()
  server.add("cancel", tool({ description: "Cancel an order", input: z.object({ id: z.string() }), run: ({ id }) => `cancelled ${id}` }))
  await until(() => a.notifications("notifications/tools/list_changed").length === 1 && b.notifications("notifications/tools/list_changed").length === 1, 5000, "list_changed")
  expect((await a.request("tools/list")).result.tools.map((entry: any) => entry.name)).toContain("cancel")
  expect(text(await b.request("tools/call", { name: "cancel", arguments: { id: "1" } }))).toBe("cancelled 1")
  expect(server.remove("lookup")).toBe(true)
  await until(() => a.notifications("notifications/tools/list_changed").length === 2, 5000, "second list_changed")
  expect((await a.request("tools/list")).result.tools.map((entry: any) => entry.name)).not.toContain("lookup")
  // A connection opened later gets the current set.
  const c = await bridge(host, "orders")
  await c.initialize()
  expect((await c.request("tools/list")).result.tools.map((entry: any) => entry.name).sort()).toEqual(["boom", "cancel", "json"])
})

test("cancellation: notifications/cancelled aborts ctx.signal; a session interrupt aborts it and answers isError", async () => {
  const aborted: string[] = []
  const started: string[] = []
  const server = mcpServer({
    name: "slow",
    tools: {
      wait: tool({
        description: "Waits until cancelled",
        input: z.object({ tag: z.string() }),
        run: ({ tag }, ctx) => new Promise((_, reject) => {
          started.push(tag)
          ctx.signal.addEventListener("abort", () => { aborted.push(tag); reject(new Error("aborted")) }, { once: true })
        }),
      }),
    },
  })
  const events: ToolEvent[] = []
  const host = await startHost(scratch(), [server], events)
  const client = await bridge(host, "slow")
  await client.initialize()
  // 1. The agent cancels its own call.
  const id = 1000
  client.send({ jsonrpc: "2.0", id, method: "tools/call", params: { name: "wait", arguments: { tag: "agent" } } })
  await until(() => started.includes("agent"), 5000, "first call")
  client.notify("notifications/cancelled", { requestId: id, reason: "user" })
  await until(() => aborted.includes("agent"), 5000, "agent cancel")
  // 2. The session is interrupted: the call is aborted and answered right away.
  const pending = client.request("tools/call", { name: "wait", arguments: { tag: "interrupt" } })
  await until(() => started.includes("interrupt"), 5000, "second call")
  host.interrupt("s1")
  const response = await pending
  expect(response.result.isError).toBe(true)
  expect(text(response)).toContain("interrupted")
  await until(() => aborted.includes("interrupt"), 5000, "interrupt abort")
  // Exactly one answer for the interrupted call (the SDK's late one is dropped).
  await new Promise(resolve => setTimeout(resolve, 100))
  expect(client.received.filter(message => message.id === 2 && message.method === undefined)).toHaveLength(1)
  await until(() => events.filter(event => event.type === "tool.finished").length === 2, 5000, "finished events")
  expect(events.filter(event => event.type === "tool.finished").map(event => (event as any).ok)).toEqual([false, false])
})

test("create(ctx): any SDK McpServer, built once per connection with that connection's context", async () => {
  const contexts: string[] = []
  const server = mcpServer({
    name: "custom",
    create: ctx => {
      contexts.push(`${ctx.sessionId}/${ctx.agent}/${ctx.server}`)
      const sdk = new McpServer({ name: "custom", version: "2.0.0" })
      sdk.registerTool("who", { description: "who", inputSchema: z.object({}) }, async () => ({ content: [{ type: "text", text: `session ${ctx.sessionId}` }] }))
      return sdk
    },
  })
  const host = await startHost(scratch(), [server])
  const a = await bridge(host, "custom"), b = await bridge(host, "custom")
  await a.initialize(); await b.initialize()
  expect(text(await a.request("tools/call", { name: "who", arguments: {} }))).toBe("session s1")
  expect(contexts).toEqual(["s1/claude/custom", "s1/claude/custom"])
  expect(() => server.add("x", tool({ description: "x", run: () => "" }))).toThrow()
})

test("create(ctx) with JSON Schema tools: McpServer + fromJsonSchema re-exported from supermux-core/mcp (no zod in the host)", async () => {
  const { McpServer: Sdk, fromJsonSchema } = await import("../src/mcp/index.js")
  const schema = { type: "object", properties: { text: { type: "string" }, format: { type: "string", enum: ["text", "markdownv2"] } }, required: ["text"] }
  const server = mcpServer({
    name: "jsonschema",
    create: () => {
      const sdk = new Sdk({ name: "jsonschema", version: "1.0.0" })
      sdk.registerTool("say", { description: "say", inputSchema: fromJsonSchema(schema) }, async (args: Record<string, unknown>) => ({ content: [{ type: "text", text: `said ${JSON.stringify(args)}` }] }))
      return sdk
    },
  })
  const host = await startHost(scratch(), [server])
  const client = await bridge(host, "jsonschema")
  await client.initialize()
  const listed = await client.request("tools/list", {})
  expect(listed.result.tools[0].inputSchema).toEqual(schema)
  expect(text(await client.request("tools/call", { name: "say", arguments: { text: "hi" } }))).toBe('said {"text":"hi"}')
  const invalid = await client.request("tools/call", { name: "say", arguments: { format: "bad" } })
  expect(invalid.result.isError).toBe(true)
})

// ------------------------------------------------------------ reconnect

test("reconnect: host unavailable while down, then the bridge replays initialize to the new host and calls work again", async () => {
  const state = scratch()
  const first = await startHost(state, [orders()])
  const client = await bridge(first, "orders")
  await client.initialize()
  expect(text(await client.request("tools/call", { name: "lookup", arguments: { id: "1" } }))).toBe("order 1 for s1")
  await first.close()
  // Down: a request is answered at once with -32000; a notification is dropped silently.
  await until(async () => (await client.request("ping")).error?.code === -32000, 5000, "host unavailable")
  const before = client.received.length
  client.notify("notifications/roots/list_changed")
  const down = await client.request("tools/call", { name: "lookup", arguments: { id: "2" } })
  expect(down.error).toEqual({ code: -32000, message: "host unavailable" })
  expect(client.received.length).toBe(before + 1)
  // A new host on the same state (same secret): the bridge reconnects on its own.
  const second = await startHost(state, [orders()])
  await until(async () => (await client.request("ping")).error === undefined, 10_000, "reconnect")
  expect(text(await client.request("tools/call", { name: "lookup", arguments: { id: "3" } }))).toBe("order 3 for s1")
  // The replayed initialize's response never reached the agent: one initialize result only.
  expect(client.received.filter(message => message.result?.protocolVersion !== undefined)).toHaveLength(1)
  expect(client.child.exitCode).toBe(null)
  void second
})

test("reconnect replay, byte level: initialize (original params) then notifications/initialized before anything else", async () => {
  const dir = scratch()
  const path = join(dir, "fake.sock")
  const seen: Array<Array<Record<string, any>>> = []
  const sockets: Socket[] = []
  const fake = createServer(socket => {
    sockets.push(socket)
    const lines: Array<Record<string, any>> = []
    seen.push(lines)
    let buffer = ""
    let greeted = false
    socket.on("data", chunk => {
      buffer += chunk.toString()
      for (let newline = buffer.indexOf("\n"); newline >= 0; newline = buffer.indexOf("\n")) {
        const message = JSON.parse(buffer.slice(0, newline)); buffer = buffer.slice(newline + 1)
        lines.push(message)
        if (!greeted) { greeted = true; socket.write(JSON.stringify({ ok: true }) + "\n"); continue }
        if (message.method === "initialize") socket.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { protocolVersion: "2025-06-18", capabilities: {}, serverInfo: { name: "fake", version: "1" } } }) + "\n")
        else if (message.id !== undefined && message.method) socket.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { echo: message.method } }) + "\n")
      }
    })
    socket.on("error", () => {})
  })
  await new Promise<void>(resolve => fake.listen(path, resolve))
  const client = new BridgeClient({ socket: path, session: "s1", server: "orders", token: "t".repeat(64) })
  clients.push(client)
  const init = await client.initialize()
  expect(init.result.serverInfo.name).toBe("fake")
  expect((await client.request("tools/list")).result).toEqual({ echo: "tools/list" })
  // Drop the connection; the bridge reconnects to the same listener.
  sockets[0]!.destroy()
  await until(() => seen.length === 2 && seen[1]!.length >= 3, 10_000, "replay")
  const [hello, replayed, initialized] = seen[1]!
  expect(hello).toMatchObject({ protocol: "supermux-mcp-bridge/1", sessionId: "s1", server: "orders" })
  expect(replayed!.method).toBe("initialize")
  expect(replayed!.params).toEqual(seen[0]![1]!.params)
  expect(String(replayed!.id)).toMatch(/^supermux-bridge-replay-/)
  expect(initialized).toEqual({ jsonrpc: "2.0", method: "notifications/initialized" })
  // Then traffic flows again and the replayed answer never reached the agent.
  expect((await client.request("tools/call", {})).result).toEqual({ echo: "tools/call" })
  expect(client.received.filter(message => message.result?.serverInfo)).toHaveLength(1)
  for (const socket of sockets) socket.destroy()
  await new Promise<void>(resolve => fake.close(() => resolve()))
  expect(existsSync(path)).toBe(false)
})
