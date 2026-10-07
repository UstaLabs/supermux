import { expect, test } from "bun:test"
import { RESULT_MAX, SubagentStore, FINISHED_KEEP } from "./subagent-store"

function collect(store: SubagentStore) {
  const changes: Array<{ session: string; id: string; status: string }> = []
  const cleared: string[] = []
  store.on("change", (session: string, view: { id: string; status: string }) => changes.push({ session, id: view.id, status: view.status }))
  store.on("clear", (session: string) => cleared.push(session))
  return { changes, cleared }
}

test("folds a Claude-shaped lifecycle into one view", () => {
  const store = new SubagentStore()
  const { changes } = collect(store)
  store.applyBody("s1", {
    kind: "subagent", subagentId: "a1", phase: "started", parentCallId: "toolu_1", name: "general-purpose",
    description: "Inspect work dir", prompt: "List the files", background: false, messaging: "relay",
  }, 1000)
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "progress", activity: "Reading sub/secret.txt", stats: { toolCalls: 2, tokens: 13031, durationMs: 4386 } }, 2000)
  // A derived child-tool activity must not override what the agent itself reports.
  store.applyChildActivity("s1", "a1", "Bash: ls", 2500)
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "completed", result: "PELICAN", stats: { toolCalls: 2, tokens: 14549, durationMs: 13713 } }, 3000)

  const [view] = store.get("s1")
  expect(view).toEqual({
    id: "a1", name: "general-purpose", description: "Inspect work dir", prompt: "List the files", background: false,
    status: "completed", activity: "Reading sub/secret.txt", stats: { toolCalls: 2, tokens: 14549, durationMs: 13713 },
    result: "PELICAN", messaging: "relay", parentCallId: "toolu_1", startedAt: 1000, endedAt: 3000, lastActivityAt: 3000,
  })
  expect(changes.map((c) => c.status)).toEqual(["running", "running", "running", "completed"])
})

test("derives activity and toolCalls from child tool calls when the agent sends none", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "started", messaging: "direct" }, 1)
  store.applyChildActivity("s1", "c1", "Bash: pwd", 2)
  store.applyChildActivity("s1", "c1", "Bash: ls -la", 3)
  const [view] = store.get("s1")
  expect(view!.activity).toBe("Bash: ls -la")
  expect(view!.stats.toolCalls).toBe(2)
  expect(view!.lastActivityAt).toBe(3)
})

test("agent-provided toolCalls win once seen", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "started" }, 1)
  store.applyChildActivity("s1", "c1", "Bash: pwd", 2)
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "progress", stats: { toolCalls: 7 } }, 3)
  store.applyChildActivity("s1", "c1", "Bash: ls", 4)
  expect(store.get("s1")[0]!.stats.toolCalls).toBe(7)
})

test("a child tool for an unknown subagent creates a running placeholder", () => {
  const store = new SubagentStore()
  store.applyChildActivity("s1", "ghost", "Read: a.txt", 5)
  expect(store.get("s1")).toEqual([{ id: "ghost", status: "running", activity: "Read: a.txt", stats: { toolCalls: 1 }, startedAt: 5, lastActivityAt: 5 }])
})

test("resumed reopens a finished subagent and keeps the first parentCallId", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "started", parentCallId: "spawn" }, 1)
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "completed", result: "one" }, 2)
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "resumed", parentCallId: "sendmessage" }, 3)
  let view = store.get("s1")[0]!
  expect(view.status).toBe("running")
  expect(view.endedAt).toBeUndefined()
  expect(view.parentCallId).toBe("spawn")
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "failed", result: "boom" }, 4)
  view = store.get("s1")[0]!
  expect(view.status).toBe("failed")
  expect(view.result).toBe("boom")
})

test("clips a huge result and flags it", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a1", phase: "completed", result: "x".repeat(RESULT_MAX + 50) }, 1)
  const view = store.get("s1")[0]!
  expect(view.result!.length).toBe(RESULT_MAX)
  expect(view.resultClipped).toBe(true)
})

test("keeps every running subagent and only the newest finished ones", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "live", phase: "started" }, 0)
  for (let i = 0; i < FINISHED_KEEP + 5; i++) {
    store.applyBody("s1", { kind: "subagent", subagentId: `f${i}`, phase: "started" }, 10 + i)
    store.applyBody("s1", { kind: "subagent", subagentId: `f${i}`, phase: "completed" }, 100 + i)
  }
  const ids = store.get("s1").map((v) => v.id)
  expect(ids).toContain("live")
  expect(ids.filter((id) => id.startsWith("f"))).toHaveLength(FINISHED_KEEP)
  expect(ids).not.toContain("f0")
  expect(ids).toContain(`f${FINISHED_KEEP + 4}`)
  // Ordered by startedAt.
  expect(ids[0]).toBe("live")
})

test("backgroundRunning counts only running background subagents", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started", background: true }, 1)
  store.applyBody("s1", { kind: "subagent", subagentId: "b", phase: "started", background: false }, 2)
  store.applyBody("s1", { kind: "subagent", subagentId: "c", phase: "started", background: true }, 3)
  store.applyBody("s1", { kind: "subagent", subagentId: "c", phase: "completed" }, 4)
  expect(store.backgroundRunning("s1")).toBe(1)
})

