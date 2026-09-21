import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../../../../packages/supermux-core/src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { CoreGrokAdapter } from "./core-adapter"

const tick = () => new Promise<void>((r) => setTimeout(r, 0))
const flush = async () => { await tick(); await tick() }
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
async function waitUntil(pred: () => boolean, tries = 40) {
  for (let i = 0; i < tries && !pred(); i++) await tick()
  expect(pred()).toBe(true)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function fakeAgentDriver(options: { configure?: boolean; nativeId?: string } = {}) {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  const applied: SessionConfiguration[] = []
  let closes = 0
  let interruptCalls = 0
  let liveConfig: SessionConfiguration = {}
  let promptGate: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  let promptSignal: AbortSignal | undefined
  let holdPrompt = false
  let nextFail: Error | undefined
  let interruptHangs = false
  let ignoreAbort = false
  const attachmentHold = deferred<string>()
  let attachmentWaiting: ReturnType<typeof deferred<string>> | undefined

  const driver: AgentDriver = {
    id: "grok",
    async open(ctx) {
      opens.push(ctx)
      if (ctx.configuration) liveConfig = { ...ctx.configuration }
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
        capabilities: {
          resume: true, steer: false, fork: false, detach: false,
          configure: options.configure !== false, history: false,
        },
        async prompt(content, signal) {
          prompts.push(content.map((c) => ("text" in c ? String(c.text) : "")))
          if (nextFail) {
            const err = nextFail
            nextFail = undefined
            throw err
          }
          promptSignal = signal
          const work = deferred<{ stopReason: string }>()
          promptGate = work
          const cancel = () => work.resolve({ stopReason: "cancelled" })
          if (!ignoreAbort) signal.addEventListener("abort", cancel, { once: true })
          if (!holdPrompt) work.resolve({ stopReason: "end_turn" })
          try { return await work.promise }
          finally {
            signal.removeEventListener("abort", cancel)
            if (promptGate === work) promptGate = undefined
          }
        },
        async interrupt() {
          interruptCalls++
          if (interruptHangs) return
          promptGate?.resolve({ stopReason: "cancelled" })
        },
        async close() { closes++; promptGate?.resolve({ stopReason: "cancelled" }) },
        async configure(configuration) {
          liveConfig = { ...configuration }
          applied.push({ ...configuration })
        },
        configuration: () => ({ ...liveConfig }),
      }
      return runtime
    },
  }

  return {
    driver, opens, prompts, applied,
    get closes() { return closes },
    get interruptCalls() { return interruptCalls },
    holdNextPrompt() { holdPrompt = true },
    completePrompt() { promptGate?.resolve({ stopReason: "end_turn" }); holdPrompt = false },
    failNextPrompt(error: Error) { nextFail = error },
    hangInterrupt() { interruptHangs = true },
    ignoreAbort() { ignoreAbort = true },
    emit(update: { protocol: "acp" | "native"; value: unknown; replay?: boolean }) {
      opens.at(-1)?.onUpdate(update as Parameters<DriverContext["onUpdate"]>[0])
    },
    startActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "started" }) },
    completeActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "completed" }) },
    failRuntime(error: Error) { opens.at(-1)?.onExit(error) },
    get lastCtx() { return opens.at(-1) },
    waitAttachment: () => { attachmentWaiting = attachmentHold; return attachmentHold },
  }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
const adapters: CoreGrokAdapter[] = []

async function harness(driver: AgentDriver = fakeAgentDriver().driver) {
  const workdir = await mkdtemp(join(tmpdir(), "grok-wd-"))
  const stateDirectory = await mkdtemp(join(tmpdir(), "grok-core-"))
  dirs.push(workdir, stateDirectory)
  const core = createCore({
    stateDirectory,
    agents: [driver],
    limits: { interruptTimeoutMs: 40, maxPending: 128, outstandingActivity: 256 },
  })
  cores.push(core)
  return { core, workdir, stateDirectory }
}

afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(cores.splice(0).map((c) => c.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})

