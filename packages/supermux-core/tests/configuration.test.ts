import { afterEach, describe, expect, test } from "bun:test"
import { chmod, mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, Session } from "../src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext, HistoryOptions, SessionConfiguration, SessionRecord } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function configurableDriver(options: {
  history?: boolean
  failConfigure?: boolean
  applyThenThrow?: boolean
  mutateConfigureArg?: boolean
  failRollback?: boolean
  failOpen?: boolean
  wrongResumeId?: boolean
  defaultConfiguration?: SessionConfiguration
} = {}) {
  const opens: DriverContext[] = []
  const applied: SessionConfiguration[] = []
  const historyCalls: HistoryOptions[] = []
  let live: SessionConfiguration = { ...(options.defaultConfiguration ?? {}) }
  let configureCalls = 0
  let configureGate: ReturnType<typeof deferred<void>> | undefined
  let openGate: ReturnType<typeof deferred<void>> | undefined
  let enteredOpen: ReturnType<typeof deferred<void>> | undefined
  let promptGate: ReturnType<typeof deferred<void>> | undefined
  let closed = 0
  let closeStarted = 0
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      enteredOpen?.resolve()
      if (openGate) await openGate.promise
      opens.push(ctx)
      if (options.failOpen) throw new Error("native open failed")
      if (ctx.configuration) live = { ...ctx.configuration }
      const runtime: AgentRuntime = {
        agentSessionId: options.wrongResumeId ? "other-native" : (ctx.resumeId ?? `native-${opens.length}`),
        capabilities: { resume: true, steer: false, fork: true, detach: false, configure: true, history: options.history ?? true },
        prompt: async () => {
          if (promptGate) await promptGate.promise
          return { stopReason: "end_turn" }
        },
        interrupt: async () => {},
        close: async () => { closeStarted++; closed++ },
        async configure(configuration) {
          if (configureGate) await configureGate.promise
          configureCalls++
          if (options.mutateConfigureArg) configuration.model = "mutated"
          if (options.failConfigure && configureCalls === 1) throw new Error("native configure failed")
          if (options.failRollback && configureCalls > 1) throw new Error("rollback failed")
          live = { ...configuration }
          applied.push({ ...configuration })
          if (options.applyThenThrow && configureCalls === 1) throw new Error("after apply")
        },
        configuration: () => ({ ...live }),
        async history(query) {
          historyCalls.push({ ...query })
          return { protocol: "native", items: [{ opaque: query.cursor ?? "head" }], cursor: query.cursor ? `${query.cursor}-next` : "page-2" }
        },
      }
      return runtime
    },
  }
  return {
    driver, opens, applied, historyCalls,
    get closed() { return closed },
    get closeStarted() { return closeStarted },
    holdConfigure() { configureGate = deferred(); return configureGate },
    holdOpen() {
      openGate = deferred()
      enteredOpen = deferred()
      return { gate: openGate, entered: enteredOpen }
    },
    holdPrompt() { promptGate = deferred(); return promptGate },
  }
}

