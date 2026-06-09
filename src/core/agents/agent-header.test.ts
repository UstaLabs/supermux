import { test, expect } from "bun:test"
import { buildAgentHeader } from "./agent-header"

test("header names the session", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h).toContain('"alpha"')
})

test("header states the worker role", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h.toLowerCase()).toContain("worker")
})

test("header states the personal-assistant role for main", () => {
  const h = buildAgentHeader({ name: "ana", role: "main", workdir: "/srv/app" })
  expect(h.toLowerCase()).toContain("personal-assistant")
})

test("header tells streamed agents reply is file-only", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h.toLowerCase()).toContain("reply tool")
  expect(h.toLowerCase()).toContain("only")
  expect(h.toLowerCase()).toContain("files[]")
  expect(h.toLowerCase()).toContain("normal assistant output")
})

test("header memory rule is relevance-triggered, not every-task", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h).toContain("domains/")
  expect(h.toLowerCase()).toContain("when your task touches")
  expect(h.toLowerCase()).not.toContain("every task")
})

test("header includes the working directory for scope", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h).toContain("/srv/app")
})

test("header points skills at the plugin host, namespaced <plugin>:<name>", () => {
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h).toContain("plugin")
  expect(h).toContain("<plugin>:<name>")
  // The retired hand-managed skills path must be gone.
  expect(h).not.toContain("~/.mux/skills/")
})

test("header tells the agent to actually read SKILL.md and never fake applying a skill", () => {
  // Codex/Cursor have no Skill tool — the load-bearing instruction is to READ
  // the SKILL.md file, not pretend. Regression guard for the codex-3 fake.
  const h = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(h).toContain("SKILL.md")
  expect(h.toLowerCase()).toContain("never claim")
})

test("worker header nudges the agent to rename its session; main does not", () => {
  const worker = buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app" })
  expect(worker).toContain("rename_session")
  expect(worker).toContain('"alpha"')
  const main = buildAgentHeader({ name: "ana", role: "main", workdir: "/srv/app" })
  expect(main).not.toContain("rename_session")
})
