import { afterEach, expect, test } from "bun:test"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { AgentDriver, AgentRuntime, DriverContext, Host, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { createAcpNormalizer } from "../../../../packages/supermux-core/src/acp/normalize.js"
import { CoreOpenCodeAdapter } from "./core-adapter"
import { createOpenCodeCoreHost } from "./core-host"

function attachOpenCodeNormalizer(runtime: AgentRuntime): AgentRuntime {
  const normalizer = createAcpNormalizer()
  runtime.normalize = (update) => normalizer(update)
  runtime.flush = () => normalizer.flush()
  return runtime
}

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
    id: "opencode",
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
      return attachOpenCodeNormalizer(runtime)
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
const hosts: Host[] = []
const adapters: CoreOpenCodeAdapter[] = []

async function harness(fake: { driver: AgentDriver } | AgentDriver = fakeAgentDriver()) {
  const workdir = await mkdtemp(join(tmpdir(), "opencode-wd-"))
  const stateDirectory = await mkdtemp(join(tmpdir(), "opencode-core-"))
  dirs.push(workdir, stateDirectory)
  const driver = "driver" in fake ? fake.driver : fake
  const host = createOpenCodeCoreHost({
    stateDirectory,
    driverFactory: () => driver,
    limits: { interruptTimeoutMs: 40, maxPending: 128, outstandingActivity: 256 },
  })
  hosts.push(host)
  return { host, workdir, stateDirectory }
}

function makeAdapter(host: Host, opts: {
  id: string
  sessionName: string
  workdir: string
  persistSessionId: (nativeId: string) => Promise<void>
  initialSessionId?: string
  model?: string
  effort?: string
  resolveAttachment?: (file_id: string) => Promise<string>
  stallTimeoutMs?: number
}): CoreOpenCodeAdapter {
  const extra: Record<string, unknown> = {
    cwd: opts.workdir,
    workdir: opts.workdir,
    sessionHome: opts.workdir,
    sessionName: opts.sessionName,
    sessionId: opts.id,
  }
  if (opts.initialSessionId) extra.nativeSessionId = opts.initialSessionId
  if (opts.model) extra.model = opts.model
  const handle = host.register({ id: opts.id, env: {}, extra })
  const adapter = new CoreOpenCodeAdapter({
    handle,
    reregister: (fields) => host.register({
      id: opts.id,
      env: {},
      extra: { ...extra, ...(fields.model ? { model: fields.model } : {}), prompts: fields.prompts === true },
    }),
    core: host.core,
    ...opts,
  })
  return adapter
}

afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})

function listen(adapter: CoreOpenCodeAdapter) {
  const events: any[] = []
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete", "error", "commands-update", "activity"]) {
    adapter.on(k, (e) => events.push(e))
  }
  return events
}

test("adopt uses exact resume id and never session/new (no extra open without resumeId)", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-prior" })
  const { host, workdir } = await harness(fake)
  const persisted: string[] = []
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    initialSessionId: "native-prior",
    persistSessionId: async (id) => { persisted.push(id) },
  })
  await adapter.start()
  expect(fake.opens).toHaveLength(1)
  expect(fake.opens[0]?.resumeId).toBe("native-prior")
  expect(persisted).toEqual(["native-prior"])
  const record = await host.core.sessions.get("sess-1")
  expect(record?.agentSessionId).toBe("native-prior")
})

test("existing core record with mismatched agent/cwd/native id is rejected", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const first = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await first.start()
  await first.stop()
  const other = await mkdtemp(join(tmpdir(), "opencode-wd-"))
  dirs.push(other)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir: other,
    persistSessionId: async () => {},
  })
  await expect(adapter.start()).rejects.toThrow(/cwd mismatch/)
  expect(fake.opens).toHaveLength(1) // first start only
})

test("existing record with wrong native id is rejected before a replacement open", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-a" })
  const { host, workdir } = await harness(fake)
  const first = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await first.start()
  await first.stop()
  const opensAfterCreate = fake.opens.length
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    initialSessionId: "native-other",
    persistSessionId: async () => {},
  })
  await expect(adapter.start()).rejects.toThrow(/native id mismatch/)
  expect(fake.opens.length).toBe(opensAfterCreate)
})

test("new session creates with broker id and persists native id", async () => {
  const fake = fakeAgentDriver({ nativeId: "minted" })
  const { host, workdir } = await harness(fake)
  const persisted: string[] = []
  const adapter = makeAdapter(host, {id: "broker-id", sessionName: "s1", workdir,
    persistSessionId: async (id) => { persisted.push(id) },
  })
  await adapter.start()
  expect(fake.opens[0]?.resumeId).toBeUndefined()
  expect(persisted).toEqual(["minted"])
  expect((await host.core.sessions.get("broker-id"))?.agentSessionId).toBe("minted")
})

test("persist failure closes the opened session and leaves adapter stopped", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => { throw new Error("disk full") },
  })
  await expect(adapter.start()).rejects.toThrow("disk full")
  expect(fake.closes).toBe(1)
  await expect(adapter.send("hi")).rejects.toThrow(/not initialized|stopped/)
})

