import { test, expect } from "bun:test"
import { readEnvironmentMd } from "../../src/core/agents/environment"

test("readEnvironmentMd returns the doc content", () => {
  const content = readEnvironmentMd()
  expect(content).toContain("You are running inside supermux")
  expect(content).toContain("Which channel am I talking to?")
  // Skills reach Claude via the agentmux plugin host (--plugin-dir), not the old
  // hand-managed ~/.claude/skills symlinks — so environment.md must not mention them.
  expect(content).not.toContain("~/.claude/skills")
})
