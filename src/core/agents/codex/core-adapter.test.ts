import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../../../../packages/supermux-core/src/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import type { AgentDriver, AgentRuntime, ContentBlock, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { CoreCodexAdapter } from "./core-adapter"

const tick = () => new Promise<void>((r) => setTimeout(r, 0))
const flush = async () => { await tick(); await tick() }
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

function fakeAgentDriver(options: { configure?: boolean; nativeId?: string; steerBusy?: boolean } = {}) {
  const opens: DriverContext[] = []
  const prompts: ContentBlock[][] = []
  const steers: ContentBlock[][] = []
  const applied: SessionConfiguration[] = []
  let closes = 0
  let interruptCalls = 0
  let liveConfig: SessionConfiguration = {}
  let promptGate: ReturnType<typeof deferred<{ stopReason: string }>> | undefined
  let holdPrompt = false
  let interruptHangs = false
  let runtimeRequest: ((method: string, params: unknown) => Promise<unknown>) | undefined

  const driver: AgentDriver = {
    id: "codex",
    async open(ctx) {
      opens.push(ctx)
      if (ctx.configuration) liveConfig = { ...ctx.configuration }
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
        capabilities: {
          resume: true, steer: true, fork: false, detach: false,
          configure: options.configure !== false, history: false,
        },
        async prompt(content) {
          prompts.push(content)
          const work = deferred<{ stopReason: string }>()
          promptGate = work
          if (!holdPrompt) work.resolve({ stopReason: "end_turn" })
          try { return await work.promise }
          finally { if (promptGate === work) promptGate = undefined }
        },
        async steer(content) {
          if (options.steerBusy) throw new CoreError("session_busy", "Native work is ambiguous")
          steers.push(content)
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
    driver, opens, prompts, steers, applied,
    get closes() { return closes },
    get interruptCalls() { return interruptCalls },
    holdNextPrompt() { holdPrompt = true },
    completePrompt() { promptGate?.resolve({ stopReason: "end_turn" }); holdPrompt = false },
    hangInterrupt() { interruptHangs = true },
    emit(update: { protocol: "acp" | "native"; value: unknown; replay?: boolean }) {
      opens.at(-1)?.onUpdate(update as Parameters<DriverContext["onUpdate"]>[0])
    },
    startActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "started" }) },
    completeActivity(id: string) { opens.at(-1)?.onActivity?.({ id, phase: "completed" }) },
    failRuntime(error: Error) { opens.at(-1)?.onExit(error) },
    setRuntimeRequest(fn: (method: string, params: unknown) => Promise<unknown>) { runtimeRequest = fn },
    get runtimeRequest() { return runtimeRequest },
  }
}

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
const adapters: CoreCodexAdapter[] = []

async function harness(driver: AgentDriver = fakeAgentDriver().driver) {
  const workdir = await mkdtemp(join(tmpdir(), "codex-wd-"))
  const stateDirectory = await mkdtemp(join(tmpdir(), "codex-core-"))
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

function listen(adapter: CoreCodexAdapter) {
  const events: any[] = []
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete", "error"]) {
    adapter.on(k, (e) => events.push(e))
  }
  return events
}

test("idle send waits for one completion and emits one turn-start/turn-complete", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  await adapter.send("hello")
  await flush()
  expect(fake.prompts).toHaveLength(1)
  expect(fake.steers).toHaveLength(0)
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("mid-turn send uses steer not a second prompt", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
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

test("ambiguous steer surfaces an error and does not queue a second prompt", async () => {
  const fake = fakeAgentDriver({ steerBusy: true })
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.prompts.length === 1)
  await adapter.send("nope")
  await flush()
  expect(events.filter((e) => e.kind === "error")).toHaveLength(1)
  expect(fake.prompts).toHaveLength(1)
  fake.completePrompt()
  await first
})

test("autonomous native turn emits turn-start/turn-complete without a send", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("auto-1")
  fake.emit({ protocol: "native", value: { method: "turn/started", params: { turn: { id: "auto-1" } } } })
  await flush()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  fake.emit({ protocol: "native", value: { method: "turn/completed", params: { turn: { id: "auto-1" } } } })
  fake.completeActivity("auto-1")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
  expect(fake.prompts).toHaveLength(0)
})

test("item/completed agentMessage emits one assistant-message; deltas are ignored", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.emit({ protocol: "native", value: { method: "item/agentMessage/delta", params: { item: { type: "agentMessage", text: "partial" }, delta: "partial" } } })
  fake.emit({ protocol: "native", value: { method: "item/completed", params: { item: { type: "agentMessage", text: "final answer" } } } })
  await flush()
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["final answer"])
})

