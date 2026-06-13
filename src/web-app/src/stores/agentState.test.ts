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

// Must match the chat view's "Working…" spinner exactly: phase thinking/running,
// with NO connection gate — a working-but-disconnected session still shows in the
// chat, so it must show in the list too.
test("isAgentWorking: true exactly for thinking/running, regardless of connection", () => {
  expect(isAgentWorking("thinking")).toBe(true)
  expect(isAgentWorking("running")).toBe(true)
  expect(isAgentWorking("idle")).toBe(false)
  expect(isAgentWorking("sending")).toBe(false)
  expect(isAgentWorking("stalled")).toBe(false)
  expect(isAgentWorking(undefined)).toBe(false)
})
