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

function controlledDriver() {
  const opens: DriverContext[] = []
  const prompts: string[] = []
  let promptEntered = 0
  let active: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content, signal) {
          promptEntered++
          prompts.push((content[0] as { text: string }).text)
          const work = deferred<{ stopReason: string }>()
          active = work
          const cancel = () => work.resolve({ stopReason: "cancelled" })
          signal.addEventListener("abort", cancel, { once: true })
          try { return await work.promise } finally { signal.removeEventListener("abort", cancel); if (active === work) active = undefined }
        },
        async interrupt() { active?.resolve({ stopReason: "cancelled" }) },
        async close() { active?.resolve({ stopReason: "cancelled" }) },
      }
      return runtime
    },
  }
  return { driver, opens, prompts, get promptEntered() { return promptEntered }, complete: () => active?.resolve({ stopReason: "end_turn" }) }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-msg-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS })
  cores.push(core)
  return { core }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
const input = (text: string, idempotencyKey?: string) => ({ content: [{ type: "text" as const, text }], whenBusy: "queue" as const, idempotencyKey })
const tick = () => new Promise<void>(resolve => queueMicrotask(resolve))
const activity = (id: string, phase: ActivityNotice["phase"]): ActivityNotice => ({ id, phase })
const typesFor = (events: CoreEvent[], messageId: string) =>
  events.filter(e => "messageId" in e && e.messageId === messageId).map(e => e.type)

describe("message.started", () => {
  test("actual dispatch vs queued behind native activity", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("native", "started"))
    const r = await s.send(input("owned"))
    await tick()
    expect(typesFor(events, r.messageId)).toEqual(["message.accepted"])
    expect(d.prompts).toEqual([])
    d.opens[0]!.onActivity!(activity("native", "completed"))
    await tick()
    expect(typesFor(events, r.messageId)).toEqual(["message.accepted", "message.started"])
    expect(d.prompts).toEqual(["owned"])
    d.complete()
    expect(await r.completed).toEqual({ status: "completed", stopReason: "end_turn" })
    await tick()
    expect(typesFor(events, r.messageId)).toEqual(["message.accepted", "message.started", "message.completed"])
  })

  test("pending.cancel and interrupt discard emit no started", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const first = await s.send(input("first"))
    const cancelled = await s.send(input("queued-cancel"))
    expect(s.pending.cancel(cancelled.messageId)).toBe(true)
    expect(await cancelled.completed).toEqual({ status: "cancelled" })
    await tick()
    expect(typesFor(events, cancelled.messageId)).toEqual(["message.accepted", "message.completed"])
    const discarded = await s.send(input("queued-discard"))
    await s.interrupt({ pending: "discard" })
    expect(await discarded.completed).toEqual({ status: "cancelled" })
    await tick()
    expect(typesFor(events, discarded.messageId)).toEqual(["message.accepted", "message.completed"])
    expect(typesFor(events, first.messageId).includes("message.started")).toBe(true)
    d.complete()
    await first.completed
  })

  test("idempotent same key starts once", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const first = await s.send(input("same", "k"))
    const again = await s.send(input("same", "k"))
    expect(again.messageId).toBe(first.messageId)
    await tick()
    expect(events.filter(e => e.type === "message.started" && e.messageId === first.messageId)).toHaveLength(1)
    d.complete()
    await first.completed
    await tick()
    expect(events.filter(e => e.type === "message.started" && e.messageId === first.messageId)).toHaveLength(1)
  })

  test("callback observer is a microtask; actual native prompt can already run", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    let promptAtStarted: number | undefined
    core.subscribe(e => {
      if (e.type === "message.started") promptAtStarted = d.promptEntered
    })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const r = await s.send(input("go"))
    expect(d.promptEntered).toBe(1)
    expect(d.prompts).toEqual(["go"])
    await tick()
    expect(promptAtStarted).toBe(1)
    d.complete()
    await r.completed
  })

  test("autonomous native onActivity does not emit message.started", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    d.opens[0]!.onActivity!(activity("auto", "started"))
    await tick()
    expect(events.filter(e => e.type === "message.started")).toEqual([])
    expect(events.filter(e => e.type === "message.accepted")).toEqual([])
    d.opens[0]!.onActivity!(activity("auto", "completed"))
    await tick()
    expect(events.filter(e => e.type === "message.started")).toEqual([])
  })
})
