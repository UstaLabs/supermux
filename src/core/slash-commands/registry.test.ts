import { test, expect } from "bun:test"
import { CommandRegistry } from "./registry"
import { agentCommand, type AgentCommandProvider } from "./types"

function stubProvider(names: string[], onCall: () => void): AgentCommandProvider {
  return { kind: "claude", async list() { onCall(); return names.map((n) => agentCommand({ name: n, sigil: "/" })) } }
}

const sessionStub = { name: "s1", kind: "claude" as const, workdir: "/tmp", muted: false, pluginSpawnArgs: [] }

test("get returns control immediately; agent commands fill in after refresh", async () => {
  let calls = 0
  const reg = new CommandRegistry({ providers: { claude: stubProvider(["verify"], () => calls++) }, resolveSession: () => sessionStub })
  expect(reg.get("s1").map((c) => c.name)).toEqual(["spawn", "model", "rename", "mute", "stop", "kill"])
  await reg.refresh("s1")
  expect(reg.get("s1").map((c) => c.name)).toContain("verify")
  expect(calls).toBe(1)
})

test("refresh dedupes concurrent calls for the same session", async () => {
  let calls = 0
  const reg = new CommandRegistry({ providers: { claude: stubProvider(["verify"], () => calls++) }, resolveSession: () => sessionStub })
  await Promise.all([reg.refresh("s1"), reg.refresh("s1")])
  expect(calls).toBe(1)
})

test("invalidate forces the next refresh to re-run the provider", async () => {
  let calls = 0
  const reg = new CommandRegistry({ providers: { claude: stubProvider(["verify"], () => calls++) }, resolveSession: () => sessionStub })
  await reg.refresh("s1")
  reg.invalidate("s1")
  await reg.refresh("s1")
  expect(calls).toBe(2)
})

test("refreshPreview caches agent commands for the launcher", async () => {
  let calls = 0
  const reg = new CommandRegistry({ providers: { claude: stubProvider(["verify"], () => calls++) }, resolveSession: () => sessionStub })
  await reg.refreshPreview({ kind: "claude", workdir: "/tmp", pluginSpawnArgs: [] })
  expect(reg.getPreview("claude", "/tmp").map((c) => c.name)).toEqual(["verify"])
  expect(reg.isPreviewResolved("claude", "/tmp")).toBe(true)
  expect(calls).toBe(1)
  await reg.refreshPreview({ kind: "claude", workdir: "/tmp", pluginSpawnArgs: [] })
  expect(calls).toBe(2)
})

test("onChange fires with the merged list after a refresh", async () => {
  const seen: string[][] = []
  const reg = new CommandRegistry({
    providers: { claude: stubProvider(["verify"], () => {}) },
    resolveSession: () => sessionStub,
    onChange: (_n, cmds) => seen.push(cmds.map((c) => c.name)),
  })
  await reg.refresh("s1")
  expect(seen).toHaveLength(1)
  expect(seen[0]).toContain("verify")
  expect(seen[0]).toContain("kill")
})

test("run and preview hand the grok fields through to the provider", async () => {
  // Pins the ctx plumbing for grok: the live ACP command list rides in via
  // resolveSession; a launcher preview (no adapter yet) gets the skills dirs.
  const ctxs: any[] = []
  const grokProvider: AgentCommandProvider = { kind: "grok", async list(ctx) { ctxs.push(ctx); return [] } }
  const grokCommands = [{ name: "soul", _meta: { scope: "user", path: "/p/skills/soul/SKILL.md" } }]
  const reg = new CommandRegistry({
    providers: { grok: grokProvider },
    resolveSession: () => ({ name: "g1", kind: "grok" as const, workdir: "/tmp", muted: false, pluginSpawnArgs: [], grokCommands, grokSkillsDirs: ["/p/skills"] }),
  })
  await reg.refresh("g1")
  expect(ctxs[0].grokCommands).toEqual(grokCommands)
  expect(ctxs[0].grokSkillsDirs).toEqual(["/p/skills"])
  await reg.refreshPreview({ kind: "grok", workdir: "/tmp", pluginSpawnArgs: [], grokSkillsDirs: ["/p/skills"] })
  expect(ctxs[1].grokCommands).toBeUndefined()
  expect(ctxs[1].grokSkillsDirs).toEqual(["/p/skills"])
})
