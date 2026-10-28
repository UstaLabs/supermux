import { afterEach, beforeEach, expect, spyOn, test } from "bun:test"
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createHost } from "../../../../packages/supermux-core/src/index.js"
import type { AgentDriver, AgentRuntime, ContentBlock, DriverContext, DriverContextSupport, Host, PermissionsSpec, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { createAcpNormalizer } from "../../../../packages/supermux-core/src/acp/normalize.js"
import { createCodexNormalizer } from "../../../../packages/supermux-core/src/codex/normalize.js"

import { CoreAdapter, CORE_ADAPTER_PROFILES } from "./core-adapter"
import { createGrokCoreHost } from "../grok/core-host"
import { createCodexCoreHost } from "../codex/core-host"
import { createCursorCoreHost } from "../cursor/core-host"
import { createOpenCodeCoreHost } from "../opencode/core-host"
import { createClaudeCoreHost } from "../claude/core-host"
import { createAntigravityCoreHost } from "../antigravity/core-host"
import type { AgentKind } from "../types"
import { askPermissionMode, defaultPermissionMode, permissionsFor } from "../permission-modes"
import { attachmentMeta } from "../attachment-meta"

const QUEUE_KINDS = ["grok", "opencode", "cursor", "claude", "antigravity"] as const
type QueueKind = typeof QUEUE_KINDS[number]
type Kind = AgentKind

function attachNormalizer(kind: Kind, runtime: AgentRuntime): AgentRuntime {
  if (kind === "codex") {
    const normalizer = createCodexNormalizer()
    runtime.normalize = (update) => normalizer(update)
    runtime.flush = () => normalizer.flush()
    return runtime
  }
  const normalizer = kind === "grok" ? createAcpNormalizer({ vendor: "grok" }) : createAcpNormalizer({})
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

function fakeAgentDriver(kind: Kind, options: { configure?: boolean; nativeId?: string; steer?: boolean; steerBusy?: boolean } = {}) {
  const opens: DriverContext[] = []
  const prompts: ContentBlock[][] = []
  const promptTexts: string[][] = []
  const steers: ContentBlock[][] = []
  const applied: SessionConfiguration[] = []
  const permissionSets: PermissionsSpec[] = []
  let closes = 0
  let interruptCalls = 0
  let liveConfig: SessionConfiguration = {}
  let promptGate: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  let promptSignal: AbortSignal | undefined
  let holdPrompt = false
  let nextFail: Error | undefined
  let nextPermissionsFail: Error | undefined
  let permissionsSupported = true
  /** The spec the agent behind the runtime runs: the launch spec, or what a live switch set. */
  let agentSpec: PermissionsSpec | undefined
  /** A keeper-held agent the next open re-attaches to: it keeps the spec it has, whatever the launch says. */
  let keptAgent: PermissionsSpec | undefined
  let interruptHangs = false
  let interruptError: Error | undefined
  let ignoreAbort = false

  const driver: AgentDriver = {
    id: kind,
    async open(ctx) {
      opens.push(ctx)
      agentSpec = keptAgent ?? ctx.permissions
      keptAgent = undefined
      if (ctx.configuration) liveConfig = { ...ctx.configuration }
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
        capabilities: {
          resume: true, steer: options.steer === true || kind === "codex", fork: false, detach: false,
          configure: options.configure !== false, history: false, permissions: permissionsSupported,
        },
        async prompt(content, signal) {
          prompts.push(content)
          promptTexts.push(content.map((c) => ("text" in c ? String(c.text) : "")))
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
        async steer(content) {
          if (options.steerBusy) throw new CoreError("session_busy", "Native work is ambiguous")
          steers.push(content)
        },
        async interrupt() {
          interruptCalls++
          if (interruptError) throw interruptError
          if (interruptHangs) return
          promptGate?.resolve({ stopReason: "cancelled" })
        },
        async close() { closes++; promptGate?.resolve({ stopReason: "cancelled" }) },
        async configure(configuration) {
          liveConfig = { ...configuration }
          applied.push({ ...configuration })
        },
        configuration: () => ({ ...liveConfig }),
        async setPermissions(spec) {
          if (nextPermissionsFail) {
            const err = nextPermissionsFail
            nextPermissionsFail = undefined
            throw err
          }
          permissionSets.push(structuredClone(spec))
          agentSpec = structuredClone(spec)
          return { applied: kind === "codex" ? "next-turn" : "now" }
        },
      }
      return attachNormalizer(kind, runtime)
    },
  }

  return {
    driver, opens, prompts, promptTexts, steers, applied, permissionSets,
    get closes() { return closes },
    get interruptCalls() { return interruptCalls },
    holdNextPrompt() { holdPrompt = true },
    completePrompt() { promptGate?.resolve({ stopReason: "end_turn" }); holdPrompt = false },
    failNextPrompt(error: Error) { nextFail = error },
    failNextSetPermissions(error: Error) { nextPermissionsFail = error },
    /** Whether runtimes opened from now on report live permission switching. */
    supportPermissions(on: boolean) { permissionsSupported = on },
    keepAgentOn(spec: PermissionsSpec) { keptAgent = spec },
    get agentSpec() { return agentSpec },
    hangInterrupt() { interruptHangs = true },
    failInterrupt(error: Error) { interruptError = error },
    ignoreAbort() { ignoreAbort = true },
    emit(update: { protocol: "acp" | "native"; value: unknown; replay?: boolean }) {
      opens.at(-1)?.onUpdate(update as Parameters<DriverContext["onUpdate"]>[0])
    },
    startActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "started" }) },
    completeActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "completed" }) },
    failRuntime(error: Error) { opens.at(-1)?.onExit(error) },
    get lastCtx() { return opens.at(-1) },
  }
}

// Hermetic credentials: the host's prepare step takes CURSOR_API_KEY / OPENAI_API_KEY first and only falls back
// to the user's own login files without one: files CI does not have, and that a developer
// machine must not have copied (or written back) by a test. A fake key per test, restored after.
const CREDENTIAL_KEYS = ["CURSOR_API_KEY", "OPENAI_API_KEY"] as const
const savedKeys = new Map<string, string | undefined>()
beforeEach(() => {
  for (const k of CREDENTIAL_KEYS) { savedKeys.set(k, process.env[k]); process.env[k] = "test-key" }
})
afterEach(() => {
  for (const k of CREDENTIAL_KEYS) {
    const prev = savedKeys.get(k)
    if (prev === undefined) delete process.env[k]
    else process.env[k] = prev
  }
})

