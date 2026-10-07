import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { ActivityNotice, AgentDriver, AgentRuntime, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function activityDriver() {
  const opens: DriverContext[] = []
  const prompts: string[] = []
  const steers: string[] = []
  let interrupts = 0
  let closes = 0
  let closeError: Error | undefined
  let closeGate: ReturnType<typeof deferred<void>> | undefined
  let interruptGate: ReturnType<typeof deferred<void>> | undefined
  let active: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: true, fork: true, detach: false, configure: true },
        async steer(content) { steers.push((content[0] as { text: string }).text) },
        async prompt(content, signal) {
          prompts.push((content[0] as { text: string }).text)
          const work = deferred<{ stopReason: string }>()
          active = work
          const cancel = () => work.resolve({ stopReason: "cancelled" })
          signal.addEventListener("abort", cancel, { once: true })
          try { return await work.promise } finally { signal.removeEventListener("abort", cancel); if (active === work) active = undefined }
        },
        async interrupt() {
          interrupts++
          if (interruptGate) await interruptGate.promise
        },
        async close() {
          closes++
          if (closeGate) await closeGate.promise
          if (closeError) throw closeError
          active?.resolve({ stopReason: "cancelled" })
        },
        async configure() {},
      }
      return runtime
    },
  }
  return {
    driver, opens, prompts, steers,
    get interrupts() { return interrupts },
    get closes() { return closes },
    complete: () => active?.resolve({ stopReason: "end_turn" }),
    failClose: (error: Error) => { closeError = error },
    delayClose: () => { closeGate = deferred<void>(); return closeGate },
    stallInterrupt: () => { interruptGate = deferred<void>(); return interruptGate },
  }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver, options = {}) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-activity-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS, ...options })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
const input = (text: string) => ({ content: [{ type: "text" as const, text }], whenBusy: "queue" as const })
const tick = () => new Promise<void>(resolve => setTimeout(resolve, 0))
const activity = (id: string, phase: ActivityNotice["phase"]): ActivityNotice => ({ id, phase })

