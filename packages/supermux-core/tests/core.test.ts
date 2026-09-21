import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, Completion, DriverContext, CoreEvent } from "../src/types.js"
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
  let closes = 0
  let active: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content, signal) {
          prompts.push((content[0] as { text: string }).text)
          const work = deferred<{ stopReason: string }>()
          active = work
          const cancel = () => work.resolve({ stopReason: "cancelled" })
          signal.addEventListener("abort", cancel, { once: true })
          try { return await work.promise } finally { signal.removeEventListener("abort", cancel); if (active === work) active = undefined }
        },
        async interrupt() { active?.resolve({ stopReason: "cancelled" }) },
        async close() { closes++; active?.resolve({ stopReason: "cancelled" }) },
      }
      return runtime
    },
  }
  return { driver, opens, prompts, get closes() { return closes }, complete: () => active?.resolve({ stopReason: "end_turn" }) }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver, options = {}) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS, ...options })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
const input = (text: string, idempotencyKey?: string) => ({ content: [{ type: "text" as const, text }], whenBusy: "queue" as const, idempotencyKey })
const tick = () => new Promise<void>(resolve => setTimeout(resolve, 0))

describe("session lifecycle", () => {
  test("persists native identity across core instances; reading never spawns", async () => {
    const d = controlledDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    expect(session.snapshot().state).toBe("idle")
    const record = await core.sessions.get(session.id)
    expect(record?.agentSessionId).toBe("native-1")
    await core.close({ agents: "shutdown" })
    expect(d.closes).toBe(1)
    const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [d.driver] })
    cores.push(next)
    expect((await next.sessions.list()).map(s => s.id)).toEqual([session.id])
    expect(d.opens).toHaveLength(1)
    const resumed = await next.sessions.resume(session.id)
    expect(resumed.id).toBe(session.id)
    expect(d.opens[1]?.resumeId).toBe("native-1")
    expect(await next.sessions.resume(session.id)).toBe(resumed)
  })

  test("missing resume fails without creating a new agent conversation", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    await expect(core.sessions.resume("missing")).rejects.toMatchObject({ code: "session_not_found" })
    expect(d.opens).toHaveLength(0)
  })

  test("failed resume preserves saved history and does not retry new", async () => {
    const d = controlledDriver()
    const { core, stateDirectory } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await core.close({ agents: "shutdown" })
    const requests: DriverContext[] = []
    const next = createCore({ stateDirectory, agents: [{ id: "test", async open(ctx) { requests.push(ctx); throw new Error("history gone") } }], limits: TEST_LIMITS })
    cores.push(next)
    await expect(next.sessions.resume(s.id)).rejects.toThrow("history gone")
    expect(requests).toHaveLength(1)
    expect(requests[0]?.resumeId).toBe("native-1")
    expect((await next.sessions.get(s.id))?.agentSessionId).toBe("native-1")
  })

  test("close waits for an opening session and releases the late runtime", async () => {
    const opening = deferred<void>()
    const entered = deferred<void>()
    let closed = 0
    const { core } = await setup({ id: "slow", async open() {
      entered.resolve()
      await opening.promise
      return { agentSessionId: "n", capabilities: { resume: true, fork: false, steer: false, detach: false },
        prompt: async () => ({ stopReason: "end_turn" }), interrupt: async () => {}, close: async () => { closed++ } }
    } })
    const creating = core.sessions.create({ id: nextId(), agent: "slow", cwd: tmpdir() }).catch(e => e)
    await entered.promise
    const closing = core.close({ agents: "shutdown" })
    opening.resolve()
    await closing
    expect(await creating).toMatchObject({ code: "core_closed" })
    expect(closed).toBe(1)
  })

  test("fork, detach and steer are explicit unsupported operations", async () => {
    const { core } = await setup(controlledDriver().driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await expect(s.fork({ id: nextId() })).rejects.toMatchObject({ code: "unsupported_operation" })
    await expect(s.detach()).rejects.toMatchObject({ code: "unsupported_operation" })
    await expect(s.steer(input("change"))).rejects.toMatchObject({ code: "unsupported_operation" })
  })
})