function listen(adapter: CoreGrokAdapter) {
  const events: any[] = []
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete", "error", "commands-update"]) {
    adapter.on(k, (e) => events.push(e))
  }
  return events
}

test("adopt uses exact resume id and never session/new (no extra open without resumeId)", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-prior" })
  const { core, workdir } = await harness(fake.driver)
  const persisted: string[] = []
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    initialSessionId: "native-prior",
    persistSessionId: async (id) => { persisted.push(id) },
  })
  adapters.push(adapter)
  await adapter.start()
  expect(fake.opens).toHaveLength(1)
  expect(fake.opens[0]?.resumeId).toBe("native-prior")
  expect(persisted).toEqual(["native-prior"])
  const record = await core.sessions.get("sess-1")
  expect(record?.agentSessionId).toBe("native-prior")
})

test("existing core record with mismatched agent/cwd/native id is rejected", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  await core.sessions.create({ id: "sess-1", agent: "grok", cwd: workdir })
  const other = await mkdtemp(join(tmpdir(), "grok-wd-"))
  dirs.push(other)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir: other,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  await expect(adapter.start()).rejects.toThrow(/cwd mismatch/)
  expect(fake.opens).toHaveLength(1) // create only
})

test("existing record with wrong native id is rejected before a replacement open", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-a" })
  const { core, workdir } = await harness(fake.driver)
  await core.sessions.create({ id: "sess-1", agent: "grok", cwd: workdir })
  const opensAfterCreate = fake.opens.length
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    initialSessionId: "native-other",
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  await expect(adapter.start()).rejects.toThrow(/native id mismatch/)
  expect(fake.opens.length).toBe(opensAfterCreate)
})

test("new session creates with broker id and persists native id", async () => {
  const fake = fakeAgentDriver({ nativeId: "minted" })
  const { core, workdir } = await harness(fake.driver)
  const persisted: string[] = []
  const adapter = new CoreGrokAdapter({
    core, id: "broker-id", sessionName: "s1", workdir,
    persistSessionId: async (id) => { persisted.push(id) },
  })
  adapters.push(adapter)
  await adapter.start()
  expect(fake.opens[0]?.resumeId).toBeUndefined()
  expect(persisted).toEqual(["minted"])
  expect((await core.sessions.get("broker-id"))?.agentSessionId).toBe("minted")
})

test("persist failure closes the opened session and leaves adapter stopped", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => { throw new Error("disk full") },
  })
  adapters.push(adapter)
  await expect(adapter.start()).rejects.toThrow("disk full")
  expect(fake.closes).toBe(1)
  await expect(adapter.send("hi")).rejects.toThrow(/not initialized|stopped/)
})

test("send waits for completion and serializes attachment resolution before later messages", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const order: string[] = []
  let releaseFirst!: (path: string) => void
  const firstPath = new Promise<string>((r) => { releaseFirst = r })
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async (id) => {
      order.push(`resolve:${id}`)
      if (id === "a") return firstPath
      return `/tmp/${id}`
    },
  })
  adapters.push(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one", { attachment_file_id: "a" })
  await tick()
  const second = adapter.send("two", { attachment_file_id: "b" })
  await tick()
  expect(order).toEqual(["resolve:a"])
  expect(fake.prompts).toHaveLength(0)
  releaseFirst("/tmp/a.txt")
  await tick()
  fake.completePrompt()
  await first
  fake.holdNextPrompt()
  await tick()
  expect(order).toEqual(["resolve:a", "resolve:b"])
  expect(fake.prompts[0]?.[0]).toContain("[Attached file: /tmp/a.txt]")
  fake.completePrompt()
  await second
  expect(fake.prompts[1]?.[0]).toContain("[Attached file: /tmp/b]")
})

test("stop during attachment resolution does not deliver the prompt", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const gate = deferred<string>()
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => gate.promise,
  })
  adapters.push(adapter)
  await adapter.start()
  const sent = adapter.send("hi", { attachment_file_id: "x" })
  await tick()
  await adapter.stop()
  gate.resolve("/tmp/x")
  await expect(sent).rejects.toThrow(/stopped/)
  expect(fake.prompts).toHaveLength(0)
})