describe("native activity contract", () => {
  test("external turn interrupt calls runtime and waits matching completion", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("turn-1", "started"))
    expect(s.snapshot().state).toBe("running")
    expect(events.filter(e => e.type === "message.accepted")).toEqual([])
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    expect(d.interrupts).toBe(1)
    expect(s.snapshot().state).toBe("interrupting")
    d.opens[0]!.onActivity!(activity("turn-1", "completed"))
    expect(await stopping).toEqual({ status: "stopped" })
    expect(s.snapshot().state).toBe("idle")
    expect(events.filter(e => e.type === "message.completed")).toEqual([])
  })

  test("unconfirmed native interrupt stays busy", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("turn-1", "started"))
    expect(await s.interrupt({ pending: "discard" })).toEqual({ status: "unconfirmed" })
    expect(s.snapshot().state).toBe("interrupting")
    await expect(s.send({ ...input("next"), whenBusy: "reject" })).rejects.toMatchObject({ code: "session_busy" })
    expect(() => s.pending.continue()).toThrow()
  })

  test("queue, reject, configure, and fork honor native busy", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("turn-1", "started"))
    await expect(s.send({ ...input("nope"), whenBusy: "reject" })).rejects.toMatchObject({ code: "session_busy" })
    await expect(s.configure({ model: "x" })).rejects.toMatchObject({ code: "session_busy" })
    await expect(s.fork({ id: nextId() })).rejects.toMatchObject({ code: "session_busy" })
    const queued = await s.send(input("later"))
    await tick()
    expect(d.prompts).toEqual([])
    d.opens[0]!.onActivity!(activity("turn-1", "completed"))
    await tick()
    expect(d.prompts).toEqual(["later"])
    d.complete()
    expect((await queued.completed).status).toBe("completed")
  })

  test("duplicate start and stale completion cannot end newer work", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    ctx.onActivity!(activity("a", "started"))
    ctx.onActivity!(activity("a", "started"))
    ctx.onActivity!(activity("b", "started"))
    ctx.onActivity!(activity("a", "completed"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("missing", "completed"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("b", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("owned prompt overlapping native activity is one receipt and both must finish", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await s.send(input("hello"))
    d.opens[0]!.onActivity!(activity("same-turn", "started"))
    expect(events.filter(e => e.type === "message.accepted")).toHaveLength(1)
    d.complete()
    expect((await receipt.completed).status).toBe("completed")
    expect(s.snapshot().state).toBe("running")
    const queued = await s.send(input("next"))
    await tick()
    expect(d.prompts).toEqual(["hello"])
    d.opens[0]!.onActivity!(activity("same-turn", "completed"))
    await tick()
    expect(d.prompts).toEqual(["hello", "next"])
    d.complete()
    expect((await queued.completed).status).toBe("completed")
    expect(s.snapshot().state).toBe("idle")
  })

  test("native start after owned prompt before the receipt settles is not lost", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await s.send(input("hello"))
    expect(s.snapshot().state).toBe("running")
    d.opens[0]!.onActivity!(activity("handoff", "started"))
    d.complete()
    await receipt.completed
    expect(s.snapshot().state).toBe("running")
    d.opens[0]!.onActivity!(activity("handoff", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("activity during open is buffered until the session exists", async () => {
    const opened = deferred<DriverContext>()
    const driver: AgentDriver = {
      id: "test",
      async open(ctx) {
        ctx.onActivity!(activity("boot", "started"))
        queueMicrotask(() => ctx.onActivity!(activity("persist", "started")))
        opened.resolve(ctx)
        return {
          agentSessionId: "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => {},
        }
      },
    }
    const { core } = await setup(driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(s.snapshot().state).toBe("running")
    const ctx = await opened.promise
    ctx.onActivity!(activity("boot", "completed"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("persist", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("close failure and late callbacks cannot confirm native idle or revive", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    ctx.onActivity!(activity("bg", "started"))
    d.failClose(new Error("still running"))
    await expect(s.close({ mode: "shutdown" })).rejects.toThrow("still running")
    expect(s.snapshot().state).toBe("failed")
    ctx.onActivity!(activity("bg", "completed"))
    ctx.onActivity!(activity("newer", "started"))
    expect(s.snapshot().state).toBe("failed")
    await expect(s.send(input("nope"))).rejects.toMatchObject({ code: "session_failed" })
  })

  test("idle interrupt without native work does not call runtime.interrupt", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(await s.interrupt({ pending: "discard" })).toEqual({ status: "already_idle" })
    expect(d.interrupts).toBe(0)
  })

  test("drivers that never report activity keep owned-prompt behavior", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await s.send(input("only"))
    d.complete()
    expect((await receipt.completed).status).toBe("completed")
    expect(s.snapshot().state).toBe("idle")
    expect(await s.interrupt({ pending: "discard" })).toEqual({ status: "already_idle" })
  })

  test("resume does not invent activity for prior native turns", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("live", "started"))
    d.opens[0]!.onActivity!(activity("live", "completed"))
    await s.close({ mode: "shutdown" })
    const resumed = await core.sessions.resume(s.id)
    expect(resumed.snapshot().state).toBe("idle")
    expect(await resumed.interrupt({ pending: "discard" })).toEqual({ status: "already_idle" })
  })

  test("new activity after interrupt snapshot is not cleared by the old completion", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    ctx.onActivity!(activity("old", "started"))
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    ctx.onActivity!(activity("new", "started"))
    ctx.onActivity!(activity("old", "completed"))
    expect(await stopping).toEqual({ status: "stopped" })
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("new", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("delayed close rejects a pending native interrupt as session_closed, not stopped", async () => {
    const d = activityDriver()
    const gate = d.delayClose()
    const { core } = await setup(d.driver, { limits: { ...TEST_LIMITS, interruptTimeoutMs: 200 } })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("bg", "started"))
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    const closing = s.close({ mode: "shutdown" })
    await tick()
    expect(s.snapshot().state).toBe("closing")
    gate.resolve()
    await closing
    await expect(stopping).rejects.toMatchObject({ code: "session_closed" })
    expect(s.snapshot().state).toBe("closed")
  })

  test("failing close rejects a pending native interrupt as session_failed, not stopped", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("bg", "started"))
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    d.failClose(new Error("native still running"))
    await expect(s.close({ mode: "shutdown" })).rejects.toThrow("native still running")
    await expect(stopping).rejects.toMatchObject({ code: "session_failed" })
    expect(s.snapshot().state).toBe("failed")
  })

  test("onExit during pending interrupt rejects native and owned paths as session_failed", async () => {
    const nativeDriver = activityDriver()
    const { core: nativeCore } = await setup(nativeDriver.driver)
    const nativeSession = await nativeCore.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    nativeDriver.opens[0]!.onActivity!(activity("bg", "started"))
    const nativeStopping = nativeSession.interrupt({ pending: "discard" })
    await tick()
    nativeDriver.opens[0]!.onExit(new Error("transport failed, cleanup pending"))
    await expect(nativeStopping).rejects.toMatchObject({ code: "session_failed" })
    expect(nativeSession.snapshot().state).toBe("failed")

    const ownedDriver = activityDriver()
    const { core: ownedCore } = await setup(ownedDriver.driver)
    const ownedSession = await ownedCore.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await ownedSession.send(input("owned"))
    const ownedStopping = ownedSession.interrupt({ pending: "discard" })
    await tick()
    ownedDriver.opens[0]!.onExit(new Error("transport failed, cleanup pending"))
    await expect(ownedStopping).rejects.toMatchObject({ code: "session_failed" })
    expect((await receipt.completed).status).toBe("failed")
    expect(ownedSession.snapshot().state).toBe("failed")
  })

  test("close-cancelled owned receipt is not native confirmation for interrupt", async () => {
    const d = activityDriver()
    const gate = d.delayClose()
    const { core } = await setup(d.driver, { limits: { ...TEST_LIMITS, interruptTimeoutMs: 200 } })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await s.send(input("owned"))
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    const closing = s.close({ mode: "shutdown" })
    await tick()
    expect((await receipt.completed).status).toBe("cancelled")
    expect(s.snapshot().state).toBe("closing")
    gate.resolve()
    await closing
    await expect(stopping).rejects.toMatchObject({ code: "session_closed" })
  })

  test("uncooperative runtime.interrupt still times out as unconfirmed", async () => {
    const d = activityDriver()
    d.stallInterrupt()
    const { core } = await setup(d.driver, { limits: { ...TEST_LIMITS, interruptTimeoutMs: 20 } })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("bg", "started"))
    expect(await s.interrupt({ pending: "discard" })).toEqual({ status: "unconfirmed" })
    expect(s.snapshot().state).toBe("interrupting")
  })

  test("more than 256 start/complete cycles keep the final outstanding truth", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    for (let i = 0; i < 300; i++) {
      ctx.onActivity!(activity(`cycle-${i}`, "started"))
      ctx.onActivity!(activity(`cycle-${i}`, "completed"))
    }
    expect(s.snapshot().state).toBe("idle")
    ctx.onActivity!(activity("live", "started"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("live", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("live outstanding-id overflow fails the session instead of dropping work", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    for (let i = 0; i < 256; i++) ctx.onActivity!(activity(`id-${i}`, "started"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("overflow", "started"))
    expect(s.snapshot().state).toBe("failed")
    ctx.onActivity!(activity("id-0", "completed"))
    ctx.onActivity!(activity("after", "started"))
    expect(s.snapshot().state).toBe("failed")
  })

  test("open outstanding-id overflow fails create and closes the runtime", async () => {
    let closes = 0
    const driver: AgentDriver = {
      id: "test",
      async open(ctx) {
        for (let i = 0; i < 256; i++) ctx.onActivity!(activity(`id-${i}`, "started"))
        ctx.onActivity!(activity("overflow", "started"))
        return {
          agentSessionId: "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => { closes++ },
        }
      },
    }
    const { core } = await setup(driver)
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })).rejects.toMatchObject({ code: "activity_overflow" })
    expect(closes).toBe(1)
  })

  test("open buffer compaction keeps final truth across many completed cycles", async () => {
    let opened!: DriverContext
    const driver: AgentDriver = {
      id: "test",
      async open(ctx) {
        opened = ctx
        for (let i = 0; i < 300; i++) {
          ctx.onActivity!(activity("repeat", "started"))
          ctx.onActivity!(activity("repeat", "completed"))
        }
        ctx.onActivity!(activity("final", "started"))
        return {
          agentSessionId: "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => {},
        }
      },
    }
    const { core } = await setup(driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(s.snapshot().state).toBe("running")
    opened.onActivity!(activity("final", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("mutating a notice object after onActivity cannot rewrite buffered or live activity", async () => {
    const notice = activity("boot", "started")
    let opened!: DriverContext
    const driver: AgentDriver = {
      id: "test",
      async open(ctx) {
        opened = ctx
        ctx.onActivity!(notice)
        notice.id = "mutated"
        notice.phase = "completed"
        return {
          agentSessionId: "n1",
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => {},
        }
      },
    }
    const { core } = await setup(driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(s.snapshot().state).toBe("running")
    opened.onActivity!({ id: "boot", phase: "completed" })
    expect(s.snapshot().state).toBe("idle")
    const live = activity("live", "started")
    opened.onActivity!(live)
    live.phase = "completed"
    live.id = "other"
    expect(s.snapshot().state).toBe("running")
    opened.onActivity!({ id: "other", phase: "completed" })
    expect(s.snapshot().state).toBe("running")
    opened.onActivity!({ id: "live", phase: "completed" })
    expect(s.snapshot().state).toBe("idle")
  })

  test("malformed notices and unknown completions are no-ops", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const ctx = d.opens[0]!
    ctx.onActivity!(activity("live", "started"))
    ctx.onActivity!({ id: "", phase: "completed" })
    ctx.onActivity!({ id: "live", phase: "nope" as ActivityNotice["phase"] })
    ctx.onActivity!(undefined as unknown as ActivityNotice)
    ctx.onActivity!(activity("missing", "completed"))
    expect(s.snapshot().state).toBe("running")
    ctx.onActivity!(activity("live", "completed"))
    expect(s.snapshot().state).toBe("idle")
  })

  test("native-only activity can steer without a receipt", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("turn-1", "started"))
    await s.steer({ content: [{ type: "text", text: "nudge" }] })
    expect(d.steers).toEqual(["nudge"])
    expect(events.filter(e => e.type === "message.accepted")).toEqual([])
  })

  test("two outstanding native ids reject steer as session_busy", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("a", "started"))
    d.opens[0]!.onActivity!(activity("b", "started"))
    await expect(s.steer({ content: [{ type: "text", text: "x" }] })).rejects.toMatchObject({ code: "session_busy" })
    expect(d.steers).toEqual([])
    const stopping = s.interrupt({ pending: "discard" })
    d.opens[0]!.onActivity!(activity("a", "completed"))
    d.opens[0]!.onActivity!(activity("b", "completed"))
    expect(await stopping).toEqual({ status: "stopped" })
  })

  test("idle steer is session_not_running", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(s.steer({ content: [{ type: "text", text: "x" }] })).rejects.toMatchObject({ code: "session_not_running" })
  })

  test("owned prompt plus one native id can steer", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const receipt = await s.send(input("hello"))
    d.opens[0]!.onActivity!(activity("same-turn", "started"))
    await s.steer({ content: [{ type: "text", text: "nudge" }] })
    expect(d.steers).toEqual(["nudge"])
    d.complete()
    expect((await receipt.completed).status).toBe("completed")
    d.opens[0]!.onActivity!(activity("same-turn", "completed"))
  })

  test("owned prompt plus two native ids reject steer as session_busy", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.send(input("hello"))
    d.opens[0]!.onActivity!(activity("a", "started"))
    d.opens[0]!.onActivity!(activity("b", "started"))
    await expect(s.steer({ content: [{ type: "text", text: "x" }] })).rejects.toMatchObject({ code: "session_busy" })
    expect(d.steers).toEqual([])
  })

  test("interrupting rejects steer as session_busy before running check", async () => {
    const d = activityDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("turn-1", "started"))
    const stopping = s.interrupt({ pending: "discard" })
    await tick()
    await expect(s.steer({ content: [{ type: "text", text: "x" }] })).rejects.toMatchObject({ code: "session_busy" })
    d.opens[0]!.onActivity!(activity("turn-1", "completed"))
    expect(await stopping).toEqual({ status: "stopped" })
  })
})