describe("input ownership", () => {
  test("an observer may close a session from its closing event without reentrant teardown", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    let callbacks = 0
    core.subscribe(event => {
      if (event.type === "session.stateChanged" && event.state === "closing") {
        callbacks++
        if (callbacks < 5) void s.close({ mode: "shutdown" })
      }
    })
    await s.close({ mode: "shutdown" })
    await tick()
    expect(d.closes).toBe(1)
  })

  test("unconfirmed interrupt never resumes the queue or reports idle", async () => {
    const d = controlledDriver()
    const open = d.driver.open
    d.driver.open = async ctx => ({ ...await open(ctx), interrupt: async () => {} })
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.send(input("first"))
    await s.send(input("second"))
    expect(await s.interrupt({ pending: "keep" })).toEqual({ status: "unconfirmed" })
    expect(s.snapshot().state).toBe("interrupting")
    expect(() => s.pending.continue()).toThrow()
    expect(d.prompts).toEqual(["first"])
  })

  test("forget and resume cannot both mutate the same saved conversation", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.close({ mode: "shutdown" })
    // The first operation owns the record until its storage operation finishes.
    const [forget, resume] = await Promise.allSettled([core.sessions.forget(s.id), core.sessions.resume(s.id)])
    expect(forget.status).toBe("fulfilled")
    expect(resume).toMatchObject({ status: "rejected", reason: { code: "session_busy" } })
  })

  test("profiles resolve for auth and sessions without persisting their environment", async () => {
    const d = controlledDriver()
    let loginProfile: unknown
    d.driver.auth = { methods: async () => [{ id: "token", name: "Token" }], login: async ctx => { loginProfile = ctx.profile } }
    const { core, stateDirectory } = await setup(d.driver, { profiles: { work: { agent: "test", env: { TOKEN: "private" }, methodId: "token" } } })
    expect((await core.auth.methods({ agent: "test", profile: "work" }))[0]?.id).toBe("token")
    await core.auth.login({ agent: "test", profile: "work", methodId: "token" })
    expect(loginProfile).toEqual({ agent: "test", env: { TOKEN: "private" }, methodId: "token" })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), authProfile: "work" })
    expect(d.opens[0]?.profile?.env).toEqual({ TOKEN: "private" })
    expect(JSON.stringify(await core.sessions.get(s.id))).not.toContain("private")
    await expect(core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), authProfile: "missing" })).rejects.toMatchObject({ code: "invalid_auth_profile" })
  })

  test("concurrent resume opens one runtime", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.close({ mode: "shutdown" })
    const [a, b] = await Promise.all([core.sessions.resume(s.id), core.sessions.resume(s.id)])
    expect(a).toBe(b)
    expect(d.opens).toHaveLength(2)
  })

  test("acceptance is immediate, execution is sequential, duplicate in-flight input runs once", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const [first, duplicate] = await Promise.all([s.send(input("first", "key")), s.send(input("first", "key"))])
    expect(first.messageId).toBe(duplicate.messageId)
    const second = await s.send(input("second"))
    expect(d.prompts).toEqual(["first"])
    d.complete()
    expect(await first.completed).toEqual({ status: "completed", stopReason: "end_turn" })
    await tick()
    expect(d.prompts).toEqual(["first", "second"])
    d.complete()
    expect((await second.completed).status).toBe("completed")
  })

  test("busy reject and conflicting idempotency key never execute extra input", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.send(input("first", "key"))
    await expect(s.send({ ...input("second"), whenBusy: "reject" })).rejects.toMatchObject({ code: "session_busy" })
    await expect(s.send(input("different", "key"))).rejects.toMatchObject({ code: "idempotency_conflict" })
    expect(d.prompts).toEqual(["first"])
  })

  test("interrupt keep pauses retained input until explicitly continued", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const first = await s.send(input("first"))
    const second = await s.send(input("second"))
    expect(await s.interrupt({ pending: "keep" })).toEqual({ status: "stopped" })
    expect(await first.completed).toEqual({ status: "cancelled" })
    await tick()
    expect(d.prompts).toEqual(["first"])
    expect(s.pending.list()).toEqual([{ messageId: second.messageId }])
    s.pending.continue()
    await tick()
    expect(d.prompts).toEqual(["first", "second"])
  })

  test("queued cancellation settles receipt; close never starts leftover input", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const first = await s.send(input("first"))
    const second = await s.send(input("second"))
    expect(s.pending.cancel(second.messageId)).toBe(true)
    expect(await second.completed).toEqual({ status: "cancelled" })
    const third = await s.send(input("third"))
    await Promise.all([s.close({ mode: "shutdown" }), s.close({ mode: "shutdown" })])
    expect(await first.completed).toEqual({ status: "cancelled" })
    expect(await third.completed).toEqual({ status: "cancelled" })
    expect(d.prompts).toEqual(["first"])
    expect(d.closes).toBe(1)
    await expect(s.send(input("late"))).rejects.toMatchObject({ code: "session_closed" })
  })

  test("observer errors cannot turn accepted input into a retryable failure", async () => {
    const d = controlledDriver()
    const errors: Error[] = []
    const events: CoreEvent[] = []
    const { core } = await setup(d.driver, { onObserverError: (e: Error) => errors.push(e) })
    core.subscribe(() => { throw new Error("consumer broke") })
    core.subscribe(async () => { throw new Error("async observer broke") })
    core.subscribe(e => { events.push(e) })
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const r = await s.send(input("first"))
    d.complete()
    expect((await r.completed).status).toBe("completed")
    await tick()
    expect(errors.length).toBeGreaterThan(0)
    expect(events.some(e => e.type === "message.accepted")).toBe(true)
  })

  test("runtime exit settles active and queued work with failures", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    const first = await s.send(input("first"))
    const second = await s.send(input("second"))
    d.opens[0]!.onExit(new Error("worker died"))
    expect((await first.completed).status).toBe("failed")
    expect((await second.completed).status).toBe("failed")
    expect(s.snapshot().state).toBe("failed")
    expect(d.prompts).toEqual(["first"])
  })

  test("late callbacks from a closed runtime cannot fail the resumed handle", async () => {
    const d = controlledDriver()
    const { core } = await setup(d.driver)
    const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
    await s.close({ mode: "shutdown" })
    const resumed = await core.sessions.resume(s.id)
    d.opens[0]!.onExit(new Error("late exit"))
    expect(resumed.snapshot().state).toBe("idle")
  })
})

