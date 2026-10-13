import { afterEach, expect, test } from "bun:test"
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, renameSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { createHash } from "node:crypto"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { prepareAntigravityEnvironment, linkAntigravityCli, shareAntigravityHistory, writeAntigravityMcpConfig, writeAntigravityPlugins } from "../src/environment/index.js"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
function fakeUser() {
  const base = mkdtempSync(join(tmpdir(), "agy-env-")); dirs.push(base)
  const user = join(base, "user"), shadow = join(base, "state", "home")
  mkdirSync(join(user, ".ssh"), { recursive: true }); writeFileSync(join(user, ".gitconfig"), "[user]\nname=x\n")
  mkdirSync(join(user, ".gemini", "antigravity-cli", "conversations"), { recursive: true })
  writeFileSync(join(user, ".gemini", "antigravity-cli", "antigravity-oauth-token"), "{}")
  writeFileSync(join(user, ".gemini", "GEMINI.md"), "global rules\n")
  mkdirSync(join(user, ".gemini", "config", "plugins", "user-plugin"), { recursive: true })
  mkdirSync(join(user, ".gemini", "config", "projects"), { recursive: true })
  writeFileSync(join(user, ".gemini", "config", "mcp_config.json"), JSON.stringify({ mcpServers: { mine: { command: "mine" } }, extra: 1 }))
  return { base, user, shadow }
}
/** Every path under root → type + symlink target / content hash / mode. */
function snapshot(root: string): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (p: string) => {
    const st = lstatSync(p)
    if (st.isSymbolicLink()) { out[p] = `link:${readlinkSync(p)}`; return }
    if (st.isDirectory()) { out[p] = `dir:${st.mode}`; for (const e of readdirSync(p)) walk(join(p, e)); return }
    out[p] = `file:${st.mode}:${createHash("sha256").update(readFileSync(p)).digest("hex")}`
  }
  if (existsSync(root)) walk(root)
  return out
}
const spec = (u: ReturnType<typeof fakeUser>) => ({ home: u.shadow, userHome: u.user, platform: process.platform })

test("links every top-level entry except .gemini; .gemini is a real dir with generated config", async () => {
  const u = fakeUser(); const env = await prepareAntigravityEnvironment(spec(u))
  expect(env.env.HOME).toBe(u.shadow); expect(env.credentials).toBe("canonical")
  expect(readlinkSync(join(u.shadow, ".ssh"))).toBe(join(u.user, ".ssh"))
  expect(readlinkSync(join(u.shadow, ".gitconfig"))).toBe(join(u.user, ".gitconfig"))
  expect(lstatSync(join(u.shadow, ".gemini")).isSymbolicLink()).toBe(false)
  expect(readlinkSync(join(u.shadow, ".gemini", "antigravity-cli"))).toBe(join(u.user, ".gemini", "antigravity-cli"))
  expect(readlinkSync(join(u.shadow, ".gemini", "GEMINI.md"))).toBe(join(u.user, ".gemini", "GEMINI.md"))
  expect(lstatSync(join(u.shadow, ".gemini", "config")).isSymbolicLink()).toBe(false)
  expect(readlinkSync(join(u.shadow, ".gemini", "config", "projects"))).toBe(join(u.user, ".gemini", "config", "projects"))
  expect(readlinkSync(join(u.shadow, ".gemini", "config", "plugins", "user-plugin"))).toBe(join(u.user, ".gemini", "config", "plugins", "user-plugin"))
  const mcp = join(u.shadow, ".gemini", "config", "mcp_config.json")
  expect(lstatSync(mcp).isSymbolicLink()).toBe(false)
  expect(JSON.parse(readFileSync(mcp, "utf8"))).toEqual({ mcpServers: { mine: { command: "mine" } }, extra: 1 })
})

