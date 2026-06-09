import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useAgentState } from "./agentState"

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
