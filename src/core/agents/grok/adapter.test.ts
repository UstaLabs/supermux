import { test, expect } from "bun:test"
import { GrokAdapter } from "./adapter"
import { AcpClient } from "./acp-client"

// The fake runner uses a no-op write, so responses are fed by hand. A JSON-RPC
// response is only matched if its request is already registered — and start()/
// send() register their requests across `await` boundaries (microtasks). So we
// yield a real macrotask before feeding each id-carrying response, otherwise the
// response arrives before the request is pending and is dropped (test would hang).
const tick = () => new Promise((r) => setTimeout(r, 0))

function fakeRunner() {
  let client!: AcpClient
  let exit!: (c: number | null, stderr?: string) => void
  const runner = (opts: any) => { client = opts.client; exit = opts.onExit; return { kill: () => exit(0) } }
  return { runner, feed: (o: any) => client.feed(JSON.stringify(o) + "\n"), get client() { return client }, exit: (code: number | null = 0, stderr?: string) => exit(code, stderr) }
}

test("start() handshakes and send() streams reply + tool events then completes", async () => {
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1, _meta: { modelState: { availableModels: [{ modelId: "grok-4.5" }] } } } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("write a poem")
  await tick()
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call", toolCallId: "c0", title: "write", rawInput: { file_path: "/w/p.txt" } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Done." } } } })
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn", _meta: { inputTokens: 10, outputTokens: 2 } } })
  await sent

  expect(events.find((e) => e.kind === "turn-start")).toBeTruthy()
  expect(events.find((e) => e.kind === "tool-call" && e.phase === "started")).toBeTruthy()
  expect(events.find((e) => e.kind === "assistant-message")?.text).toBe("Done.")
  expect(events.find((e) => e.kind === "turn-complete")).toBeTruthy()
})

test("accumulates streamed chunk deltas into ONE assistant message when no tools run", async () => {
  // Regression guard: grok streams agent_message_chunk token-by-token ("Hel","lo","!").
  // Emitting per chunk would push one chat message per token.
  const fr = fakeRunner()
  const msgs: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  adapter.on("assistant-message", (e) => msgs.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("hi")
  await tick()
  for (const t of ["Hel", "lo", ", wor", "ld!"]) {
    fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: t } } } })
  }
  // No message may be emitted mid-stream (only at tool boundary / turn end).
  expect(msgs.length).toBe(0)
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn" } })
  await sent

  expect(msgs.length).toBe(1)
  expect(msgs[0].text).toBe("Hello, world!")
})

test("flushes pending text on each new tool_call, then final text on turn end", async () => {
  // Live-verified multi-step shape: msg → tool → msg → tool → msg → end_turn.
  // Without tool-boundary flush, the user only sees everything after tools finish.
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  for (const k of ["assistant-message", "tool-call"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("do steps")
  await tick()
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Step 1." } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call", toolCallId: "c1", title: "write" } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call_update", toolCallId: "c1", kind: "edit", status: "completed" } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Step 2." } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call", toolCallId: "c2", title: "bash" } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call_update", toolCallId: "c2", kind: "execute", status: "completed" } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Done." } } } })
  // After first tool_call, pre-tool text must already be visible.
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["Step 1.", "Step 2."])
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn" } })
  await sent

  expect(events.filter((e) => e.kind === "assistant-message").map((e) => e.text)).toEqual(["Step 1.", "Step 2.", "Done."])
  // Tool events still fire after the flush (order: text, tool-started, tool-completed, …).
  const kinds = events.map((e) => e.kind === "tool-call" ? `tool:${e.phase}` : e.kind)
  expect(kinds).toEqual([
    "assistant-message", "tool:started", "tool:completed",
    "assistant-message", "tool:started", "tool:completed",
    "assistant-message",
  ])
})

test("flushes the partial answer when a turn is interrupted", async () => {
  const fr = fakeRunner()
  const msgs: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  adapter.on("assistant-message", (e) => msgs.push(e))
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("count")
  await tick()
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "1 2 3" } } } })
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "Cancelled" } })
  await sent

  expect(msgs.map((m) => m.text)).toEqual(["1 2 3"])
})