const dirs: string[] = []
const hosts: Host[] = []
const adapters: CoreAdapter[] = []

function createHostFor(kind: Kind, stateDirectory: string, driver: AgentDriver): Host {
  const limits = { interruptTimeoutMs: 40, maxPending: 128, outstandingActivity: 256 }
  const opts = { stateDirectory, driverFactory: () => driver, limits }
  if (kind === "codex") return createCodexCoreHost(opts)
  if (kind === "cursor") {
    return createCursorCoreHost({ ...opts, smoke: async () => {}, sharedRuntime: null })
  }
  if (kind === "opencode") return createOpenCodeCoreHost(opts)
  if (kind === "claude") return createClaudeCoreHost(opts)
  if (kind === "antigravity") return createAntigravityCoreHost({ ...opts, userHome: stateDirectory })
  return createGrokCoreHost(opts)
}

async function harness(kind: Kind, fake: { driver: AgentDriver } | AgentDriver = fakeAgentDriver(kind)) {
  const workdir = await mkdtemp(join(tmpdir(), `${kind}-wd-`))
  const stateDirectory = await mkdtemp(join(tmpdir(), `${kind}-core-`))
  dirs.push(workdir, stateDirectory)
  const driver = "driver" in fake ? fake.driver : fake
  const host = createHostFor(kind, stateDirectory, driver)
  hosts.push(host)
  return { host, workdir, stateDirectory }
}

function makeAdapter(kind: Kind, host: Host, opts: {
  id: string
  sessionName: string
  workdir: string
  persistSessionId: (nativeId: string) => Promise<void>
  initialSessionId?: string
  model?: string
  effort?: string
  resolveAttachment?: (file_id: string) => Promise<string>
  stallTimeoutMs?: number
  permissionMode?: string
  onUsageUpdate?: CoreAdapter["onUsageUpdate"]
  getPrevUsage?: CoreAdapter["getPrevUsage"]
  fast?: boolean
  onReregister?: (fields: Parameters<CoreAdapter["setConfiguration"]>[0] & { permissionMode?: string }) => void
}): CoreAdapter {
  const extra: Record<string, unknown> = {
    cwd: opts.workdir,
    workdir: opts.workdir,
    sessionHome: opts.workdir,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    permissionMode: opts.permissionMode,
  }
  if (opts.initialSessionId) extra.nativeSessionId = opts.initialSessionId
  const handle = host.register({ id: opts.id, env: {}, extra })
  const adapter = new CoreAdapter(CORE_ADAPTER_PROFILES[kind], {
    handle,
    reregister: (fields) => {
      opts.onReregister?.(fields)
      return host.register({
        id: opts.id,
        env: {},
        extra: { ...extra, permissionMode: fields.permissionMode },
      })
    },
    core: host.core,
    ...opts,
  })
  adapters.push(adapter)
  return adapter
}

afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})

function listen(adapter: CoreAdapter) {
  const events: Array<{ kind: string; error?: Error; text?: string; phase?: string; detail?: unknown; events?: unknown[] }> = []
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete", "error", "commands-update", "activity"]) {
    adapter.on(k, (e: { kind: string }) => events.push(e))
  }
  return events
}

function acrossQueue(name: string, fn: (kind: QueueKind) => Promise<void>) {
  for (const kind of QUEUE_KINDS) {
    test(`${kind}: ${name}`, async () => { await fn(kind) })
  }
}

function acrossGrok(name: string, fn: (kind: "grok") => Promise<void>) {
  test(`grok: ${name}`, async () => { await fn("grok") })
}

acrossQueue("adopt uses exact resume id and never session/new (no extra open without resumeId)", async (kind) => {
  const fake = fakeAgentDriver(kind, { nativeId: "native-prior" })
  const { host, workdir } = await harness(kind, fake)
  const persisted: string[] = []
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

acrossQueue("existing core record with mismatched agent/cwd/native id is rejected", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const first = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await first.start()
  await first.stop()
  const other = await mkdtemp(join(tmpdir(), "grok-wd-"))
  dirs.push(other)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir: other,
    persistSessionId: async () => {},
  })
  await expect(adapter.start()).rejects.toThrow(/cwd mismatch/)
  expect(fake.opens).toHaveLength(1) // first start only
})

acrossQueue("existing record with wrong native id is rejected before a replacement open", async (kind) => {
  const fake = fakeAgentDriver(kind, { nativeId: "native-a" })
  const { host, workdir } = await harness(kind, fake)
  const first = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await first.start()
  await first.stop()
  const opensAfterCreate = fake.opens.length
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    initialSessionId: "native-other",
    persistSessionId: async () => {},
  })
  await expect(adapter.start()).rejects.toThrow(/native id mismatch/)
  expect(fake.opens.length).toBe(opensAfterCreate)
})

acrossQueue("new session creates with broker id and persists native id", async (kind) => {
  const fake = fakeAgentDriver(kind, { nativeId: "minted" })
  const { host, workdir } = await harness(kind, fake)
  const persisted: string[] = []
  const adapter = makeAdapter(kind, host, {id: "broker-id", sessionName: "s1", workdir,
    persistSessionId: async (id) => { persisted.push(id) },
  })
  await adapter.start()
  expect(fake.opens[0]?.resumeId).toBeUndefined()
  expect(persisted).toEqual(["minted"])
  expect((await host.core.sessions.get("broker-id"))?.agentSessionId).toBe("minted")
})

acrossQueue("persist failure closes the opened session and leaves adapter stopped", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => { throw new Error("disk full") },
  })
  await expect(adapter.start()).rejects.toThrow("disk full")
  expect(fake.closes).toBe(1)
  await expect(adapter.send("hi")).rejects.toThrow(/not initialized|stopped/)
})

acrossQueue("send waits for completion and serializes attachment resolution before later messages", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const order: string[] = []
  let releaseFirst!: (path: string) => void
  const firstPath = new Promise<string>((r) => { releaseFirst = r })
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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
  expect(fake.promptTexts).toHaveLength(0)
  releaseFirst("/tmp/a.txt")
  await tick()
  fake.completePrompt()
  await first
  fake.holdNextPrompt()
  await tick()
  expect(order).toEqual(["resolve:a", "resolve:b"])
  expect(fake.promptTexts[0]?.[0]).toContain("[Attached file: /tmp/a.txt]")
  fake.completePrompt()
  await second
  expect(fake.promptTexts[1]?.[0]).toContain("[Attached file: /tmp/b]")
})

