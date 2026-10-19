// A session's working directory and the state directory. The keepers and the core write the state directory
// unsandboxed: an agent that can write it could plant links there for them to follow out of its sandbox. A cwd that is
// the state directory or sits inside it is always refused; one that holds it is refused with
// refuseWorkdirsContainingState, which supermux() turns on (a host whose agents work in a folder above its state
// directory, a home directory say, leaves it off).
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, defaults, supermux, type Core, type SupermuxOptions } from "../src/index.js"
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

/** A temp folder holding `state` (the state directory, with a folder `inside` it) and `work` beside it. */
function folders() {
  const root = mkdtempSync(join(tmpdir(), "workdir-"))
  roots.push(root)
  const state = join(root, "state")
  const work = join(root, "work")
  mkdirSync(join(state, "inside"), { recursive: true })
  mkdirSync(work)
  return { root, state, work }
}

type Setup = { stateDirectory?: (root: string) => string; refuseWorkdirsContainingState?: boolean }

/** A core over `root/state` (or `stateDirectory(root)`) with a driver that records its opens. */
function setup({ stateDirectory, refuseWorkdirsContainingState }: Setup = {}) {
  const { root, state, work } = folders()
  const { driver, opens } = recordingDriver()
  const core = createCore({
    stateDirectory: stateDirectory?.(root) ?? state, agents: [driver], limits: TEST_LIMITS,
    ...(refuseWorkdirsContainingState === undefined ? {} : { refuseWorkdirsContainingState }),
  })
  cores.push(core)
  return { root, state, work, core, opens }
}

const REFUSED = { code: "invalid_workdir", message: expect.stringContaining("the state directory must live outside the folders agents work in") }

// Always refused, whatever refuseWorkdirsContainingState says.

for (const refuseWorkdirsContainingState of [undefined, false]) {
  for (const [what, cwdOf] of [
    ["is the state directory", ({ state }) => state],
    ["is inside the state directory", ({ state }) => join(state, "inside")],
  ] as Array<[string, (s: { state: string }) => string]>) {
    test(`create refuses a cwd that ${what} (refuseWorkdirsContainingState: ${refuseWorkdirsContainingState}), before launching anything`, async () => {
      const s = setup({ refuseWorkdirsContainingState })
      await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: cwdOf(s) })).rejects.toMatchObject(REFUSED)
      expect(s.opens).toEqual([])
      expect(await s.core.sessions.get("one")).toBeUndefined()
    })
  }
}

test("a cwd beside the state directory is fine", async () => {
  const s = setup({ refuseWorkdirsContainingState: true })
  await s.core.sessions.create({ id: "one", agent: "test", cwd: s.work })
  expect(s.opens.map(open => open.cwd)).toEqual([s.work])
  expect((await s.core.sessions.get("one"))?.cwd).toBe(s.work)
})

test("real paths are compared: a link to the state directory, or to a folder inside it, is refused", async () => {
  const s = setup()
  symlinkSync(s.state, join(s.work, "to-state"))
  symlinkSync(join(s.state, "inside"), join(s.work, "to-inside"))
  await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: join(s.work, "to-state") })).rejects.toMatchObject(REFUSED)
  await expect(s.core.sessions.create({ id: "two", agent: "test", cwd: join(s.work, "to-inside") })).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})

// adopt and resume refuse the same cwds as create: the one always refused, and the one the option refuses.
for (const [what, refuseWorkdirsContainingState, cwdOf] of [
  ["is inside the state directory", undefined, ({ state }) => join(state, "inside")],
  ["contains the state directory (refuseWorkdirsContainingState)", true, ({ root }) => root],
] as Array<[string, boolean | undefined, (s: { root: string; state: string }) => string]>) {
  test(`adopt refuses a cwd that ${what}, and saves nothing`, async () => {
    const s = setup({ refuseWorkdirsContainingState })
    await expect(s.core.sessions.adopt({ id: "one", agent: "test", agentSessionId: "native", cwd: cwdOf(s) })).rejects.toMatchObject(REFUSED)
    expect(await s.core.sessions.get("one")).toBeUndefined()
  })

  test(`resume refuses a saved cwd that ${what}, without launching`, async () => {
    const s = setup({ refuseWorkdirsContainingState })
    await s.core.sessions.adopt({ id: "old", agent: "test", agentSessionId: "native-old", cwd: s.work })
    // A record saved before this check, or one whose state directory was moved since.
    const file = join(s.state, "sessions", "old.json")
    writeFileSync(file, JSON.stringify({ ...JSON.parse(readFileSync(file, "utf8")), cwd: cwdOf(s) }))
    await expect(s.core.sessions.resume("old")).rejects.toMatchObject(REFUSED)
    expect(s.opens).toEqual([])
  })
}