test("flushes a turn grok starts by itself at its own turn_completed", async () => {
  // Live-verified: when a background task grok launched finishes, grok injects its
  // own user message and runs a whole turn with no session/prompt from us. The
  // request lifetime therefore cannot own the turn boundary — the stream does.
  // Without this, that turn's text sat in the buffer and left glued to the FRONT
  // of the next turn's first flush (or was dropped entirely).
  const fr = fakeRunner()
  const msgs: any[] = []
  const turns: string[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  adapter.on("assistant-message", (e) => msgs.push(e))
  for (const k of ["turn-start", "turn-complete"]) adapter.on(k, (e) => turns.push(e.kind))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("hi", { chat_id: "web" } as any)
  await tick()
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "user_message_chunk", content: { type: "text", text: "hi" } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Answer." } } } })
  fr.feed({ jsonrpc: "2.0", method: "_x.ai/session_notification", params: { update: { sessionUpdate: "turn_completed", prompt_id: "p1", stop_reason: "end_turn" } } })
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn" } })
  await sent

  // Now supermux is idle. Grok runs a turn on its own.
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "user_message_chunk", content: { type: "text", text: "<system-reminder>\nBackground task \"t1\" completed (exit 0).\n</system-reminder>" } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "That background task finished." } } } })
  fr.feed({ jsonrpc: "2.0", method: "_x.ai/session_notification", params: { update: { sessionUpdate: "turn_completed", prompt_id: "task-completed-t1", stop_reason: "end_turn" } } })

  // Delivered on its own, in its own message, to the chat we last talked on.
  expect(msgs.map((m) => m.text)).toEqual(["Answer.", "That background task finished."])
  expect(msgs.map((m) => m.chat_id)).toEqual(["web", "web"])
  // One start + one complete per turn — no double-fire from the request path.
  expect(turns).toEqual(["turn-start", "turn-complete", "turn-start", "turn-complete"])
})

test("start() passes model + effort as spawn flags, not as session/prompt params", async () => {
  // Regression guard: grok ignores a `model` param on session/prompt, and has no
  // session/set_reasoning_effort. Both must ride the spawn flags or the model and
  // effort pills silently do nothing.
  const fr = fakeRunner()
  let seen: any
  const runner = (opts: any) => { seen = opts; return fr.runner(opts) }
  const adapter = new GrokAdapter({
    sessionName: "s1", workdir: "/w", runner, persistSessionId: async () => {},
    model: "grok-4.5", effort: "low",
  })
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  expect(seen.model).toBe("grok-4.5")
  expect(seen.effort).toBe("low")

  const writes: string[] = []
  fr.client.setWrite((l) => writes.push(l))
  const sent = adapter.send("hi")
  await tick()
  const prompt = writes.map((w) => JSON.parse(w)).find((m) => m.method === "session/prompt")
  expect(prompt.params.model).toBeUndefined()
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn" } })
  await sent
})

test("start() does not advertise unimplemented client FS capabilities", async () => {
  // Regression: advertising readTextFile/writeTextFile:true without implementing
  // fs/* handlers made Grok's read_file/search_replace fail with
  // "failed to deserialize response" (empty {} reply). Claim false so Grok uses
  // its local filesystem tools instead.
  const fr = fakeRunner()
  const writes: string[] = []
  const runner = (opts: any) => {
    const handle = fr.runner(opts)
    fr.client.setWrite((l) => writes.push(l))
    return handle
  }
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner, persistSessionId: async () => {} })
  const started = adapter.start()
  await tick()
  const init = writes.map((w) => JSON.parse(w)).find((m) => m.method === "initialize")
  expect(init?.params?.clientCapabilities?.fs).toEqual({ readTextFile: false, writeTextFile: false })
  fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started
})

test("setting model on a live session issues session/set_model", async () => {
  const fr = fakeRunner()
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const writes: string[] = []
  fr.client.setWrite((l) => writes.push(l))
  adapter.model = "grok-4.5-fast"
  const setModel = writes.map((w) => JSON.parse(w)).find((m) => m.method === "session/set_model")
  expect(setModel.params).toMatchObject({ sessionId: "sess-1", modelId: "grok-4.5-fast" })
  expect(adapter.model).toBe("grok-4.5-fast")
})

test("start() prefers the modelState echoed by session/new", async () => {
  const fr = fakeRunner()
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1, _meta: { modelState: { availableModels: [{ modelId: "stale" }] } } } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1", models: { availableModels: [{ modelId: "grok-4.5" }] } } })
  await started
  expect(adapter.availableModels.map((m: any) => m.modelId)).toEqual(["grok-4.5"])
})