acrossQueue("stop during attachment resolution does not deliver the prompt", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const gate = deferred<string>()
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => gate.promise,
  })
  await adapter.start()
  const sent = adapter.send("hi", { attachment_file_id: "x" })
  await tick()
  await adapter.stop()
  gate.resolve("/tmp/x")
  await expect(sent).rejects.toThrow(/stopped/)
  expect(fake.promptTexts).toHaveLength(0)
})

acrossQueue("interrupt discards queued core work, invalidates pre-interrupt inputs, and surfaces unconfirmed", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const attach = deferred<string>()
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    resolveAttachment: async () => attach.promise,
  })
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  for (let i = 0; i < 20 && fake.promptTexts.length === 0; i++) await tick()
  expect(fake.promptTexts).toHaveLength(1)
  const second = adapter.send("two", { attachment_file_id: "held" })
  await tick()
  await adapter.interrupt()
  await first
  attach.resolve("/tmp/held")
  await expect(second).rejects.toThrow(/stopped/)
  expect(fake.promptTexts).toHaveLength(1)

  fake.hangInterrupt()
  fake.holdNextPrompt()
  const third = adapter.send("three")
  for (let i = 0; i < 20 && fake.promptTexts.length < 2; i++) await tick()
  await expect(adapter.interrupt()).rejects.toThrow(/unconfirmed/)
  fake.completePrompt()
  await third.catch(() => {})
})

acrossQueue("new user input after confirmed interrupt continues the paused empty queue", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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
  expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "two"])
})

for (const kind of [...QUEUE_KINDS, "codex"] as const) {
  test(`${kind}: new user input after an unconfirmed interrupt runs once the turn has ended`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    const events = listen(adapter)
    await adapter.start()
    fake.hangInterrupt()
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    await expect(adapter.interrupt()).rejects.toThrow(/unconfirmed/)
    // The turn still runs: input is refused, not parked behind the interrupt.
    const early = adapter.send("early")
    await waitUntil(() => events.some((e) => e.kind === "error" && /not been confirmed/.test(e.error?.message ?? "")))
    await early
    // It ends on its own: the session is idle with its queue paused, and the next input runs.
    fake.completePrompt()
    await first
    await adapter.send("two")
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "two"])
  })
}

for (const kind of [...QUEUE_KINDS, "codex"] as const) {
  test(`${kind}: new user input after an interrupt that threw runs once the turn has ended`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    const events = listen(adapter)
    await adapter.start()
    // The driver's interrupt fails after Core has paused the queue (e.g. Codex turn/interrupt refused).
    fake.failInterrupt(new Error("turn/interrupt refused"))
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    await expect(adapter.interrupt()).rejects.toThrow(/turn\/interrupt refused/)
    fake.completePrompt()
    await first
    const second = adapter.send("two")
    await waitUntil(() => fake.promptTexts.length === 2 || events.some((e) => e.kind === "error"))
    expect(events.filter((e) => e.kind === "error").map((e) => e.error?.message)).toEqual([])
    await second
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "two"])
  })
}

acrossGrok("skips replay chat but still applies commands and initialize metadata", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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

acrossGrok("buffers assistant deltas and flushes before tool start and turn end", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

acrossGrok("self-started background turns via vendor notifications get their own turn latch", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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

acrossQueue("session.failed and message failure emit a single error", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

acrossQueue("stop during start closes the late-opened session; resume after stop uses the same id", async (kind) => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: kind,
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
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  await started.catch(() => {})
  expect(closes).toBe(1)
  const resumed = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await resumed.start()
  expect(opens).toBe(2)
  expect((await host.core.sessions.get("sess-1"))?.id).toBe("sess-1")
})

acrossGrok("setConfiguration awaits core configure and rolls adapter fields back on failure", async (kind) => {
  let fail = false
  let opens = 0
  const driver: AgentDriver = {
    id: kind,
    async open() {
      opens++
      if (fail) throw new Error("native configure failed")
      return {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {},
        configure: async () => {
          if (fail) throw new Error("native configure failed")
        },
        configuration: () => ({}),
      }
    },
  }
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
    effort: "low",
  })
  await adapter.start()
  expect(adapter.model).toBe("grok-4.5")
  expect(adapter.effort).toBe("low")
  await adapter.setConfiguration({ model: "grok-fast", effort: "high" })
  expect(adapter.model).toBe("grok-fast")
  fail = true
  await expect(adapter.setConfiguration({ model: "nope" })).rejects.toThrow(/native configure failed|could not be saved or restored/)
  expect(adapter.model).toBe("grok-fast")
})

acrossQueue("stop awaits blocked open; start during stop does not join the abandoned open", async (kind) => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: kind,
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
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  expect(closes).toBe(1)
  await started.catch(() => {})
  const next = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  await next.send("hi")
  expect(opens).toBe(2)
})

acrossQueue("failed close is retained: stop rejects and retry stop can close", async (kind) => {
  let closeAttempts = 0
  const driver: AgentDriver = {
    id: kind,
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
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await adapter.start()
  await expect(adapter.stop()).rejects.toThrow(/native close failed/)
  await adapter.stop()
  expect(closeAttempts).toBe(2)
  const next = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  await next.send("hi")
})

acrossQueue("interrupt of an already-accepted queued send fulfills without a duplicate error", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  for (let i = 0; i < 20 && fake.promptTexts.length === 0; i++) await tick()
  expect(fake.promptTexts).toHaveLength(1)
  const second = adapter.send("two")
  for (let i = 0; i < 20; i++) await tick()
  await adapter.interrupt()
  await first
  await second
  expect(fake.promptTexts).toHaveLength(1)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
})

acrossGrok("setConfiguration does not adopt model fields after stop wins the race", async (kind) => {
  const cfgGate = deferred<void>()
  const enteredCfg = deferred<void>()
  let holdConfigure = false
  const driver: AgentDriver = {
    id: kind,
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
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
  })
  await adapter.start()
  expect(adapter.model).toBe("grok-4.5")
  holdConfigure = true
  const configuring = adapter.setConfiguration({ model: "x" })
  await enteredCfg.promise
  const stopping = adapter.stop()
  cfgGate.resolve()
  await configuring.catch(() => {})
  await stopping
  expect(adapter.model).toBe("grok-4.5")
})

