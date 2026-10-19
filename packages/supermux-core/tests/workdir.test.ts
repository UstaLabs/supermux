// A session's working directory and the state directory never overlap. The keepers and the core write the state
// directory unsandboxed: an agent that can write it (because it is, holds or sits inside its working directory) could
// plant links there for them to follow out of its sandbox.
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, type Core } from "../src/index.js"
import type { AgentDriver, DriverContext } from "../src/types.js"
import { TEST_LIMITS } from "./helpers.js"

const roots: string[] = []
const cores: Core[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function recordingDriver() {
  const opens: DriverContext[] = []
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      return {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
    },
  }
  return { driver, opens }
}

/** `root/state` (the state directory, with a folder `inside` it) and `root/work` beside it. */
function setup(stateDirectory?: (root: string) => string) {
  const root = mkdtempSync(join(tmpdir(), "workdir-"))
  roots.push(root)
  const state = join(root, "state")
  const work = join(root, "work")
  mkdirSync(join(state, "inside"), { recursive: true })
  mkdirSync(work)
  const { driver, opens } = recordingDriver()
  const core = createCore({ stateDirectory: stateDirectory?.(root) ?? state, agents: [driver], limits: TEST_LIMITS })
  cores.push(core)
  return { root, state, work, core, opens }
}

const REFUSED = { code: "invalid_workdir", message: expect.stringContaining("the state directory must live outside the folders agents work in") }

for (const [what, cwdOf] of [
  ["is the state directory", ({ state }) => state],
  ["contains the state directory", ({ root }) => root],
  ["is inside the state directory", ({ state }) => join(state, "inside")],
  ["is the root of the file system", () => "/"],
] as Array<[string, (s: { root: string; state: string }) => string]>) {
  test(`create refuses a cwd that ${what}, before launching anything`, async () => {
    const s = setup()
    await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: cwdOf(s) })).rejects.toMatchObject(REFUSED)
    expect(s.opens).toEqual([])
    expect(await s.core.sessions.get("one")).toBeUndefined()
  })
}

test("a cwd beside the state directory is fine", async () => {
  const s = setup()
  await s.core.sessions.create({ id: "one", agent: "test", cwd: s.work })
  expect(s.opens.map(open => open.cwd)).toEqual([s.work])
  expect((await s.core.sessions.get("one"))?.cwd).toBe(s.work)
})

test("real paths are compared: a link to a folder that contains the state directory is refused", async () => {
  const s = setup()
  const link = join(s.work, "to-root")
  symlinkSync(s.root, link)
  await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: link })).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})

test("real paths are compared: a state directory named through a link that leads into the cwd is refused", async () => {
  // The state directory is given as root/link, which resolves to root/work/.state, inside the cwd root/work.
  const s = setup(root => {
    mkdirSync(join(root, "work", ".state"), { recursive: true })
    symlinkSync(join(root, "work", ".state"), join(root, "link"))
    return join(root, "link")
  })
  await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: s.work })).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})

test("adopt refuses a cwd that contains the state directory, and saves nothing", async () => {
  const s = setup()
  await expect(s.core.sessions.adopt({ id: "one", agent: "test", agentSessionId: "native", cwd: s.root })).rejects.toMatchObject(REFUSED)
  expect(await s.core.sessions.get("one")).toBeUndefined()
})

test("resume refuses a saved cwd that contains the state directory, without launching", async () => {
  const s = setup()
  await s.core.sessions.adopt({ id: "old", agent: "test", agentSessionId: "native-old", cwd: s.work })
  // A record saved before this check, or one whose state directory was moved under its cwd since.
  const file = join(s.state, "sessions", "old.json")
  writeFileSync(file, JSON.stringify({ ...JSON.parse(readFileSync(file, "utf8")), cwd: s.root }))
  await expect(s.core.sessions.resume("old")).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})
