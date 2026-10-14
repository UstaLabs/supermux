import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { ANTIGRAVITY_CONTEXT, antigravityContextLaunch, cursorPreamble, sessionPreamble } from "../src/context/agents.js"
import type { LaunchContext } from "../src/context/types.js"
import { prepareAntigravityEnvironment } from "../src/environment/index.js"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
const json = (path: string) => JSON.parse(readFileSync(path, "utf8"))
const SHIM = { name: "mux-shim", command: "shim", args: ["--x"], env: { T: "1" } }

async function rig() {
  const base = mkdtempSync(join(tmpdir(), "agy-ctx-")); dirs.push(base)
  const user = join(base, "user"), shadow = join(base, "shadow")
  mkdirSync(join(user, ".gemini", "config", "plugins", "user-plugin"), { recursive: true })
  writeFileSync(join(user, ".gemini", "config", "mcp_config.json"), JSON.stringify({ mcpServers: { mine: { command: "mine" } } }))
  await prepareAntigravityEnvironment({ home: shadow, userHome: user, platform: process.platform })
  const skills = join(base, "skills"); mkdirSync(join(skills, "tidy"), { recursive: true }); writeFileSync(join(skills, "tidy", "SKILL.md"), "---\nname: tidy\n---\n")
  const full = join(base, "full")
  mkdirSync(join(full, ".claude-plugin"), { recursive: true }); mkdirSync(join(full, "skills", "lint"), { recursive: true }); mkdirSync(join(full, "hooks"))
  writeFileSync(join(full, "hooks", "hooks.json"), "{}")
  writeFileSync(join(full, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "Full Kit" }))
  writeFileSync(join(full, ".mcp.json"), JSON.stringify({ mcpServers: {
    kit: { command: "${CLAUDE_PLUGIN_ROOT}/bin/kit", args: ["--root", "${CLAUDE_PLUGIN_ROOT}"] },
    web: { type: "http", url: "https://example.invalid/mcp" },
  } }))
  const mcpOnly = join(base, "mcp-only"); mkdirSync(mcpOnly)
  writeFileSync(join(mcpOnly, ".mcp.json"), JSON.stringify({ mcpServers: { "mux-shim": { command: "theirs" }, extra: { command: "extra" } } }))
  const hooksOnly = join(base, "hooks-only"); mkdirSync(join(hooksOnly, "hooks"), { recursive: true }); writeFileSync(join(hooksOnly, "hooks", "hooks.json"), "{}")
  const launch = (extra: Partial<LaunchContext> = {}): LaunchContext => ({
    skills: [], plugins: [], mcpServers: [], directory: join(base, "ctx"), launch: "create", dropped: [], fingerprint: "f", ...extra,
  })
  return {
    base, user, shadow, skills, full, mcpOnly, hooksOnly, launch, o: { shadowHome: shadow, userHome: user },
    plugins: join(shadow, ".gemini", "config", "plugins"), mcpFile: join(shadow, ".gemini", "config", "mcp_config.json"),
  }
}

test("context MCP servers land in the shadow mcp_config.json as supermux-*; aliases map back; the real file is untouched", async () => {
  const r = await rig()
  const out = antigravityContextLaunch(r.launch({ mcpServers: [SHIM] }), r.o)
  expect(out.serverAliases).toEqual({ "supermux-mux-shim": "mux-shim" })
  expect(out.files[0]).toBe(r.mcpFile)
  expect(json(r.mcpFile).mcpServers).toEqual({ mine: { command: "mine" }, "supermux-mux-shim": { command: "shim", args: ["--x"], env: { T: "1" } } })
  expect(json(join(r.user, ".gemini", "config", "mcp_config.json"))).toEqual({ mcpServers: { mine: { command: "mine" } } })
})