acrossQueue("resume during stop waits for close then reopens a new epoch", async (kind) => {
  const closeEntered = deferred<void>()
  const closeRelease = deferred<void>()
  let opens = 0
  let closes = 0
  const driver: AgentDriver = {
    id: kind,
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
  const { host, workdir } = await harness(kind, driver)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await adapter.start()
  expect(opens).toBe(1)
  const stopping = adapter.stop()
  await closeEntered.promise
  closeRelease.resolve()
  await stopping
  const next = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await next.start()
  expect(opens).toBe(2)
  await next.send("hi")
  expect(closes).toBe(1)
})

acrossGrok("active-turn delayed close emits turn-complete only after native close", async (kind) => {
  const closeEntered = deferred<void>()
  const closeRelease = deferred<void>()
  const fake = fakeAgentDriver(kind)
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
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  for (let i = 0; i < 20 && fake.promptTexts.length === 0; i++) await tick()
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

acrossGrok("active-turn failed close keeps pending buffer and does not fake idle", async (kind) => {
  let closeAttempts = 0
  const fake = fakeAgentDriver(kind)
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
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  for (let i = 0; i < 20 && fake.promptTexts.length === 0; i++) await tick()
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

acrossQueue("stall watchdog cancels through core after no activity", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

// A turn that has started (any event arrived) may then stay quiet for as long as
// it needs — a subagent or a long tool run must not be cut by the watchdog.
acrossQueue("stall watchdog is disarmed by the first event of the turn", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-quiet", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("hi")
  await waitUntil(() => fake.prompts.length === 1)
  fake.emit({ protocol: "acp", value: { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "working…" } } })
  await new Promise((r) => setTimeout(r, 120))
  fake.completePrompt()
  await sent
  await flush()
  expect(events.find((e) => e.kind === "error")).toBeUndefined()
  expect(fake.interruptCalls).toBe(0)
})

acrossQueue("native A plus queued B: no stall until owned dispatch; idle then running order", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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
  expect(fake.promptTexts).toHaveLength(0)
  expect(events.filter((e) => e.kind === "error")).toHaveLength(0)
  expect(fake.interruptCalls).toBe(0)
  fake.completeActivity("A")
  await waitUntil(() => fake.promptTexts.length === 1)
  expect(fake.promptTexts[0]?.[0]).toBe("B")
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

acrossQueue("interrupt discard does not fake A complete before ack", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("A")
  await flush()
  fake.holdNextPrompt()
  const queued = adapter.send("B")
  await flush()
  expect(fake.promptTexts).toHaveLength(0)
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

acrossQueue("stale raw completion cannot close current native work", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

acrossQueue("watchdog arms on actual dispatch; follow-up after confirmed stall works", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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
  await waitUntil(() => fake.promptTexts.length === 1)
  await sleep(60)
  expect(events.find((e) => e.kind === "error")?.error?.message).toMatch(/stalled/)
  expect(fake.interruptCalls).toBeGreaterThan(0)
  await first
  await flush()
  fake.holdNextPrompt()
  const second = adapter.send("follow-up")
  await waitUntil(() => fake.promptTexts.length === 2)
  fake.completePrompt()
  await second
  expect(fake.promptTexts[1]?.[0]).toBe("follow-up")
})

acrossQueue("unconfirmed stall retains latch", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  fake.hangInterrupt()
  fake.ignoreAbort()
  const sent = adapter.send("hi")
  await waitUntil(() => fake.promptTexts.length === 1)
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

acrossGrok("direct and nested native params both flush assistant and replay commands", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
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

acrossGrok("initial configuration is captured in driver.open including resume clear", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const origOpen = fake.driver.open.bind(fake.driver)
  fake.driver.open = async (ctx) => {
    ctx.onActivity?.({ id: "boot", phase: "started" })
    return origOpen(ctx)
  }
  const { host, workdir } = await harness(kind, fake)
  const first = makeAdapter(kind, host, {id: "sess-cfg", sessionName: "s1", workdir,
    persistSessionId: async () => {},
    model: "grok-4.5",
    effort: "high",
  })
  await first.start()
  expect(first.model).toBe("grok-4.5")
  expect(first.effort).toBe("high")
  expect(fake.lastCtx?.configuration).toEqual({ model: "grok-4.5", reasoningEffort: "high" })
  fake.completeActivity("boot")
  await flush()
  await first.stop()
  const second = makeAdapter(kind, host, {id: "sess-cfg", sessionName: "s1", workdir,
    persistSessionId: async () => {},
  })
  await second.start()
  expect(second.model).toBeUndefined()
  expect(fake.lastCtx?.configuration).toEqual({ model: "grok-4.5", reasoningEffort: "high" })
})

acrossQueue("admission failure during native work errors without fake idle", async (kind) => {
  if (CORE_ADAPTER_PROFILES[kind].attachments === "image-block") return
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir,
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

function grokFrameToUpdate(frame: { method?: string; params?: Record<string, unknown> }) {
  if (frame.method === "session/update" && frame.params && typeof frame.params.update === "object") {
    return { protocol: "acp" as const, value: frame.params.update }
  }
  return { protocol: "native" as const, value: { method: frame.method, params: frame.params } }
}

acrossGrok("replays real grok-turn.ndjson through Core normalizer into broker events", async (kind) => {
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
  })
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("Read notes.txt")
  await waitUntil(() => fake.promptTexts.length === 1)
  const raw = await readFile("packages/supermux-core/tests/fixtures/real/grok-turn.ndjson", "utf8")
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue
    fake.emit(grokFrameToUpdate(JSON.parse(line)))
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
  expect(activity.some((c) => typeof c === "object" && c !== null && (c as { kind: string }).kind === "tool")).toBe(true)
  expect(activity.some((c) => typeof c === "object" && c !== null && (c as { kind: string }).kind === "tool_result")).toBe(true)
  const texts = events.filter((e) => e.kind === "assistant-message").map((e) => e.text).join("\n")
  expect(texts).toContain("42")
  expect(events.filter((e) => e.kind === "commands-update")).toHaveLength(1)
  const assistantAt = kinds.lastIndexOf("assistant-message")
  const completeAt = kinds.lastIndexOf("turn-complete")
  expect(assistantAt).toBeGreaterThan(-1)
  expect(assistantAt).toBeLessThan(completeAt)
})

test("cursor: setPermissionMode does not restart", async () => {
  const kind = "cursor" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await adapter.start()
  const applied = await adapter.setPermissionMode("ask")
  expect(applied).toEqual({ applied: "now" })
  expect(fake.opens).toHaveLength(1)
})

// The shim socket drops while the native process is replaced; the broker must
// not report the session dead for that window.
test("isAlive stays true across a permission-mode restart and false after stop", async () => {
  const kind = "cursor" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-alive", sessionName: "s1", workdir, persistSessionId: async () => {} })
  expect(adapter.isAlive()).toBe(false)
  await adapter.start()
  expect(adapter.isAlive()).toBe(true)
  const restart = adapter.setPermissionMode("ask")
  expect(adapter.isAlive()).toBe(true)
  await restart
  expect(adapter.isAlive()).toBe(true)
  await adapter.stop()
  expect(adapter.isAlive()).toBe(false)
})

test("setPermissionMode is a no-op for the same id", async () => {
  const kind = "grok" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: "ask" })
  await adapter.start()
  await adapter.setPermissionMode("ask")
  expect(fake.opens).toHaveLength(1)
})

test("setPermissionMode with a running turn succeeds", async () => {
  const kind = "grok" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await adapter.start()
  fake.holdNextPrompt()
  const sent = adapter.send("busy")
  await waitUntil(() => fake.promptTexts.length === 1)
  await expect(adapter.setPermissionMode("ask")).resolves.toEqual({ applied: "now" })
  fake.completePrompt()
  await sent
})

// The library launches a resumed session with the permissions its record stored. The broker's own
// mode moves while no runtime is live (SessionManager.switchPermissionMode then only updates the
// registry), so the record can be behind it: the adapter brings the resumed session to its mode.
for (const kind of [...QUEUE_KINDS, "codex"] as const) {
  test(`${kind}: a resumed session whose record is behind the broker's permission mode is brought to it`, async () => {
    const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
    const { host, workdir } = await harness(kind, fake)
    const stored = defaultPermissionMode(kind)
    const wanted = askPermissionMode(kind)
    const first = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: wanted })
    await first.start()
    await first.setPermissionMode(stored) // a live switch writes `stored` into the core record
    await first.stop()
    const switches = fake.permissionSets.length

    const second = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: wanted })
    await second.start()

    expect(fake.opens.at(-1)?.permissions).toEqual(permissionsFor(kind, wanted)) // the launch itself used the broker's spec, not the record's
    expect(fake.permissionSets.slice(switches)).toEqual([permissionsFor(kind, wanted)]) // and the live session was told too (a no-op for a fresh launch)
    expect((await host.core.sessions.get("sess-1"))?.permissions).toEqual(permissionsFor(kind, wanted))
    expect(second.permissionMode).toBe(wanted)
  })
}

// A record with no spec (a session from before the broker stored one, or opened while no mode was
// known) leaves the driver on whatever it launched with, and a keeper-reattached agent can be on an
// older mode than the broker's. The adapter sets the broker's mode once; that also stores it, so
// later resumes are explicit and need no switch.
for (const kind of [...QUEUE_KINDS, "codex"] as const) {
  test(`${kind}: a resumed session whose record holds no spec is given the broker's permission mode once`, async () => {
    const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
    const { host, workdir } = await harness(kind, fake)
    const wanted = askPermissionMode(kind)
    const common = { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} }
    const first = makeAdapter(kind, host, common) // no broker mode known: nothing to apply, the record stays spec-less
    await first.start()
    await first.stop()
    expect(fake.permissionSets).toEqual([])
    expect((await host.core.sessions.get("sess-1"))?.permissions).toBeUndefined()

    const second = makeAdapter(kind, host, { ...common, permissionMode: wanted })
    await second.start()
    expect(fake.opens.at(-1)?.permissions).toEqual(permissionsFor(kind, wanted)) // the resume carries the broker's spec
    expect(fake.permissionSets).toEqual([permissionsFor(kind, wanted)])
    expect((await host.core.sessions.get("sess-1"))?.permissions).toEqual(permissionsFor(kind, wanted))
    await second.stop()

    const third = makeAdapter(kind, host, { ...common, permissionMode: wanted })
    await third.start()
    expect(fake.permissionSets).toHaveLength(1) // the record holds the broker's mode now: nothing more to switch
  })
}

