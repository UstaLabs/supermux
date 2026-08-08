import { test, expect } from "bun:test"
import { mapCodexSkills, CodexCommandProvider } from "./codex"

const sample = { data: [{ cwd: "/tmp", skills: [
  { name: "mux:browser", description: "control chrome", path: "/x", scope: "user", enabled: true },
  { name: "mux:soul", description: "set up PA identity", path: "/soul", scope: "user", enabled: true },
  { name: "mux:hidden", description: "no", path: "/y", scope: "user", enabled: false },
]}]}

test("mapCodexSkills flattens, filters disabled, uses $ sigil", () => {
  const cmds = mapCodexSkills(sample)
  expect(cmds.map((c) => c.name)).toEqual(["mux:browser", "mux:soul"])
  expect(cmds[0]).toMatchObject({ family: "agent", sigil: "$", insertText: "$mux:browser ", description: "control chrome" })
})

test("CodexCommandProvider calls skills/list on the client", async () => {
  const provider = new CodexCommandProvider()
  const client: any = { request: async (m: string) => (m === "skills/list" ? sample : (() => { throw new Error("unexpected " + m) })()) }
  const cmds = await provider.list({ sessionName: "s", workdir: "/tmp", pluginSpawnArgs: [], agentContext: client })
  expect(cmds.map((c) => c.name)).toEqual(["mux:browser", "mux:soul"])
})

test("CodexCommandProvider returns [] without a client", async () => {
  expect(await new CodexCommandProvider().list({ sessionName: "s", workdir: "/tmp", pluginSpawnArgs: [] })).toEqual([])
})
