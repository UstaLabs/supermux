import { test, expect } from "bun:test"
import { CodexAdapter, CODEX_TOOL_ITEM_TYPES, isCodexToolItem } from "./adapter"

test("only real tool item types are tools; messages/reasoning are not", () => {
  expect(isCodexToolItem("command_execution")).toBe(true)
  expect(isCodexToolItem("fileChange")).toBe(true)
  expect(isCodexToolItem("webSearch")).toBe(true)
  expect(isCodexToolItem("mcp_tool_call")).toBe(true)
  expect(isCodexToolItem("dynamicToolCall")).toBe(true)
  // NOT tools:
  expect(isCodexToolItem("userMessage")).toBe(false)   // the bug: user messages must NOT be cards
  expect(isCodexToolItem("agentMessage")).toBe(false)
  expect(isCodexToolItem("reasoning")).toBe(false)
  expect(isCodexToolItem("error")).toBe(false)
  expect(isCodexToolItem("")).toBe(false)
  expect(isCodexToolItem(undefined)).toBe(false)
  expect(CODEX_TOOL_ITEM_TYPES.has("command_execution")).toBe(true)
})

test("current commandExecution exitCode marks the completed tool call failed", () => {
  let notify: ((n: { method: string; params: any }) => void) | undefined
  const client = {
    request: async <T = any>() => ({} as T),
    onNotification: (handler: (n: { method: string; params: any }) => void) => { notify = handler },
  }
  const adapter = new CodexAdapter({ sessionName: "s", workdir: "/w", client, persistThreadId: async () => {} })
  let received: any
  adapter.on("tool-call", (event) => { received = event })

  notify?.({ method: "item/completed", params: { item: { type: "commandExecution", id: "c1", status: "completed", exitCode: 9, aggregatedOutput: "boom" } } })

  expect(received).toMatchObject({ tool: "commandExecution", phase: "failed", call_id: "c1" })
})

test("dynamicToolCall uses its actual tool name", () => {
  let notify: ((n: { method: string; params: any }) => void) | undefined
  const client = {
    request: async <T = any>() => ({} as T),
    onNotification: (handler: (n: { method: string; params: any }) => void) => { notify = handler },
  }
  const adapter = new CodexAdapter({ sessionName: "s", workdir: "/w", client, persistThreadId: async () => {} })
  let received: any
  adapter.on("tool-call", (event) => { received = event })

  notify?.({ method: "item/started", params: { item: { type: "dynamicToolCall", id: "d1", tool: "Imagegen", arguments: {} } } })

  expect(received).toMatchObject({ tool: "Imagegen", phase: "started", call_id: "d1" })
})