test("failed shutdown retains state ownership and can be retried", async () => {
  const d = controlledDriver()
  const open = d.driver.open
  let attempts = 0
  d.driver.open = async ctx => ({ ...await open(ctx), close: async () => { if (++attempts === 1) throw new Error("still alive") } })
  const { core, stateDirectory } = await setup(d.driver)
  await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  await expect(core.close({ agents: "shutdown" })).rejects.toThrow()
  const other = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [] })
  cores.push(other)
  await expect(other.sessions.list()).rejects.toThrow()
  await core.close({ agents: "shutdown" })
  const next = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [] })
  cores.push(next)
  expect(await next.sessions.list()).toHaveLength(1)
})

test("failed cleanup of a late-opening runtime stays owned until retry", async () => {
  const entered = deferred<void>()
  const opening = deferred<void>()
  let attempts = 0
  const { core, stateDirectory } = await setup({ id: "late", async open() {
    entered.resolve()
    await opening.promise
    return {
      agentSessionId: "late-native", capabilities: { resume: false, steer: false, fork: false, detach: false },
      prompt: async () => ({ stopReason: "end_turn" }), interrupt: async () => {},
      close: async () => { if (++attempts <= 2) throw new Error("cleanup failed") },
    }
  } })
  const creating = core.sessions.create({ id: nextId(), agent: "late", cwd: tmpdir() }).catch(e => e)
  await entered.promise
  const closing = core.close({ agents: "shutdown" })
  opening.resolve()
  await expect(closing).rejects.toThrow()
  await creating
  const other = createCore({
    limits: TEST_LIMITS, stateDirectory, agents: [] })
  cores.push(other)
  await expect(other.sessions.list()).rejects.toThrow()
  await core.close({ agents: "shutdown" })
})

test("an asynchronous observer error reporter cannot leak rejections", async () => {
  let reported = 0
  const { core } = await setup(controlledDriver().driver, {
    onObserverError: async () => { reported++; throw new Error("reporter failed") },
  })
  core.subscribe(() => { throw new Error("observer failed") })
  const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  await tick()
  expect(reported).toBeGreaterThan(0)
  expect(s.snapshot().state).toBe("idle")
})

test("fork persists lineage and uses native checkpoint identity", async () => {
  const d = controlledDriver()
  const open = d.driver.open
  d.driver.open = async ctx => ({ ...await open(ctx), capabilities: {resume:true,steer:false,fork:true,detach:false} })
  const {core}=await setup(d.driver)
  const parent=await core.sessions.create({ id: nextId(), agent:'test',cwd:tmpdir()})
  const child=await parent.fork({ id: nextId(), at:{nativeTurnId:'turn-1'}})
  expect(child.id).not.toBe(parent.id)
  expect(d.opens[1]?.forkFrom).toEqual({agentSessionId:'native-1',at:{nativeTurnId:'turn-1'}})
  expect((await core.sessions.get(child.id))?.lineage).toEqual({parentSessionId:parent.id,nativeTurnId:'turn-1'})
  await child.close({ mode: "shutdown" })
  const resumed=await core.sessions.resume(child.id)
  expect(resumed.snapshot().lineage?.parentSessionId).toBe(parent.id)
  expect(d.opens[2]?.forkFrom).toBeUndefined()
})

