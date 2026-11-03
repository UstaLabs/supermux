import { expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import { AntigravityCommandProvider } from "./antigravity"

test("lists the skills in the context's skills dirs as / commands, none of them folder-only", async () => {
  const root = mkdtempSync(join(tmpdir(), "agy-skills-"))
  try {
    mkdirSync(join(root, "skill-a"), { recursive: true })
    writeFileSync(join(root, "skill-a", "SKILL.md"), "---\nname: skill-a\ndescription: d\n---\nbody\n")
    const provider = new AntigravityCommandProvider()
    expect(provider.kind).toBe(AgentKind.Antigravity)
    const list = await provider.list({ sessionName: "s", workdir: "/w", agentContext: { skillsDirs: [root] } })
    expect(list?.commands.map((c) => c.insertText)).toEqual(["/skill-a "])
    expect(list?.projectIds).toEqual([])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("an empty context lists nothing", async () => {
  expect(await new AntigravityCommandProvider().list({ sessionName: "s", workdir: "/w" })).toEqual({ commands: [], projectIds: [] })
})
