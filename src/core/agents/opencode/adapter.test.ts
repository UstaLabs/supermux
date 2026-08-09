import { test, expect } from "bun:test"
import { OpenCodeAdapter, type OpenCodeClientLike, type OpenCodeEvent, type OpenCodePromptBody } from "./adapter"
import type { AgentEvent } from "../types"

const tick = () => new Promise((r) => setTimeout(r, 5))

type PromptCall = { id: string; body: OpenCodePromptBody }

function makeClient(opts: {
  createResult?: { data?: { id?: string }; error?: unknown }
  promptResult?: { data?: { parts?: any[] }; error?: unknown }
  promptThrows?: unknown
  events?: OpenCodeEvent[]
} = {}) {
  const promptCalls: PromptCall[] = []
  const abortCalls: string[] = []
  const client: OpenCodeClientLike = {
    session: {
      async create() {
        return opts.createResult ?? { data: { id: "sess_1" } }
      },
      async update() {
        return { data: {} }
      },
      async prompt(o) {
        promptCalls.push({ id: o.path.id, body: o.body })
        if (opts.promptThrows) throw opts.promptThrows
        return opts.promptResult ?? { data: { parts: [{ type: "text", text: "ok" }] } }
      },
      async abort(o) {
        abortCalls.push(o.path.id)
        return true
      },
    },
    listCommands: async () => [],
    event: {
      async subscribe() {
        const evs = opts.events ?? []
        return {
          stream: (async function* () {
            for (const e of evs) yield e
          })(),
        }
      },
    },
  }
  return { client, promptCalls, abortCalls }
}

function collect(adapter: OpenCodeAdapter) {
  const events: AgentEvent[] = []
  for (const k of ["turn-start", "turn-complete", "assistant-message", "tool-call", "error"] as const) {
    adapter.on(k, (e: AgentEvent) => events.push(e))
  }
  return events
}

test("start() creates a session and persists its id", async () => {
  const { client } = makeClient({ createResult: { data: { id: "sess_abc" } } })
  let persisted: string | undefined
  const adapter = new OpenCodeAdapter({
    sessionName: "demo", workdir: "/tmp/x", client,
    persistSessionId: async (id) => { persisted = id },
  })
  await adapter.start()
  expect(persisted).toBe("sess_abc")
})

test("start() throws when create returns an error", async () => {
  const { client } = makeClient({ createResult: { error: "boom" } })
  const adapter = new OpenCodeAdapter({
    sessionName: "demo", workdir: "/tmp/x", client, persistSessionId: async () => {},
  })
  await expect(adapter.start()).rejects.toThrow("boom")
})

test("send() brackets the turn and emits assistant text from returned parts", async () => {
  const { client, promptCalls } = makeClient({
    promptResult: { data: { parts: [
      { type: "text", text: "first" },
      { type: "tool", tool: "bash", callID: "c1", state: { status: "completed" } },
      { type: "text", text: "  " },          // whitespace-only → skipped
      { type: "text", text: "second" },
    ] } },
  })
  const adapter = new OpenCodeAdapter({
    sessionName: "demo", workdir: "/tmp/x", client, persistSessionId: async () => {},
  })
  await adapter.start()
  const events = collect(adapter)
  await adapter.send("hi", { chat_id: "web" })

  const kinds = events.map((e) => e.kind)
  expect(kinds[0]).toBe("turn-start")
  expect(kinds[kinds.length - 1]).toBe("turn-complete")
  const msgs = events.filter((e) => e.kind === "assistant-message") as Extract<AgentEvent, { kind: "assistant-message" }>[]
  expect(msgs.map((m) => m.text)).toEqual(["first", "second"])
  // The adapter says WHAT to send, never WHERE — the broker owns the destination.
  expect(msgs.every((m) => !("chat_id" in m))).toBe(true)
  expect(promptCalls[0]!.id).toBe("sess_1")
})

test("send() encodes the model as providerID/modelID and omits it when unsplittable", async () => {
  const withModel = makeClient()
  const a1 = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client: withModel.client, persistSessionId: async () => {}, model: "anthropic/claude-sonnet-4",
  })
  await a1.start()
  await a1.send("hi")
  expect(withModel.promptCalls[0]!.body.model).toEqual({ providerID: "anthropic", modelID: "claude-sonnet-4" })

  const noModel = makeClient()
  const a2 = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client: noModel.client, persistSessionId: async () => {}, model: "bareword",
  })
  await a2.start()
  await a2.send("hi")
  expect(noModel.promptCalls[0]!.body.model).toBeUndefined()
})

test("send() error result emits error then turn-complete (no assistant message)", async () => {
  const { client } = makeClient({ promptResult: { error: "rate limited" } })
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {},
  })
  await adapter.start()
  const events = collect(adapter)
  await adapter.send("hi")
  const kinds = events.map((e) => e.kind)
  expect(kinds).toContain("error")
  expect(kinds).not.toContain("assistant-message")
  expect(kinds[kinds.length - 1]).toBe("turn-complete")
})

