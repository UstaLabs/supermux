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
  let exit!: (c: number | null) => void
  const runner = (opts: any) => { client = opts.client; exit = opts.onExit; return { kill: () => exit(0) } }
  return { runner, feed: (o: any) => client.feed(JSON.stringify(o) + "\n"), get client() { return client }, exit: () => exit(0) }
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
