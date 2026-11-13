import { describe, expect, test } from "bun:test"
import { AGENT_KINDS } from "../shared/agents"
import { SHOW_HTML_DESCRIPTION } from "../core/show-html/description"
import { callTool, listTools, RPC_TOOLS, shimTools, toolResult, toolRoute } from "./tools"

describe("shim tools", () => {
  test("spawn_session schema includes every agent kind", () => {
    const tool = listTools().find((t) => t.name === "spawn_session")
    expect(tool).toBeTruthy()
    const schema = tool!.inputSchema as {
      properties: { agent: { enum: readonly string[] } }
    }
    expect(schema.properties.agent.enum).toEqual(AGENT_KINDS)
  })

  test("callTool passes Claude's tool-call id from _meta as the op's callId, outside args", async () => {
    const ops: any[] = []
    await callTool({ name: "delegate", arguments: { task: "t" }, _meta: { "claudecode/toolUseId": "toolu_1" } }, {
      callOutbound: async () => ({ ok: true }),
      callOrchestration: async (op) => { ops.push(op); return { ok: true } },
    })
    expect(ops).toEqual([{ name: "delegate", args: { task: "t" }, callId: "toolu_1" }])
  })

  test("council_report is listed only for council members, and routed for anyone (the handler refuses a non-member)", () => {
    const names = listTools().map((t) => t.name)
    expect(names).not.toContain("council_report")
    expect(names).toContain("council")
    expect(names).toContain("council_round")
    expect(listTools(false, { councilMember: true }).map((t) => t.name)).toContain("council_report")
    expect(toolRoute("council_report")).toEqual({ kind: "orchestration", op: "council_report" })
  })

  test("shimTools: the list the broker serves each external shim server, every tool with its route", () => {
    const withRoutes = (rpcOnly: boolean, tools: Array<{ name: string; description: string; inputSchema: Record<string, unknown>; annotations?: { readOnlyHint?: boolean } }>) =>
      tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema, ...(t.annotations ? { annotations: t.annotations } : {}), route: toolRoute(t.name, rpcOnly)! }))
    expect(shimTools("mux-shim")).toEqual(withRoutes(false, listTools()))
    expect(shimTools("mux-shim", { councilMember: true })).toEqual(withRoutes(false, listTools(false, { councilMember: true })))
    expect(shimTools("mux-rpc")).toEqual(withRoutes(true, RPC_TOOLS))
    expect(shimTools("mux-rpc")!.map((t) => t.route.op)).toEqual(["rpc_resolve", "rpc_reject"])
    // mux-channel serves no tools; a shim that does not say which server it is gets none either.
    expect(shimTools("mux-channel")).toBeUndefined()
    expect(shimTools(undefined)).toBeUndefined()
  })

  test("callTool routes by the served list, so a tool this build does not have reaches the broker", async () => {
    const calls: Array<[string, unknown]> = []
    const shim = {
      callOutbound: async (op: unknown) => { calls.push(["outbound", op]); return { ok: true } },
      callOrchestration: async (op: unknown) => { calls.push(["orchestration", op]); return { ok: true, value: "zapped" } },
    }
    const served = [{ name: "zap", description: "Zap", inputSchema: { type: "object" }, route: { kind: "outbound" as const, op: "zap_op" } }]
    expect(await callTool({ name: "zap", arguments: { x: 1 } }, shim, undefined, false, served)).toEqual({ content: [{ type: "text", text: '"ok"' }] })
    expect(calls).toEqual([["outbound", { name: "zap_op", args: { x: 1 } }]])
    // Not in the served list: this build's own route still applies (council_report is refused by the handler, not here).
    await callTool({ name: "council_report", arguments: { text: "t" } }, shim, undefined, false, served)
    expect(calls[1]).toEqual(["orchestration", { name: "council_report", args: { text: "t" } }])
    expect((await callTool({ name: "nope" }, shim, undefined, false, served)).isError).toBe(true)
  })
})

test("with the broker's choices, spawn_session / delegate / council / council_add give access and effort as enums described per agent", () => {
  const choices = {
    claude: { access: [{ id: "bypass", level: "full", description: "Skip approval." }, { id: "plan", level: "read-only", description: "Plan mode." }], efforts: ["low", "high"] },
    cursor: { access: [{ id: "ask", level: "ask", description: "Ask first." }], efforts: [] },
  }
  const tools = listTools(false, { choices })
  const council = tools.find((t) => t.name === "council")!.inputSchema as any
  const item = council.properties.members.items.properties
  expect(item.access.enum).toEqual(["bypass", "plan", "ask"])
  expect(item.access.description).toContain("claude: bypass (full: Skip approval.); plan (read-only: Plan mode.)")
  expect(item.access.description).toContain("cursor: ask (ask: Ask first.)")
  expect(item.effort.enum).toEqual(["low", "high"])
  expect(item.effort.description).toContain("cursor: none, leave effort out")
  expect(item.agent.enum).toBeDefined()
  const add = tools.find((t) => t.name === "council_add")!.inputSchema as any
  expect(add.properties.members.items.properties.access.enum).toEqual(["bypass", "plan", "ask"])
  const delegate = tools.find((t) => t.name === "delegate")!.inputSchema as any
  expect(delegate.properties.access.enum).toEqual(["bypass", "plan", "ask"])
  expect(delegate.required).toEqual((listTools().find((t) => t.name === "delegate")!.inputSchema as any).required)
  // spawn_session takes effort (same enum) and model, but no access.
  const spawn = tools.find((t) => t.name === "spawn_session")!.inputSchema as any
  expect(spawn.properties.effort).toEqual(delegate.properties.effort)
  expect(spawn.properties.model).toEqual({ type: "string" })
  expect(spawn.properties.access).toBeUndefined()
  expect(spawn.required).toEqual(["workdir"])
  // Other tools, and the list without choices, are unchanged.
  expect(tools.find((t) => t.name === "attach")).toEqual(listTools().find((t) => t.name === "attach"))
  expect((listTools().find((t) => t.name === "council")!.inputSchema as any).properties.members.items.properties.access).toEqual({ type: "string" })
})

test("show_html: an outbound, read-only-hinted tool with the description and the schema of #104", () => {
  const tool = listTools().find((t) => t.name === "show_html") as any
  expect(tool.description).toBe(SHOW_HTML_DESCRIPTION)
  expect(tool.annotations).toEqual({ readOnlyHint: true })
  expect(tool.inputSchema.required).toEqual(["title"])
  expect(tool.inputSchema.properties.title).toMatchObject({ type: "string", minLength: 1, maxLength: 80 })
  expect(tool.inputSchema.properties.height).toMatchObject({ minimum: 80, maximum: 2000 })
  expect(Object.keys(tool.inputSchema.properties).sort()).toEqual(["file", "height", "html", "title"])
  expect(toolRoute("show_html")).toEqual({ kind: "outbound", op: "show_html" })
  expect(toolRoute("show_html", true)).toBeUndefined()
  expect(shimTools("mux-shim")!.find((t) => t.name === "show_html")!.route).toEqual({ kind: "outbound", op: "show_html" })
})

test("show_html's result reaches the agent as the sentence, an error as an error", () => {
  expect(toolResult("show_html", { ok: true, value: "shown (id: ab)" })).toEqual({ content: [{ type: "text", text: "shown (id: ab)" }] })
  expect(toolResult("show_html", { ok: false, error: "file must be inside" })).toEqual({ isError: true, content: [{ type: "text", text: "file must be inside" }] })
})
