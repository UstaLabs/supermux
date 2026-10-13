import { test, expect } from "bun:test"
import { normalizeToolName } from "./tool-normalize"

test("codex shell/command_execution -> Bash", () => {
  expect(normalizeToolName("codex", "shell")).toBe("Bash")
  expect(normalizeToolName("codex", "command_execution")).toBe("Bash")
})
test("codex file edits -> Edit", () => {
  expect(normalizeToolName("codex", "file_change")).toBe("Edit")
  expect(normalizeToolName("codex", "apply_patch")).toBe("Edit")
})
test("cursor strips ToolCall and maps known stems", () => {
  expect(normalizeToolName("cursor", "readToolCall")).toBe("Read")
  expect(normalizeToolName("cursor", "editToolCall")).toBe("Edit")
  expect(normalizeToolName("cursor", "writeToolCall")).toBe("Write")
  expect(normalizeToolName("cursor", "grepToolCall")).toBe("Grep")
  expect(normalizeToolName("cursor", "lsToolCall")).toBe("Glob")
  expect(normalizeToolName("cursor", "runTerminalCmdToolCall")).toBe("Bash")
})
test("cursor unknown stem -> capitalized stem", () => {
  expect(normalizeToolName("cursor", "fooBarToolCall")).toBe("FooBar")
})
test("mcp__ names extract the tool segment", () => {
  expect(normalizeToolName("opencode", "mcp__mux-shim__attach")).toBe("Attach")
  expect(normalizeToolName("opencode", "mcp__mux-shim__spawn_session")).toBe("Spawn_session")
  expect(normalizeToolName("opencode", "mcp__github__list_issues")).toBe("List_issues")
})
test("antigravity (agy) native tool names map to canonical cards", () => {
  const cases: Array<[string, string]> = [
    ["run_command", "Bash"], ["send_command_input", "Bash"], ["command_status", "Bash"],
    ["write_to_file", "Write"], ["view_file", "Read"],
    ["replace_file_content", "Edit"], ["multi_replace_file_content", "Edit"], ["sed_file", "Edit"], ["notebook_edit", "Edit"],
    ["list_dir", "Glob"], ["find_by_name", "Glob"], ["grep_search", "Grep"],
    ["read_url_content", "WebFetch"], ["search_web", "WebFetch"],
  ]
  for (const [raw, card] of cases) expect(normalizeToolName("antigravity", raw)).toBe(card)
  expect(normalizeToolName("antigravity", "mcp__mux-shim__attach")).toBe("Attach")
})
test("mcp__ with only two segments -> Tool fallback", () => {
  expect(normalizeToolName("opencode", "mcp__short")).toBe("Tool")
})
test("empty / unknown -> 'tool'", () => {
  expect(normalizeToolName("codex", "")).toBe("tool")
  expect(normalizeToolName("cursor", "")).toBe("tool")
})
test("grok tool stems normalize to canonical names", () => {
  expect(normalizeToolName("grok", "write")).toBe("Write")
  expect(normalizeToolName("grok", "read")).toBe("Read")
  expect(normalizeToolName("grok", "edit")).toBe("Edit")
  expect(normalizeToolName("grok", "shell")).toBe("Bash")
})
test("search_replace / str_replace map to Edit (not Grep search)", () => {
  expect(normalizeToolName("grok", "search_replace")).toBe("Edit")
  expect(normalizeToolName("grok", "searchReplace")).toBe("Edit")
  expect(normalizeToolName("cursor", "search_replace")).toBe("Edit")
  expect(normalizeToolName("cursor", "strReplaceToolCall")).toBe("Edit")
  // bare "search" still Grep
  expect(normalizeToolName("cursor", "search")).toBe("Grep")
})
