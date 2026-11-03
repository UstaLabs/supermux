import { expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import { AGY_SYSTEM_COMMANDS, AntigravityCommandProvider } from "./antigravity"

function skill(dir: string, name: string, extra = "") {
  mkdirSync(join(dir, name), { recursive: true })
  writeFileSync(join(dir, name, "SKILL.md"), `---\nname: ${name}\ndescription: ${name} skill\n${extra}---\nbody\n`)
}

function sandbox() {
  const root = mkdtempSync(join(tmpdir(), "agy-cmds-"))
  const userHome = join(root, "home")
  mkdirSync(userHome, { recursive: true })
  return { root, userHome, done: () => rmSync(root, { recursive: true, force: true }) }
}

async function names(workdir: string, agentContext: unknown) {
  const list = await new AntigravityCommandProvider().list({ sessionName: "s", workdir, agentContext })
  return list!
}

test("agy's own system commands and built-in skills are always listed, with agy's descriptions", async () => {
  const s = sandbox()
  try {
    const list = await names(join(s.root, "empty"), { userHome: s.userHome })
    expect(new AntigravityCommandProvider().kind).toBe(AgentKind.Antigravity)
    expect(list.commands.map((c) => c.name)).toEqual(AGY_SYSTEM_COMMANDS.map((c) => c.name))
    expect(list.commands.map((c) => c.name)).toEqual(["goal", "compact", "schedule", "browser", "plan", "grill-me", "teamwork", "learn", "boost", "agy-customizations", "antigravity-guide"])
    expect(list.commands[0]).toMatchObject({ insertText: "/goal ", description: "Run until the specified goal is completely finished." })
    expect(list.projectIds).toEqual([])
  } finally { s.done() }
})

test("the folder's own skills (.agents, .agent, _agents, _agent) up to the git root, and only those count as the folder's", async () => {
  const s = sandbox()
  try {
    const repo = join(s.root, "repo"), work = join(repo, "pkg", "app")
    mkdirSync(join(repo, ".git"), { recursive: true })
    mkdirSync(work, { recursive: true })
    skill(join(work, ".agents", "skills"), "ws-agents")
    skill(join(work, ".agent", "skills"), "ws-agent")
    skill(join(work, "_agents", "skills"), "ws-underscore")
    skill(join(work, "_agent", "skills"), "ws-underscore-one")
    skill(join(repo, ".agents", "skills"), "repo-root")
    skill(join(s.root, ".agents", "skills"), "above-the-repo")
    const list = await names(work, { userHome: s.userHome })
    const own = ["ws-agents", "ws-agent", "ws-underscore", "ws-underscore-one", "repo-root"]
    expect(list.commands.slice(0, 5).map((c) => c.name)).toEqual(own)
    expect(list.commands.map((c) => c.name)).not.toContain("above-the-repo")
    expect(list.projectIds).toEqual(own.map((n) => `agent:${n}`))
  } finally { s.done() }
})

test("outside a git repository only the folder itself is read", async () => {
  const s = sandbox()
  try {
    const work = join(s.root, "plain", "sub")
    mkdirSync(work, { recursive: true })
    skill(join(work, ".agents", "skills"), "here")
    skill(join(s.root, "plain", ".agents", "skills"), "parent")
    const list = await names(work, { userHome: s.userHome })
    expect(list.commands.map((c) => c.name)).toContain("here")
    expect(list.commands.map((c) => c.name)).not.toContain("parent")
  } finally { s.done() }
})

test("the user's global skills and the session's plugin skills, after the folder's own; a hidden one is left out", async () => {
  const s = sandbox()
  try {
    const work = join(s.root, "w"), plugins = join(s.root, "plugin", "skills")
    mkdirSync(work, { recursive: true })
    skill(join(s.userHome, ".gemini", "config", "skills"), "user-skill")
    skill(join(s.userHome, ".gemini", "config", "skills"), "user-hidden", "disable-slash-command: true\n")
    skill(plugins, "plugin-skill")
    const list = await names(work, { userHome: s.userHome, skillsDirs: [plugins] })
    expect(list.commands.slice(0, 2).map((c) => c.name)).toEqual(["user-skill", "plugin-skill"])
    expect(list.commands.map((c) => c.name)).not.toContain("user-hidden")
    expect(list.projectIds).toEqual([])
  } finally { s.done() }
})

test("a name is listed once: the folder's skill wins over the user's, and any skill over agy's built-in of that name", async () => {
  const s = sandbox()
  try {
    const work = join(s.root, "w")
    skill(join(work, ".agents", "skills"), "shared")
    skill(join(s.userHome, ".gemini", "config", "skills"), "shared")
    skill(join(s.userHome, ".gemini", "config", "skills"), "goal")
    const list = await names(work, { userHome: s.userHome })
    expect(list.commands.filter((c) => c.name === "shared")).toHaveLength(1)
    expect(list.projectIds).toEqual(["agent:shared"])
    expect(list.commands.filter((c) => c.name === "goal")).toEqual([expect.objectContaining({ description: "goal skill" })])
  } finally { s.done() }
})