test("interrupt discards queued core work, invalidates pre-interrupt inputs, and surfaces unconfirmed", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const attach = deferred<string>()
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => attach.promise,
  })
  adapters.push(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  for (let i = 0; i < 20 && fake.prompts.length === 0; i++) await tick()
  expect(fake.prompts).toHaveLength(1)
  const second = adapter.send("two", { attachment_file_id: "held" })
  await tick()
  await adapter.interrupt()
  await first
  attach.resolve("/tmp/held")
  await expect(second).rejects.toThrow(/stopped/)
  expect(fake.prompts).toHaveLength(1)

  fake.hangInterrupt()
  fake.holdNextPrompt()
  const third = adapter.send("three")
  for (let i = 0; i < 20 && fake.prompts.length < 2; i++) await tick()
  await expect(adapter.interrupt()).rejects.toThrow(/unconfirmed/)
  fake.completePrompt()
  await third.catch(() => {})
})

test("new user input after confirmed interrupt continues the paused empty queue", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await tick()
  await adapter.interrupt()
  await first
  fake.holdNextPrompt()
  const second = adapter.send("two")
  await tick()
  fake.completePrompt()
  await second
  expect(fake.prompts.map((p) => p[0])).toEqual(["one", "two"])
})

test("skips replay chat but still applies commands and initialize metadata", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.emit({
    protocol: "native",
    value: { method: "initialize", params: { _meta: { modelState: { availableModels: [{ modelId: "grok-4.5" }] }, availableCommands: [{ name: "compact" }] } } },
  })
  fake.emit({
    protocol: "acp",
    replay: true,
    value: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "old history" } },
  })
  fake.emit({
    protocol: "acp",
    replay: true,
    value: { sessionUpdate: "available_commands_update", availableCommands: [{ name: "soul" }] },
  })
  await flush()
  expect(adapter.availableModels.map((m) => m.modelId)).toEqual(["grok-4.5"])
  expect(adapter.availableCommands.map((c) => c.name)).toEqual(["soul"])
  expect(events.some((e) => e.kind === "assistant-message")).toBe(false)
  expect(events.some((e) => e.kind === "commands-update")).toBe(true)
})

test("buffers assistant deltas and flushes before tool start and turn end", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("do")
  await tick()
  fake.emit({ protocol: "acp", value: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Step 1." } } })
  fake.emit({ protocol: "acp", value: { sessionUpdate: "tool_call", toolCallId: "c1", title: "write" } })
  fake.emit({ protocol: "acp", value: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Done." } } })
  fake.emit({ protocol: "acp", value: { sessionUpdate: "turn_completed" } })
  fake.completePrompt()
  await sent
  await flush()
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["Step 1.", "Done."])
  expect(events.filter((e) => e.kind === "tool-call")[0]?.phase).toBe("started")
})

test("self-started background turns via vendor notifications get their own turn latch", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("bg")
  await flush()
  fake.emit({
    protocol: "native",
    value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "user_message_chunk", content: { type: "text", text: "bg" } } } },
  })
  fake.emit({
    protocol: "native",
    value: { method: "_x.ai/session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "finished" } } } },
  })
  fake.emit({
    protocol: "native",
    value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "turn_completed" } } },
  })
  await flush()
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["finished"])
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  fake.completeActivity("bg")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("session.failed and message failure emit a single error", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  await tick()
  fake.failRuntime(new Error("child died"))
  await sent
  await flush()
  expect(events.filter((e) => e.kind === "error")).toHaveLength(1)
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/child died/)
})

