import { test, expect } from "bun:test"
import { AgentStateStore } from "./agent-state-store"

test("default state is idle", () => {
  expect(new AgentStateStore().get("x").phase).toBe("idle")
})

test("UserPromptSubmit -> thinking; PreToolUse -> running(+tool); PostToolUse -> thinking; Stop -> idle", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  expect(s.get("a")).toEqual({ phase: "thinking", since: 1000, workingSince: 1000 })
  s.applyEvent("a", "PreToolUse", "Bash", 2000)
  expect(s.get("a")).toEqual({ phase: "running", tool: "Bash", since: 2000, workingSince: 1000 })
  s.applyEvent("a", "PostToolUse", undefined, 3000)
  expect(s.get("a")).toEqual({ phase: "thinking", since: 3000, workingSince: 1000 })
  s.applyEvent("a", "Stop", undefined, 4000)
  expect(s.get("a")).toEqual({ phase: "idle", since: 4000 })
})

test("interrupt -> idle and clears workingSince", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "interrupt", undefined, 1500)
  expect(s.get("a")).toEqual({ phase: "idle", since: 1500 })
})

test("dead from any phase; connected revives only from dead", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "dead", undefined, 2000)
  expect(s.get("a")).toEqual({ phase: "dead", since: 2000 })
  s.applyEvent("a", "connected", undefined, 3000)
  expect(s.get("a")).toEqual({ phase: "idle", since: 3000 })
})

test("connected is a no-op when not dead (a pong must not reset working)", () => {
  const s = new AgentStateStore()
  const seen: string[] = []
  s.on("change", (_sid, st) => seen.push(st.phase))
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "connected", undefined, 1100)
  expect(s.get("a").phase).toBe("thinking")
  expect(s.get("a").workingSince).toBe(1000)
  expect(seen).toEqual(["thinking"])
})

test("emits change only on real transitions", () => {
  const s = new AgentStateStore()
  const seen: string[] = []
  s.on("change", (_sid, st) => seen.push(st.phase))
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "turn-start", undefined, 1200)
  expect(seen).toEqual(["thinking"])
})

test("thoughtComplete fires when leaving a >=1s thinking stretch", () => {
  const s = new AgentStateStore()
  const thoughts: number[] = []
  s.on("thoughtComplete", (_sid, ms) => thoughts.push(ms))
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "PreToolUse", "Bash", 2500)
  expect(thoughts).toEqual([1500])
})

test("connected on a running session preserves phase, tool, and workingSince", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "PreToolUse", "Bash", 1000)   // running, workingSince 1000
  s.applyEvent("a", "connected", undefined, 1500)
  expect(s.get("a")).toEqual({ phase: "running", tool: "Bash", since: 1000, workingSince: 1000 })
})

test("dead -> dead is a no-op (no spurious emit, no re-stamp)", () => {
  const s = new AgentStateStore()
  const seen: string[] = []
  s.on("change", (_sid, st) => seen.push(st.phase))
  s.applyEvent("a", "dead", undefined, 1000)
  s.applyEvent("a", "dead", undefined, 2000)
  expect(seen).toEqual(["dead"])
  expect(s.get("a").since).toBe(1000)
})

test("thoughtComplete does NOT fire for sub-1s thinking", () => {
  const s = new AgentStateStore()
  const thoughts: number[] = []
  s.on("thoughtComplete", (_sid, ms) => thoughts.push(ms))
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "PreToolUse", "Bash", 1500)   // 500ms < 1000ms threshold
  expect(thoughts).toEqual([])
})
