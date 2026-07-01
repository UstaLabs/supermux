import { test, expect } from "bun:test"
import { toAgentStateFrame } from "./agent-state-frame"

test("thinking -> working + detail thinking + legacy phase thinking", () => {
  expect(toAgentStateFrame("a", { phase: "thinking", since: 5, workingSince: 5 }))
    .toEqual({ type: "agent_state", session: "a", state: "working", working: true, detail: "thinking", tool: undefined, since: 5, workingSince: 5, phase: "thinking" })
})
test("running carries tool (full frame, asserts working=true)", () => {
  expect(toAgentStateFrame("a", { phase: "running", tool: "Bash", since: 5, workingSince: 4 }))
    .toEqual({ type: "agent_state", session: "a", state: "working", working: true, detail: "running", tool: "Bash", since: 5, workingSince: 4, phase: "running" })
})
test("dead -> state dead, legacy phase stalled, not working", () => {
  const f = toAgentStateFrame("a", { phase: "dead", since: 9 })
  expect(f.state).toBe("dead"); expect(f.working).toBe(false); expect(f.detail).toBeNull(); expect(f.phase).toBe("stalled")
})
test("idle", () => {
  const f = toAgentStateFrame("a", { phase: "idle", since: 0 })
  expect(f).toEqual({ type: "agent_state", session: "a", state: "idle", working: false, detail: null, tool: undefined, since: 0, workingSince: undefined, phase: "idle" })
})