test("stop during start closes the late-opened session; resume after stop uses the same id", async () => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: "grok",
    async open(ctx) {
      opens++
      entered.resolve()
      await openGate.promise
      return {
        agentSessionId: ctx.resumeId ?? "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => { closes++ },
        configure: async () => {},
        configuration: () => ({}),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  await started.catch(() => {})
  expect(closes).toBe(1)
  await adapter.start()
  expect(opens).toBe(2)
  expect((await core.sessions.get("sess-1"))?.id).toBe("sess-1")
})

test("setConfiguration awaits core configure and rolls adapter fields back on failure", async () => {
  let fail = false
  const applied: SessionConfiguration[] = []
  const driver: AgentDriver = {
    id: "grok",
    async open() {
      let live: SessionConfiguration = {}
      return {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {},
        async configure(configuration) {
          if (fail) {
            fail = false
            throw new Error("native configure failed")
          }
          live = { ...configuration }
          applied.push({ ...configuration })
        },
        configuration: () => ({ ...live }),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
    effort: "low",
  })
  adapters.push(adapter)
  await adapter.start()
  expect(adapter.model).toBe("grok-4.5")
  expect(adapter.effort).toBe("low")
  await adapter.setConfiguration({ model: "grok-fast", effort: "high" })
  expect(adapter.model).toBe("grok-fast")
  fail = true
  await expect(adapter.setConfiguration({ model: "nope" })).rejects.toThrow(/native configure failed/)
  expect(adapter.model).toBe("grok-fast")
  await adapter.setEffort(undefined)
  expect(adapter.effort).toBeUndefined()
  expect(applied.at(-1)).toEqual({ model: "grok-fast" })
})

test("stop awaits blocked open; start during stop does not join the abandoned open", async () => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: "grok",
    async open(ctx) {
      opens++
      entered.resolve()
      await openGate.promise
      return {
        agentSessionId: ctx.resumeId ?? `native-${opens}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => { closes++ },
        configure: async () => {},
        configuration: () => ({}),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  const started2 = adapter.start()
  openGate.resolve()
  await stopping
  expect(closes).toBe(1)
  await started2
  await adapter.send("hi")
  expect(opens).toBe(2)
  await started.catch(() => {})
})

test("failed close is retained: stop rejects and retry stop can close", async () => {
  let closeAttempts = 0
  const driver: AgentDriver = {
    id: "grok",
    async open() {
      return {
        agentSessionId: "n-fail-close",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        async close() {
          closeAttempts++
          if (closeAttempts === 1) throw new Error("native close failed")
        },
        configure: async () => {},
        configuration: () => ({}),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  await expect(adapter.stop()).rejects.toThrow(/native close failed/)
  await adapter.stop()
  expect(closeAttempts).toBe(2)
  await adapter.start()
  await adapter.send("hi")
})

test("interrupt of an already-accepted queued send fulfills without a duplicate error", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  for (let i = 0; i < 20 && fake.prompts.length === 0; i++) await tick()
  expect(fake.prompts).toHaveLength(1)
  const second = adapter.send("two")
  for (let i = 0; i < 20; i++) await tick()
  await adapter.interrupt()
  await first
  await second
  expect(fake.prompts).toHaveLength(1)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
})

test("setConfiguration does not adopt model fields after stop wins the race", async () => {
  const cfgGate = deferred<void>()
  const enteredCfg = deferred<void>()
  let holdConfigure = false
  const driver: AgentDriver = {
    id: "grok",
    async open() {
      let live: SessionConfiguration = { model: "grok-4.5" }
      return {
        agentSessionId: "n-cfg",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {},
        async configure(configuration) {
          if (holdConfigure) {
            enteredCfg.resolve()
            await cfgGate.promise
          }
          live = { ...configuration }
        },
        configuration: () => ({ ...live }),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
  })
  adapters.push(adapter)
  await adapter.start()
  expect(adapter.model).toBe("grok-4.5")
  holdConfigure = true
  const configuring = adapter.setConfiguration({ model: "x" })
  await enteredCfg.promise
  const stopping = adapter.stop()
  cfgGate.resolve()
  await configuring
  await stopping
  expect(adapter.model).toBe("grok-4.5")
})

test("resume during stop waits for close then reopens a new epoch", async () => {
  const closeEntered = deferred<void>()
  const closeRelease = deferred<void>()
  let opens = 0
  let closes = 0
  const driver: AgentDriver = {
    id: "grok",
    async open(ctx) {
      opens++
      return {
        agentSessionId: ctx.resumeId ?? `native-${opens}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {
          closes++
          closeEntered.resolve()
          await closeRelease.promise
        },
        configure: async () => {},
        configuration: () => ({}),
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  expect(opens).toBe(1)
  const stopping = adapter.stop()
  await closeEntered.promise
  let resumeDone = false
  const resuming = adapter.resume().then(() => { resumeDone = true })
  await tick()
  expect(resumeDone).toBe(false)
  expect(opens).toBe(1)
  closeRelease.resolve()
  await stopping
  await resuming
  expect(opens).toBe(2)
  await adapter.send("hi")
  expect(closes).toBe(1)
})

test("active-turn delayed close emits turn-complete only after native close", async () => {
  const closeEntered = deferred<void>()
  const closeRelease = deferred<void>()
  const fake = fakeAgentDriver()
  const origOpen = fake.driver.open.bind(fake.driver)
  fake.driver.open = async (ctx) => {
    const runtime = await origOpen(ctx)
    const innerClose = runtime.close.bind(runtime)
    runtime.close = async (opts) => {
      closeEntered.resolve()
      await closeRelease.promise
      await innerClose(opts)
    }
    return runtime
  }
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  for (let i = 0; i < 20 && fake.prompts.length === 0; i++) await tick()
  fake.emit({ protocol: "acp", value: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "partial" } } })
  await tick()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter((e) => e.kind === "assistant-message")).toHaveLength(0)
  try {
    const stopping = adapter.stop()
    await closeEntered.promise
    await tick()
    expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
    expect(events.filter((e) => e.kind === "assistant-message")).toHaveLength(0)
    closeRelease.resolve()
    await stopping
    await sent.catch(() => {})
    expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["partial"])
    expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
  } finally {
    closeRelease.resolve()
  }
})

