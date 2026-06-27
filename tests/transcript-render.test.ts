import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { renderTranscript } from "../src/core/search/transcript-render"

function fixture(): string {
  const p = join(mkdtempSync(join(tmpdir(), "mux-tx-")), "t.jsonl")
  const lines = [
    { type: "user", message: { role: "user", content: "please fix the bug" }, timestamp: "2026-06-26T10:00:00.000Z" },
    { type: "assistant", message: { role: "assistant", content: [{ type: "text", text: "On it." }] }, timestamp: "2026-06-26T10:00:01.000Z" },
    { type: "assistant", message: { role: "assistant", content: [{ type: "tool_use", id: "t1", name: "Bash", input: { command: "ls -la" } }] }, timestamp: "2026-06-26T10:00:02.000Z" },
    { type: "user", message: { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "file.ts\n" }] }, timestamp: "2026-06-26T10:00:03.000Z" },
  ]
  writeFileSync(p, lines.map((l) => JSON.stringify(l)).join("\n") + "\n")
  return p
}

test("renders user/assistant text and tool calls, toggle tool calls off", () => {
  const p = fixture()
  const withTools = renderTranscript(p, { includeToolCalls: true })
  expect(withTools).toContain("USER: please fix the bug")
  expect(withTools).toContain("ASSISTANT: On it.")
  expect(withTools).toContain("Bash")
  expect(withTools).toContain("ls -la")
  const noTools = renderTranscript(p, { includeToolCalls: false })
  expect(noTools).not.toContain("Bash")
  expect(noTools).toContain("ASSISTANT: On it.")
})

test("grep filters to matching lines; missing file returns a marker", () => {
  const p = fixture()
  expect(renderTranscript(p, { includeToolCalls: true, grep: "bug" })).toContain("please fix the bug")
  expect(renderTranscript(p, { includeToolCalls: true, grep: "bug" })).not.toContain("On it.")
  expect(renderTranscript("/no/such.jsonl", {})).toContain("(no transcript")
})
