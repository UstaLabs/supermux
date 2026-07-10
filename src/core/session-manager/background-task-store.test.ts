// src/core/session-manager/background-task-store.test.ts
import { describe, expect, test } from "bun:test"
import { BackgroundTaskStore } from "./background-task-store"

const open = (id: string, ts = 1000) => ({ id, kind: "shell" as const, label: `run ${id}`, ts })

describe("BackgroundTaskStore", () => {
  test("upsertOpen adds a running task and emits change", () => {
    const store = new BackgroundTaskStore()
    let changed = 0
    store.on("change", () => changed++)
    store.upsertOpen("s1", open("b1"))
    expect(store.get("s1")).toEqual([
      { id: "b1", kind: "shell", label: "run b1", startedAt: 1000, status: "running" },
    ])
    expect(store.openCount("s1")).toBe(1)
    expect(changed).toBe(1)
  })

  test("upsertOpen is idempotent by id (replay-safe)", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    let changed = 0
    store.on("change", () => changed++)
    store.upsertOpen("s1", open("b1", 2000))
    expect(store.get("s1")).toHaveLength(1)
    expect(store.get("s1")[0]!.startedAt).toBe(1000)   // first sighting wins
    expect(changed).toBe(0)                             // no spurious broadcast
  })

  test("close marks completed/failed with summary and endedAt", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    store.close("s1", { id: "b1", status: "failed", summary: "exit 1", ts: 5000 })
    expect(store.get("s1")[0]).toMatchObject({ status: "failed", summary: "exit 1", endedAt: 5000 })
    expect(store.openCount("s1")).toBe(0)
  })

  test("close matches by callId when the task-id differs (Monitor: opened under its tool_use_id)", () => {
    const store = new BackgroundTaskStore()
    // Monitor tasks are stored keyed by their launching tool_use_id (no task-id at start).
    store.upsertOpen("s1", { id: "toolu_MON", kind: "shell", label: "Monitor build 38", ts: 1000, callId: "toolu_MON" })
    // The notification reports a DIFFERENT task-id but the same tool-use-id.
    store.close("s1", { id: "bhos3m26s", status: "completed", ts: 5000, callId: "toolu_MON" })
    expect(store.openCount("s1")).toBe(0)
    const t = store.get("s1")[0]!
    expect(t).toMatchObject({ id: "toolu_MON", label: "Monitor build 38", status: "completed", endedAt: 5000 })
    expect(store.get("s1")).toHaveLength(1)   // did NOT create a second (unseen-id) task
  })

  test("close for an unseen id creates it already-closed (kind from prefix)", () => {
    const store = new BackgroundTaskStore()
    store.close("s1", { id: "a9", status: "completed", ts: 5000 })
    expect(store.get("s1")[0]).toMatchObject({ id: "a9", kind: "agent", status: "completed", label: "a9" })
  })

  test("close on already-closed id is a no-op (no re-emit)", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    store.close("s1", { id: "b1", status: "completed", ts: 5000 })
    let changed = 0
    store.on("change", () => changed++)
    store.close("s1", { id: "b1", status: "completed", ts: 6000 })
    expect(changed).toBe(0)
  })

  test("keeps all open + last 20 closed", () => {
    const store = new BackgroundTaskStore()
    for (let i = 0; i < 30; i++) {
      store.upsertOpen("s1", open(`b${i}`, i))
      store.close("s1", { id: `b${i}`, status: "completed", ts: i + 100 })
    }
    store.upsertOpen("s1", open("live", 999))
    const tasks = store.get("s1")
    expect(tasks.filter((t) => t.status !== "running")).toHaveLength(20)
    expect(tasks.find((t) => t.id === "live")).toBeDefined()
    expect(tasks.find((t) => t.id === "b0")).toBeUndefined() // oldest closed evicted
  })

  test("clear drops the session and emits change only if something existed", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    let changed = 0
    store.on("change", () => changed++)
    store.clear("s1")
    expect(store.get("s1")).toEqual([])
    expect(changed).toBe(1)
    store.clear("s1")
    expect(changed).toBe(1)   // clearing nothing does not emit
  })

  test("kindFromId maps prefixes", async () => {
    const { kindFromId } = await import("./background-task-store")
    expect(kindFromId("b3137swze")).toBe("shell")
    expect(kindFromId("a2bee0ed")).toBe("agent")
    expect(kindFromId("wf_abc123")).toBe("workflow")
    expect(kindFromId("x99")).toBe("task")
  })
})