test("interrupt() sends session/cancel notification", async () => {
  const fr = fakeRunner()
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started
  const writes: string[] = []
  fr.client.setWrite((l) => writes.push(l))
  await adapter.interrupt()
  expect(writes.some((w) => JSON.parse(w).method === "session/cancel")).toBe(true)
})

test("session/prompt JSON-RPC error emits error event with faithful message and completes turn", async () => {
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  for (const k of ["error", "turn-start", "turn-complete"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("hi")
  await tick()
  fr.feed({ jsonrpc: "2.0", id: 3, error: { code: -32000, message: "rate limited", data: "retry later" } })
  await sent

  const err = events.find((e) => e.kind === "error")
  expect(err?.error?.message).toBe("rate limited: retry later")
  expect(events.some((e) => e.kind === "turn-start")).toBe(true)
  expect(events.some((e) => e.kind === "turn-complete")).toBe(true)
})

test("agent exit mid-turn surfaces stderr in the error event", async () => {
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  for (const k of ["error", "turn-complete"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("hi")
  await tick()
  // Simulate runner calling onExit with code + stderr (process crash mid-turn).
  fr.exit(1, "fatal: auth token expired\n")
  await sent

  const err = events.find((e) => e.kind === "error")
  expect(err?.error?.message).toMatch(/auth token expired/)
  expect(events.some((e) => e.kind === "turn-complete")).toBe(true)
})

test("stall watchdog emits error when no session/update arrives", async () => {
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({
    sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {},
    stallTimeoutMs: 30,
  })
  for (const k of ["error", "turn-complete"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("hi")
  // No session/update and no prompt result — watchdog should fire.
  await sent

  const err = events.find((e) => e.kind === "error")
  expect(err?.error?.message).toMatch(/stalled/i)
  expect(events.some((e) => e.kind === "turn-complete")).toBe(true)
})

test("formatGrokExitError prefers last stderr line", async () => {
  const { formatGrokExitError } = await import("./adapter")
  expect(formatGrokExitError(1, "warn: x\nfatal: boom\n").message).toBe("grok agent exited (exit 1): fatal: boom")
  expect(formatGrokExitError(2).message).toBe("grok agent exited with code 2")
  expect(formatGrokExitError(0).message).toBe("grok agent exited")
})

test("start() without initialSessionId creates via session/new (never session/load)", async () => {
  // Capture every outbound frame from first write by wrapping the runner.
  const writes: string[] = []
  let client!: AcpClient
  const runner = (opts: any) => {
    client = opts.client
    client.setWrite((l: string) => writes.push(l))
    return { kill: () => {} }
  }
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner, persistSessionId: async () => {} })
  const started = adapter.start()
  await tick(); client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } }) + "\n")
  await tick(); client.feed(JSON.stringify({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-new" } }) + "\n")
  await started

  const methods = writes.map((w) => JSON.parse(w).method)
  expect(methods).toContain("session/new")
  expect(methods).not.toContain("session/load")
  const newReq = writes.map((w) => JSON.parse(w)).find((m) => m.method === "session/new")
  expect(newReq.params.loadSessionId).toBeUndefined()
})

test("start() with initialSessionId resumes via session/load (not loadSessionId on session/new)", async () => {
  // Regression: broker restart used session/new + loadSessionId, which Grok ignores —
  // every restart minted a fresh session and agents spoke as if newly spawned.
  const writes: string[] = []
  let client!: AcpClient
  const runner = (opts: any) => {
    client = opts.client
    client.setWrite((l: string) => writes.push(l))
    return { kill: () => {} }
  }
  const persisted: string[] = []
  const events: any[] = []
  const adapter = new GrokAdapter({
    sessionName: "s1",
    workdir: "/w",
    runner,
    persistSessionId: async (id) => { persisted.push(id) },
    initialSessionId: "sess-prior",
  })
  for (const k of ["assistant-message", "tool-call"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1, agentCapabilities: { loadSession: true } } }) + "\n")
  await tick()
  // Replay a prior turn during load — must NOT surface as chat/tool events.
  client.feed(JSON.stringify({
    jsonrpc: "2.0", method: "session/update",
    params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "old history" } } },
  }) + "\n")
  client.feed(JSON.stringify({
    jsonrpc: "2.0", method: "session/update",
    params: { update: { sessionUpdate: "tool_call", toolCallId: "old", title: "write" } },
  }) + "\n")
  // Grok puts the id under _meta.sessionId (top-level sessionId is absent).
  client.feed(JSON.stringify({
    jsonrpc: "2.0", id: 2,
    result: { models: { availableModels: [{ modelId: "grok-4.5" }] }, _meta: { sessionId: "sess-prior" } },
  }) + "\n")
  await started

  const loadReq = writes.map((w) => JSON.parse(w)).find((m) => m.method === "session/load")
  expect(loadReq?.params).toMatchObject({ cwd: "/w", sessionId: "sess-prior", mcpServers: [] })
  expect(writes.some((w) => JSON.parse(w).method === "session/new")).toBe(false)

  // Replay suppressed; registry keeps the prior id (no spurious re-persist of a new id).
  expect(events).toEqual([])
  expect(persisted).toEqual([])
  expect(adapter.availableModels.map((m: any) => m.modelId)).toEqual(["grok-4.5"])

  // Live prompts must still work against the loaded id.
  const sent = adapter.send("continue")
  await tick()
  const prompt = writes.map((w) => JSON.parse(w)).find((m) => m.method === "session/prompt")
  expect(prompt?.params?.sessionId).toBe("sess-prior")
  client.feed(JSON.stringify({
    jsonrpc: "2.0", method: "session/update",
    params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "live" } } },
  }) + "\n")
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn" } }) + "\n")
  await sent
  expect(events.find((e) => e.kind === "assistant-message")?.text).toBe("live")
})

