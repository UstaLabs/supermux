import { expect, test } from "bun:test"
import { agentChoices, effortError } from "./agent-choices"
import { unattendedModesFor } from "../agents/permission-modes"
import { claudeReasoningLevels } from "./reasoning-levels"

test("each agent's access levels from the catalog and the efforts its models take", () => {
  const c = agentChoices(() => [])
  // Only the modes a hidden session can run in: Claude's plan (waits for plan approval) is not offered.
  expect(c.claude!.access.map((m) => m.id)).toEqual(unattendedModesFor("claude").map((m) => m.id))
  expect(c.claude!.access.find((m) => m.id === "dont-ask")).toMatchObject({ level: "read-only" })
  expect(c.claude!.access.find((m) => m.id === "plan")).toBeUndefined()
  expect(c.claude!.efforts).toEqual(claudeReasoningLevels().map((l) => l.id))
  for (const agent of ["cursor", "opencode", "antigravity"]) expect(c[agent]!.efforts).toEqual([])
  // No catalog yet (right after a broker start): Codex and Grok efforts aren't known, so nothing is refused.
  expect(c.codex!.efforts).toBeNull()
  expect(effortError(c, "codex", "xhigh")).toBeUndefined()
})

test("effortError: absent is fine, a value outside the agent's list is refused, an agent without efforts takes none", () => {
  const c = agentChoices(() => [])
  expect(effortError(c, "claude", undefined)).toBeUndefined()
  expect(effortError(c, "claude", claudeReasoningLevels()[0]!.id)).toBeUndefined()
  expect(effortError(c, "claude", "turbo")).toMatch(/^effort must be one of .+ for claude$/)
  expect(effortError(c, "cursor", "high")).toBe("cursor has no effort setting: leave effort out")
})
