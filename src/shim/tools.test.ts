import { describe, expect, test } from "bun:test"
import { AGENT_KINDS } from "../shared/agents"
import { callTool, listTools, toolRoute } from "./tools"

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
})