test("start() falls back to session/new when session/load fails", async () => {
  const writes: string[] = []
  let client!: AcpClient
  const runner = (opts: any) => {
    client = opts.client
    client.setWrite((l: string) => writes.push(l))
    return { kill: () => {} }
  }
  const persisted: string[] = []
  const adapter = new GrokAdapter({
    sessionName: "s1", workdir: "/w", runner,
    persistSessionId: async (id) => { persisted.push(id) },
    initialSessionId: "sess-missing",
  })

  const started = adapter.start()
  await tick(); client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } }) + "\n")
  await tick()
  // session/load fails (stale id / missing on-disk store).
  client.feed(JSON.stringify({
    jsonrpc: "2.0", id: 2,
    error: { code: -32603, message: "Path not found.", data: { code: "FS_NOT_FOUND" } },
  }) + "\n")
  await tick()
  // Fallback session/new succeeds with a fresh id.
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 3, result: { sessionId: "sess-fresh" } }) + "\n")
  await started

  const methods = writes.map((w) => JSON.parse(w).method)
  expect(methods).toContain("session/load")
  expect(methods).toContain("session/new")
  expect(persisted).toEqual(["sess-fresh"])
})

test("resume() is a no-op when the child is already running", async () => {
  const fr = fakeRunner()
  const adapter = new GrokAdapter({
    sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {},
    initialSessionId: "sess-1",
  })
  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { _meta: { sessionId: "sess-1" } } })
  await started
  // Second resume must not open another session/load (child already live).
  const writes: string[] = []
  fr.client.setWrite((l) => writes.push(l))
  await adapter.resume()
  expect(writes).toEqual([])
})

test("captures availableCommands from the handshake and available_commands_update pushes", async () => {
  // Live-verified (grok 0.2.101): initialize carries _meta.availableCommands and
  // the session pushes available_commands_update with the skill-backed list.
  const fr = fakeRunner()
  const updates: any[] = []
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  adapter.on("commands-update", (e) => updates.push(e))
  for (const k of ["assistant-message", "tool-call"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  await tick(); fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1, _meta: { availableCommands: [{ name: "compact" }] } } })
  await tick(); fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started
  expect(adapter.availableCommands.map((c) => c.name)).toEqual(["compact"])

  fr.feed({
    jsonrpc: "2.0", method: "session/update",
    params: { update: { sessionUpdate: "available_commands_update", availableCommands: [
      { name: "compact" },
      { name: "soul", description: "d", _meta: { scope: "user", path: "/p/skills/soul/SKILL.md" } },
    ] } },
  })
  expect(updates.length).toBe(1)
  expect(adapter.availableCommands.map((c) => c.name)).toEqual(["compact", "soul"])
  // The push is ambient state, never a chat/tool event.
  expect(events).toEqual([])
})