test("the real ~/.gemini (and HOME) is byte-identical after prepare, mcp/plugin writes and cli relinks", async () => {
  const u = fakeUser(); const before = snapshot(u.user)
  await prepareAntigravityEnvironment(spec(u)); await prepareAntigravityEnvironment(spec(u))
  writeAntigravityMcpConfig({ shadowHome: u.shadow, userHome: u.user, servers: [{ name: "mux-shim", command: "shim", args: [], env: {} }] })
  writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [{ name: "supermux-skills-1", skills: join(u.base, "skills"), mcpServers: [] }] })
  const acct = join(u.base, "acct"); mkdirSync(acct); linkAntigravityCli(u.shadow, acct, u.user)
  shareAntigravityHistory(acct, join(u.user, ".gemini", "antigravity-cli"))
  expect(snapshot(u.user)).toEqual(before)
})

test("refresh adds new entries, prunes stale links, keeps content the agent created", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u))
  mkdirSync(join(u.user, ".newtool")); rmSync(join(u.user, ".ssh"), { recursive: true })
  writeFileSync(join(u.shadow, ".agent-made"), "x")
  await prepareAntigravityEnvironment(spec(u))
  expect(readlinkSync(join(u.shadow, ".newtool"))).toBe(join(u.user, ".newtool"))
  expect(existsSync(join(u.shadow, ".ssh"))).toBe(false)
  expect(readFileSync(join(u.shadow, ".agent-made"), "utf8")).toBe("x")
})

test("a symlinked mcp_config.json in the shadow is replaced, never written through", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u))
  const shadowMcp = join(u.shadow, ".gemini", "config", "mcp_config.json"); const real = join(u.user, ".gemini", "config", "mcp_config.json")
  rmSync(shadowMcp); symlinkSync(real, shadowMcp); const before = readFileSync(real, "utf8")
  writeAntigravityMcpConfig({ shadowHome: u.shadow, userHome: u.user, servers: [{ name: "x", command: "x", args: [], env: {} }] })
  expect(lstatSync(shadowMcp).isSymbolicLink()).toBe(false); expect(readFileSync(real, "utf8")).toBe(before)
})

test("mcp merge: user servers kept; ours supermux-*; a clash renames ours, never theirs", () => {
  const u = fakeUser()
  writeFileSync(join(u.user, ".gemini", "config", "mcp_config.json"), JSON.stringify({ mcpServers: { "supermux-mux-shim": { command: "theirs" } } }))
  mkdirSync(join(u.shadow, ".gemini", "config"), { recursive: true })
  const r = writeAntigravityMcpConfig({ shadowHome: u.shadow, userHome: u.user, servers: [{ name: "mux-shim", command: "ours", args: ["-a"], env: { K: "v" } }] })
  expect(r.aliases).toEqual({ "supermux-mux-shim-2": "mux-shim" })
  expect(JSON.parse(readFileSync(r.path, "utf8")).mcpServers).toEqual({
    "supermux-mux-shim": { command: "theirs" }, "supermux-mux-shim-2": { command: "ours", args: ["-a"], env: { K: "v" } } })
})

test("plugins: supermux-* wrappers rewritten, user plugin links kept", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u))
  const skills = join(u.base, "skills"); mkdirSync(skills)
  writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [{ name: "supermux-old", mcpServers: [] }] })
  const [p] = writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [{ name: "supermux-skills-1", skills, mcpServers: [{ name: "s", command: "c", args: [], env: {} }] }] })
  const dir = join(u.shadow, ".gemini", "config", "plugins")
  expect(readdirSync(dir).sort()).toEqual(["supermux-skills-1", "user-plugin"])
  expect(JSON.parse(readFileSync(join(p!, "plugin.json"), "utf8"))).toEqual({ name: "supermux-skills-1" })
  expect(readlinkSync(join(p!, "skills"))).toBe(skills)
  expect(JSON.parse(readFileSync(join(p!, "mcp_config.json"), "utf8"))).toEqual({ mcpServers: { s: { command: "c", args: [], env: {} } } })
})

test("no real ~/.gemini at all: shadow gets a dangling cli link and nothing real is created", async () => {
  const u = fakeUser(); rmSync(join(u.user, ".gemini"), { recursive: true })
  const env = await prepareAntigravityEnvironment(spec(u))
  expect(env.credentials).toBe("none"); expect(existsSync(join(u.user, ".gemini"))).toBe(false)
  expect(readlinkSync(join(u.shadow, ".gemini", "antigravity-cli"))).toBe(join(u.user, ".gemini", "antigravity-cli"))
})

