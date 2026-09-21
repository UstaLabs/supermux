import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function trackingDriver() {
  const opens: DriverContext[] = []
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {},
        configure: async () => {},
      }
      return runtime
    },
  }
  return { driver, opens }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver, options = {}) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-adopt-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS, ...options })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

describe("session adopt", () => {
  test("registers metadata without opening a driver and resume uses the native id", async () => {
    const d = trackingDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const createdAt = "2024-01-02T03:04:05.000Z"
    const record = await core.sessions.adopt({
      id: "migrated-1", agent: "test", agentSessionId: "native-from-broker", cwd: tmpdir(), createdAt,
    })
    expect(record).toMatchObject({ id: "migrated-1", agentSessionId: "native-from-broker", createdAt, agent: "test" })
    expect(d.opens).toHaveLength(0)
    expect((await core.sessions.get("migrated-1"))?.agentSessionId).toBe("native-from-broker")
    await core.close({ agents: "shutdown" })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    const resumed = await next.sessions.resume("migrated-1")
    expect(d.opens).toHaveLength(1)
    expect(d.opens[0]?.resumeId).toBe("native-from-broker")
    expect(resumed.id).toBe("migrated-1")
    expect(await next.sessions.resume("migrated-1")).toBe(resumed)
  })

  test("failed native resume keeps the adopted record and does not open a new conversation", async () => {
    const d = trackingDriver()
    const { core, stateDirectory } = await setup(d.driver)
    await core.sessions.adopt({ id: "keep-me", agent: "test", agentSessionId: "native-keep", cwd: tmpdir() })
    await core.close({ agents: "shutdown" })
    const requests: DriverContext[] = []
    const next = createCore({
      stateDirectory,
      agents: [{ id: "test", async open(ctx) { requests.push(ctx); throw new Error("history gone") } }],
      limits: TEST_LIMITS,
    })
    cores.push(next)
    await expect(next.sessions.resume("keep-me")).rejects.toThrow("history gone")
    expect(requests).toHaveLength(1)
    expect(requests[0]?.resumeId).toBe("native-keep")
    expect((await next.sessions.get("keep-me"))?.agentSessionId).toBe("native-keep")
    expect(await next.sessions.list()).toHaveLength(1)
  })

  test("duplicate create and adopt collide without a second native open", async () => {
    const entered = deferred<void>()
    const release = deferred<void>()
    const opens: DriverContext[] = []
    const { core } = await setup({
      id: "test",
      async open(ctx) {
        opens.push(ctx)
        entered.resolve()
        await release.promise
        return {
          agentSessionId: ctx.resumeId ?? "native-slow",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => {},
        }
      },
    })
    const creating = core.sessions.create({ id: "shared-id", agent: "test", cwd: tmpdir() })
    await entered.promise
    const [adopted, createdAgain] = await Promise.allSettled([
      core.sessions.adopt({ id: "shared-id", agent: "test", agentSessionId: "other-native", cwd: tmpdir() }),
      core.sessions.create({ id: "shared-id", agent: "test", cwd: tmpdir() }),
    ])
    expect(adopted).toMatchObject({ status: "rejected", reason: { code: "session_busy" } })
    expect(createdAgain).toMatchObject({ status: "rejected", reason: { code: "session_busy" } })
    expect(opens).toHaveLength(1)
    release.resolve()
    const session = await creating
    expect(session.id).toBe("shared-id")
    await expect(core.sessions.adopt({ id: "shared-id", agent: "test", agentSessionId: "other-native", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "session_busy" })
    await session.close({ mode: "shutdown" })
    await expect(core.sessions.adopt({ id: "shared-id", agent: "test", agentSessionId: "other-native", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "session_exists" })
    await expect(core.sessions.create({ id: "shared-id", agent: "test", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "session_exists" })
    expect(opens).toHaveLength(1)
  })

  test("invalid input creates nothing", async () => {
    const d = trackingDriver()
    const { core } = await setup(d.driver)
    await expect(core.sessions.adopt({ id: "../escape", agent: "test", agentSessionId: "n", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "invalid_session_id" })
    await expect(core.sessions.adopt({ id: "ok", agent: "test", agentSessionId: "", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.adopt({ id: "ok", agent: "missing", agentSessionId: "n", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "unknown_agent" })
    await expect(core.sessions.adopt({ id: "ok", agent: "test", agentSessionId: "n", cwd: "/definitely-not-here-adopt" }))
      .rejects.toMatchObject({ code: "invalid_workdir" })
    await expect(core.sessions.adopt({ id: "ok", agent: "test", agentSessionId: "n", cwd: tmpdir(), createdAt: "not-a-date" }))
      .rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.adopt({
      id: "ok", agent: "test", agentSessionId: "n", cwd: tmpdir(),
      configuration: { model: "" },
    })).rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.adopt({
      id: "ok", agent: "test", agentSessionId: "n", cwd: tmpdir(),
      configuration: { unknown: "x" } as never,
    })).rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.create({ id: "../nope", agent: "test", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "invalid_session_id" })
    expect(d.opens).toHaveLength(0)
    expect(await core.sessions.list()).toHaveLength(0)
  })

  test("profile and configuration propagate on adopt and resume without persisting secrets", async () => {
    const d = trackingDriver()
    const { core } = await setup(d.driver, { profiles: { work: { agent: "test", env: { TOKEN: "private" }, methodId: "token" } } })
    const record = await core.sessions.adopt({
      id: "cfg-1", agent: "test", agentSessionId: "native-cfg", cwd: tmpdir(),
      authProfile: "work", configuration: { model: "codex", reasoningEffort: "high" },
    })
    expect(record.authProfile).toBe("work")
    expect(record.configuration).toEqual({ model: "codex", reasoningEffort: "high" })
    expect(JSON.stringify(record)).not.toContain("private")
    expect(JSON.stringify(await core.sessions.get("cfg-1"))).not.toContain("private")
    await expect(core.sessions.adopt({
      id: "cfg-2", agent: "test", agentSessionId: "n", cwd: tmpdir(), authProfile: "missing",
    })).rejects.toMatchObject({ code: "invalid_auth_profile" })
    expect(await core.sessions.list()).toHaveLength(1)
    const resumed = await core.sessions.resume("cfg-1")
    expect(d.opens[0]?.resumeId).toBe("native-cfg")
    expect(d.opens[0]?.profile?.env).toEqual({ TOKEN: "private" })
    expect(d.opens[0]?.configuration).toEqual({ model: "codex", reasoningEffort: "high" })
    expect(resumed.snapshot().configuration).toEqual({ model: "codex", reasoningEffort: "high" })
  })

  test("close waits for adopt and releases reservations", async () => {
    const d = trackingDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const adopting = core.sessions.adopt({ id: "during-close", agent: "test", agentSessionId: "native-close", cwd: tmpdir() })
    const closing = core.close({ agents: "shutdown" })
    await closing
    const adopted = await adopting.catch(e => e)
    if (adopted && typeof adopted === "object" && "id" in adopted) {
      expect(adopted.id).toBe("during-close")
    } else {
      expect(adopted).toMatchObject({ code: "core_closed" })
    }
    await expect(core.sessions.adopt({ id: "after-close", agent: "test", agentSessionId: "n", cwd: tmpdir() }))
      .rejects.toMatchObject({ code: "core_closed" })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    const listed = await next.sessions.list()
    if (listed.length) {
      expect(listed[0]?.id).toBe("during-close")
      expect(listed[0]?.agentSessionId).toBe("native-close")
    }
    expect(d.opens).toHaveLength(0)
  })

  test("forget and resume see adopt reservations", async () => {
    const d = trackingDriver()
    const { core } = await setup(d.driver)
    await core.sessions.adopt({ id: "idle-adopt", agent: "test", agentSessionId: "n1", cwd: tmpdir() })
    const [forget, resume] = await Promise.allSettled([core.sessions.forget("idle-adopt"), core.sessions.resume("idle-adopt")])
    expect(forget.status === "fulfilled" || resume.status === "fulfilled").toBe(true)
    if (forget.status === "fulfilled") {
      expect(resume).toMatchObject({ status: "rejected", reason: { code: "session_busy" } })
      expect(await core.sessions.get("idle-adopt")).toBeUndefined()
    }
  })
})
