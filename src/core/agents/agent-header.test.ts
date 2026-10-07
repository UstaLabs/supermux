import { test, expect } from "bun:test"
import { buildAgentHeader } from "./agent-header"
import { AgentKind } from "../../shared/agents"

const worker = (agent: AgentKind = AgentKind.Codex) => buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app", agent })

test("header names the session and says to answer with that name", () => {
  expect(worker()).toContain('You are "alpha"')
  expect(worker()).toContain('answer "alpha"')
})

test("header states the role", () => {
  expect(worker().toLowerCase()).toContain("worker")
  expect(buildAgentHeader({ name: "ana", role: "main", workdir: "/srv/app", agent: AgentKind.Claude }).toLowerCase()).toContain("personal-assistant")
})

test("header: text is the reply, every turn ends with text, files go through attach", () => {
  const h = worker()
  expect(h.toLowerCase()).toContain("normal assistant output is your reply")
  expect(h).toContain("end every turn with a text message")
  expect(h).toContain("`attach`")
  expect(h).toContain("files[]")
})

test("header memory rule is relevance-triggered and digest-first", () => {
  const h = worker()
  expect(h.toLowerCase()).toContain("when your task touches")
  expect(h).toContain("domains/<topic>.digest.md")
  expect(h).toContain("Never edit `*.digest.md`")
})

test("header includes the working directory for scope", () => {
  expect(worker()).toContain("/srv/app")
})

test("skills rule is per agent: Skill tool, skill tool, SKILL.md, or none for cursor", () => {
  expect(worker(AgentKind.Claude)).toContain("Skill tool")
  expect(worker(AgentKind.OpenCode)).toContain("native `skill` tool")
  expect(worker(AgentKind.Codex)).toContain("SKILL.md")
  expect(worker(AgentKind.Codex)).toContain("<plugin>:<name>")
  expect(worker(AgentKind.Codex).toLowerCase()).toContain("never claim")
  expect(worker(AgentKind.Cursor)).not.toContain("SKILLS:")
})

test("worker header nudges the agent to rename its session; a PA does not", () => {
  expect(worker()).toContain("rename_session")
  expect(buildAgentHeader({ name: "ana", role: "personal_assistant", workdir: "/srv/app", agent: AgentKind.Claude })).not.toContain("rename_session")
})
