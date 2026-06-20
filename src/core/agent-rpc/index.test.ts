import { test, expect } from "bun:test"
import { createAgentRpc, type AgentRpcDeps } from "./index"

const tick = () => new Promise<void>(r => setTimeout(r, 0))

function harness(over: Partial<AgentRpcDeps> = {}) {
  const delivered: { sessionId: string; text: string }[] = []
  let spawnCount = 0
  let idc = 0
  const deps: AgentRpcDeps = {
    spawnWorker: async ({ key }) => { spawnCount++; return { sessionId: `sess-${key}-${spawnCount}` } },
    deliver: async (sessionId, text) => { delivered.push({ sessionId, text }) },
    killWorker: async () => {},
    isAlive: () => true,
    buildPrompt: (taskType, _payload, requestId) => `${taskType}:${requestId}`,
    newRequestId: () => `req-${++idc}`,
    now: () => 1000,
    defaultAgent: "claude",
    defaultTimeoutMs: 30_000,
    ...over,
  }
  return { rpc: createAgentRpc(deps), delivered, get spawnCount() { return spawnCount } }
}

test("callAgent spawns a worker, delivers a prompt, resolves on settle", async () => {
  const h = harness()
  const p = h.rpc.callAgent({ key: "voice:s1", taskType: "voice", payload: { draft: "x" } })
  await tick()
  expect(h.delivered.length).toBe(1)
  expect(h.delivered[0]!.text).toBe("voice:req-1")
  h.rpc.settle("req-1", { text: "fixed" })
  expect(await p).toEqual({ text: "fixed" })
})

test("same key reuses one worker; different keys spawn separate workers", async () => {
  const h = harness()
  const p1 = h.rpc.callAgent({ key: "k1", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-1", { a: 1 }); await p1
  const p2 = h.rpc.callAgent({ key: "k1", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-2", { a: 2 }); await p2
  expect(h.spawnCount).toBe(1)
  const p3 = h.rpc.callAgent({ key: "k2", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-3", { a: 3 }); await p3
  expect(h.spawnCount).toBe(2)
})

test("two calls to one worker run serially (FIFO)", async () => {
  const h = harness()
  const p1 = h.rpc.callAgent({ key: "k", taskType: "t", payload: { n: 1 } })
  const p2 = h.rpc.callAgent({ key: "k", taskType: "t", payload: { n: 2 } })
  await tick()
  expect(h.delivered.length).toBe(1)            // only the first is in flight
  h.rpc.settle("req-1", { n: 1 }); expect(await p1).toEqual({ n: 1 })
  await tick()
  expect(h.delivered.length).toBe(2)            // second delivered after first settles
  h.rpc.settle("req-2", { n: 2 }); expect(await p2).toEqual({ n: 2 })
})

test("fail() rejects the promise", async () => {
  const h = harness()
  const p = h.rpc.callAgent({ key: "k", taskType: "t", payload: {} })
  await tick()
  h.rpc.fail("req-1", "bad input")
  await expect(p).rejects.toThrow("bad input")
})

test("timeout rejects and recycles the worker", async () => {
  const killed: string[] = []
  const h = harness({ defaultTimeoutMs: 5, killWorker: async (id) => { killed.push(id) } })
  const p = h.rpc.callAgent({ key: "k", taskType: "t", payload: {} })
  await expect(p).rejects.toThrow(/timeout/)
  await tick()
  expect(killed.length).toBe(1)
})

test("reapIdle kills idle workers but not busy ones", async () => {
  let t = 1000
  const killed: string[] = []
  const h = harness({ now: () => t, killWorker: async (id) => { killed.push(id) } })
  const p = h.rpc.callAgent({ key: "idle", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-1", {}); await p     // now idle
  h.rpc.callAgent({ key: "busy", taskType: "t", payload: {} }).catch(() => {})  // left in-flight (busy)
  await tick()
  t = 1000 + 60_000
  await h.rpc.reapIdle(10_000)
  expect(killed).toContain("sess-idle-1")
  expect(killed).not.toContain("sess-busy-2")
})

test("on timeout, queued calls for the same worker are rejected, not left hanging", async () => {
  const h = harness({ defaultTimeoutMs: 5 })
  const p1 = h.rpc.callAgent({ key: "k", taskType: "t", payload: { n: 1 } })
  const p2 = h.rpc.callAgent({ key: "k", taskType: "t", payload: { n: 2 } })
  // p1 (in-flight) and p2 (queued) are both rejected synchronously inside the
  // timeout handler. Capture p2's outcome with a handler attached at creation so
  // its rejection can't surface as an unhandled rejection before we assert on it.
  const p2settled = p2.then(() => "resolved", () => "rejected")
  await expect(p1).rejects.toThrow(/timeout/)
  expect(await p2settled).toBe("rejected")   // queued call rejected too, not stuck
})

test("a dead worker is evicted and respawned on the next call", async () => {
  let alive = true
  const h = harness({ isAlive: () => alive })
  const p1 = h.rpc.callAgent({ key: "k", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-1", { a: 1 }); await p1
  alive = false                                   // worker dies
  const p2 = h.rpc.callAgent({ key: "k", taskType: "t", payload: {} })
  await tick(); h.rpc.settle("req-2", { a: 2 }); await p2
  expect(h.spawnCount).toBe(2)                     // respawned because the first was dead
})
