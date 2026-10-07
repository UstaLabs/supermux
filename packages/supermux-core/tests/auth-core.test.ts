import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

function idleRuntime(): AgentRuntime {
  return {
    agentSessionId: "native-1",
    capabilities: { resume: true, steer: false, fork: false, detach: false },
    prompt: async () => ({ stopReason: "end_turn" }),
    interrupt: async () => {},
    close: async () => {},
  }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver, options: Record<string, unknown> = {}) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-auth-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS, ...options })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

test("wrong-agent profile is rejected before driver.open", async () => {
  let opened = 0
  let listed = 0
  const driver: AgentDriver = {
    id: "test",
    async open() {
      opened++
      return idleRuntime()
    },
    auth: {
      methods: async () => {
        listed++
        return [{ id: "token", name: "Token" }]
      },
      login: async () => {},
    },
  }
  const { core } = await setup(driver, {
    profiles: { work: { agent: "other", env: { TOKEN: "private" } } },
  })
  await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), authProfile: "work" })).rejects.toMatchObject({
    code: "invalid_auth_profile",
  })
  await expect(core.auth.methods({ agent: "test", profile: "work" })).rejects.toMatchObject({
    code: "invalid_auth_profile",
  })
  expect(opened).toBe(0)
  expect(listed).toBe(0)
})

test("auth methods and empty methodId fail without inventing a login UI", async () => {
  const driver: AgentDriver = { id: "test", open: async () => idleRuntime() }
  const { core } = await setup(driver)
  await expect(core.auth.methods({ agent: "test" })).rejects.toMatchObject({
    name: "UnsupportedOperation",
    code: "unsupported_operation",
  })
  await expect(core.auth.login({ agent: "test", methodId: "token" })).rejects.toMatchObject({
    name: "UnsupportedOperation",
    code: "unsupported_operation",
  })
  const authed: AgentDriver = {
    id: "authed",
    open: async () => idleRuntime(),
    auth: {
      methods: async () => [{ id: "token", name: "Token" }],
      login: async () => { throw new Error("login must not run") },
    },
  }
  const { core: withAuthSurface } = await setup(authed)
  await expect(withAuthSurface.auth.login({ agent: "authed", methodId: "" })).rejects.toMatchObject({
    code: "invalid_input",
  })
})

test("Core does not rewrite opaque native auth failures from driver.open", async () => {
  const native = Object.assign(new Error("vendor login expired"), { code: "native_auth_expired" })
  const driver: AgentDriver = {
    id: "test",
    open: async (_context: DriverContext) => { throw native },
  }
  const { core } = await setup(driver)
  const error = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() }).catch(e => e)
  expect(error).toBe(native)
  expect(error.code).toBe("native_auth_expired")
  expect(error.code).not.toBe("auth_missing")
  expect(error.code).not.toBe("auth_invalid")
})
