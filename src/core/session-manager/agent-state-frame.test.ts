import { test, expect } from "bun:test"
import { toAgentStateFrame } from "./agent-state-frame"

test("thinking -> working + detail thinking + legacy phase thinking", () => {
  expect(toAgentStateFrame("a", { phase: "thinking", since: 5, workingSince: 5 }))
    .toEqual({ type: "agent_state", session: "a", state: "working", working: true, detail: "thinking", tool: undefined, since: 5, workingSince: 5, waiting: false, bgOpen: 0, phase: "thinking" })
})
test("running carries tool (full frame, asserts working=true)", () => {
  expect(toAgentStateFrame("a", { phase: "running", tool: "Bash", since: 5, workingSince: 4 }))
    .toEqual({ type: "agent_state", session: "a", state: "working", working: true, detail: "running", tool: "Bash", since: 5, workingSince: 4, waiting: false, bgOpen: 0, phase: "running" })
})
test("dead -> state dead, legacy phase stalled, not working", () => {
  const f = toAgentStateFrame("a", { phase: "dead", since: 9 })
  expect(f.state).toBe("dead"); expect(f.working).toBe(false); expect(f.detail).toBeNull(); expect(f.phase).toBe("stalled")
})
test("idle", () => {
  const f = toAgentStateFrame("a", { phase: "idle", since: 0 })
  expect(f).toEqual({ type: "agent_state", session: "a", state: "idle", working: false, detail: null, tool: undefined, since: 0, workingSince: undefined, waiting: false, bgOpen: 0, phase: "idle" })
})
test("idle with open bg tasks derives waiting (legacy fields untouched)", () => {
  const f = toAgentStateFrame("s1", { phase: "idle", since: 5 }, 2)
  expect(f.waiting).toBe(true)
  expect(f.bgOpen).toBe(2)
  expect(f.state).toBe("idle")      // legacy clients keep seeing idle
  expect(f.working).toBe(false)
  expect(f.phase).toBe("idle")
})
test("working/dead never derive waiting even with open tasks", () => {
  expect(toAgentStateFrame("s1", { phase: "running", tool: "Bash", since: 5 }, 3).waiting).toBe(false)
  expect(toAgentStateFrame("s1", { phase: "thinking", since: 5 }, 3).waiting).toBe(false)
  expect(toAgentStateFrame("s1", { phase: "dead", since: 5 }, 3).waiting).toBe(false)
})
test("bgOpen defaults to 0 when omitted", () => {
  const f = toAgentStateFrame("s1", { phase: "idle", since: 5 })
  expect(f.waiting).toBe(false)
  expect(f.bgOpen).toBe(0)
})

// Card #75: "starting" is computed from the session's chain (a wake in flight), never stored.
test("a session being woken shows as starting, since the wake began, whatever the store says", () => {
  for (const st of [{ phase: "idle" as const, since: 5 }, { phase: "dead" as const, since: 5 }]) {
    expect(toAgentStateFrame("s1", st, 0, 1_000))
      .toEqual({ type: "agent_state", session: "s1", state: "working", working: true, detail: "starting", tool: undefined, since: 1_000, workingSince: 1_000, waiting: false, bgOpen: 0, phase: "thinking" })
  }
})
test("without a wake in flight the store's state is the frame", () => {
  expect(toAgentStateFrame("s1", { phase: "idle", since: 5 }, 0, undefined).detail).toBeNull()
})