test("refuses a shadow home that is HOME or inside ~/.gemini", async () => {
  const u = fakeUser()
  await expect(prepareAntigravityEnvironment({ ...spec(u), home: u.user })).rejects.toThrow(/shadow home/)
  await expect(prepareAntigravityEnvironment({ ...spec(u), home: join(u.user, ".gemini", "x") })).rejects.toThrow(/shadow home/)
})

test("shareAntigravityHistory links existing real history into an account dir only", () => {
  const u = fakeUser(); const acct = join(u.base, "acct"); mkdirSync(join(acct, "brain"), { recursive: true })
  shareAntigravityHistory(acct, join(u.user, ".gemini", "antigravity-cli"))
  expect(readlinkSync(join(acct, "conversations"))).toBe(join(u.user, ".gemini", "antigravity-cli", "conversations"))
  expect(lstatSync(join(acct, "brain")).isSymbolicLink()).toBe(false) // real brain/ missing → no link, no creation
  expect(existsSync(join(u.user, ".gemini", "antigravity-cli", "brain"))).toBe(false)
})

/** Paths whose snapshot entry changed. */
function diff(before: Record<string, string>, after: Record<string, string>): string[] {
  return Object.keys({ ...before, ...after }).filter((k) => before[k] !== after[k])
}

test("C1: a shadow home that is a symlink to the real HOME is refused and nothing real is touched", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u))
  const dotCfg = join(u.base, "dotfiles-config"); rmSync(join(u.user, ".gemini", "config"), { recursive: true })
  mkdirSync(dotCfg); writeFileSync(join(dotCfg, "mcp_config.json"), "{}"); symlinkSync(dotCfg, join(u.user, ".gemini", "config"))
  rmSync(join(u.user, ".gemini", "antigravity-cli"), { recursive: true })
  const before = { ...snapshot(u.user), ...snapshot(dotCfg) }
  rmSync(u.shadow, { recursive: true }); symlinkSync(u.user, u.shadow)
  await expect(prepareAntigravityEnvironment(spec(u))).rejects.toThrow(/shadow home/)
  expect(diff(before, { ...snapshot(u.user), ...snapshot(dotCfg) })).toEqual([])
})

test("C1: a shadow home symlinked elsewhere, or an ancestor of HOME, is refused", async () => {
  const u = fakeUser(); const elsewhere = join(u.base, "elsewhere"); mkdirSync(elsewhere)
  mkdirSync(join(u.base, "state")); symlinkSync(elsewhere, u.shadow)
  await expect(prepareAntigravityEnvironment(spec(u))).rejects.toThrow(/symlink/)
  expect(readdirSync(elsewhere)).toEqual([])
  const before = snapshot(u.user)
  await expect(prepareAntigravityEnvironment({ ...spec(u), home: u.base })).rejects.toThrow(/shadow home/)
  await expect(prepareAntigravityEnvironment({ ...spec(u), home: join(u.user, ".gemini") })).rejects.toThrow(/shadow home/)
  expect(snapshot(u.user)).toEqual(before)
})

test("C1: a dotfiles-symlinked real ~/.gemini still counts as the real ~/.gemini", async () => {
  const u = fakeUser(); const dot = join(u.base, "dotfiles-gemini")
  renameSync(join(u.user, ".gemini"), dot); symlinkSync(dot, join(u.user, ".gemini"))
  const before = snapshot(dot)
  await expect(prepareAntigravityEnvironment({ ...spec(u), home: join(dot, "shadow") })).rejects.toThrow(/shadow home/)
  expect(snapshot(dot)).toEqual(before)
})