test("send() treats opencode assistant info.error as a turn error", async () => {
  const { client } = makeClient({
    promptResult: {
      data: {
        info: {
          error: {
            name: "APIError",
            data: { message: "Insufficient balance" },
          },
        },
        parts: [],
      } as any,
    },
  })
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {},
  })
  await adapter.start()
  const events = collect(adapter)
  await adapter.send("hi")
  const kinds = events.map((e) => e.kind)
  expect(kinds).toContain("error")
  expect(kinds).not.toContain("assistant-message")
  expect(kinds[kinds.length - 1]).toBe("turn-complete")
  const err = events.find((e) => e.kind === "error") as Extract<AgentEvent, { kind: "error" }>
  expect(err.error.message).toContain("Insufficient balance")
})

test("send() folds a resolved attachment path into the prompt text", async () => {
  const { client, promptCalls } = makeClient()
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {},
    resolveAttachment: async () => "/tmp/pic.png",
  })
  await adapter.start()
  await adapter.send("look", { attachment_file_id: "f1", attachment_name: "pic.png" })
  const textPart = promptCalls[0]!.body.parts[0] as unknown as { text: string }
  expect(textPart.text).toContain("look")
  expect(textPart.text).toContain("[Attached file: pic.png (/tmp/pic.png)]")
})

test("event stream maps tool-part state transitions and dedupes started", async () => {
  const events: OpenCodeEvent[] = [
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "bash", callID: "c1", sessionID: "sess_1", state: { status: "running" } } } },
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "bash", callID: "c1", sessionID: "sess_1", state: { status: "running" } } } }, // no input yet → still ignored
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "bash", callID: "c1", sessionID: "sess_1", state: { status: "running", input: { command: "npm test" } } } } },
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "bash", callID: "c1", sessionID: "sess_1", state: { status: "running", input: { command: "npm test" } } } } }, // dup → ignored
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "bash", callID: "c1", sessionID: "sess_1", state: { status: "completed", input: { command: "npm test" }, output: "ok", title: "npm test" } } } },
    { type: "message.part.updated", properties: { part: { type: "tool", tool: "edit", callID: "c2", sessionID: "sess_1", state: { status: "error", input: { filePath: "/a.ts" }, error: "fail" } } } },
    { type: "session.idle", properties: { sessionID: "sess_1" } }, // ignored by mapping
  ]
  const { client } = makeClient({ events })
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {},
  })
  const collected = collect(adapter)
  await adapter.start()
  await tick()
  const tools = collected.filter((e) => e.kind === "tool-call") as Extract<AgentEvent, { kind: "tool-call" }>[]
  expect(tools.map((t) => `${t.tool}:${t.phase}`)).toEqual(["bash:started", "bash:completed", "edit:started", "edit:failed"])
})

test("interrupt() aborts the session", async () => {
  const { client, abortCalls } = makeClient()
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {},
  })
  await adapter.start()
  await adapter.interrupt()
  expect(abortCalls).toEqual(["sess_1"])
})

// --- stall watchdog (a lost prompt must not hang the turn forever) ---

/** A controllable event stream so a test can emit activity *during* send(). */
function manualStream() {
  const buf: OpenCodeEvent[] = []
  let pending: ((r: IteratorResult<OpenCodeEvent>) => void) | null = null
  return {
    push(e: OpenCodeEvent) {
      if (pending) { const p = pending; pending = null; p({ value: e, done: false }) }
      else buf.push(e)
    },
    stream: {
      [Symbol.asyncIterator]() {
        return {
          next(): Promise<IteratorResult<OpenCodeEvent>> {
            if (buf.length) return Promise.resolve({ value: buf.shift()!, done: false })
            return new Promise((res) => { pending = res })
          },
        }
      },
    } as AsyncIterable<OpenCodeEvent>,
  }
}

test("send() watchdog: a stalled prompt with no activity aborts and surfaces an error", async () => {
  const { client, abortCalls } = makeClient()
  ;(client.session as any).prompt = () => new Promise(() => {}) // never resolves
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {}, stallTimeoutMs: 40,
  })
  await adapter.start()
  const events = collect(adapter)
  await adapter.send("hi")
  const kinds = events.map((e) => e.kind)
  expect(kinds[0]).toBe("turn-start")
  expect(kinds).toContain("error")
  expect(kinds[kinds.length - 1]).toBe("turn-complete")
  expect(abortCalls).toContain("sess_1") // the stalled turn was aborted
})

// --- serialization: a message sent mid-turn must not race the in-flight one ---

/** A client whose prompt() stays pending until released, so a test can overlap
 * two send()s and observe how many prompts run at once. Each reply echoes its
 * own input text ("reply:<input>") so replies can be attributed to their turn. */