test("an agent without live permissions is left alone: no switch is attempted and the session stays open", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const common = { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: askPermissionMode(kind) }
  const first = makeAdapter(kind, host, common)
  await first.start()
  await first.setPermissionMode(defaultPermissionMode(kind)) // the record is now behind the broker's mode
  await first.stop()
  const switches = fake.permissionSets.length

  fake.supportPermissions(false) // the resumed agent cannot switch permissions live
  const second = makeAdapter(kind, host, common)
  await second.start()

  expect(fake.permissionSets).toHaveLength(switches)
  expect(second.isAlive()).toBe(true)
  expect(host.core.sessions.live("sess-1")).toBeDefined()
  expect((await host.core.sessions.get("sess-1"))?.permissions).toEqual(permissionsFor(kind, askPermissionMode(kind))) // what the launch used
})

test("a failed permission switch on resume fails the start and does not leave the session open", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const wanted = askPermissionMode(kind)
  const first = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: wanted })
  await first.start()
  await first.setPermissionMode(defaultPermissionMode(kind))
  await first.stop()
  const closesBefore = fake.closes

  const second = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, permissionMode: wanted })
  fake.failNextSetPermissions(new Error("switch refused"))
  const level = process.env.MUX_LOG_LEVEL
  process.env.MUX_LOG_LEVEL = "info"
  const stderr = spyOn(process.stderr, "write")
  let logged = ""
  try {
    await expect(second.start()).rejects.toThrow("switch refused")
    logged = stderr.mock.calls.map((call) => String(call[0])).join("")
  } finally {
    stderr.mockRestore()
    if (level === undefined) delete process.env.MUX_LOG_LEVEL
    else process.env.MUX_LOG_LEVEL = level
  }

  expect(fake.closes).toBe(closesBefore + 1) // the opened runtime was closed, not left running under the stale mode
  expect(host.core.sessions.live("sess-1")).toBeUndefined()
  expect(second.isAlive()).toBe(false)
  // The shutdown is on purpose, but it is said: which session, which agent, and why.
  const warning = logged.split("\n").find((line) => line.includes("[WARN]") && line.includes("permissions_reconcile_failed"))
  expect(warning).toBeDefined()
  expect(warning).toContain('"session_id":"sess-1"')
  expect(warning).toContain('"session":"s1"')
  expect(warning).toContain('"agent":"claude"')
  expect(warning).toContain('"err":"switch refused"')
})

