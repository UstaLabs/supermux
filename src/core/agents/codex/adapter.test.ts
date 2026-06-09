import { test, expect } from "bun:test"
import { CODEX_TOOL_ITEM_TYPES, isCodexToolItem } from "./adapter"

test("only real tool item types are tools; messages/reasoning are not", () => {
  expect(isCodexToolItem("command_execution")).toBe(true)
  expect(isCodexToolItem("fileChange")).toBe(true)
  expect(isCodexToolItem("webSearch")).toBe(true)
  expect(isCodexToolItem("mcp_tool_call")).toBe(true)
  // NOT tools:
  expect(isCodexToolItem("userMessage")).toBe(false)   // the bug: user messages must NOT be cards
  expect(isCodexToolItem("agentMessage")).toBe(false)
  expect(isCodexToolItem("reasoning")).toBe(false)
  expect(isCodexToolItem("error")).toBe(false)
  expect(isCodexToolItem("")).toBe(false)
  expect(isCodexToolItem(undefined)).toBe(false)
  expect(CODEX_TOOL_ITEM_TYPES.has("command_execution")).toBe(true)
})