test("webSearch defers blank start until completed snapshot", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.emit({ protocol: "native", value: { method: "item/started", params: { item: { type: "webSearch", id: "w1", query: "", action: null } } } })
  await flush()
  expect(events.filter((e) => e.kind === "tool-call")).toEqual([])
  const completed = { type: "webSearch", id: "w1", query: "docs", action: { query: "docs" } }
  fake.emit({ protocol: "native", value: { method: "item/completed", params: { item: completed } } })
  await flush()
  expect(events.filter((e) => e.kind === "tool-call")).toEqual([
    { kind: "tool-call", tool: "webSearch", phase: "started", call_id: "w1", detail: completed },
    { kind: "tool-call", tool: "webSearch", phase: "completed", call_id: "w1", detail: completed },
  ])
})

test("rate-limit notify maps through onUsageUpdate", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const usage: unknown[] = []
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
    onUsageUpdate: (data) => { usage.push(data) },
  })
  adapters.push(adapter)
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

test("image attachment becomes a Core image block; non-image is folded into text", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const img = join(workdir, "pic.png")
  await writeFile(img, Buffer.from("PNGDATA"))
  const note = join(workdir, "note.txt")
  await writeFile(note, "hello")
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    persistThreadId: async () => {},
    resolveAttachment: async (id) => id === "img" ? img : note,
  })
  adapters.push(adapter)
  await adapter.start()
  await adapter.send("see", { attachment_file_id: "img", attachment_mime: "image/png", attachment_kind: "image" })
  const imageBlock = fake.prompts[0]?.find((b) => b.type === "image") as { type: "image"; data: string; mimeType: string }
  expect(imageBlock?.type).toBe("image")
  expect(imageBlock?.mimeType).toBe("image/png")
  expect(imageBlock?.data).toBe(Buffer.from("PNGDATA").toString("base64"))
  expect(fake.prompts[0]?.some((b) => b.type === "resource_link")).toBe(false)
  await adapter.send("read", { attachment_file_id: "doc", attachment_name: "note.txt" })
  const text = (fake.prompts[1]?.[0] as { text: string }).text
  expect(text).toContain("[Attached file: note.txt")
  expect(text).toContain(note)
})

test("interrupt confirmed vs unconfirmed", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.prompts.length === 1)
  await adapter.interrupt()
  await first
  expect(fake.interruptCalls).toBe(1)

  fake.hangInterrupt()
  fake.holdNextPrompt()
  const second = adapter.send("two")
  await waitUntil(() => fake.prompts.length === 2)
  await expect(adapter.interrupt()).rejects.toThrow(/unconfirmed/)
  expect(events.filter((e) => e.kind === "error").some((e) => String(e.error?.message).includes("unconfirmed"))).toBe(true)
  fake.completePrompt()
  await second.catch(() => {})
})

