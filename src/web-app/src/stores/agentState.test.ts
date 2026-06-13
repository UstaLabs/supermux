import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useAgentState, isAgentWorking } from "./agentState"

beforeEach(() => setActivePinia(createPinia()))

test("default is idle", () => {
  const a = useAgentState()
  expect(a.get("s1").phase).toBe("idle")
})

test("set updates a session's state", () => {
  const a = useAgentState()
  a.set("s1", { phase: "running", tool: "Bash", since: 5 })
  expect(a.get("s1")).toEqual({ phase: "running", tool: "Bash", since: 5 })
})

test("set ignores undefined / malformed (e.g. missing snapshot key)", () => {
  const a = useAgentState()
  a.set("s1", undefined)
  expect(a.get("s1").phase).toBe("idle")
})

test("markSending flips a session to sending immediately", () => {
  const a = useAgentState()
  a.markSending("s1", 1234)
  expect(a.get("s1")).toEqual({ phase: "sending", since: 1234 })
})

test("isAgentWorking: true only for thinking/running while connected", () => {
  expect(isAgentWorking("thinking", true)).toBe(true)
  expect(isAgentWorking("running", true)).toBe(true)
  expect(isAgentWorking("idle", true)).toBe(false)
  expect(isAgentWorking("sending", true)).toBe(false)
  expect(isAgentWorking("stalled", true)).toBe(false)
  expect(isAgentWorking(undefined, true)).toBe(false)
})

test("isAgentWorking: disconnected sessions never read as working (stuck-spinner guard)", () => {
  expect(isAgentWorking("thinking", false)).toBe(false)
  expect(isAgentWorking("running", false)).toBe(false)
})
