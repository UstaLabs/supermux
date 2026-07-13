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