// A keeper-reattached agent keeps the spec it is running whatever the resume says (the live agent
// is the truth), and the resume stores the broker's spec in the record. So the adapter judges the
// record as it was BEFORE the open: behind the broker's mode means the live session must be told.
test("a keeper-reattached agent that kept an older spec is brought to the broker's mode", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const running = defaultPermissionMode(kind)
  const wanted = askPermissionMode(kind)
  const common = { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} }
  const first = makeAdapter(kind, host, { ...common, permissionMode: wanted })
  await first.start()
  await first.setPermissionMode(running) // the agent and the record are on `running`
  await first.stop()

  fake.keepAgentOn(permissionsFor(kind, running)) // the broker's mode moved meanwhile; the agent that is re-attached did not
  const second = makeAdapter(kind, host, { ...common, permissionMode: wanted })
  await second.start()

  expect(fake.agentSpec).toEqual(permissionsFor(kind, wanted))
  expect((await host.core.sessions.get("sess-1"))?.permissions).toEqual(permissionsFor(kind, wanted))
})

test("antigravity profile: model configures live, effort unsupported, prompt-path attachments, queued sends", () => {
  expect(CORE_ADAPTER_PROFILES.antigravity).toEqual({
    kind: "antigravity",
    configuration: { model: "configure", effort: "unsupported", fast: "unsupported" },
    permissionMode: "live",
    attachments: "path-in-prompt",
    sendWhenBusy: "queue",
    startConfiguration: "desired",
  })
})

test("antigravity: setConfiguration({ effort }) is a no-op; a model change configures without reopening", async () => {
  const kind = "antigravity" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "gemini-3-pro-high" })
  await adapter.start()
  await adapter.setConfiguration({ effort: "high" })
  expect(adapter.effort).toBeUndefined()
  expect(fake.applied).toEqual([])
  await adapter.setConfiguration({ model: "gemini-3-flash", effort: "high" })
  expect(adapter.model).toBe("gemini-3-flash")
  expect(fake.applied.at(-1)).toEqual({ model: "gemini-3-flash" })
  expect(fake.opens).toHaveLength(1)
})

test("opencode: setConfiguration({ model }) restarts the same native id", async () => {
  const kind = "opencode" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "old/model" })
  await adapter.start()
  await adapter.setConfiguration({ model: "new/model" })
  expect(adapter.model).toBe("new/model")
  expect(fake.opens).toHaveLength(2)
  expect(fake.opens[1]?.resumeId).toBe("native-keep")
})

test("claude: setConfiguration({ model }) restarts the same native id", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "old/model" })
  await adapter.start()
  await adapter.setConfiguration({ model: "new/model" })
  expect(adapter.model).toBe("new/model")
  expect(fake.opens).toHaveLength(2)
  expect(fake.opens[1]?.resumeId).toBe("native-keep")
})

test("claude: an effort or fast change relaunches with the NEW values", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const seen: Array<{ model?: string; effort?: string; fast?: boolean }> = []
  const adapter = makeAdapter(kind, host, {
    id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
    model: "claude-opus-5-5", effort: "low",
    onReregister: (f) => seen.push({ model: f.model, effort: f.effort, fast: f.fast }),
  })
  await adapter.start()
  await adapter.setConfiguration({ effort: "high" })
  expect(seen.at(-1)).toEqual({ model: "claude-opus-5-5", effort: "high", fast: false })
  await adapter.setConfiguration({ fast: true })
  expect(seen.at(-1)).toEqual({ model: "claude-opus-5-5", effort: "high", fast: true })
  expect(adapter.fast).toBe(true)
  await adapter.setConfiguration({ fast: true })
  expect(fake.opens).toHaveLength(3)
  expect(fake.opens[2]?.resumeId).toBe("native-keep")
})

test("codex: fast configures the Fast service tier live and clears it", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "gpt-6-sol" })
  await adapter.start()
  await adapter.setConfiguration({ fast: true })
  expect(fake.applied.at(-1)).toEqual({ model: "gpt-6-sol", serviceTier: "priority" })
  await adapter.setConfiguration({ fast: false })
  expect(fake.applied.at(-1)).toEqual({ model: "gpt-6-sol" })
  expect(fake.opens).toHaveLength(1)
})

test("codex: a session started fast opens with the Fast service tier", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "gpt-6-sol", fast: true })
  await adapter.start()
  expect(fake.opens[0]?.configuration).toEqual({ model: "gpt-6-sol", serviceTier: "priority" })
})

test("claude: send during model restart is delivered", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind, { nativeId: "native-keep" })
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "old/model" })
  const errors: unknown[] = []
  adapter.on("error", (e) => errors.push(e))
  await adapter.start()
  const restart = adapter.setConfiguration({ model: "new/model" })
  const sent = adapter.send("hello after switch")
  await Promise.all([restart, sent])
  await flush()
  expect(fake.opens).toHaveLength(2)
  expect(fake.promptTexts.flat()).toContain("hello after switch")
  expect(errors).toHaveLength(0)
})

test("codex: idle send waits for one completion", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  const events = listen(adapter)
  await adapter.start()
  await adapter.send("hello")
  await flush()
  expect(fake.prompts).toHaveLength(1)
  expect(fake.steers).toHaveLength(0)
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("codex: mid-turn send uses steer not a second prompt", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.prompts.length === 1)
  await adapter.send("steer-me")
  expect(fake.steers).toHaveLength(1)
  expect((fake.steers[0]?.[0] as { text?: string }).text).toBe("steer-me")
  expect(fake.prompts).toHaveLength(1)
  fake.completePrompt()
  await first
})

test("codex: image attachment becomes a Core image block", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const img = join(workdir, "pic.png")
  await writeFile(img, Buffer.from("PNGDATA"))
  const note = join(workdir, "note.txt")
  await writeFile(note, "hello")
  const adapter = makeAdapter(kind, host, {
    id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
    resolveAttachment: async (id) => id === "img" ? img : note,
  })
  await adapter.start()
  await adapter.send("see", { attachment_file_id: "img", attachment_mime: "image/png", attachment_kind: "image" })
  const imageBlock = fake.prompts[0]?.find((b) => b.type === "image") as { type: "image"; data: string; mimeType: string }
  expect(imageBlock?.type).toBe("image")
  expect(imageBlock?.mimeType).toBe("image/png")
  await adapter.send("read", { attachment_file_id: "doc", attachment_name: "note.txt" })
  const text = (fake.prompts[1]?.[0] as { text: string }).text
  expect(text).toContain("[Attached file: note.txt")
})