test("skills and plugins become supermux-* wrappers; plugin servers are translated, a clash keeps the context server", async () => {
  const r = await rig()
  const out = antigravityContextLaunch(r.launch({ skills: [r.skills], plugins: [r.full, r.mcpOnly], mcpServers: [SHIM] }), r.o)
  expect(readdirSync(r.plugins).sort()).toEqual(["supermux-Full-Kit", "supermux-mcp-only", "supermux-skills-1", "user-plugin"])
  expect(readlinkSync(join(r.plugins, "supermux-skills-1", "skills"))).toBe(r.skills)
  expect(existsSync(join(r.plugins, "supermux-skills-1", "mcp_config.json"))).toBe(false)
  expect(json(join(r.plugins, "supermux-Full-Kit", "plugin.json"))).toEqual({ name: "supermux-Full-Kit" })
  expect(readlinkSync(join(r.plugins, "supermux-Full-Kit", "skills"))).toBe(join(r.full, "skills"))
  expect(json(join(r.plugins, "supermux-Full-Kit", "mcp_config.json"))).toEqual({ mcpServers: { kit: { command: `${r.full}/bin/kit`, args: ["--root", r.full], env: {} } } })
  expect(json(join(r.plugins, "supermux-mcp-only", "mcp_config.json"))).toEqual({ mcpServers: { extra: { command: "extra", args: [], env: {} } } })
  expect(existsSync(join(r.plugins, "supermux-mcp-only", "skills"))).toBe(false)
  expect(out.files).toEqual([r.mcpFile, ...["supermux-skills-1", "supermux-Full-Kit", "supermux-mcp-only"].map(n => join(r.plugins, n))])
  expect(out.drops).toEqual([])
})

test("a relaunch without context clears every generated server and wrapper; user entries stay", async () => {
  const r = await rig()
  antigravityContextLaunch(r.launch({ skills: [r.skills], plugins: [r.full], mcpServers: [SHIM] }), r.o)
  expect(antigravityContextLaunch(undefined, r.o)).toEqual({ serverAliases: {}, files: [r.mcpFile], drops: [] })
  expect(readdirSync(r.plugins)).toEqual(["user-plugin"])
  expect(json(r.mcpFile).mcpServers).toEqual({ mine: { command: "mine" } })
})

test("items the core dropped are not applied; an unusable plugin the core kept is a launch drop", async () => {
  const r = await rig()
  const out = antigravityContextLaunch(r.launch({
    skills: [r.skills], plugins: [r.hooksOnly], mcpServers: [{ name: "gone", command: "g", args: [], env: {} }],
    dropped: [{ kind: "skills", item: r.skills, reason: "x" }, { kind: "plugins", item: r.hooksOnly, reason: "x" }, { kind: "mcpServers", item: "gone", reason: "x" }],
  }), r.o)
  expect(out).toEqual({ serverAliases: {}, files: [r.mcpFile], drops: [] })
  expect(readdirSync(r.plugins)).toEqual(["user-plugin"])
  expect(antigravityContextLaunch(r.launch({ plugins: [r.hooksOnly] }), r.o).drops)
    .toEqual([{ kind: "plugins", item: r.hooksOnly, reason: expect.stringMatching(/neither/) }])
})

test("ANTIGRAVITY_CONTEXT.drops: unmappable plugin parts, server clashes, plugins with nothing agy can take", async () => {
  const r = await rig()
  const drops = ANTIGRAVITY_CONTEXT.drops!({ skills: [], plugins: [r.full, r.mcpOnly, r.hooksOnly], mcpServers: [{ name: "mux-shim", command: "s", args: [], env: {} }] })
  expect(drops.map(d => d.item)).toEqual([`${r.full} (hooks)`, `${r.full} (mcpServers.web)`, `${r.mcpOnly} (mcpServers.mux-shim)`, `${r.hooksOnly} (hooks)`, r.hooksOnly])
})

test("capabilities: everything supported, every change a reload; sessionPreamble is the renamed cursorPreamble", () => {
  expect(Object.values(ANTIGRAVITY_CONTEXT.capabilities).map(c => c.support)).toEqual(["supported", "supported", "supported", "supported"])
  for (const kind of ["skills", "plugins", "mcpServers"] as const) expect(ANTIGRAVITY_CONTEXT.update![kind]).toMatchObject({ add: "reload", remove: "reload" })
  expect(cursorPreamble).toBe(sessionPreamble)
  const text = sessionPreamble("Be terse.")
  expect(text.startsWith("<session-instructions>\n")).toBe(true)
  expect(text.endsWith("\nBe terse.\n</session-instructions>")).toBe(true)
})
