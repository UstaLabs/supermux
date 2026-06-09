import { test, expect } from "bun:test"
import { existsSync } from "fs"
import { ENVIRONMENT_MD_PATH, readEnvironmentMd } from "../../src/core/agents/environment"

test("ENVIRONMENT_MD_PATH points at an existing prompts/environment.md", () => {
  expect(ENVIRONMENT_MD_PATH.endsWith("prompts/environment.md")).toBe(true)
  expect(existsSync(ENVIRONMENT_MD_PATH)).toBe(true)
})

test("readEnvironmentMd returns the doc content", () => {
  const content = readEnvironmentMd()
  expect(content).toContain("You are running inside supermux")
  expect(content).toContain("Which channel am I talking to?")
  // Skills reach Claude via the agentmux plugin host (--plugin-dir), not the old
  // hand-managed ~/.claude/skills symlinks — so environment.md must not mention them.
  expect(content).not.toContain("~/.claude/skills")
})