test("codex: every attachment on a turn reaches the agent", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const files: Record<string, string> = {}
  for (const name of ["a.png", "b.png", "c.txt", "d.txt"]) {
    files[name] = join(workdir, name)
    await writeFile(files[name]!, name.endsWith(".png") ? Buffer.from("PNGDATA") : "hello")
  }
  const adapter = makeAdapter(kind, host, {
    id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
    resolveAttachment: async (id) => files[id]!,
  })
  await adapter.start()
  await adapter.send("all four", {
    ...attachmentMeta([
      { file_id: "a.png", kind: "photo", mime: "image/png" },
      { file_id: "b.png", kind: "photo", mime: "image/png" },
      { file_id: "c.txt", kind: "document", name: "c.txt" },
      { file_id: "d.txt", kind: "document", name: "d.txt" },
    ]),
  })
  const blocks = fake.prompts[0]!
  expect(blocks.filter((b) => b.type === "image")).toHaveLength(2)
  const text = (blocks.find((b) => b.type === "text") as { text: string }).text
  expect(text).toContain("[Attached file: c.txt")
  expect(text).toContain("[Attached file: d.txt")
})

test("cursor: path-in-prompt lists every attachment", async () => {
  const kind = "cursor" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, {
    id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
    resolveAttachment: async (id) => `/files/${id}`,
  })
  await adapter.start()
  await adapter.send("two", { ...attachmentMeta([{ file_id: "x", kind: "photo" }, { file_id: "y", kind: "document" }]) })
  const text = (fake.prompts[0]?.[0] as { text: string }).text
  expect(text).toBe("two\n\n[Attached file: /files/x]\n\n[Attached file: /files/y]")
})

test("codex: rpc passthrough refuses turn/* methods", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await adapter.start()
  adapter.attachRuntimeRequest(async (method, params) => {
    if (method.startsWith("turn/") || method.startsWith("thread/")) throw new Error(`Codex runtime request refuses ${method}`)
    if (method === "skills/list") return { data: [] }
    return { method, params }
  })
  expect(await adapter.rpc.request<{ data: unknown[] }>("skills/list", {})).toEqual({ data: [] })
  await expect(adapter.rpc.request("turn/start", {})).rejects.toThrow(/refuses turn\/start/)
})

test("codex: rate-limit notify maps through onUsageUpdate", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const usage: unknown[] = []
  const adapter = makeAdapter(kind, host, {
    id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {},
    onUsageUpdate: (data) => { usage.push(data) },
  })
  await adapter.start()
  fake.emit({
    protocol: "native",
    value: {
      method: "account/rateLimits/updated",
      params: {
        rateLimits: {
          primary: { used_percent: 10, window_duration_mins: 5, resets_at: 1_700_000_000 },
        },
      },
    },
  })
  await flush()
  expect(usage.length).toBeGreaterThan(0)
})

test("codex: an inbox message with busy=queue waits for the running turn instead of steering", async () => {
  const kind = "codex" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.prompts.length === 1)
  const second = adapter.send("from inbox", { busy: "queue" })
  await flush()
  expect(fake.steers).toHaveLength(0)
  expect(fake.prompts).toHaveLength(1)
  fake.completePrompt()
  await first
  await second
  expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "from inbox"])
})

/** What the logger wrote to stderr (info level and up) while `fn` ran. */
async function stderrDuring(fn: () => Promise<void>): Promise<string> {
  const level = process.env.MUX_LOG_LEVEL
  process.env.MUX_LOG_LEVEL = "info"
  const stderr = spyOn(process.stderr, "write")
  try {
    await fn()
    return stderr.mock.calls.map((call) => String(call[0])).join("")
  } finally {
    stderr.mockRestore()
    if (level === undefined) delete process.env.MUX_LOG_LEVEL
    else process.env.MUX_LOG_LEVEL = level
  }
}

for (const kind of [...QUEUE_KINDS, "codex"] as const) {
  test(`${kind}: busy=interrupt ends the running turn, keeps waiting input in order, then runs`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    await adapter.start()
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    const queued = adapter.send("queued", { busy: "queue" })
    const urgent = adapter.send("urgent", { busy: "interrupt" })
    // The fake's hold outlives the interrupt that ends "one": release the input that runs after it.
    await waitUntil(() => fake.interruptCalls === 1)
    fake.completePrompt()
    await Promise.all([first, queued, urgent])
    expect(fake.interruptCalls).toBe(1)
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "queued", "urgent"])
  })

  test(`${kind}: busy=interrupt on an idle session just runs`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    await adapter.start()
    await adapter.send("hello", { busy: "interrupt" })
    expect(fake.interruptCalls).toBe(0)
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["hello"])
  })

  test(`${kind}: busy=interrupt after an unconfirmed interrupt runs once the turn has ended`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    await adapter.start()
    fake.hangInterrupt()
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    const logged = await stderrDuring(async () => {
      const urgent = adapter.send("urgent", { busy: "interrupt" })
      await waitUntil(() => fake.interruptCalls === 1)
      // Past the harness's 40 ms interrupt timeout: unconfirmed, the turn still runs and the message waits.
      await sleep(60)
      expect(adapter.sessionSnapshotState()).toBe("interrupting")
      expect(fake.promptTexts).toHaveLength(1)
      fake.completePrompt()
      await first
      await urgent
    })
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "urgent"])
    const warning = logged.split("\n").find((line) => line.includes("[WARN]") && line.includes("inbox_interrupt_unconfirmed"))
    expect(warning).toContain('"session_id":"sess-1"')
    expect(warning).toContain('"outcome":"unconfirmed"')
  })

  test(`${kind}: busy=interrupt with a driver whose interrupt throws still runs, in order, once the turn has ended`, async () => {
    const fake = fakeAgentDriver(kind)
    const { host, workdir } = await harness(kind, fake)
    const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
    const events = listen(adapter)
    await adapter.start()
    fake.failInterrupt(new Error("turn/interrupt refused"))
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    const queued = adapter.send("queued", { busy: "queue" })
    const logged = await stderrDuring(async () => {
      const urgent = adapter.send("urgent", { busy: "interrupt" })
      await waitUntil(() => fake.interruptCalls === 1)
      await flush()
      expect(fake.promptTexts).toHaveLength(1) // the turn still runs: the message waits for it
      fake.completePrompt()
      await Promise.all([first, queued, urgent])
    })
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "queued", "urgent"])
    expect(events.filter((e) => e.kind === "error")).toEqual([])
    const warning = logged.split("\n").find((line) => line.includes("[WARN]") && line.includes("inbox_interrupt_unconfirmed"))
    expect(warning).toContain('"outcome":"failed"')
    expect(warning).toContain('"err":"turn/interrupt refused"')
  })

  for (const moment of ["its interrupt is pending", "it waits for the turn to end"] as const) {
    test(`${kind}: busy=interrupt: a Stop while ${moment} drops it with the other pending input`, async () => {
      const fake = fakeAgentDriver(kind)
      const { host, workdir } = await harness(kind, fake)
      const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {} })
      listen(adapter)
      await adapter.start()
      fake.hangInterrupt()
      fake.holdNextPrompt()
      const first = adapter.send("one")
      await waitUntil(() => fake.promptTexts.length === 1)
      const queued = adapter.send("queued", { busy: "queue" })
      const urgent = adapter.send("urgent", { busy: "interrupt" })
      await waitUntil(() => fake.interruptCalls === 1)
      if (moment === "it waits for the turn to end") {
        await sleep(60) // past the 40 ms interrupt timeout: unconfirmed, the send now waits for idle
        expect(adapter.sessionSnapshotState()).toBe("interrupting")
      }
      await expect(adapter.interrupt()).rejects.toThrow(/unconfirmed/)
      fake.completePrompt()
      await first
      await queued
      await expect(urgent).rejects.toThrow(/stopped/)
      // Nothing is left paused: the next input runs.
      await adapter.send("after")
      expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "after"])
    })
  }
}

