// #102: the SKILLS line says how each agent finds and loads a plugin skill. Checked against what each agent showed
// on a live install (2026-11-07): Claude and Codex list plugin skills as `<plugin>:<name>`, Grok, OpenCode,
// Antigravity and Cursor by the bare name; Codex reads the SKILL.md at the path it lists (there is no plugin cache).
import { test, expect } from "bun:test"
import { buildAgentHeader } from "./agent-header"
import { AgentKind } from "../../shared/agents"

const skillsLine = (agent: AgentKind) =>
  buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app", agent }).split("\n").find((line) => line.startsWith("- SKILLS:"))

test("Claude and Codex are told the `<plugin>:<name>` form they list", () => {
  expect(skillsLine(AgentKind.Claude)).toContain("<plugin>:<name>")
  expect(skillsLine(AgentKind.Codex)).toContain("<plugin>:<name>")
})

test("agents that list bare names are not told skills are namespaced", () => {
  for (const agent of [AgentKind.Grok, AgentKind.OpenCode, AgentKind.Antigravity, AgentKind.Cursor]) {
    expect(skillsLine(agent)).toBeDefined()
    expect(skillsLine(agent)).not.toContain("<plugin>:<name>")
  }
})

test("Codex reads the SKILL.md it lists, not a plugin cache", () => {
  expect(skillsLine(AgentKind.Codex)).not.toContain("plugin cache")
  expect(skillsLine(AgentKind.Codex)).toContain("SKILL.md")
})