test("send waits for completion and serializes attachment resolution before later messages", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const order: string[] = []
  let releaseFirst!: (path: string) => void
  const firstPath = new Promise<string>((r) => { releaseFirst = r })
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async (id) => {
      order.push(`resolve:${id}`)
      if (id === "a") return firstPath
      return `/tmp/${id}`
    },
  })
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
  const { host, workdir } = await harness(fake)
  const gate = deferred<string>()
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => gate.promise,
  })
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
  const { host, workdir } = await harness(fake)
  const attach = deferred<string>()
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => attach.promise,
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.emit({
    protocol: "native",
    value: { method: "initialize", params: { _meta: { modelState: { availableModels: [{ modelId: "opencode-4.5" }] }, availableCommands: [{ name: "compact" }] } } },
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
  expect(adapter.availableModels.map((m) => m.modelId)).toEqual(["opencode-4.5"])
  expect(adapter.availableCommands.map((c) => c.name)).toEqual(["soul"])
  expect(events.some((e) => e.kind === "assistant-message")).toBe(false)
  expect(events.some((e) => e.kind === "commands-update")).toBe(true)
})

test("buffers assistant deltas and flushes before tool start and turn end", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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

test("session.failed and message failure emit a single error", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
    id: "opencode",
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
  const { host, workdir } = await harness(driver)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  await started.catch(() => {})
  expect(closes).toBe(1)
  const resumed = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await resumed.start()
  expect(opens).toBe(2)
  expect((await host.core.sessions.get("sess-1"))?.id).toBe("sess-1")
})

test("setConfiguration({ model }) stops and resumes the same native id with the new configuration", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-keep" })
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "old/model",
  })
  await adapter.start()
  expect(fake.opens).toHaveLength(1)
  expect(fake.opens[0]?.resumeId).toBeUndefined()
  await adapter.setConfiguration({ model: "new/model" })
  expect(adapter.model).toBe("new/model")
  expect(fake.opens).toHaveLength(2)
  expect(fake.opens[1]?.resumeId).toBe("native-keep")
})

test("setConfiguration({ model }) throws session_busy while a turn is running", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await adapter.start()
  fake.holdNextPrompt()
  const sending = adapter.send("hi")
  for (let i = 0; i < 40 && fake.prompts.length === 0; i++) await tick()
  expect(fake.prompts).toHaveLength(1)
  await expect(adapter.setConfiguration({ model: "x/y" })).rejects.toMatchObject({ code: "session_busy" })
  fake.completePrompt()
  await sending
})

test("stop awaits blocked open; start during stop does not join the abandoned open", async () => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: "opencode",
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
  const { host, workdir } = await harness(driver)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  expect(closes).toBe(1)
  await started.catch(() => {})
  const next = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  await next.send("hi")
  expect(opens).toBe(2)
})

test("failed close is retained: stop rejects and retry stop can close", async () => {
  let closeAttempts = 0
  const driver: AgentDriver = {
    id: "opencode",
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
  const { host, workdir } = await harness(driver)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await adapter.start()
  await expect(adapter.stop()).rejects.toThrow(/native close failed/)
  await adapter.stop()
  expect(closeAttempts).toBe(2)
  const next = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  await next.send("hi")
})

test("interrupt of an already-accepted queued send fulfills without a duplicate error", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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

test("resume during stop waits for close then reopens a new epoch", async () => {
  const closeEntered = deferred<void>()
  const closeRelease = deferred<void>()
  let opens = 0
  let closes = 0
  const driver: AgentDriver = {
    id: "opencode",
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
  const { host, workdir } = await harness(driver)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await adapter.start()
  expect(opens).toBe(1)
  const stopping = adapter.stop()
  await closeEntered.promise
  closeRelease.resolve()
  await stopping
  const next = makeAdapter(host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  expect(opens).toBe(2)
  await next.send("hi")
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  const events = listen(adapter)
  const coreEvents: string[] = []
  host.core.subscribe((e) => {
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
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
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
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

test("admission failure during native work errors without fake idle", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => { throw new Error("missing file") },
  })
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

function opencodeFrameToUpdate(frame: { method?: string; params?: Record<string, unknown> }) {
  if (frame.method === "session/update" && frame.params && typeof frame.params.update === "object") {
    return { protocol: "acp" as const, value: frame.params.update }
  }
  return { protocol: "native" as const, value: { method: frame.method, params: frame.params } }
}

test("replays real opencode-turn.ndjson through Core normalizer into broker events", async () => {
  const fake = fakeAgentDriver()
  const { host, workdir } = await harness(fake)
  const adapter = makeAdapter(host, {id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("Read notes.txt")
  await waitUntil(() => fake.prompts.length === 1)
  const raw = await readFile("packages/supermux-core/tests/fixtures/real/opencode-turn.ndjson", "utf8")
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue
    fake.emit(opencodeFrameToUpdate(JSON.parse(line)))
  }
  fake.completePrompt()
  await sent
  await flush()
  await flush()
  const kinds = events.map((e) => e.kind)
  expect(kinds[0]).toBe("turn-start")
  expect(kinds.at(-1)).toBe("turn-complete")
  const tools = events.filter((e) => e.kind === "tool-call")
  expect(tools.some((e) => e.phase === "started" && e.detail && typeof e.detail === "object")).toBe(true)
  expect(tools.some((e) => e.phase === "completed" && e.detail && typeof e.detail === "object")).toBe(true)
  const activity = events.filter((e) => e.kind === "activity").flatMap((e) => e.events ?? [])
  expect(activity.some((c: { kind: string }) => c.kind === "tool")).toBe(true)
  expect(activity.some((c: { kind: string }) => c.kind === "tool_result")).toBe(true)
  const texts = events.filter((e) => e.kind === "assistant-message").map((e) => e.text).join("\n")
  expect(texts).toContain("42")
  expect(events.filter((e) => e.kind === "commands-update")).toHaveLength(1)
  const assistantAt = kinds.lastIndexOf("assistant-message")
  const completeAt = kinds.lastIndexOf("turn-complete")
  expect(assistantAt).toBeGreaterThan(-1)
  expect(assistantAt).toBeLessThan(completeAt)
})
