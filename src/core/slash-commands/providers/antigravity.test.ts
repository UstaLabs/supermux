import { expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import { AntigravityCommandProvider } from "./antigravity"
import type { ProviderCtx } from "../types"

test("lists skills from the context's skills dirs as / commands", async () => {
  const root = mkdtempSync(join(tmpdir(), "agy-skills-"))
  try {
    mkdirSync(join(root, "skill-a"), { recursive: true })
    writeFileSync(join(root, "skill-a", "SKILL.md"), "---\nname: skill-a\ndescription: d\n---\nbody\n")
    const provider = new AntigravityCommandProvider()
    expect(provider.kind).toBe(AgentKind.Antigravity)
    const cmds = await provider.list({ agentContext: { skillsDirs: [root] } } as unknown as ProviderCtx)
    expect(cmds.map((c) => c.insertText)).toEqual(["/skill-a "])
    expect(cmds[0]!.sigil).toBe("/")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("empty context lists nothing", async () => {
  const provider = new AntigravityCommandProvider()
  expect(await provider.list({} as unknown as ProviderCtx)).toEqual([])
})
