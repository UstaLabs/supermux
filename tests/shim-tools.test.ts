import { test, expect } from "bun:test"
import { listTools, callTool } from "../src/shim/tools"

function fakeShim() {
  const outbound: any[] = []
  const orchestration: any[] = []
  return {
    outbound, orchestration,
    callOutbound: async (op: any) => { outbound.push(op); return { ok: true, value: { message_id: 999 } } },
    callOrchestration: async (op: any) => { orchestration.push(op); return { ok: true, value: { ok: 1 } } },
  } as any
}

test("listTools advertises reply / react / edit_message / download_attachment", () => {
  const names = listTools().map(t => t.name)
  for (const n of ["reply", "react", "edit_message", "download_attachment"]) expect(names).toContain(n)
})

test("listTools advertises orchestration tools too", () => {
  const names = listTools().map(t => t.name)
  for (const n of ["spawn_session", "kill_session", "rename_session", "mute_session", "list_sessions", "set_active", "get_active"]) {
    expect(names).toContain(n)
  }
})

test("rename_session asks agents for a natural display title", () => {
  const description = listTools().find(t => t.name === "rename_session")?.description ?? ""
  expect(description).toContain("human-readable")
  expect(description).toContain("with spaces")
  expect(description).toContain("normal capitalization")
  expect(description).not.toContain("joined by '-'")
  expect(description).not.toContain("normalized")
})

test("outbound tool descriptions are channel-neutral", () => {
  const desc = (name: string) => {
    const t = listTools("claude").find(t => t.name === name)
    if (!t) throw new Error(`tool ${name} not found`)
    return t.description
  }
  expect(desc("reply")).not.toContain("Telegram reply")
  // The agent does not choose a destination at all — the broker routes the
  // reply to the chat the session is talking to.
  expect(desc("reply")).not.toContain("chat_id")
  expect(desc("download_attachment")).not.toContain("Telegram")
  expect(desc("react")).toContain("Telegram only")
  expect(desc("edit_message")).toContain("Telegram only")
})

test("reply forwards to broker outbound", async () => {
  const shim = fakeShim()
  const r = await callTool({ name: "reply", arguments: { text: "hi" } }, shim)
  expect(shim.outbound).toEqual([{ name: "reply", args: { text: "hi" } }])
  expect(r.content[0]).toEqual({ type: "text", text: "sent (id: 999)" })
})

test("reply takes no chat_id — the broker owns the destination", () => {
  const reply = listTools("claude").find((t) => t.name === "reply")!
  expect(Object.keys(reply.inputSchema.properties)).not.toContain("chat_id")
  expect(reply.inputSchema.required).toEqual(["text"])
})

test("spawn_session forwards to broker orchestration", async () => {
  const shim = fakeShim()
  await callTool({ name: "spawn_session", arguments: { workdir: "/tmp/foo" } }, shim)
  expect(shim.orchestration).toEqual([{ name: "spawn_session", args: { workdir: "/tmp/foo" } }])
})

test("broker error becomes MCP error response", async () => {
  const shim = {
    callOutbound: async () => ({ ok: false, error: "broker said no" }),
    callOrchestration: async () => ({ ok: false, error: "denied" }),
  } as any
  const r = await callTool({ name: "reply", arguments: { chat_id: "c1", text: "x" } }, shim)
  expect(r.isError).toBe(true)
  expect(r.content[0]).toEqual({ type: "text", text: "broker said no" })
})

import { describe } from "bun:test"

describe("shim tool surface gating", () => {
  test("listTools('claude') includes reply", () => {
    const names = listTools("claude").map((t: any) => t.name)
    expect(names).toContain("reply")
  })

  test("listTools('codex') includes reply with file-only description", () => {
    const tools = listTools("codex")
    const names = tools.map((t: any) => t.name)
    expect(names).toContain("reply")
    expect(names).toContain("react")
    const reply = tools.find((t: any) => t.name === "reply")
    expect(reply?.description).toContain("files[]")
    expect(reply?.description.toLowerCase()).toContain("only")
  })

  test("listTools('cursor') includes reply with file-only description", () => {
    const tools = listTools("cursor")
    expect(tools.map((t: any) => t.name)).toContain("reply")
    expect(tools.find((t: any) => t.name === "reply")?.description).toContain("files[]")
  })

  test("listTools() with no arg defaults to claude (back-compat)", () => {
    const names = listTools().map((t: any) => t.name)
    expect(names).toContain("reply")
  })
})

test("rpc tools map resolve/reject to orchestration ops rpc_resolve/rpc_reject", async () => {
  const ops: { name: string; args: any }[] = []
  const fakeShim = {
    callOutbound: async () => ({ ok: true }),
    callOrchestration: async (op: { name: string; args: any }) => { ops.push(op); return { ok: true, value: "ok" } },
  }
  const res = await callTool({ name: "resolve", arguments: { request_id: "req-1", data: { text: "hi" } } }, fakeShim as any, "claude", true /* rpcOnly */)
  expect(res.isError).toBeFalsy()
  expect(ops).toEqual([{ name: "rpc_resolve", args: { request_id: "req-1", data: { text: "hi" } } }])
})
