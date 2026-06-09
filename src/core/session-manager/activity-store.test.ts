// src/core/session-manager/activity-store.test.ts
import { test, expect } from "bun:test"
import { ActivityStore } from "./activity-store"
import type { ActivityEvent } from "../agents/claude/activity-event"

function ev(title: string): ActivityEvent {
  return { ts: "2026-05-29T00:00:00.000Z", kind: "tool", tool: "Bash", title }
}

test("append stores per session and emits", () => {
  const store = new ActivityStore(3)
  const seen: Array<[string, string]> = []
  store.on("append", (sid, e) => seen.push([sid, e.title]))
  store.append("s1", ev("a"))
  store.append("s2", ev("b"))
  expect(store.get("s1").map((e) => e.title)).toEqual(["a"])
  expect(store.get("s2").map((e) => e.title)).toEqual(["b"])
  expect(seen).toEqual([["s1", "a"], ["s2", "b"]])
})

test("ring buffer drops oldest beyond cap", () => {
  const store = new ActivityStore(2)
  store.append("s1", ev("a")); store.append("s1", ev("b")); store.append("s1", ev("c"))
  expect(store.get("s1").map((e) => e.title)).toEqual(["b", "c"])
})

test("clear removes a session's buffer", () => {
  const store = new ActivityStore(2)
  store.append("s1", ev("a"))
  store.clear("s1")
  expect(store.get("s1")).toEqual([])
})
