import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext } from "../src/types.js"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver | AgentDriver[], options = {}) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-close-id-"))
  dirs.push(stateDirectory)
  const agents = Array.isArray(driver) ? driver : [driver]
  const core = createCore({ stateDirectory, agents, interruptTimeoutMs: 30, ...options })
  cores.push(core)
  return { core, stateDirectory }
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close().catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

function capabilities() {
  return { resume: true, steer: false, fork: false, detach: false } as const
}

function runtime(id: string, close: () => Promise<void>): AgentRuntime {
  return {
    agentSessionId: id,
    capabilities: capabilities(),
    prompt: async () => ({ stopReason: "end_turn" }),
    interrupt: async () => {},
    close,
  }
}

describe("sessions.close(id)", () => {
  test("failed open plus failed close blocks create/adopt/resume/forget until close(id) succeeds", async () => {
    let opens = 0
    let allowClose = false
    const closes: number[] = []
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        const n = ++opens
        if (n === 1) ctx.onExit(new Error("setup failed"))
        return runtime(`native-${n}`, async () => {
          closes.push(n)
          if (n === 1 && !allowClose) throw new Error("still alive")
        })
      },
    })
    await expect(core.sessions.create({ id: "same-id", agent: "test", cwd: stateDirectory })).rejects.toBeInstanceOf(AggregateError)
    expect(opens).toBe(1)
    await expect(core.sessions.create({ id: "same-id", agent: "test", cwd: stateDirectory })).rejects.toMatchObject({ code: "session_busy" })
    await expect(core.sessions.adopt({
      id: "same-id", agent: "test", cwd: stateDirectory, agentSessionId: "adopted",
    })).rejects.toMatchObject({ code: "session_busy" })
    await expect(core.sessions.resume("same-id")).rejects.toMatchObject({ code: "session_busy" })
    await expect(core.sessions.forget("same-id")).rejects.toMatchObject({ code: "session_busy" })
    expect(opens).toBe(1)
    await expect(core.sessions.close("same-id")).rejects.toThrow("still alive")
    expect(opens).toBe(1)
    await expect(core.sessions.create({ id: "same-id", agent: "test", cwd: stateDirectory })).rejects.toMatchObject({ code: "session_busy" })
    allowClose = true
    await core.sessions.close("same-id")
    const session = await core.sessions.create({ id: "same-id", agent: "test", cwd: stateDirectory })
    expect(session.snapshot().agentSessionId).toBe("native-2")
    expect(opens).toBe(2)
    expect(closes.filter(n => n === 1).length).toBeGreaterThanOrEqual(2)
  })

  test("close immediately after create cannot succeed then let that create spawn later", async () => {
    const entered = deferred<void>()
    const release = deferred<void>()
    let opens = 0
    let closes = 0
    const { core, stateDirectory } = await setup({
      id: "test",
      async open() {
        opens++
        entered.resolve()
        await release.promise
        return runtime("native-1", async () => { closes++ })
      },
    })
    const creating = core.sessions.create({ id: "held", agent: "test", cwd: stateDirectory })
    const closing = core.sessions.close("held")
    await entered.promise
    expect(opens).toBe(1)
    release.resolve()
    const created = await creating.then(s => s, e => e)
    await closing
    expect(opens).toBe(1)
    expect(closes).toBe(1)
    if (created && typeof created === "object" && "snapshot" in created) {
      expect(created.snapshot().state).toBe("closed")
    }
    const again = await core.sessions.resume("held")
    expect(again.snapshot().state).toBe("idle")
    expect(opens).toBe(2)
  })

  test("close before driver.open is entered still joins create and never opens a second native", async () => {
    let opens = 0
    let closes = 0
    const gate = deferred<void>()
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        opens++
        await gate.promise
        return runtime(ctx.resumeId ?? `native-${opens}`, async () => { closes++ })
      },
    })
    const creating = core.sessions.create({ id: "early", agent: "test", cwd: stateDirectory })
    const closing = core.sessions.close("early")
    await Promise.resolve()
    gate.resolve()
    await closing
    await creating.catch(() => {})
    expect(opens).toBeLessThanOrEqual(1)
    expect(closes).toBe(opens)
    const next = opens === 1
      ? await core.sessions.resume("early")
      : await core.sessions.create({ id: "early", agent: "test", cwd: stateDirectory })
    expect(next.snapshot().state).toBe("idle")
  })

  test("held create close joins and never second-opens", async () => {
    const entered = deferred<void>()
    const release = deferred<void>()
    let opens = 0
    let closeCalls = 0
    const { core, stateDirectory } = await setup({
      id: "test",
      async open() {
        opens++
        entered.resolve()
        await release.promise
        return runtime("n", async () => { closeCalls++ })
      },
    })
    const creating = core.sessions.create({ id: "join-create", agent: "test", cwd: stateDirectory })
    await entered.promise
    const closing = core.sessions.close("join-create")
    expect(opens).toBe(1)
    release.resolve()
    await closing
    await creating.catch(() => {})
    expect(opens).toBe(1)
    expect(closeCalls).toBe(1)
  })

  test("held resume close joins and never second-opens", async () => {
    const { core, stateDirectory } = await setup({
      id: "test",
      async open() {
        return runtime("saved", async () => {})
      },
    })
    const session = await core.sessions.create({ id: "resume-me", agent: "test", cwd: stateDirectory })
    await session.close()
    await core.close()
    const entered = deferred<void>()
    const release = deferred<void>()
    let opens = 0
    let closes = 0
    const next = createCore({
      stateDirectory,
      agents: [{
        id: "test",
        async open(ctx) {
          opens++
          entered.resolve()
          await release.promise
          return runtime(ctx.resumeId ?? "x", async () => { closes++ })
        },
      }],
    })
    cores.push(next)
    const resuming = next.sessions.resume("resume-me")
    await entered.promise
    const closing = next.sessions.close("resume-me")
    expect(opens).toBe(1)
    release.resolve()
    await closing
    await resuming.catch(() => {})
    expect(opens).toBe(1)
    expect(closes).toBe(1)
  })

  test("simultaneous close of the same leftover id performs one close attempt", async () => {
    let allow = false
    let closeCalls = 0
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        ctx.onExit(new Error("setup failed"))
        return runtime("n1", async () => {
          closeCalls++
          if (!allow) throw new Error("still alive")
        })
      },
    })
    await expect(core.sessions.create({ id: "one", agent: "test", cwd: stateDirectory })).rejects.toBeInstanceOf(AggregateError)
    const firstAttempt = closeCalls
    expect(firstAttempt).toBe(1)
    const [a, b] = await Promise.allSettled([core.sessions.close("one"), core.sessions.close("one")])
    expect(a.status).toBe("rejected")
    expect(b.status).toBe("rejected")
    expect(closeCalls).toBe(firstAttempt + 1)
    allow = true
    await Promise.all([core.sessions.close("one"), core.sessions.close("one")])
    expect(closeCalls).toBe(firstAttempt + 2)
  })

  test("close(id) versus global close does not double-close leftover or deadlock", async () => {
    const entered = deferred<void>()
    const release = deferred<void>()
    let closeCalls = 0
    const { core, stateDirectory } = await setup({
      id: "test",
      async open() {
        entered.resolve()
        await release.promise
        return runtime("live", async () => { closeCalls++ })
      },
    })
    const creating = core.sessions.create({ id: "race", agent: "test", cwd: stateDirectory })
    await entered.promise
    const perId = core.sessions.close("race")
    const global = core.close()
    release.resolve()
    await perId
    await global
    await creating.catch(() => {})
    expect(closeCalls).toBe(1)
  })

  test("failed leftover on A does not block B; close(A) does not close B", async () => {
    const closes: string[] = []
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        if (ctx.sessionId === "A") ctx.onExit(new Error("setup failed"))
        return runtime(ctx.sessionId, async () => {
          closes.push(ctx.sessionId)
          if (ctx.sessionId === "A") throw new Error("A still alive")
        })
      },
    })
    await expect(core.sessions.create({ id: "A", agent: "test", cwd: stateDirectory })).rejects.toBeInstanceOf(AggregateError)
    const b = await core.sessions.create({ id: "B", agent: "test", cwd: stateDirectory })
    expect(b.snapshot().state).toBe("idle")
    expect(closes).toEqual(["A"])
    await expect(core.sessions.close("A")).rejects.toThrow("A still alive")
    expect(b.snapshot().state).toBe("idle")
    expect(closes).toEqual(["A", "A"])
  })

  test("fork open tracks the new id so fire-and-forget close waits for held open", async () => {
    const hold = deferred<void>()
    const entered = deferred<void>()
    let closes = 0
    let closeDone = false
    let closing: Promise<void> | undefined
    let coreRef: ReturnType<typeof createCore> | undefined
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        if (ctx.forkFrom) {
          closing = coreRef!.sessions.close(ctx.sessionId)
          void closing.then(() => { closeDone = true })
          entered.resolve()
          await hold.promise
          expect(closeDone).toBe(false)
          return {
            agentSessionId: "child-native",
            capabilities: { resume: true, steer: false, fork: true, detach: false },
            prompt: async () => ({ stopReason: "end_turn" }),
            interrupt: async () => {},
            close: async () => { closes++ },
          }
        }
        return {
          agentSessionId: "parent-native",
          capabilities: { resume: true, steer: false, fork: true, detach: false },
          prompt: async () => ({ stopReason: "end_turn" }),
          interrupt: async () => {},
          close: async () => { closes++ },
        }
      },
    })
    coreRef = core
    const parent = await core.sessions.create({ id: "parent", agent: "test", cwd: stateDirectory })
    const forking = parent.fork()
    await entered.promise
    expect(closeDone).toBe(false)
    hold.resolve()
    const child = await forking.catch(e => e)
    await closing
    expect(closeDone).toBe(true)
    expect(closes).toBeGreaterThanOrEqual(1)
    if (child && typeof child === "object" && "snapshot" in child) {
      expect(child.snapshot().state).toBe("closed")
    }
  })

  test("close never reports complete while an accepted replacement startup is held", async () => {
    const bad: { ticks: number; opens: number; secondClosed: boolean }[] = []
    for (let ticks = 0; ticks < 24; ticks++) {
      const oldHold = deferred<void>()
      const newHold = deferred<void>()
      let opens = 0
      let oldClosing = false
      let secondClosed = false
      const { core, stateDirectory } = await setup({
        id: "t",
        async open(ctx: DriverContext) {
          const n = ++opens
          if (n === 2) await newHold.promise
          return runtime(ctx.resumeId ?? "native", async () => {
            if (n === 1) {
              oldClosing = true
              await oldHold.promise
            }
          })
        },
      })
      await core.sessions.create({ id: "s", agent: "t", cwd: stateDirectory })
      const firstClose = core.sessions.close("s")
      while (!oldClosing) await Promise.resolve()
      oldHold.resolve()
      for (let i = 0; i < ticks; i++) await Promise.resolve()
      const opening = core.sessions.resume("s").catch(e => e)
      const secondClose = core.sessions.close("s").then(() => { secondClosed = true })
      await new Promise(r => setTimeout(r, 15))
      if (opens === 2 && secondClosed) bad.push({ ticks, opens, secondClosed })
      newHold.resolve()
      await Promise.all([firstClose, opening, secondClose])
    }
    expect(bad).toEqual([])
  })

  test("unknown close is idempotent and does not open", async () => {
    let opens = 0
    const { core } = await setup({
      id: "test",
      async open() {
        opens++
        return runtime("n", async () => {})
      },
    })
    await core.sessions.close("nobody")
    await core.sessions.close("nobody")
    expect(opens).toBe(0)
    await expect(core.sessions.close("bad id")).rejects.toMatchObject({ code: "invalid_session_id" })
    expect(opens).toBe(0)
  })

  test("global close retries leftover after failed per-id cleanup", async () => {
    let closes = 0
    const { core, stateDirectory } = await setup({
      id: "test",
      async open(ctx: DriverContext) {
        ctx.onExit(new Error("setup failed"))
        return runtime("n1", async () => {
          closes++
          if (closes < 3) throw new Error("still alive")
        })
      },
    })
    await expect(core.sessions.create({ id: "retry", agent: "test", cwd: stateDirectory })).rejects.toBeInstanceOf(AggregateError)
    await expect(core.sessions.close("retry")).rejects.toThrow("still alive")
    await core.close()
    expect(closes).toBe(3)
  })
})