function isolatedSession(runtime: AgentRuntime, persistRecord: (record: SessionRecord) => Promise<void> = async () => {}) {
  const record: SessionRecord = {
    version: 1,
    id: "session-1",
    agent: "test",
    agentSessionId: runtime.agentSessionId,
    cwd: tmpdir(),
    createdAt: new Date().toISOString(),
  }
  return new Session(record, runtime, () => {}, 30, 8, () => {}, async () => { throw new Error("fork unused") }, persistRecord)
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-config-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

describe("optional configuration and history", () => {
  test("unsupported drivers reject configure and history explicitly", async () => {
    const driver: AgentDriver = {
      id: "plain",
      async open() {
        return {
          agentSessionId: "n",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => {},
        }
      },
    }
    const { core } = await setup(driver)
    const session = await core.sessions.create({ id: nextId(), agent: "plain", cwd: tmpdir() })
    await expect(session.configure({ model: "gpt" })).rejects.toMatchObject({ code: "unsupported_operation" })
    await expect(session.history()).rejects.toMatchObject({ code: "unsupported_operation" })
    expect(session.configuration()).toEqual({})
  })

  test("applies, persists, and restores configuration on reopen", async () => {
    const d = configurableDriver({ defaultConfiguration: { model: "default" } })
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(session.configuration()).toEqual({})
    await session.configure({ model: "codex", reasoningEffort: "high" })
    expect(d.applied).toEqual([{ model: "codex", reasoningEffort: "high" }])
    expect(session.configuration()).toEqual({ model: "codex", reasoningEffort: "high" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "codex", reasoningEffort: "high" })
    await session.configure({ reasoningEffort: undefined })
    expect(d.applied.at(-1)).toEqual({ model: "codex" })
    await core.close({ agents: "shutdown" })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    const resumed = await next.sessions.resume(session.id)
    expect(d.opens.at(-1)?.configuration).toEqual({ model: "codex" })
    expect(resumed.configuration()).toEqual({ model: "codex" })
    expect(resumed.snapshot().configuration).toEqual({ model: "codex" })
  })

  test("clearing requested keys omits saved overrides so resume uses driver defaults", async () => {
    const d = configurableDriver({ defaultConfiguration: { model: "default" } })
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await session.configure({ model: "codex" })
    await session.configure({ model: undefined })
    expect(d.applied.at(-1)).toEqual({})
    expect(session.configuration()).toEqual({})
    expect((await core.sessions.get(session.id))?.configuration).toBeUndefined()
    await core.close({ agents: "shutdown" })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    const resumed = await next.sessions.resume(session.id)
    expect(d.opens.at(-1)?.configuration).toBeUndefined()
    expect(resumed.configuration()).toEqual({})
  })

  test("saved nonempty configuration cannot reopen on a runtime without configure", async () => {
    const d = configurableDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await session.configure({ model: "keep-me" })
    await core.close({ agents: "shutdown" })
    const next = createCore({
    limits: TEST_LIMITS,
      stateDirectory,
      agents: [{
        id: "test",
        async open() {
          return {
            agentSessionId: "native-1",
            capabilities: { resume: true, steer: false, fork: false, detach: false },
            prompt: async () => ({ stopReason: "end_turn" }),
            interrupt: async () => {},
            close: async () => {},
          }
        },
      }],
    })
    cores.push(next)
    await expect(next.sessions.resume(session.id)).rejects.toMatchObject({ code: "unsupported_operation" })
    expect((await next.sessions.get(session.id))?.configuration).toEqual({ model: "keep-me" })
  })

  test("in-flight configuration blocks send and fork", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const gate = d.holdConfigure()
    const pending = session.configure({ model: "slow" })
    await expect(session.send({ content: [{ type: "text", text: "hi" }], whenBusy: "queue" })).rejects.toMatchObject({ code: "session_busy" })
    await expect(session.fork({ id: nextId() })).rejects.toMatchObject({ code: "session_busy" })
    await expect(session.steer({ content: [{ type: "text", text: "x" }] })).rejects.toMatchObject({ code: "session_busy" })
    gate.resolve()
    await pending
  })

  test("close waits for in-flight configuration before runtime teardown", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const gate = d.holdConfigure()
    const pending = session.configure({ model: "held" })
    const closing = session.close({ mode: "shutdown" })
    await Promise.resolve()
    expect(d.closeStarted).toBe(0)
    expect(d.applied).toHaveLength(0)
    gate.resolve()
    await pending
    await closing
    expect(d.applied).toEqual([{ model: "held" }])
    expect(d.closed).toBe(1)
  })

  test("later configure while closing is session_closed rather than session_busy", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const gate = d.holdConfigure()
    const pending = session.configure({ model: "held" })
    const closing = session.close({ mode: "shutdown" })
    await Promise.resolve()
    await expect(session.configure({ model: "again" })).rejects.toMatchObject({ code: "session_closed" })
    gate.resolve()
    await pending
    await closing
  })

  test("driver mutations of the configure payload are not persisted", async () => {
    const d = configurableDriver({ mutateConfigureArg: true })
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await session.configure({ model: "requested" })
    expect(session.configuration()).toEqual({ model: "requested" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "requested" })
    expect(d.applied.at(-1)).toEqual({ model: "mutated" })
  })

  test("storage failure rolls back native configuration and does not claim the patch", async () => {
    const d = configurableDriver({ defaultConfiguration: { model: "original" } })
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await chmod(join(stateDirectory, "sessions"), 0o500)
    await expect(session.configure({ model: "next" })).rejects.toThrow()
    expect(session.configuration()).toEqual({})
    expect((await core.sessions.get(session.id))?.configuration).toBeUndefined()
    expect(d.applied.at(-1)).toEqual({ model: "original" })
    await chmod(join(stateDirectory, "sessions"), 0o700)
  })

  test("driver configure failure does not persist or expose the new config", async () => {
    const d = configurableDriver({ failConfigure: true, defaultConfiguration: { model: "stay" } })
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(session.configure({ model: "nope" })).rejects.toThrow("native configure failed")
    expect(session.configuration()).toEqual({})
    expect((await core.sessions.get(session.id))?.configuration).toBeUndefined()
    expect(d.applied).toEqual([{ model: "stay" }])
  })

  test("driver apply-then-throw rolls back and does not claim the new requested config", async () => {
    const d = configurableDriver({ applyThenThrow: true, defaultConfiguration: { model: "stay" } })
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(session.configure({ model: "next" })).rejects.toThrow("after apply")
    expect(session.configuration()).toEqual({})
    expect((await core.sessions.get(session.id))?.configuration).toBeUndefined()
    expect(d.applied.at(-1)).toEqual({ model: "stay" })
  })

  test("persist failure plus rollback failure fails the session and rejects send", async () => {
    const d = configurableDriver({ failRollback: true, defaultConfiguration: { model: "stay" } })
    const runtime = await d.driver.open({
      sessionId: "session-1",
      cwd: tmpdir(),
      signal: new AbortController().signal,
      onUpdate() {},
      onExit() {},
      async requestPermission() { return { outcome: { outcome: "cancelled" } } },
      async requestAnswers() { return { outcome: "cancelled" as const } },
    })
    const session = isolatedSession(runtime, async () => { throw new Error("disk full") })
    let persistRollback: unknown
    try {
      await session.configure({ model: "next" })
    } catch (error) { persistRollback = error }
    expect(persistRollback).toBeInstanceOf(AggregateError)
    expect((persistRollback as AggregateError).message).toBe("Session configuration could not be saved or restored")
    expect(session.snapshot().state).toBe("failed")
    expect(session.configuration()).toEqual({})
    await expect(session.send({ content: [{ type: "text", text: "hi" }], whenBusy: "queue" })).rejects.toMatchObject({ code: "session_failed" })
  })

  test("native apply-then-throw plus rollback failure fails the session", async () => {
    const d = configurableDriver({ applyThenThrow: true, failRollback: true, defaultConfiguration: { model: "stay" } })
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    let nativeRollback: unknown
    try {
      await session.configure({ model: "next" })
    } catch (error) { nativeRollback = error }
    expect(nativeRollback).toBeInstanceOf(AggregateError)
    expect((nativeRollback as AggregateError).message).toBe("Session configuration could not be saved or restored")
    expect(session.snapshot().state).toBe("failed")
    expect(session.configuration()).toEqual({})
    await expect(session.send({ content: [{ type: "text", text: "hi" }], whenBusy: "queue" })).rejects.toMatchObject({ code: "session_failed" })
  })

  test("history pagination is forwarded with opaque native items", async () => {
    const d = configurableDriver({ history: true })
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(session.history({ limit: 0 })).rejects.toMatchObject({ code: "invalid_input" })
    await expect(session.history({ cursor: 1 as unknown as string })).rejects.toMatchObject({ code: "invalid_input" })
    const page = await session.history({ cursor: "abc", limit: 2 })
    expect(page).toEqual({ protocol: "native", items: [{ opaque: "abc" }], cursor: "abc-next" })
    expect(d.historyCalls).toEqual([{ cursor: "abc", limit: 2 }])
  })

  test("unknown keys and empty strings are rejected", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(session.configure({ model: "" })).rejects.toMatchObject({ code: "invalid_input" })
    await expect(session.configure({ temperature: "1" } as SessionConfiguration)).rejects.toMatchObject({ code: "invalid_input" })
    expect(d.applied).toHaveLength(0)
  })
})

