import { describe, expect, test } from "bun:test"
import { AGENT_KINDS, AgentKind, agentDisplayName, isAgentKind, parseAgentKind, spawnCommandForAgent } from "./agents"

describe("shared agent kinds", () => {
  test("lists every supported agent in stable display order", () => {
    expect(AGENT_KINDS).toEqual([
      AgentKind.Claude,
      AgentKind.Codex,
      AgentKind.Cursor,
      AgentKind.OpenCode,
      AgentKind.Grok,
    ])
  })

  test("recognizes only supported agents", () => {
    for (const kind of AGENT_KINDS) expect(isAgentKind(kind)).toBe(true)
    expect(isAgentKind("gemini")).toBe(false)
    expect(isAgentKind(undefined)).toBe(false)
    expect(isAgentKind(42)).toBe(false)
  })

  test("grok is a recognized agent kind", () => {
    expect(AgentKind.Grok).toBe("grok")
    expect(AGENT_KINDS).toContain("grok")
    expect(isAgentKind("grok")).toBe(true)
    expect(parseAgentKind("grok")).toBe("grok")
  })

  test("parseAgentKind returns default only for nullish input", () => {
    expect(parseAgentKind(undefined)).toBe(AgentKind.Claude)
    expect(parseAgentKind(null)).toBe(AgentKind.Claude)
    expect(parseAgentKind("opencode")).toBe(AgentKind.OpenCode)
    expect(() => parseAgentKind("gemini")).toThrow("unsupported agent kind: gemini")
  })

  test("every agent kind has a distinct, Telegram-safe spawn command", () => {
    const commands = AGENT_KINDS.map(spawnCommandForAgent)
    expect(new Set(commands).size).toBe(AGENT_KINDS.length)
    for (const command of commands) expect(command).toMatch(/^[a-z0-9_]{1,32}$/)
  })

  test("claude, the default agent, keeps the bare spawn command", () => {
    expect(spawnCommandForAgent(AgentKind.Claude)).toBe("spawn")
    for (const kind of AGENT_KINDS) {
      if (kind === AgentKind.Claude) continue
      expect(spawnCommandForAgent(kind)).toBe(`spawn_${kind}`)
    }
  })

  test("every agent kind has a display label", () => {
    for (const kind of AGENT_KINDS) {
      const label = agentDisplayName(kind)
      expect(label.length).toBeGreaterThan(0)
      expect(label.toLowerCase()).toBe(kind)
    }
  })
})
