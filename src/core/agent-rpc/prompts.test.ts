import { test, expect } from "bun:test"
import { buildRpcPrompt } from "./prompts"

test("prompt embeds request_id, resolve/reject instruction, and JSON payload", () => {
  const p = buildRpcPrompt("voice", { draft: "helo wrld" }, "req-42")
  expect(p).toContain("req-42")
  expect(p).toContain("resolve")
  expect(p).toContain("reject")
  expect(p).toContain('"draft": "helo wrld"')
})

test("pr_text asks for a title and a body from the commits, without inventing", () => {
  const p = buildRpcPrompt("pr_text", { base: "dev", branch: "mux/x", commits: [] }, "req-7")
  expect(p).toContain("{ title")
  expect(p).toContain("body")
  expect(p).toMatch(/invent/i)
  expect(p).not.toContain("text-cleanup")
})

test("voice keeps its own fast-cleanup instruction", () => {
  expect(buildRpcPrompt("voice", { draft: "x" }, "r")).toContain("fast text-cleanup")
})