test("active-turn failed close keeps pending buffer and does not fake idle", async () => {
  let closeAttempts = 0
  const fake = fakeAgentDriver()
  const origOpen = fake.driver.open.bind(fake.driver)
  fake.driver.open = async (ctx) => {
    const runtime = await origOpen(ctx)
    const innerClose = runtime.close.bind(runtime)
    runtime.close = async (opts) => {
      closeAttempts++
      if (closeAttempts === 1) throw new Error("native close failed")
      await innerClose(opts)
    }
    return runtime
  }
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  for (let i = 0; i < 20 && fake.prompts.length === 0; i++) await tick()
  fake.emit({ protocol: "acp", value: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "held" } } })
  await tick()
  await expect(adapter.stop()).rejects.toThrow(/native close failed/)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  expect(events.filter((e) => e.kind === "assistant-message")).toHaveLength(0)
  await adapter.stop()
  await sent.catch(() => {})
  expect(closeAttempts).toBe(2)
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["held"])
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("stall watchdog cancels through core after no activity", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  await sent
  await flush()
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/stalled/)
  expect(fake.interruptCalls).toBeGreaterThan(0)
})

test("native A plus queued B: no stall until owned dispatch; idle then running order", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  adapters.push(adapter)
  const events = listen(adapter)
  const coreEvents: string[] = []
  core.subscribe((e) => {
    if (e.sessionId !== "sess-1") return
    if (e.type === "session.stateChanged") coreEvents.push(e.state)
    if (e.type === "message.started") coreEvents.push("started")
  })
  await adapter.start()
  fake.startActivity("A")
  await flush()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  fake.holdNextPrompt()
  const sent = adapter.send("B")
  await sleep(50)
  expect(fake.prompts).toHaveLength(0)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
  expect(fake.interruptCalls).toBe(0)
  fake.completeActivity("A")
  await waitUntil(() => fake.prompts.length === 1)
  expect(fake.prompts[0]?.[0]).toBe("B")
  await flush()
  const idleAt = coreEvents.indexOf("idle")
  const startedAt = coreEvents.indexOf("started")
  expect(idleAt).toBeGreaterThanOrEqual(0)
  expect(startedAt).toBeGreaterThan(idleAt)
  expect(events.filter((e) => e.kind === "turn-complete").length).toBeGreaterThanOrEqual(1)
  expect(events.filter((e) => e.kind === "turn-start").length).toBeGreaterThanOrEqual(2)
  fake.completePrompt()
  await sent
  await flush()
})

