import { describe, expect, test } from "bun:test"
import { AGENT_KINDS } from "../shared/agents"
import { listTools } from "./tools"

describe("shim tools", () => {
  test("spawn_session schema includes every agent kind", () => {
    const tool = listTools().find((t) => t.name === "spawn_session")
    expect(tool).toBeTruthy()
    const schema = tool!.inputSchema as {
      properties: { agent: { enum: readonly string[] } }
    }
    expect(schema.properties.agent.enum).toEqual(AGENT_KINDS)
  })
})
