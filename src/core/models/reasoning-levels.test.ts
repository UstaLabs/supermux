import { expect, test } from "bun:test"
import { AgentKind } from "../../shared/agents"
import { supportedReasoningLevels } from "./reasoning-levels"

test("antigravity has no reasoning levels (effort is part of the model id)", () => {
  const models = [{ id: "x", displayName: "x", agent: AgentKind.Antigravity, reasoningLevels: [{ id: "high" }] }]
  expect(supportedReasoningLevels(AgentKind.Antigravity, models, "x")).toEqual([])
  expect(supportedReasoningLevels(AgentKind.Antigravity, [], "x")).toEqual([])
})
