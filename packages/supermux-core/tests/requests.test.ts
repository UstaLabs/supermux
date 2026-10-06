import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, CoreError } from "../src/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext, PermissionRequest } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes, reject = no })
  return { promise, resolve, reject }
}

function permissionDriver(options: { dual?: boolean } = {}) {
  const asks: PermissionRequest[] = []
  let last: Awaited<ReturnType<PermissionRequest extends never ? never : DriverContext["requestPermission"]>> | undefined
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      const runtime: AgentRuntime = {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(_content, signal) {
          const body: PermissionRequest = {
            sessionId: "native-1",
            coreSessionId: ctx.sessionId,
            toolCall: { toolCallId: "c1", title: "Bash", kind: "execute", rawInput: { command: "ls" } },
            options: [
              { optionId: "allow_once", name: "Allow once", kind: "allow_once" },
              { optionId: "reject_once", name: "Reject", kind: "reject_once" },
            ],
            detail: { command: "ls" },
          }
          if (options.dual) {
            const second: PermissionRequest = { ...body, toolCall: { ...body.toolCall, toolCallId: "c2" } }
            const [a, b] = await Promise.all([
              ctx.requestPermission(body, signal).then(r => { asks.push(body); return r }),
              ctx.requestPermission(second, signal).then(r => { asks.push(second); return r }),
            ])
            last = b
            return { stopReason: a.outcome.outcome === "cancelled" && b.outcome.outcome === "cancelled" ? "cancelled" : "end_turn" }
          }
          asks.push(body)
          last = await ctx.requestPermission(body, signal)
          return { stopReason: last.outcome.outcome === "cancelled" ? "cancelled" : "end_turn" }
        },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
  return { driver, asks, last: () => last }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
async function setup(driver: AgentDriver, limits = TEST_LIMITS) {
  const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-req-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits })
  cores.push(core)
  return core
}
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

test("permission-request event, respond allow_once, message, errors, interrupt, overflow, snapshot", async () => {
  const fake = permissionDriver()
  const core = await setup(fake.driver)
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  const session = await core.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receipt = await session.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const start = Date.now()
  while (session.requests.list().length === 0 && Date.now() - start < 1000) await new Promise(r => setTimeout(r, 5))
  const listed = session.requests.list()
  expect(listed).toHaveLength(1)
  expect(session.snapshot().pendingRequests).toBe(1)
  const body = listed[0]!.body
  expect(body.kind).toBe("permission-request")
  expect(body.options.map(o => o.id)).toEqual(["allow_once", "reject_once"])
  expect(body.toolCall.callId).toBe("c1")
  expect(body.detail?.command).toBe("ls")
  expect(events.some(e => e.type === "session.event" && e.event.kind === "permission-request")).toBe(true)

  await expect(session.requests.respond("missing", { optionId: "allow_once" })).rejects.toMatchObject({ code: "request_not_found" })
  await expect(session.requests.respond(listed[0]!.requestId, { optionId: "nope" })).rejects.toMatchObject({ code: "invalid_input" })
  expect(session.requests.list()).toHaveLength(1)

  await session.requests.respond(listed[0]!.requestId, { optionId: "allow_once", message: "ok" })
  expect(await receipt.completed).toEqual({ status: "completed", stopReason: "end_turn" })
  expect(fake.last()).toMatchObject({ outcome: { outcome: "selected", optionId: "allow_once" }, message: "ok" })
  expect(session.requests.list()).toHaveLength(0)
  expect(session.snapshot().pendingRequests).toBe(0)
  expect(events.some(e => e.type === "session.event" && e.event.kind === "request-resolved" && e.event.outcome === "answered")).toBe(true)
  // The resolution carries WHAT was chosen — a subscriber cannot recover it from the driver's
  // response, and the UI would otherwise have to guess.
  expect(events.find(e => e.type === "session.event" && e.event.kind === "request-resolved")).toMatchObject({
    event: { answer: { optionId: "allow_once", message: "ok" } },
  })

  const fake2 = permissionDriver()
  const core2 = await setup(fake2.driver)
  const events2: CoreEvent[] = []
  core2.subscribe(e => { events2.push(e) })
  const session2 = await core2.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receipt2 = await session2.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const t2 = Date.now()
  while (session2.requests.list().length === 0 && Date.now() - t2 < 1000) await new Promise(r => setTimeout(r, 5))
  await session2.interrupt({ pending: "discard" })
  expect(await receipt2.completed).toEqual({ status: "cancelled" })
  const t3 = Date.now()
  while (session2.requests.list().length > 0 && Date.now() - t3 < 1000) await new Promise(r => setTimeout(r, 5))
  expect(session2.requests.list()).toEqual([])
  expect(events2.some(e => e.type === "session.event" && e.event.kind === "request-resolved" && e.event.outcome === "cancelled")).toBe(true)

  const fake3 = permissionDriver({ dual: true })
  const core3 = await setup(fake3.driver, { interruptTimeoutMs: 30, maxPending: 1, outstandingActivity: 256 })
  const events3: CoreEvent[] = []
  core3.subscribe(e => { events3.push(e) })
  const session3 = await core3.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receipt3 = await session3.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const t4 = Date.now()
  while (session3.requests.list().length === 0 && Date.now() - t4 < 1000) await new Promise(r => setTimeout(r, 5))
  expect(session3.requests.list()).toHaveLength(1)
  expect(session3.snapshot().pendingRequests).toBe(1)
  const pendingId = session3.requests.list()[0]!.requestId
  await session3.requests.respond(pendingId, { optionId: "allow_once" })
  expect(await receipt3.completed).toEqual({ status: "completed", stopReason: "end_turn" })
  expect(events3.some(e => e.type === "session.event" && e.event.kind === "warning")).toBe(true)
  expect(CoreError).toBeDefined()
})