test("I1: shareAntigravityHistory refuses an account dir that is (inside) the real ~/.gemini or a symlink", () => {
  const u = fakeUser(); const realCli = join(u.user, ".gemini", "antigravity-cli")
  mkdirSync(join(realCli, "brain")); const before = snapshot(u.user)
  expect(() => shareAntigravityHistory(realCli, realCli)).toThrow(/~\/.gemini/)
  expect(() => shareAntigravityHistory(join(u.user, ".gemini", "config"), realCli)).toThrow(/~\/.gemini/)
  const link = join(u.base, "acct-link"); symlinkSync(realCli, link)
  expect(() => shareAntigravityHistory(link, realCli)).toThrow(/symlink/)
  expect(snapshot(u.user)).toEqual(before)
})

test("I2: writers refuse a shadowHome that is the real HOME (or a symlink to it)", () => {
  const u = fakeUser(); const before = snapshot(u.user)
  const link = join(u.base, "home-link"); symlinkSync(u.user, link)
  for (const shadowHome of [u.user, link]) {
    expect(() => writeAntigravityMcpConfig({ shadowHome, userHome: u.user, servers: [] })).toThrow(/shadow home/)
    expect(() => writeAntigravityPlugins({ shadowHome, userHome: u.user, wrappers: [{ name: "supermux-a", mcpServers: [] }] })).toThrow(/shadow home/)
    expect(() => linkAntigravityCli(shadowHome, "/nowhere", u.user)).toThrow(/shadow home/)
  }
  expect(snapshot(u.user)).toEqual(before)
})

test("I2: a shadow .gemini or config swapped for a symlink into the real tree is never written through", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u)); const before = snapshot(u.user)
  const cfg = join(u.shadow, ".gemini", "config"); rmSync(cfg, { recursive: true }); symlinkSync(join(u.user, ".gemini", "config"), cfg)
  expect(() => writeAntigravityMcpConfig({ shadowHome: u.shadow, userHome: u.user, servers: [] })).toThrow()
  expect(() => writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [] })).toThrow()
  await prepareAntigravityEnvironment(spec(u)); expect(snapshot(u.user)).toEqual(before)
  const g = join(u.shadow, ".gemini"); rmSync(g, { recursive: true }); symlinkSync(join(u.user, ".gemini"), g)
  expect(() => linkAntigravityCli(u.shadow, "/nowhere", u.user)).toThrow()
  await prepareAntigravityEnvironment(spec(u)); expect(snapshot(u.user)).toEqual(before)
})

test("plugin removal never follows links into the real plugins dir", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u)); const before = snapshot(u.user)
  const plugins = join(u.shadow, ".gemini", "config", "plugins"); const real = join(u.user, ".gemini", "config", "plugins", "user-plugin")
  mkdirSync(join(plugins, "supermux-x")); symlinkSync(real, join(plugins, "supermux-x", "inner")); symlinkSync(real, join(plugins, "supermux-y"))
  writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [] })
  expect(readdirSync(plugins)).toEqual(["user-plugin"]); expect(snapshot(u.user)).toEqual(before)
})

test("invalid wrapper names and duplicates are rejected before old wrappers are deleted", async () => {
  const u = fakeUser(); await prepareAntigravityEnvironment(spec(u))
  writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [{ name: "supermux-old", mcpServers: [] }] })
  const w = (name: string) => ({ name, mcpServers: [] })
  expect(() => writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [w("other")] })).toThrow(/must start with/)
  expect(() => writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [w("supermux-../x")] })).toThrow(/may only contain/)
  expect(() => writeAntigravityPlugins({ shadowHome: u.shadow, userHome: u.user, wrappers: [w("supermux-a"), w("supermux-a")] })).toThrow(/duplicate/)
  expect(existsSync(join(u.shadow, ".gemini", "config", "plugins", "supermux-old", "plugin.json"))).toBe(true)
})

test("no real cli dir: the dangling cli link survives a second prepare", async () => {
  const u = fakeUser(); rmSync(join(u.user, ".gemini", "antigravity-cli"), { recursive: true })
  await prepareAntigravityEnvironment(spec(u)); await prepareAntigravityEnvironment(spec(u))
  expect(readlinkSync(join(u.shadow, ".gemini", "antigravity-cli"))).toBe(join(u.user, ".gemini", "antigravity-cli"))
})