test("stop during start closes the late-opened session", async () => {
  const openGate = deferred<void>()
  const entered = deferred<void>()
  let closes = 0
  let opens = 0
  const driver: AgentDriver = {
    id: "codex",
    async open(ctx) {
      opens++
      entered.resolve()
      await openGate.promise
      return {
        agentSessionId: ctx.resumeId ?? "native-1",
        capabilities: { resume: true, steer: true, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" as const }),
        interrupt: async () => {},
        close: async () => { closes++ },
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const started = adapter.start()
  await entered.promise
  const stopping = adapter.stop()
  openGate.resolve()
  await stopping
  await started.catch(() => {})
  expect(closes).toBe(1)
})

test("failed close is retained then retry succeeds", async () => {
  let closeAttempts = 0
  const driver: AgentDriver = {
    id: "codex",
    async open() {
      return {
        agentSessionId: "n-fail-close",
        capabilities: { resume: true, steer: true, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" as const }),
        interrupt: async () => {},
        async close() {
          closeAttempts++
          if (closeAttempts === 1) throw new Error("native close failed")
        },
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  await expect(adapter.stop()).rejects.toThrow(/native close failed/)
  await adapter.stop()
  expect(closeAttempts).toBe(2)
})

test("setConfiguration rolls adapter fields back on failure", async () => {
  let fail = false
  const applied: SessionConfiguration[] = []
  const driver: AgentDriver = {
    id: "codex",
    async open() {
      let live: SessionConfiguration = {}
      return {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: true, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" as const }),
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
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
    model: "gpt-5", effort: "low",
  })
  adapters.push(adapter)
  await adapter.start()
  await adapter.setConfiguration({ model: "gpt-5-mini", effort: "high" })
  expect(adapter.model).toBe("gpt-5-mini")
  fail = true
  await expect(adapter.setConfiguration({ model: "nope" })).rejects.toThrow(/native configure failed/)
  expect(adapter.model).toBe("gpt-5-mini")
})

test("rpc passthrough refuses turn/* methods", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  adapter.attachRuntimeRequest(async (method, params) => {
    if (method.startsWith("turn/") || method.startsWith("thread/")) throw new Error(`Codex runtime request refuses ${method}`)
    if (method === "skills/list") return { data: [] }
    return { method, params }
  })
  expect(await adapter.rpc.request<{ data: unknown[] }>("skills/list", {})).toEqual({ data: [] })
  await expect(adapter.rpc.request("turn/start", {})).rejects.toThrow(/refuses turn\/start/)
})

test("adopt uses exact resume id; existing record mismatch is rejected", async () => {
  const fake = fakeAgentDriver({ nativeId: "native-prior" })
  const { core, workdir } = await harness(fake.driver)
  const persisted: string[] = []
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir,
    initialThreadId: "native-prior",
    persistThreadId: async (id) => { persisted.push(id) },
  })
  adapters.push(adapter)
  await adapter.start()
  expect(fake.opens[0]?.resumeId).toBe("native-prior")
  expect(persisted).toEqual(["native-prior"])

  const other = await mkdtemp(join(tmpdir(), "codex-wd-"))
  dirs.push(other)
  const bad = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir: other, persistThreadId: async () => {},
  })
  adapters.push(bad)
  await expect(bad.start()).rejects.toThrow(/cwd mismatch/)
})

test("overlapping native activities close the latch only when Core goes idle", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.startActivity("auto-1")
  fake.emit({ protocol: "native", value: { method: "turn/started", params: { turn: { id: "auto-1" } } } })
  fake.startActivity("auto-2")
  fake.emit({ protocol: "native", value: { method: "turn/started", params: { turn: { id: "auto-2" } } } })
  await flush()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  fake.emit({ protocol: "native", value: { method: "turn/completed", params: { turn: { id: "auto-1" } } } })
  fake.completeActivity("auto-1")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  fake.emit({ protocol: "native", value: { method: "turn/completed", params: { turn: { id: "auto-2" } } } })
  fake.completeActivity("auto-2")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("owned prompt completion does not close the latch while native activity continues", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("owned")
  await waitUntil(() => fake.prompts.length === 1)
  fake.startActivity("auto-1")
  fake.emit({ protocol: "native", value: { method: "turn/started", params: { turn: { id: "auto-1" } } } })
  fake.completePrompt()
  await first
  await flush()
  expect(events.filter((e) => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(0)
  fake.emit({ protocol: "native", value: { method: "turn/completed", params: { turn: { id: "auto-1" } } } })
  fake.completeActivity("auto-1")
  await flush()
  expect(events.filter((e) => e.kind === "turn-complete")).toHaveLength(1)
})

test("two consecutive failing idle sends each emit an error", async () => {
  const driver: AgentDriver = {
    id: "codex",
    async open() {
      return {
        agentSessionId: "n-fail",
        capabilities: { resume: true, steer: true, fork: false, detach: false, configure: true },
        prompt: async () => { throw new Error("idle send failed") },
        interrupt: async () => {},
        close: async () => {},
      }
    },
  }
  const { core, workdir } = await harness(driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  await adapter.send("one")
  await adapter.send("two")
  await flush()
  expect(events.filter((e) => e.kind === "error")).toHaveLength(2)
})

test("session.failed plus failed completion in one turn emit a single error", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  const events = listen(adapter)
  await adapter.start()
  fake.holdNextPrompt()
  const first = adapter.send("one")
  await waitUntil(() => fake.prompts.length === 1)
  fake.failRuntime(new Error("native died"))
  await first.catch(() => {})
  await flush()
  expect(events.filter((e) => e.kind === "error")).toHaveLength(1)
})

test("idle snapshot then native activity retries send once as steer", async () => {
  const fake = fakeAgentDriver()
  const { core, workdir } = await harness(fake.driver)
  const adapter = new CoreCodexAdapter({
    core, id: "sess-1", sessionName: "s1", workdir, persistThreadId: async () => {},
  })
  adapters.push(adapter)
  await adapter.start()
  const session = (adapter as unknown as { session: { send: Function } }).session
  const origSend = session.send.bind(session)
  session.send = async (opts: unknown) => {
    fake.startActivity("native-race")
    return origSend(opts)
  }
  await adapter.send("after-race")
  expect(fake.prompts).toHaveLength(0)
  expect(fake.steers).toHaveLength(1)
  expect((fake.steers[0]?.[0] as { text?: string }).text).toBe("after-race")
  fake.completeActivity("native-race")
})