describe("initial configuration before native open", () => {
  test("create forwards cloned requested configuration on open and persist", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const configuration = { model: "grok-4", reasoningEffort: "high" }
    const pending = core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration })
    configuration.model = "mutated"
    const session = await pending
    expect(d.opens).toHaveLength(1)
    expect(d.opens[0]?.configuration).toEqual({ model: "grok-4", reasoningEffort: "high" })
    expect(session.configuration()).toEqual({ model: "grok-4", reasoningEffort: "high" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "grok-4", reasoningEffort: "high" })
  })

  test("invalid create configuration never opens native", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "" } }))
      .rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { temperature: "1" } as SessionConfiguration }))
      .rejects.toMatchObject({ code: "invalid_input" })
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: ["x"] as unknown as SessionConfiguration }))
      .rejects.toMatchObject({ code: "invalid_input" })
    expect(d.opens).toHaveLength(0)
    expect(await core.sessions.list()).toHaveLength(0)
  })

  test("create empty or omitted configuration omits open and disk fields", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const omitted = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(d.opens[0]?.configuration).toBeUndefined()
    expect((await core.sessions.get(omitted.id))?.configuration).toBeUndefined()
    const empty = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: {} })
    expect(d.opens[1]?.configuration).toBeUndefined()
    expect((await core.sessions.get(empty.id))?.configuration).toBeUndefined()
    const cleared = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: undefined } })
    expect(d.opens[2]?.configuration).toBeUndefined()
    expect((await core.sessions.get(cleared.id))?.configuration).toBeUndefined()
  })

  test("resume no-options keeps persisted configuration on open", async () => {
    const d = configurableDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "keep" } })
    await session.close({ mode: "shutdown" })
    await core.close({ agents: "shutdown" })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    const resumed = await next.sessions.resume(session.id)
    expect(d.opens.at(-1)?.configuration).toEqual({ model: "keep" })
    expect(d.opens.at(-1)?.resumeId).toBe(session.snapshot().agentSessionId)
    expect(resumed.configuration()).toEqual({ model: "keep" })
  })

  test("resume patch merges before open and persists", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "keep" } })
    const nativeId = session.snapshot().agentSessionId
    await session.close({ mode: "shutdown" })
    const resumed = await core.sessions.resume(session.id, { configuration: { reasoningEffort: "high" } })
    expect(d.opens.at(-1)?.configuration).toEqual({ model: "keep", reasoningEffort: "high" })
    expect(d.opens.at(-1)?.resumeId).toBe(nativeId)
    expect(resumed.snapshot().agentSessionId).toBe(nativeId)
    expect(resumed.configuration()).toEqual({ model: "keep", reasoningEffort: "high" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "keep", reasoningEffort: "high" })
  })

  test("resume patch undefined values clear keys for open and disk", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "keep", reasoningEffort: "low" } })
    await session.close({ mode: "shutdown" })
    const resumed = await core.sessions.resume(session.id, { configuration: { model: undefined } })
    expect(d.opens.at(-1)?.configuration).toEqual({ reasoningEffort: "low" })
    expect(resumed.configuration()).toEqual({ reasoningEffort: "low" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ reasoningEffort: "low" })
  })

  test("resume configuration {} keeps stored config but does not coalesce", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "keep" } })
    await session.close({ mode: "shutdown" })
    const held = d.holdOpen()
    const restoring = core.sessions.resume(session.id)
    await held.entered.promise
    await expect(core.sessions.resume(session.id, { configuration: {} })).rejects.toMatchObject({ code: "session_busy" })
    held.gate.resolve()
    const resumed = await restoring
    expect(resumed.configuration()).toEqual({ model: "keep" })
    expect(d.opens.at(-1)?.configuration).toEqual({ model: "keep" })
  })

  test("two no-option resumes still share one handle", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "keep" } })
    await session.close({ mode: "shutdown" })
    const [a, b] = await Promise.all([core.sessions.resume(session.id), core.sessions.resume(session.id)])
    expect(a).toBe(b)
  })

  test("no-options in flight plus override is busy and keeps old config", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    const held = d.holdOpen()
    const restoring = core.sessions.resume(session.id)
    await held.entered.promise
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toMatchObject({ code: "session_busy" })
    held.gate.resolve()
    await restoring
    expect(d.opens.at(-1)?.configuration).toEqual({ model: "old" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old" })
  })

  test("override in flight plus no-options is busy", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    const held = d.holdOpen()
    const restoring = core.sessions.resume(session.id, { configuration: { model: "new" } })
    await held.entered.promise
    await expect(core.sessions.resume(session.id)).rejects.toMatchObject({ code: "session_busy" })
    await expect(core.sessions.resume(session.id, { configuration: { reasoningEffort: "high" } })).rejects.toMatchObject({ code: "session_busy" })
    held.gate.resolve()
    const resumed = await restoring
    expect(resumed.configuration()).toEqual({ model: "new" })
  })

  test("invalid resume patch does not reserve restoring", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    await expect(core.sessions.resume(session.id, { configuration: { model: "" } })).rejects.toMatchObject({ code: "invalid_input" })
    expect(d.opens).toHaveLength(1)
    const resumed = await core.sessions.resume(session.id)
    expect(resumed.configuration()).toEqual({ model: "old" })
  })

  test("live idle override uses configure and the same handle", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    const opens = d.opens.length
    const same = await core.sessions.resume(session.id, { configuration: { reasoningEffort: "high" } })
    expect(same).toBe(session)
    expect(d.opens).toHaveLength(opens)
    expect(d.applied.at(-1)).toEqual({ model: "old", reasoningEffort: "high" })
    expect(session.configuration()).toEqual({ model: "old", reasoningEffort: "high" })
  })

  test("live busy override resume is session_busy and leaves record", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    const gate = d.holdConfigure()
    const pending = session.configure({ reasoningEffort: "low" })
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toMatchObject({ code: "session_busy" })
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old" })
    gate.resolve()
    await pending
    const promptGate = d.holdPrompt()
    const sending = session.send({ content: [{ type: "text", text: "hi" }], whenBusy: "queue" })
    await Promise.resolve()
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toMatchObject({ code: "session_busy" })
    promptGate.resolve()
    await sending
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old", reasoningEffort: "low" })
  })

  test("failed native open on override resume keeps original record", async () => {
    const d = configurableDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    const id = session.id
    await session.close({ mode: "shutdown" })
    await core.close({ agents: "shutdown" })
    const failing = configurableDriver({ failOpen: true })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [failing.driver] })
    cores.push(next)
    await expect(next.sessions.resume(id, { configuration: { model: "new" } })).rejects.toThrow("native open failed")
    expect((await next.sessions.get(id))?.configuration).toEqual({ model: "old" })
    expect(failing.closed).toBe(0)
  })

  test("persist failure after override open keeps original record and closes runtime", async () => {
    const d = configurableDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    await chmod(join(stateDirectory, "sessions"), 0o500)
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toThrow()
    expect(d.closed).toBeGreaterThanOrEqual(1)
    await chmod(join(stateDirectory, "sessions"), 0o700)
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old" })
  })

  test("put that commits then rejects restores original record and closes native", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    const store = (core as unknown as { store: { put: (record: SessionRecord) => Promise<void> } }).store
    const put = store.put.bind(store)
    let fault = true
    store.put = async (record: SessionRecord) => {
      await put(record)
      if (fault) {
        fault = false
        throw new Error("post-write failure")
      }
    }
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toThrow("post-write failure")
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old" })
    expect(d.closed).toBeGreaterThanOrEqual(2)
  })

  test("late onExit after merged put restores original record and closes native", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    const store = (core as unknown as { store: { put: (record: SessionRecord) => Promise<void> } }).store
    const put = store.put.bind(store)
    let injected = false
    store.put = async (record: SessionRecord) => {
      await put(record)
      if (!injected) {
        injected = true
        d.opens.at(-1)!.onExit(new Error("exit after persist"))
      }
    }
    await expect(core.sessions.resume(session.id, { configuration: { model: "new" } })).rejects.toThrow("exit after persist")
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "old" })
    expect(d.closed).toBeGreaterThanOrEqual(2)
  })

  test("rollback failure after committed put keeps both errors and cleanup ownership", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    await session.close({ mode: "shutdown" })
    const originalOpen = d.driver.open.bind(d.driver)
    d.driver.open = async ctx => {
      const runtime = await originalOpen(ctx)
      const close = runtime.close.bind(runtime)
      let closeAttempts = 0
      runtime.close = async () => {
        closeAttempts++
        await close()
        if (closeAttempts === 1) throw new Error("close failed")
      }
      return runtime
    }
    const store = (core as unknown as { store: { put: (record: SessionRecord) => Promise<void> } }).store
    const put = store.put.bind(store)
    let puts = 0
    store.put = async (record: SessionRecord) => {
      puts++
      if (puts === 1) {
        await put(record)
        throw new Error("post-write failure")
      }
      throw new Error("rollback failed")
    }
    let caught: unknown
    try {
      await core.sessions.resume(session.id, { configuration: { model: "new" } })
    } catch (error) {
      caught = error
    }
    expect(caught).toBeInstanceOf(AggregateError)
    const errors = (caught as AggregateError).errors.map(error => String(error))
    expect(errors.some(text => text.includes("post-write failure"))).toBe(true)
    expect(errors.some(text => text.includes("rollback failed"))).toBe(true)
    expect(errors.some(text => text.includes("close failed"))).toBe(true)
    expect((await core.sessions.get(session.id))?.configuration).toEqual({ model: "new" })
    await core.close({ agents: "shutdown" })
    expect(d.closed).toBeGreaterThanOrEqual(3)
  })

  test("create put that commits then rejects removes the new record", async () => {
    const d = configurableDriver()
    const { core } = await setup(d.driver)
    const store = (core as unknown as { store: { put: (record: SessionRecord) => Promise<void> } }).store
    const put = store.put.bind(store)
    store.put = async (record: SessionRecord) => {
      await put(record)
      throw new Error("post-write failure")
    }
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "new" } })).rejects.toThrow("post-write failure")
    expect(await core.sessions.list()).toHaveLength(0)
    expect(d.closed).toBe(1)
  })

  test("non-configurable runtime rejects nonempty create and override resume", async () => {
    const opens: DriverContext[] = []
    let closed = 0
    const plain: AgentDriver = {
      id: "test",
      async open(ctx) {
        opens.push(ctx)
        return {
          agentSessionId: ctx.resumeId ?? "n",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => { closed++ },
        }
      },
    }
    const { core, stateDirectory } = await setup(plain)
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "x" } }))
      .rejects.toMatchObject({ code: "unsupported_operation" })
    expect(await core.sessions.list()).toHaveLength(0)
    expect(closed).toBe(1)
    const ok = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await ok.close({ mode: "shutdown" })
    await core.close({ agents: "shutdown" })
    const d = configurableDriver()
    const mid = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(mid)
    const configured = await mid.sessions.resume(ok.id, { configuration: { model: "keep-me" } })
    await configured.close({ mode: "shutdown" })
    await mid.close({ agents: "shutdown" })
    closed = 0
    opens.length = 0
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [plain] })
    cores.push(next)
    await expect(next.sessions.resume(ok.id, { configuration: { reasoningEffort: "high" } })).rejects.toMatchObject({ code: "unsupported_operation" })
    expect((await next.sessions.get(ok.id))?.configuration).toEqual({ model: "keep-me" })
    expect(closed).toBe(1)
  })

  test("resume identity change with override leaves original disk config", async () => {
    const d = configurableDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), configuration: { model: "old" } })
    const id = session.id
    await session.close({ mode: "shutdown" })
    await core.close({ agents: "shutdown" })
    const wrong = configurableDriver({ wrongResumeId: true })
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [wrong.driver] })
    cores.push(next)
    await expect(next.sessions.resume(id, { configuration: { model: "new" } })).rejects.toMatchObject({ code: "resume_identity_changed" })
    expect((await next.sessions.get(id))?.configuration).toEqual({ model: "old" })
    expect(wrong.closed).toBe(1)
  })
})