// A cwd that holds the state directory: refused only with refuseWorkdirsContainingState.

for (const refuseWorkdirsContainingState of [undefined, false]) {
  test(`refuseWorkdirsContainingState: ${refuseWorkdirsContainingState}: a cwd that contains the state directory is fine`, async () => {
    const s = setup({ refuseWorkdirsContainingState })
    await s.core.sessions.create({ id: "one", agent: "test", cwd: s.root })
    expect(s.opens.map(open => open.cwd)).toEqual([s.root])
    await s.core.sessions.adopt({ id: "two", agent: "test", agentSessionId: "native-two", cwd: s.root })
    expect((await s.core.sessions.get("two"))?.cwd).toBe(s.root)
  })
}

for (const [what, cwdOf] of [
  ["contains the state directory", ({ root }) => root],
  ["is the root of the file system", () => "/"],
] as Array<[string, (s: { root: string }) => string]>) {
  test(`refuseWorkdirsContainingState: create refuses a cwd that ${what}, before launching anything`, async () => {
    const s = setup({ refuseWorkdirsContainingState: true })
    await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: cwdOf(s) })).rejects.toMatchObject(REFUSED)
    expect(s.opens).toEqual([])
    expect(await s.core.sessions.get("one")).toBeUndefined()
  })
}

test("refuseWorkdirsContainingState: real paths are compared: a link to a folder that contains the state directory is refused", async () => {
  const s = setup({ refuseWorkdirsContainingState: true })
  const link = join(s.work, "to-root")
  symlinkSync(s.root, link)
  await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: link })).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})

test("refuseWorkdirsContainingState: real paths are compared: a state directory named through a link that leads into the cwd is refused", async () => {
  // The state directory is given as root/link, which resolves to root/work/.state, inside the cwd root/work.
  const s = setup({
    refuseWorkdirsContainingState: true,
    stateDirectory: root => {
      mkdirSync(join(root, "work", ".state"), { recursive: true })
      symlinkSync(join(root, "work", ".state"), join(root, "link"))
      return join(root, "link")
    },
  })
  await expect(s.core.sessions.create({ id: "one", agent: "test", cwd: s.work })).rejects.toMatchObject(REFUSED)
  expect(s.opens).toEqual([])
})

test("refuseWorkdirsContainingState must be a boolean", () => {
  const { state } = folders()
  const { driver } = recordingDriver()
  expect(() => createCore({ stateDirectory: state, agents: [driver], limits: TEST_LIMITS, refuseWorkdirsContainingState: "yes" as never }))
    .toThrow(new TypeError("refuseWorkdirsContainingState must be a boolean"))
})

// supermux() turns it on (defaults.core); a caller may turn it off.

test("supermux() refuses a cwd that contains the state directory, unless told not to", async () => {
  expect(defaults.core.refuseWorkdirsContainingState).toBe(true)
  for (const given of [{}, { refuseWorkdirsContainingState: undefined }] as Array<Partial<SupermuxOptions>>) {
    const { root, state } = folders()
    const core = supermux({ ...given, stateDirectory: state, agents: { claude: {} } })
    cores.push(core)
    // adopt launches nothing, so the real claude CLI is never needed.
    await expect(core.sessions.adopt({ id: "one", agent: "claude", agentSessionId: "native", cwd: root })).rejects.toMatchObject(REFUSED)
    expect(await core.sessions.get("one")).toBeUndefined()
  }
})

test("supermux({ refuseWorkdirsContainingState: false }): a cwd may hold the state directory, never be it or sit inside it", async () => {
  const { root, state } = folders()
  const core = supermux({ stateDirectory: state, agents: { claude: {} }, refuseWorkdirsContainingState: false })
  cores.push(core)
  await core.sessions.adopt({ id: "one", agent: "claude", agentSessionId: "native-one", cwd: root })
  expect((await core.sessions.get("one"))?.cwd).toBe(root)
  for (const cwd of [state, join(state, "inside")]) {
    await expect(core.sessions.adopt({ id: "two", agent: "claude", agentSessionId: "native-two", cwd })).rejects.toMatchObject(REFUSED)
  }
})
