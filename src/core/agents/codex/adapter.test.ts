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

test("webSearch defers its blank started item until completion supplies the query", () => {
  let notify: ((n: { method: string; params: any }) => void) | undefined
  const client = {
    request: async <T = any>() => ({} as T),
    onNotification: (handler: (n: { method: string; params: any }) => void) => { notify = handler },
  }
  const adapter = new CodexAdapter({ sessionName: "s", workdir: "/w", client, persistThreadId: async () => {} })
  const received: any[] = []
  adapter.on("tool-call", (event) => { received.push(event) })

  notify?.({ method: "item/started", params: { item: { type: "webSearch", id: "w1", query: "", action: null } } })
  expect(received).toEqual([])

  const completed = {
    type: "webSearch",
    id: "w1",
    query: "official Codex docs",
    action: { type: "search", query: "official Codex docs", queries: null },
  }
  notify?.({ method: "item/completed", params: { item: completed } })

  expect(received).toEqual([
    { kind: "tool-call", tool: "webSearch", phase: "started", call_id: "w1", detail: completed },
    { kind: "tool-call", tool: "webSearch", phase: "completed", call_id: "w1", detail: completed },
  ])
})

test("webSearch with an early query is emitted once at item start", () => {
  let notify: ((n: { method: string; params: any }) => void) | undefined
  const client = {
    request: async <T = any>() => ({} as T),
    onNotification: (handler: (n: { method: string; params: any }) => void) => { notify = handler },
  }
  const adapter = new CodexAdapter({ sessionName: "s", workdir: "/w", client, persistThreadId: async () => {} })
  const received: any[] = []
  adapter.on("tool-call", (event) => { received.push(event) })

  const started = { type: "webSearch", id: "w2", query: "Codex docs", action: null }
  notify?.({ method: "item/started", params: { item: started } })
  notify?.({ method: "item/completed", params: { item: { ...started, action: { type: "search", query: "Codex docs" } } } })

  expect(received.map((event) => event.phase)).toEqual(["started", "completed"])
})

test("account/rateLimits/updated maps params and calls onUsageUpdate", () => {
  let notify: ((n: { method: string; params: any }) => void) | undefined
  const client = {
    request: async <T = any>() => ({} as T),
    onNotification: (handler: (n: { method: string; params: any }) => void) => { notify = handler },
  }
  const received: any[] = []
  const adapter = new CodexAdapter({
    sessionName: "s",
    workdir: "/w",
    client,
    persistThreadId: async () => {},
    getPrevUsage: () => ({
      plan: "prev",
      windows: [],
      models: [],
      credits: null,
      limitReached: false,
      resetCredits: 4,
    }),
    onUsageUpdate: (data) => { received.push(data) },
  })
  expect(adapter.kind).toBe("codex")

  notify?.({
    method: "account/rateLimits/updated",
    params: {
      rateLimits: {
        primary: { usedPercent: 40, windowDurationMins: 300, resetsAt: 1_700_000_000 },
        secondary: { usedPercent: 10, windowDurationMins: 10_080, resetsAt: 1_700_001_000 },
        planType: "plus",
        credits: { hasCredits: true, unlimited: false, balance: "12" },
        rateLimitReachedType: "primary",
      },
    },
  })

  expect(received).toHaveLength(1)
  expect(received[0]).toMatchObject({
    plan: "plus",
    resetCredits: 4,
    limitReached: true,
    credits: { hasCredits: true, balance: "12" },
  })
  expect(received[0].windows[0]).toMatchObject({
    id: "primary",
    used: 40,
    windowSeconds: 18_000,
    resetsAt: 1_700_000_000,
    label: "5-hour window",
  })
  expect(received[0].windows[1]).toMatchObject({
    id: "secondary",
    used: 10,
    windowSeconds: 604_800,
    label: "7-day window",
  })
})
