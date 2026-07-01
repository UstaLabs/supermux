import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useAgentState, isAgentWorking } from "./agentState"

beforeEach(() => setActivePinia(createPinia()))

test("default is idle, not working", () => {
  const a = useAgentState()
  expect(a.get("s1").state).toBe("idle")
  expect(isAgentWorking(a.get("s1"))).toBe(false)
})

test("set stores the broker shape; isAgentWorking reads .working", () => {
  const a = useAgentState()
  a.set("s1", { state: "working", working: true, detail: "running", tool: "Bash", since: 5, workingSince: 4 })
  expect(isAgentWorking(a.get("s1"))).toBe(true)
  expect(a.get("s1").detail).toBe("running")
})

test("set ignores undefined/malformed", () => {
  const a = useAgentState()
  a.set("s1", undefined)
  expect(a.get("s1").state).toBe("idle")
})

test("markSending shows Sending until a real state arrives", () => {
  const a = useAgentState()
  a.markSending("s1")
  expect(a.isSending("s1")).toBe(true)
  a.set("s1", { state: "working", working: true, since: 9, workingSince: 9 })
  expect(a.isSending("s1")).toBe(false)
})

test("isAgentWorking(undefined) is false", () => {
  expect(isAgentWorking(undefined)).toBe(false)
})

test("isSending is false before markSending is ever called", () => {
  const a = useAgentState()
  expect(a.isSending("s1")).toBe(false)
})

test("an idle (not-working) state arrival also clears Sending", () => {
  const a = useAgentState()
  a.markSending("s1")
  expect(a.isSending("s1")).toBe(true)
  a.set("s1", { state: "idle", working: false, since: 9 })
  expect(a.isSending("s1")).toBe(false)
})
