import { describe, expect, test } from "bun:test"
import { AGENT_KINDS } from "../shared/agents"
import { callTool, listTools } from "./tools"

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
})
