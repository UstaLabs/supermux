import { expect, test } from "bun:test"
import { buildAgentModels } from "./agent-models"
import type { ModelInfo } from "./discovery"

const codex: ModelInfo[] = [
  { id: "gpt-a", displayName: "GPT A", agent: "codex", reasoningLevels: [{ id: "low" }, { id: "high" }] },
  { id: "gpt-b", displayName: "GPT B", agent: "codex", reasoningLevels: [{ id: "medium" }] },
]

test("lists only the installed agents, in order, with their models", () => {
  const out = buildAgentModels(["codex", "cursor"], (k) => (k === "codex" ? codex : []))
  expect(out.agents.map((a) => a.kind)).toEqual(["codex", "cursor"])
  expect(out.agents[0]!.models).toEqual([{ id: "gpt-a", displayName: "GPT A" }, { id: "gpt-b", displayName: "GPT B" }])
  expect(out.agents[1]!.models).toEqual([])
})

test("carries per-model reasoning with the /reasoning-levels visibility rule", () => {
  const [entry] = buildAgentModels(["codex"], () => codex).agents
  expect(entry!.modelReasoning["gpt-a"]!.visible).toBe(true)
  expect(entry!.modelReasoning["gpt-a"]!.levels.map((l) => l.id).sort()).toEqual(["high", "low"])
  // One level is no choice: hidden, and no levels shipped.
  expect(entry!.modelReasoning["gpt-b"]).toEqual({ levels: [], visible: false })
})

test("claude's effort ladder is offered for its default", () => {
  const [entry] = buildAgentModels(["claude"], () => []).agents
  expect(entry!.reasoning.visible).toBe(true)
  expect(entry!.reasoning.levels.map((l) => l.id)).toContain("high")
})
