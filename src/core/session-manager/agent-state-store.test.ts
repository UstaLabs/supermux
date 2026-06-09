import { test, expect } from "bun:test"
import { AgentStateStore } from "./agent-state-store"

test("default state is idle", () => {
  const s = new AgentStateStore()
  expect(s.get("x").phase).toBe("idle")
})

test("deliver -> sending; PreToolUse -> running with tool; PostToolUse -> thinking; Stop -> idle", () => {
  const s = new AgentStateStore()
  const seen: Array<[string, string, string | undefined]> = []
  s.on("change", (sid, st) => seen.push([sid, st.phase, st.tool]))

  s.applyEvent("a", "deliver", undefined, 1000)
  expect(s.get("a")).toEqual({ phase: "sending", since: 1000 })

  // PreToolUse from sending -> enters working fresh; workingSince = 2000
  s.applyEvent("a", "PreToolUse", "Bash", 2000)
  expect(s.get("a")).toEqual({ phase: "running", tool: "Bash", since: 2000, workingSince: 2000 })

  // PostToolUse -> thinking; still in working, preserve workingSince = 2000
  s.applyEvent("a", "PostToolUse", undefined, 3000)
  expect(s.get("a")).toEqual({ phase: "thinking", since: 3000, workingSince: 2000 })

  s.applyEvent("a", "Stop", undefined, 4000)
  expect(s.get("a")).toEqual({ phase: "idle", since: 4000 })

  expect(seen).toEqual([
    ["a", "sending", undefined],
    ["a", "running", "Bash"],
    ["a", "thinking", undefined],
    ["a", "idle", undefined],
  ])
})

test("no change event when phase+tool are unchanged", () => {
  const s = new AgentStateStore()
  let count = 0
  s.on("change", () => count++)
  s.applyEvent("a", "UserPromptSubmit", undefined, 1000)
  s.applyEvent("a", "turn-start", undefined, 1500) // still thinking -> no emit
  expect(count).toBe(1)
})

test("switching tool while running emits (different tool)", () => {
  const s = new AgentStateStore()
  let count = 0
  s.on("change", () => count++)
  s.applyEvent("a", "PreToolUse", "Bash", 1000)
  s.applyEvent("a", "PreToolUse", "Read", 1100)
  expect(count).toBe(2)
  expect(s.get("a").tool).toBe("Read")
})

test("clear resets to idle", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "deliver", undefined, 1000)
  s.clear("a")
  expect(s.get("a").phase).toBe("idle")
})

test("per-session isolation", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "deliver", undefined, 1000)
  s.applyEvent("b", "PreToolUse", "Grep", 1000)
  expect(s.get("a").phase).toBe("sending")
  expect(s.get("b")).toEqual({ phase: "running", tool: "Grep", since: 1000, workingSince: 1000 })
})

test("emits thoughtComplete with duration when leaving thinking (>= 1s)", () => {
  const s = new AgentStateStore()
  const thoughts: Array<[string, number]> = []
  s.on("thoughtComplete", (sid: string, ms: number) => thoughts.push([sid, ms]))
  s.applyEvent("a", "deliver", undefined, 500)         // sending
  s.applyEvent("a", "turn-start", undefined, 1000)     // thinking starts at 1000
  s.applyEvent("a", "PreToolUse", "Bash", 4000)        // leaves thinking at 4000 -> 3000ms
  expect(thoughts).toEqual([["a", 3000]])
})

test("does NOT emit thoughtComplete for sub-second thinking", () => {
  const s = new AgentStateStore()
  let n = 0
  s.on("thoughtComplete", () => n++)
  s.applyEvent("a", "turn-start", undefined, 1000)     // thinking starts at 1000
  s.applyEvent("a", "PreToolUse", "Bash", 1500)        // 500ms -> skip
  expect(n).toBe(0)
})

test("thoughtComplete also fires on thinking -> idle (Stop)", () => {
  const s = new AgentStateStore()
  const ms: number[] = []
  s.on("thoughtComplete", (_sid: string, d: number) => ms.push(d))
  s.applyEvent("a", "turn-start", undefined, 0)        // thinking starts at 0
  s.applyEvent("a", "Stop", undefined, 2000)
  expect(ms).toEqual([2000])
})

test("deliver -> sending; turn-start -> thinking; then tools", () => {
  const s = new AgentStateStore()
  const seen: string[] = []
  s.on("change", (_sid, st) => seen.push(st.phase))
  s.applyEvent("a", "deliver", undefined, 1000)
  expect(s.get("a")).toEqual({ phase: "sending", since: 1000 })
  s.applyEvent("a", "turn-start", undefined, 2000)
  expect(s.get("a")).toEqual({ phase: "thinking", since: 2000, workingSince: 2000 })
  s.applyEvent("a", "PreToolUse", "Bash", 3000)
  expect(s.get("a").phase).toBe("running")
  s.applyEvent("a", "Stop", undefined, 4000)
  expect(s.get("a").phase).toBe("idle")
  expect(seen).toEqual(["sending", "thinking", "running", "idle"])
})

test("UserPromptSubmit also flips sending -> thinking (Claude's real start)", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "deliver", undefined, 1000)
  s.applyEvent("a", "UserPromptSubmit", undefined, 1500)
  expect(s.get("a").phase).toBe("thinking")
})

test("thoughtComplete still fires when leaving thinking (not affected by sending)", () => {
  const s = new AgentStateStore()
  const ms: number[] = []
  s.on("thoughtComplete", (_sid: string, d: number) => ms.push(d))
  s.applyEvent("a", "deliver", undefined, 0)        // sending
  s.applyEvent("a", "turn-start", undefined, 1000)  // thinking
  s.applyEvent("a", "PreToolUse", "Bash", 4000)     // leaves thinking after 3000ms
  expect(ms).toEqual([3000])
})

test("workingSince is set on entering working and preserved across tool transitions, cleared on idle", () => {
  const s = new AgentStateStore()
  s.applyEvent("a", "deliver", undefined, 1000)            // sending, no workingSince
  expect(s.get("a").workingSince).toBeUndefined()
  s.applyEvent("a", "turn-start", undefined, 2000)         // thinking -> workingSince 2000
  expect(s.get("a").workingSince).toBe(2000)
  s.applyEvent("a", "PreToolUse", "Bash", 5000)            // running -> preserved 2000
  expect(s.get("a").workingSince).toBe(2000)
  s.applyEvent("a", "PostToolUse", undefined, 6000)        // thinking -> preserved 2000
  expect(s.get("a").workingSince).toBe(2000)
  s.applyEvent("a", "Stop", undefined, 9000)               // idle -> cleared
  expect(s.get("a").workingSince).toBeUndefined()
})