test("claude: busy=interrupt: a model change that needs a restart is refused while the unconfirmed interrupt waits, and the message runs", async () => {
  const kind = "claude" as const
  const fake = fakeAgentDriver(kind)
  const { host, workdir } = await harness(kind, fake)
  const adapter = makeAdapter(kind, host, { id: "sess-1", sessionName: "s1", workdir, persistSessionId: async () => {}, model: "old/model" })
  const events = listen(adapter)
  await adapter.start()
  fake.hangInterrupt()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.promptTexts.length === 1)
  const urgent = adapter.send("urgent", { busy: "interrupt" })
  await waitUntil(() => fake.interruptCalls === 1)
  await sleep(60) // unconfirmed: the turn still runs and the message waits for it
  await expect(adapter.setConfiguration({ model: "new/model" })).rejects.toMatchObject({ code: "session_busy" })
  fake.completePrompt()
  await first
  await urgent
  expect(fake.opens).toHaveLength(1)
  expect(adapter.model).toBe("old/model")
  expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "urgent"])
  expect(events.filter((e) => e.kind === "error")).toEqual([])
})

/** Context updates the core applies by relaunching the session (a "reload"). */
const RELOAD_CONTEXT: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "t" }, skills: { support: "supported", note: "t" },
    plugins: { support: "supported", note: "t" }, mcpServers: { support: "supported", note: "t" },
  },
  update: {
    skills: { add: "reload", remove: "reload", note: "t" }, plugins: { add: "reload", remove: "reload", note: "t" },
    mcpServers: { add: "reload", remove: "reload", note: "t" },
  },
}

// A reload waits for the turn to end with the queue held, then takes the queue (still paused by the
// interrupt) to a new Session. Which side the turn's end wakes first depends on the interrupt:
// - confirmed: the adapter, which continues the queue and queues the input before the reload takes
//   it (this breaks if an await slips in between the reopening check, continue() and the send);
// - unconfirmed: the reload, which takes the queue first; the input follows it to the new Session.
for (const interrupt of ["confirmed", "unconfirmed"] as const) {
  test(`busy=interrupt (${interrupt}) racing a core-side reload that waits for the turn to end: both inputs run on the new Session, in order`, async () => {
    const fake = fakeAgentDriver("claude")
    const stateDirectory = await mkdtemp(join(tmpdir(), "claude-core-"))
    const workdir = await mkdtemp(join(tmpdir(), "claude-wd-"))
    const skills = await mkdtemp(join(tmpdir(), "claude-skills-"))
    dirs.push(stateDirectory, workdir, skills)
    await mkdir(join(skills, "a"))
    const host = createHost({ stateDirectory, limits: { interruptTimeoutMs: 40, maxPending: 16, outstandingActivity: 256 }, agent: "claude", driver: () => fake.driver, context: RELOAD_CONTEXT })
    hosts.push(host)
    const adapter = new CoreAdapter(CORE_ADAPTER_PROFILES.claude, {
      handle: host.register({ id: "s1", env: {}, context: { instructions: "I" } }),
      reregister: () => host.register({ id: "s1", env: {} }),
      core: host.core, id: "s1", sessionName: "s1", workdir, persistSessionId: async () => {},
    })
    adapters.push(adapter)
    const errors: unknown[] = []
    adapter.on("error", (e) => errors.push(e))
    let accepted = 0
    let acceptedWhenOldClosed: number | undefined
    host.core.subscribe((e) => {
      if (e.type === "message.accepted") accepted++
      if (e.type === "session.stateChanged" && e.state === "closed") acceptedWhenOldClosed ??= accepted
    })
    await adapter.start()
    if (interrupt === "unconfirmed") fake.hangInterrupt()
    fake.holdNextPrompt()
    const first = adapter.send("one")
    await waitUntil(() => fake.promptTexts.length === 1)
    const queued = adapter.send("queued", { busy: "queue" })
    const reload = host.core.sessions.updateContext("s1", { skills: { add: [skills] } })
    // Its context is in the live record just before the reload holds the queue and waits for idle.
    await waitUntil(() => JSON.stringify(host.core.sessions.live("s1")?.snapshot().context ?? {}).includes(skills), 1000)
    const urgent = adapter.send("urgent", { busy: "interrupt" })
    await waitUntil(() => fake.interruptCalls === 1)
    if (interrupt === "unconfirmed") await sleep(60) // past the 40 ms interrupt timeout; the turn still runs
    fake.completePrompt() // ends the unconfirmed turn; releases the fake's hold for the input after it
    await Promise.all([first, queued, urgent, reload])
    expect(fake.opens).toHaveLength(2)
    expect(fake.promptTexts.map((p) => p[0])).toEqual(["one", "queued", "urgent"])
    expect(host.core.sessions.live("s1")?.snapshot().paused).toBe(false)
    expect(errors).toEqual([])
    // "urgent" was accepted by the old Session before it closed (3), or by the new one after (2).
    expect(acceptedWhenOldClosed).toBe(interrupt === "confirmed" ? 3 : 2)
  })
}