test("options the library cannot classify are dropped; a request with none usable is cancelled, never auto-allowed", async () => {
  let answers: any[] = []
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      return {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(_content, signal) {
          const base = { sessionId: "native-1", coreSessionId: ctx.sessionId, toolCall: { toolCallId: "c1", title: "Bash", kind: "execute", rawInput: { command: "ls" } } }
          answers.push(await ctx.requestPermission({ ...base, options: [{ optionId: "mystery", name: "Proceed", kind: "mystery" }] } as any, signal))
          const second = ctx.requestPermission({ ...base, options: [{ optionId: "mystery", name: "Proceed", kind: "mystery" }, { optionId: "nope", name: "No, stop", kind: "whatever" }] } as any, signal)
          answers.push(await second)
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() {},
      }
    },
  }
  const core = await setup(driver)
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  const session = await core.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receipt = await session.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const start = Date.now()
  while (session.requests.list().length === 0 && Date.now() - start < 2000) await new Promise(r => setTimeout(r, 5))
  const pending = session.requests.list()
  expect(pending).toHaveLength(1)
  expect(pending[0]!.body.options).toEqual([{ id: "nope", kind: "reject_once", label: "No, stop" }])
  expect(pending[0]!.body.detail).toEqual({ command: "ls" })
  await session.requests.respond(pending[0]!.requestId, { optionId: "nope" })
  expect((await receipt.completed).status).toBe("completed")
  expect(answers[0]).toEqual({ outcome: { outcome: "cancelled" } })
  expect(answers[1]).toMatchObject({ outcome: { outcome: "selected", optionId: "nope" } })
  expect(events.some(e => e.type === "session.event" && e.event.kind === "warning")).toBe(true)
})

