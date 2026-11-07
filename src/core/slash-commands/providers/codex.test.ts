import { test, expect } from "bun:test"
import { mapCodexSkills, CodexCommandProvider } from "./codex"

const sample = { data: [{ cwd: "/w/proj", skills: [
  { name: "mux:browser", description: "control chrome", path: "/x", scope: "user", enabled: true },
  { name: "repo-skill", description: "this repo's own", path: "/w/proj/.agents/skills/repo-skill", scope: "repo", enabled: true },
  { name: "mux:hidden", description: "no", path: "/y", scope: "user", enabled: false },
]}]}

test("mapCodexSkills: enabled skills as $ entries; repo skills are the folder's own", () => {
  const list = mapCodexSkills(sample)
  expect(list.commands.map((c) => c.name)).toEqual(["mux:browser", "repo-skill"])
  expect(list.commands[0]).toMatchObject({ family: "agent", sigil: "$", insertText: "$mux:browser ", description: "control chrome" })
  expect(list.projectIds).toEqual(["agent:repo-skill"])
})

test("CodexCommandProvider asks the app-server about the session's own folder", async () => {
  const calls: unknown[] = []
  const client = { request: async (method: string, params: unknown) => { calls.push([method, params]); return sample as any } }
  const list = await new CodexCommandProvider().list({ sessionName: "s", workdir: "/w/proj", agentContext: client })
  expect(calls).toEqual([["skills/list", { cwds: ["/w/proj"] }]])
  expect(list?.commands.map((c) => c.name)).toEqual(["mux:browser", "repo-skill"])
})

test("CodexCommandProvider reports nothing without an app-server, or when the call fails", async () => {
  const p = new CodexCommandProvider()
  expect(await p.list({ sessionName: "s", workdir: "/w" })).toBeUndefined()
  const failing = { request: async () => { throw new Error("gone") } }
  expect(await p.list({ sessionName: "s", workdir: "/w", agentContext: failing })).toBeUndefined()
})

test("mapCodexSkills: the source Codex gives — a plugin by its prefix, then the skill's scope (#56)", () => {
  const list = mapCodexSkills({ data: [{ skills: [
    { name: "mux:browser", scope: "user" },
    { name: "imagegen", scope: "system" },
    { name: "repo-skill", scope: "repo" },
    { name: "mine", scope: "user" },
    { name: "managed", scope: "admin" },
  ] }] })
  const by = (name: string) => list.commands.find((c) => c.name === name)!
  expect(by("mux:browser")).toMatchObject({ source: "plugin", plugin: "mux" })
  expect(by("imagegen")).toMatchObject({ source: "builtin" })
  expect(by("repo-skill")).toMatchObject({ source: "project" })
  expect(by("mine")).toMatchObject({ source: "user" })
  expect(by("managed").source).toBeUndefined()
})
