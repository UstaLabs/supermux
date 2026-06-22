import { test, expect } from "bun:test"
import { buildRpcPrompt } from "./prompts"

test("prompt embeds request_id, resolve/reject instruction, and JSON payload", () => {
  const p = buildRpcPrompt("voice", { draft: "helo wrld" }, "req-42")
  expect(p).toContain("req-42")
  expect(p).toContain("resolve")
  expect(p).toContain("reject")
  expect(p).toContain('"draft": "helo wrld"')
})
