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

test("listTools advertises attach / react / edit_message / download_attachment, and no reply", () => {
  const names = listTools().map(t => t.name)
  for (const n of ["attach", "react", "edit_message", "download_attachment"]) expect(names).toContain(n)
  expect(names).not.toContain("reply")
})

test("listTools advertises orchestration tools too", () => {
  const names = listTools().map(t => t.name)
  for (const n of ["spawn_session", "kill_session", "rename_session", "mute_session", "list_sessions", "set_active", "get_active", "walkthrough", "reply_comment"]) {
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
    const t = listTools().find(t => t.name === name)
    if (!t) throw new Error(`tool ${name} not found`)
    return t.description
  }
  // The agent does not choose a destination at all — the broker routes the
  // files to the chat the session is talking to.
  expect(desc("attach")).not.toContain("Telegram")
  expect(desc("attach")).not.toContain("chat_id")
  expect(desc("download_attachment")).not.toContain("Telegram")
  expect(desc("react")).toContain("Telegram only")
  expect(desc("edit_message")).toContain("Telegram only")
})

test("attach forwards to broker outbound", async () => {
  const shim = fakeShim()
  const r = await callTool({ name: "attach", arguments: { files: ["/tmp/a.png"] } }, shim)
  expect(shim.outbound).toEqual([{ name: "attach", args: { files: ["/tmp/a.png"] } }])
  expect(r.content[0]).toEqual({ type: "text", text: "sent (id: 999)" })
})

test("attach takes no chat_id and requires files", () => {
  const attach = listTools().find((t) => t.name === "attach")!
  expect(Object.keys(attach.inputSchema.properties)).not.toContain("chat_id")
  expect(attach.inputSchema.required).toEqual(["files"])
})

test("spawn_session forwards to broker orchestration", async () => {
  const shim = fakeShim()
  await callTool({ name: "spawn_session", arguments: { workdir: "/tmp/foo" } }, shim)
  expect(shim.orchestration).toEqual([{ name: "spawn_session", args: { workdir: "/tmp/foo" } }])
})

test("walkthrough and reply_comment forward to broker orchestration", async () => {
  const shim = fakeShim()
  await callTool({ name: "walkthrough", arguments: { title: "T", steps: [] } }, shim)
  await callTool({ name: "reply_comment", arguments: { comment_id: "c1", body: "ok" } }, shim)
  expect(shim.orchestration[0]).toEqual({ name: "walkthrough", args: { title: "T", steps: [] } })
  expect(shim.orchestration[1]).toEqual({ name: "reply_comment", args: { comment_id: "c1", body: "ok" } })
})

test("broker error becomes MCP error response", async () => {
  const shim = {
    callOutbound: async () => ({ ok: false, error: "broker said no" }),
    callOrchestration: async () => ({ ok: false, error: "denied" }),
  } as any
  const r = await callTool({ name: "attach", arguments: { files: ["/tmp/x"] } }, shim)
  expect(r.isError).toBe(true)
  expect(r.content[0]).toEqual({ type: "text", text: "broker said no" })
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
