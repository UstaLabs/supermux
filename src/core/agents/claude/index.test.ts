import { test, expect } from "bun:test"
import { ClaudeCodeAdapter } from "./index"

function makeAdapter() {
  return new ClaudeCodeAdapter({
    sessionName: "s", workdir: "/tmp",
    sendInboundSocket: async () => {},
    interruptSocket: async () => {},
  })
}

test("ingestHook(UserPromptSubmit) emits turn-start", () => {
  const a = makeAdapter()
  const seen: any[] = []
  a.on("turn-start", (e) => seen.push(e))
  a.ingestHook("UserPromptSubmit")
  expect(seen).toEqual([{ kind: "turn-start" }])
})

test("ingestHook(PreToolUse) emits tool-call started with the tool", () => {
  const a = makeAdapter()
  const seen: any[] = []
  a.on("tool-call", (e) => seen.push(e))
  a.ingestHook("PreToolUse", { tool: "Bash" })
  expect(seen).toEqual([{ kind: "tool-call", tool: "Bash", phase: "started", call_id: "" }])
})

test("ingestHook(Stop) emits turn-complete", () => {
  const a = makeAdapter()
  const seen: any[] = []
  a.on("turn-complete", (e) => seen.push(e))
  a.ingestHook("Stop")
  expect(seen).toEqual([{ kind: "turn-complete" }])
})

test("ingestHook(StopFailure) emits error with type+message", () => {
  const a = makeAdapter()
  const seen: any[] = []
  a.on("error", (e) => seen.push(e))
  a.ingestHook("StopFailure", { errorType: "timeout", errorMessage: "boom" })
  expect(seen.length).toBe(1)
  expect(seen[0].kind).toBe("error")
  expect(seen[0].errorType).toBe("timeout")
  expect(seen[0].error.message).toBe("boom")
})

test("ingestHook(SessionStart) emits nothing", () => {
  const a = makeAdapter()
  let count = 0
  a.on("turn-start", () => count++); a.on("tool-call", () => count++)
  a.on("turn-complete", () => count++); a.on("error", () => count++)
  a.ingestHook("SessionStart")
  expect(count).toBe(0)
})