test("abandonRunning marks running subagents cancelled; clear drops the session", () => {
  const store = new SubagentStore()
  const { cleared } = collect(store)
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started" }, 1)
  store.applyBody("s1", { kind: "subagent", subagentId: "b", phase: "completed" }, 2)
  store.abandonRunning("s1", 9)
  expect(store.get("s1").map((v) => [v.id, v.status, v.endedAt])).toEqual([["a", "cancelled", 9], ["b", "completed", 2]])
  store.clear("s1")
  store.clear("s1")
  expect(store.get("s1")).toEqual([])
  expect(cleared).toEqual(["s1"])
})

test("find returns the view by id", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started", messaging: "none" }, 1)
  expect(store.find("s1", "a")?.messaging).toBe("none")
  expect(store.find("s1", "zz")).toBeUndefined()
})

test("truthful actions: flags, reasons and endedBy fold from the bodies; a resume clears endedBy", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "started", messaging: "direct", canMessage: true, canStop: true, actionsSource: "native" }, 1)
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "progress", name: "Anscombe" }, 2)
  store.applyBody("s1", {
    kind: "subagent", subagentId: "c1", phase: "cancelled", endedBy: "parent",
    canMessage: true, canStop: false, cannotStopReason: "It has already stopped", actionsSource: "native",
  }, 3)
  expect(store.find("s1", "c1")).toMatchObject({
    name: "Anscombe", status: "cancelled", endedBy: "parent", canMessage: true, canStop: false,
    cannotStopReason: "It has already stopped", actionsSource: "native",
  })
  store.applyBody("s1", { kind: "subagent", subagentId: "c1", phase: "resumed", canMessage: true, canStop: true }, 4)
  const resumed = store.find("s1", "c1")!
  expect(resumed.endedBy).toBeUndefined()
  expect(resumed.cannotStopReason).toBeUndefined()
  expect(resumed.canStop).toBe(true)
})

test("a refusal turns Message off with the agent's reason", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "cancelled", endedBy: "client", canMessage: true, canStop: false }, 1)
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "progress", canMessage: false, cannotMessageReason: "Stopped by you — Claude can't resume it", delivery: { status: "refused" } }, 2)
  expect(store.find("s1", "a")).toMatchObject({ canMessage: false, cannotMessageReason: "Stopped by you — Claude can't resume it" })
})

test("the subagent's own replies are counted and its conversation kept (bounded, clipped)", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started", prompt: "do it" }, 1)
  store.recordMessage("s1", { ts: "t1", kind: "subagent_message", title: "do it", text: "do it", direction: "to", sender: "parent", subagentId: "a" })
  store.recordMessage("s1", { ts: "t2", kind: "subagent_message", title: "one", text: "one", direction: "from", subagentId: "a" })
  store.recordMessage("s1", { ts: "t3", kind: "subagent_message", title: "two", text: "x".repeat(20_000), direction: "from", subagentId: "a" })
  expect(store.find("s1", "a")!.replies).toBe(2)
  const thread = store.messages("s1")
  expect(thread.map((m) => m.direction)).toEqual(["to", "from", "from"])
  expect(thread[2]!.text!.length).toBe(RESULT_MAX)
  expect(thread[2]!.truncated).toBe(true)
})

test("persists views and conversations across a broker restart", async () => {
  const { mkdtemp, rm } = await import("node:fs/promises")
  const { tmpdir } = await import("node:os")
  const { join } = await import("node:path")
  const dir = await mkdtemp(join(tmpdir(), "subagent-store-"))
  try {
    const file = join(dir, "subagents.json")
    const store = new SubagentStore({ file, saveDelayMs: 0 })
    store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started", name: "Anscombe", canMessage: true, canStop: true }, 1)
    store.recordMessage("s1", { ts: "t2", kind: "subagent_message", title: "hi", text: "hi", direction: "from", subagentId: "a" })
    store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "completed", endedBy: "self", canMessage: true, canStop: false }, 3)
    await store.flush()
    const reloaded = new SubagentStore({ file })
    reloaded.load()
    expect(reloaded.find("s1", "a")).toMatchObject({ name: "Anscombe", status: "completed", endedBy: "self", replies: 1, canStop: false })
    expect(reloaded.messages("s1")).toHaveLength(1)
    reloaded.clear("s1")
    await reloaded.flush()
    const empty = new SubagentStore({ file })
    empty.load()
    expect(empty.get("s1")).toEqual([])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test("a resumed library registry corrects what the broker remembered", () => {
  const store = new SubagentStore()
  store.applyBody("s1", { kind: "subagent", subagentId: "a", phase: "started", canMessage: true, canStop: true }, 1)
  store.applySnapshot("s1", { subagentId: "a", status: "cancelled", canMessage: true, canStop: false, cannotStopReason: "It has already stopped", actionsSource: "derived", name: "Sartre", updatedAt: 5 }, 5)
  expect(store.find("s1", "a")).toMatchObject({ status: "cancelled", canStop: false, name: "Sartre", endedAt: 5 })
})