test("fork excludes new source input until native fork settles",async()=>{
  const d=controlledDriver(), open=d.driver.open, entered=deferred<void>(), release=deferred<void>()
  d.driver.open=async ctx=>{
    if(ctx.forkFrom){entered.resolve();await release.promise}
    return {...await open(ctx),capabilities:{resume:true,steer:false,fork:true,detach:false}}
  }
  const {core}=await setup(d.driver)
  const source=await core.sessions.create({ id: nextId(), agent:'test',cwd:tmpdir()})
  const fork=source.fork({ id: nextId() });await entered.promise
  await expect(source.send(input('racing'))).rejects.toMatchObject({code:'session_busy'})
  release.resolve();await fork
})

test("fork rejects a native runtime reusing its parent's identity",async()=>{
  const d=controlledDriver(),open=d.driver.open
  d.driver.open=async ctx=>({...await open(ctx),agentSessionId:'same',capabilities:{resume:true,steer:false,fork:true,detach:false}})
  const {core}=await setup(d.driver),source=await core.sessions.create({ id: nextId(), agent:'test',cwd:tmpdir()})
  await expect(source.fork({ id: nextId() })).rejects.toMatchObject({code:'fork_identity_unchanged'})
  expect(await core.sessions.list()).toHaveLength(1)
})

test("Session.close without mode throws TypeError before teardown", async () => {
  const d = controlledDriver()
  const { core } = await setup(d.driver)
  const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  expect(() => (s as any).close()).toThrow(TypeError)
  expect(() => (s as any).close()).toThrow(/close mode is required/)
  expect(d.closes).toBe(0)
  expect(s.snapshot().state).toBe("idle")
  await s.close({ mode: "shutdown" })
})

test("createCore without limits throws TypeError", () => {
  expect(() => createCore({ stateDirectory: "/tmp", agents: [] } as any)).toThrow(TypeError)
  expect(() => createCore({ stateDirectory: "/tmp", agents: [] } as any)).toThrow(/limits/)
})

test("each invalid limit throws TypeError naming the field", () => {
  const base = { interruptTimeoutMs: 10, maxPending: 1, outstandingActivity: 1 }
  for (const field of ["interruptTimeoutMs", "maxPending", "outstandingActivity"] as const) {
    for (const bad of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => createCore({ stateDirectory: "/tmp", agents: [], limits: { ...base, [field]: bad } as any })).toThrow(TypeError)
      expect(() => createCore({ stateDirectory: "/tmp", agents: [], limits: { ...base, [field]: bad } as any })).toThrow(new RegExp(field))
    }
  }
})

test("create without id throws TypeError", async () => {
  const d = controlledDriver()
  const { core } = await setup(d.driver)
  await expect(core.sessions.create({ agent: "test", cwd: tmpdir() } as any)).rejects.toThrow(TypeError)
  await expect(core.sessions.create({ agent: "test", cwd: tmpdir() } as any)).rejects.toThrow(/id/)
})

test("interrupt without pending throws TypeError", async () => {
  const d = controlledDriver()
  const { core } = await setup(d.driver)
  const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  await expect((s as any).interrupt()).rejects.toThrow(TypeError)
  await expect((s as any).interrupt()).rejects.toThrow(/pending/)
})

test("outstandingActivity 2 fails the session on a third distinct native activity", async () => {
  const d = controlledDriver()
  const { core } = await setup(d.driver, { limits: { interruptTimeoutMs: 30, maxPending: 128, outstandingActivity: 2 } })
  const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  d.opens[0]!.onActivity!({ id: "a", phase: "started" })
  d.opens[0]!.onActivity!({ id: "b", phase: "started" })
  expect(s.snapshot().state).toBe("running")
  d.opens[0]!.onActivity!({ id: "c", phase: "started" })
  expect(s.snapshot().state).toBe("failed")
})

test("detach on a non-keeper driver is unsupported with no side effect", async () => {
  const d = controlledDriver()
  const { core } = await setup(d.driver)
  const s = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir() })
  expect(() => s.close({ mode: "detach" })).toThrow(/does not support detach/)
  expect(d.closes).toBe(0)
  expect(s.snapshot().state).toBe("idle")
  await s.close({ mode: "shutdown" })
})