function questionDriver() {
  let last: Awaited<ReturnType<DriverContext["requestAnswers"]>> | undefined
  const driver: AgentDriver = {
    id: "test",
    async open(ctx) {
      return {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(_content, signal) {
          last = await ctx.requestAnswers({
            toolCallId: "tq",
            questions: [
              { id: "color", question: "Favorite color?", options: [{ label: "Blue" }, { label: "Red" }] },
              { id: "pets", question: "Pets?", multiSelect: true, options: [{ label: "Cat" }, { label: "Dog" }] },
              { id: "other", question: "Notes?", allowFreeText: true, options: [] },
            ],
          }, signal)
          return { stopReason: last.outcome === "cancelled" || last.outcome === "declined" ? "cancelled" : "end_turn" }
        },
        async interrupt() {},
        async close() {},
      }
    },
  }
  return { driver, last: () => last }
}

test("user-question event, respond answers, decline, invalid_input, interrupt", async () => {
  const fake = questionDriver()
  const core = await setup(fake.driver)
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  const session = await core.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receipt = await session.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const start = Date.now()
  while (session.requests.list().length === 0 && Date.now() - start < 1000) await new Promise(r => setTimeout(r, 5))
  const listed = session.requests.list()
  expect(listed).toHaveLength(1)
  expect(listed[0]!.kind).toBe("question")
  expect(listed[0]!.body.kind).toBe("user-question")
  expect(listed[0]!.body.blocking).toBe(true)
  expect(listed[0]!.body.questions.map(q => q.id)).toEqual(["color", "pets", "other"])
  expect(listed[0]!.body.questions[0]!.options.map(o => o.id)).toEqual(["o1", "o2"])
  expect(events.some(e => e.type === "session.event" && e.event.kind === "user-question")).toBe(true)

  await expect(session.requests.respond(listed[0]!.requestId, { optionId: "allow_once" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(session.requests.respond(listed[0]!.requestId, { answers: { nope: "o1" } })).rejects.toMatchObject({ code: "invalid_input" })

  await session.requests.respond(listed[0]!.requestId, {
    answers: { color: "o1", pets: ["o1", "o2"], other: "freehand" },
  })
  expect(await receipt.completed).toEqual({ status: "completed", stopReason: "end_turn" })
  expect(fake.last()).toEqual({
    outcome: "answered",
    answers: { color: "Blue", pets: ["Cat", "Dog"], other: "freehand" },
  })
  expect(events.some(e => e.type === "session.event" && e.event.kind === "request-resolved" && e.event.outcome === "answered")).toBe(true)
  // Question answers ride the event as the LABELS the agent got, so they read as-is.
  expect(events.find(e => e.type === "session.event" && e.event.kind === "request-resolved")).toMatchObject({
    event: { answer: { answers: { color: "Blue", pets: ["Cat", "Dog"], other: "freehand" } } },
  })

  const fakeD = questionDriver()
  const coreD = await setup(fakeD.driver)
  const sessionD = await coreD.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receiptD = await sessionD.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const td = Date.now()
  while (sessionD.requests.list().length === 0 && Date.now() - td < 1000) await new Promise(r => setTimeout(r, 5))
  await sessionD.requests.respond(sessionD.requests.list()[0]!.requestId, { decline: true })
  expect(await receiptD.completed).toEqual({ status: "cancelled" })
  expect(fakeD.last()).toEqual({ outcome: "declined" })

  const fakeI = questionDriver()
  const coreI = await setup(fakeI.driver)
  const eventsI: CoreEvent[] = []
  coreI.subscribe(e => { eventsI.push(e) })
  const sessionI = await coreI.sessions.create({ agent: "test", cwd: process.cwd(), id: nextId() })
  const receiptI = await sessionI.send({ content: [{ type: "text", text: "go" }], whenBusy: "queue" })
  const ti = Date.now()
  while (sessionI.requests.list().length === 0 && Date.now() - ti < 1000) await new Promise(r => setTimeout(r, 5))
  await sessionI.interrupt({ pending: "discard" })
  expect(await receiptI.completed).toEqual({ status: "cancelled" })
  expect(sessionI.requests.list()).toEqual([])
  expect(eventsI.some(e => e.type === "session.event" && e.event.kind === "request-resolved" && e.event.outcome === "cancelled")).toBe(true)
})