function makeGatedClient() {
  const promptCalls: PromptCall[] = []
  const gates: Array<() => void> = []
  let inFlight = 0
  let maxInFlight = 0
  const client: OpenCodeClientLike = {
    session: {
      async create() { return { data: { id: "sess_1" } } },
      async update() { return { data: {} } },
      prompt(o): Promise<{ data?: { parts?: any[] }; error?: unknown }> {
        const idx = promptCalls.length
        promptCalls.push({ id: o.path.id, body: o.body })
        inFlight++; maxInFlight = Math.max(maxInFlight, inFlight)
        const input = (o.body.parts[0] as unknown as { text: string }).text
        return new Promise((resolve) => {
          gates[idx] = () => { inFlight--; resolve({ data: { parts: [{ type: "text", text: `reply:${input}` }] } }) }
        })
      },
      async abort() { return true },
    },
    listCommands: async () => [],
    event: { async subscribe() { return { stream: (async function* () {})() } } },
  }
  return { client, promptCalls, release: (i: number) => gates[i]?.(), maxInFlight: () => maxInFlight }
}

test("send() serializes overlapping turns: one prompt() in flight, each turn keeps its own reply", async () => {
  const g = makeGatedClient()
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client: g.client, persistSessionId: async () => {},
  })
  await adapter.start()
  const events = collect(adapter)

  const p1 = adapter.send("A", { chat_id: "web" })
  const p2 = adapter.send("B", { chat_id: "web" }) // arrives while A is still running
  await tick()

  // Only turn A is in flight; B is queued behind it.
  expect(g.promptCalls.length).toBe(1)
  expect((g.promptCalls[0]!.body.parts[0] as unknown as { text: string }).text).toBe("A")

  g.release(0)            // finish A → B should now start
  await p1
  await tick()
  expect(g.promptCalls.length).toBe(2)
  expect((g.promptCalls[1]!.body.parts[0] as unknown as { text: string }).text).toBe("B")

  g.release(1)
  await p2

  expect(g.maxInFlight()).toBe(1) // never two prompts at once
  const msgs = events.filter((e) => e.kind === "assistant-message") as Extract<AgentEvent, { kind: "assistant-message" }>[]
  expect(msgs.map((m) => m.text)).toEqual(["reply:A", "reply:B"]) // no clobber, in order
})

test("send() carries no destination — the broker routes the reply", async () => {
  const g = makeGatedClient()
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client: g.client, persistSessionId: async () => {},
  })
  await adapter.start()
  const events = collect(adapter)

  const p1 = adapter.send("A", { chat_id: "chatA" })
  const p2 = adapter.send("B", { chat_id: "chatB" })   // meta still carries it; the adapter must ignore it
  await tick()
  g.release(0); await p1; await tick()
  g.release(1); await p2

  const msgs = events.filter((e) => e.kind === "assistant-message") as Extract<AgentEvent, { kind: "assistant-message" }>[]
  expect(msgs.map((m) => m.text)).toEqual(["reply:A", "reply:B"])
  expect(msgs.every((m) => !("chat_id" in m))).toBe(true)
})

test("send() watchdog: turn activity disarms it so a slow turn still completes", async () => {
  const ms = manualStream()
  let resolvePrompt!: (v: { data?: { parts?: any[] }; error?: unknown }) => void
  const client: OpenCodeClientLike = {
    session: {
      async create() { return { data: { id: "sess_1" } } },
      async update() { return { data: {} } },
      prompt(): Promise<{ data?: { parts?: any[] }; error?: unknown }> { return new Promise((res) => { resolvePrompt = res }) },
      async abort() { return true },
    },
    listCommands: async () => [],
    event: { async subscribe() { return { stream: ms.stream } } },
  }
  const adapter = new OpenCodeAdapter({
    sessionName: "d", workdir: "/x", client, persistSessionId: async () => {}, stallTimeoutMs: 30,
  })
  const events = collect(adapter)
  await adapter.start()
  const sendP = adapter.send("hi")
  await tick() // let send() arm the watchdog
  ms.push({ type: "message.part.updated", properties: { part: { type: "text", text: "…", sessionID: "sess_1" } } })
  await new Promise((r) => setTimeout(r, 70)) // exceed the 30ms watchdog — must NOT fire (disarmed)
  resolvePrompt({ data: { parts: [{ type: "text", text: "done" }] } })
  await sendP
  const kinds = events.map((e) => e.kind)
  expect(kinds).not.toContain("error")
  expect(kinds[kinds.length - 1]).toBe("turn-complete")
  const msgs = events.filter((e) => e.kind === "assistant-message") as Extract<AgentEvent, { kind: "assistant-message" }>[]
  expect(msgs.map((m) => m.text)).toContain("done")
})
