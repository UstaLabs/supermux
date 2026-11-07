// Cursor's ACP server ignores --plugin-dir and reads skills only from the workspace and `$HOME/.cursor/skills`.
// With `homeSkills`, the core links a session's skills there, for a HOME the host made for that session (#102).
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readlinkSync, symlinkSync, writeFileSync } from "node:fs"
import { rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { cursor } from "../src/agents/index.js"
import type { LaunchContext } from "../src/context/types.js"
import type { AgentDriver, AgentRuntime, DriverContext } from "../src/types.js"
import { TEST_ACP_PERMISSIONS } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "cursor-skills-")); dirs.push(dir); return dir }
const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const ACP = { setupTimeoutMs: 5000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, inheritEnv: true }

function cursorDriver(env: Record<string, string>, homeSkills?: boolean): AgentDriver {
  return cursor({
    id: "cursor", command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env, mcpServers: [],
    permissions: TEST_ACP_PERMISSIONS, ...ACP,
    keeper: { stateDirectory: scratch(), limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } },
    ...(homeSkills === undefined ? {} : { homeSkills }),
  })
}

async function open(driver: AgentDriver, sessionContext: LaunchContext): Promise<AgentRuntime> {
  const context: DriverContext = {
    sessionId: "ctx-1", cwd: process.cwd(), signal: new AbortController().signal, sessionContext,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
  }
  return driver.open(context)
}

function skill(folder: string, name: string): void {
  mkdirSync(join(folder, name), { recursive: true })
  writeFileSync(join(folder, name, "SKILL.md"), `---\nname: ${name}\ndescription: ${name}\n---\n`)
}

/** A skills folder (alpha) and a plugin whose skills/ has beta and its own alpha. */
function skillsFixture() {
  const root = scratch()
  const skills = join(root, "skills")
  skill(skills, "alpha")
  const plugin = join(root, "my-plugin")
  skill(join(plugin, "skills"), "beta")
  skill(join(plugin, "skills"), "alpha")
  const launch = (): LaunchContext => {
    const directory = join(root, "state", "context", "ctx-1")
    mkdirSync(directory, { recursive: true })
    return { skills: [skills], plugins: [plugin], mcpServers: [], directory, launch: "create", dropped: [], fingerprint: "fixture" }
  }
  return { skills, plugin, launch }
}

test("cursor with homeSkills: the session's skills and plugin skills are linked into $HOME/.cursor/skills", async () => {
  const f = skillsFixture()
  const home = scratch()
  const driver = cursorDriver({ HOME: home }, true)
  expect(driver.context!.capabilities.skills.support).toBe("supported")
  expect(driver.context!.capabilities.plugins.support).toBe("supported")

  const runtime = await open(driver, f.launch())
  await runtime.close({ mode: "shutdown" })

  const linked = join(home, ".cursor", "skills")
  expect(readdirSync(linked).sort()).toEqual(["alpha", "beta"])
  // A skills folder comes before the plugins: its alpha wins.
  expect(readlinkSync(join(linked, "alpha"))).toBe(join(f.skills, "alpha"))
  expect(readlinkSync(join(linked, "beta"))).toBe(join(f.plugin, "skills", "beta"))
})

test("cursor without homeSkills writes nothing into HOME, and skills stay unsupported", async () => {
  const f = skillsFixture()
  const home = scratch()
  const driver = cursorDriver({ HOME: home })
  expect(driver.context!.capabilities.skills.support).toBe("unsupported")
  expect(driver.context!.capabilities.plugins.support).toBe("unsupported")

  const runtime = await open(driver, f.launch())
  await runtime.close({ mode: "shutdown" })

  expect(existsSync(join(home, ".cursor"))).toBe(false)
})

test("cursor homeSkills: a link from an earlier launch is replaced, anything else in the folder is kept", async () => {
  const f = skillsFixture()
  const home = scratch()
  const linked = join(home, ".cursor", "skills")
  mkdirSync(linked, { recursive: true })
  symlinkSync(join(scratch(), "gone"), join(linked, "stale"))
  skill(linked, "mine")

  const runtime = await open(cursorDriver({ HOME: home }, true), f.launch())
  await runtime.close({ mode: "shutdown" })

  expect(readdirSync(linked).sort()).toEqual(["alpha", "beta", "mine"])
  expect(lstatSync(join(linked, "mine")).isSymbolicLink()).toBe(false)
})

test("cursor homeSkills refuses to write into the home of the process running the core", async () => {
  const f = skillsFixture()
  const home = scratch()
  const saved = process.env.HOME
  process.env.HOME = home
  try {
    await expect(open(cursorDriver({ HOME: home }, true), f.launch())).rejects.toThrow(/homeSkills/)
    expect(existsSync(join(home, ".cursor"))).toBe(false)
  } finally {
    process.env.HOME = saved
  }
})
