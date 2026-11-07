import { test, expect } from "bun:test"
import { buildAgentHeader } from "./agent-header"
import { AgentKind } from "../../shared/agents"

const worker = (agent: AgentKind = AgentKind.Codex) => buildAgentHeader({ name: "alpha", role: "worker", workdir: "/srv/app", agent })

test("header names the session and says to answer with that name", () => {
  expect(worker()).toContain('You are "alpha"')
  expect(worker()).toContain('answer "alpha"')
})

const agentHeader = () => buildAgentHeader({ name: "Dockie", role: "personal_assistant", workdir: "/home/u/.mux/workspace/Dockie", agent: AgentKind.Claude, id: "3f9c", muxHome: "/home/u/.mux" })

test("header states the role", () => {
  expect(worker().toLowerCase()).toContain("worker")
})

test("agent header: name + id line, memory, user, home; no scope, no naming", () => {
  const h = agentHeader()
  expect(h.startsWith('You are "Dockie", an agent in supermux. Your agent id is 3f9c. When asked your name, answer "Dockie".')).toBe(true)
  expect(h).toContain("- YOUR MEMORY:")
  expect(h).toContain("/home/u/.mux/agent-memory/3f9c.digest.md")
  expect(h).toContain("/home/u/.mux/agent-memory/3f9c.md")
  expect(h).toContain("stays yours if you are renamed")
  expect(h).toContain("- THE USER: `/home/u/.mux/personal/`")
  expect(h).toContain("- HOME: Your working directory is `/home/u/.mux/workspace/Dockie`")
  expect(h).not.toContain("SCOPE:")
  expect(h).not.toContain("NAMING:")
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

test("the memory rule reads the history every session appends to: all of it when there is no digest (the curator is off by default)", () => {
  for (const h of [worker(), agentHeader()]) {
    const rule = h.split("\n").find((l) => l.startsWith("- MEMORY:"))!
    expect(rule).toContain("`~/.mux/domains/<topic>.digest.md`")
    expect(rule).toContain("if it exists")
    expect(rule).toContain("`~/.mux/domains/<topic>.md`")
    expect(rule).toContain("all of it when there is no digest")
    expect(rule).toContain("newest entries")
  }
})

test("an Agent reads its own history too: all of it while its digest is empty", () => {
  const history = agentHeader().split("\n").find((l) => l.includes("`/home/u/.mux/agent-memory/3f9c.md`"))!
  expect(history).toContain("read all of it while the digest is empty")
  expect(history).toContain("newest entries")
})

test("header includes the working directory for scope", () => {
  expect(worker()).toContain("/srv/app")
})

test("skills rule is per agent: Skill tool, skill tool, SKILL.md", () => {
  expect(worker(AgentKind.Claude)).toContain("Skill tool")
  expect(worker(AgentKind.OpenCode)).toContain("native `skill` tool")
  expect(worker(AgentKind.Codex)).toContain("SKILL.md")
  expect(worker(AgentKind.Codex)).toContain("<plugin>:<name>")
  expect(worker(AgentKind.Codex).toLowerCase()).toContain("never claim")
  // #102: Cursor reads plugin skills from its session HOME's .cursor/skills.
  expect(worker(AgentKind.Cursor)).toContain("SKILLS:")
  expect(worker(AgentKind.Cursor)).toContain("SKILL.md")
})

test("worker header keeps scope and naming, and never mentions personal/ or agent memory", () => {
  const h = worker()
  expect(h).toContain("SCOPE:")
  expect(h).toContain("NAMING:")
  expect(h).toContain("rename_session")
  expect(h).not.toContain("personal/")
  expect(h).not.toContain("agent-memory")
})