test("interrupt discard does not fake A complete before ack", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("A")
  await flush()
  fake.holdNextPrompt()
  const queued = adapter.send("B")
  await flush()
  expect(fake.prompts).toHaveLength(0)
  const interrupting = adapter.interrupt()
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  expect(fake.closes).toBe(0)
  fake.completeActivity("A")
  await interrupting
  await queued
  await flush()
  expect(fake.closes).toBe(0)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("stale raw completion cannot close current native work", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("A")
  await flush()
  fake.emit({ protocol: "acp", value: { sessionUpdate: "turn_completed" } })
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  fake.completeActivity("A")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("watchdog arms on actual dispatch; follow-up after confirmed stall works", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("A")
  await flush()
  fake.holdNextPrompt()
  const first = adapter.send("queued")
  await sleep(50)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
  expect(fake.interruptCalls).toBe(0)
  fake.completeActivity("A")
  await waitUntil(() => fake.prompts.length === 1)
  await sleep(60)
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/stalled/)
  expect(fake.interruptCalls).toBeGreaterThan(0)
  await first
  await flush()
  fake.holdNextPrompt()
  const second = adapter.send("follow-up")
  await waitUntil(() => fake.prompts.length === 2)
  fake.completePrompt()
  await second
  expect(fake.prompts[1]?.[0]).toBe("follow-up")
})

test("unconfirmed stall retains latch", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  fake.hangInterrupt()
  fake.ignoreAbort()
  const sent = adapter.send("hi")
  await waitUntil(() => fake.prompts.length === 1)
  // stallTimeout 30ms + host interruptTimeout 40ms; stay live past the combined bound
  await sleep(50)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  await sleep(40)
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/stalled/)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  fake.completePrompt()
  await sent.catch(() => {})
})

test("direct and nested native params both flush assistant and replay commands", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.emit({
    protocol: "native",
    replay: true,
    value: { method: "_x.ai/session_notification", params: { sessionUpdate: "available_commands_update", availableCommands: [{ name: "direct" }] } },
  })
  fake.emit({
    protocol: "native",
    replay: true,
    value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "available_commands_update", availableCommands: [{ name: "nested" }] } } },
  })
  await flush()
  expect(adapter.availableCommands.map((c) => c.name)).toEqual(["nested"])
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(0)
  fake.startActivity("live")
  await flush()
  fake.emit({
    protocol: "native",
    value: { method: "_x.ai/session_notification", params: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "plain" } } },
  })
  fake.emit({
    protocol: "native",
    value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: " wrapped" } } } },
  })
  await flush()
  fake.completeActivity("live")
  await flush()
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["plain wrapped"])
})

test("initial configuration is captured in driver.open including resume clear", async () => {
  const fake = fakeAgentDriver()
  const origOpen = fake.driver.open.bind(fake.driver)
  fake.driver.open = async (ctx) => {
    ctx.onActivity?.({ id: "boot", phase: "started" })
    return origOpen(ctx)
  }
  const { core, workdir } = await harness(fake.driver)
  const first = new CoreGrokAdapter({
    core, id: "sess-cfg", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
    effort: "high",
  })
  adapters.push(first)
  await first.start()
  expect(fake.lastCtx?.configuration).toEqual({ model: "grok-4.5", reasoningEffort: "high" })
  expect(fake.applied).toEqual([])
  fake.completeActivity("boot")
  await flush()
  await first.stop()
  const second = new CoreGrokAdapter({
    core, id: "sess-cfg", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  adapters.push(second)
  await second.start()
  expect(fake.lastCtx?.configuration).toBeUndefined()
})

test("admission failure during native work errors without fake idle", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreGrokAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => { throw new Error("missing file") },
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("bg")
  await flush()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  await adapter.send("hi", { attachment_file_id: "x" })
  await flush()
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/missing file/)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  fake.completeActivity("bg")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})
