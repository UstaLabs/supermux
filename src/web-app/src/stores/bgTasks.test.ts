// src/web-app/src/stores/bgTasks.test.ts
import { beforeEach, describe, expect, it } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useBgTasks, type BgTask } from "./bgTasks"

const task = (id: string, status: BgTask["status"] = "running"): BgTask =>
  ({ id, kind: "shell", label: "build", startedAt: 1000, status })

describe("bgTasks store", () => {
  beforeEach(() => setActivePinia(createPinia()))

  it("set/get round-trips and openCount counts running", () => {
    const store = useBgTasks()
    store.set("s1", [task("b1"), task("b2", "failed")])
    expect(store.get("s1")).toHaveLength(2)
    expect(store.openCount("s1")).toBe(1)
    expect(store.get("unknown")).toEqual([])
  })

  it("ignores non-array payloads", () => {
    const store = useBgTasks()
    store.set("s1", [task("b1")])
    store.set("s1", undefined)
    expect(store.get("s1")).toHaveLength(1)
  })

  it("clear drops a session", () => {
    const store = useBgTasks()
    store.set("s1", [task("b1")])
    store.clear("s1")
    expect(store.get("s1")).toEqual([])
  })
})
