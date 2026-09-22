import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import { isCoreBacked } from "./activity-dispatch"
import { CoreCodexAdapter } from "../codex/core-adapter"
import { CoreGrokAdapter } from "../grok/core-adapter"
import { CoreOpenCodeAdapter } from "../opencode/core-adapter"
import { CoreCursorAdapter } from "../cursor/core-adapter"
import { CoreClaudeAdapter } from "../claude/core-adapter"
import type { AgentAdapter } from "../types"

test("isCoreBacked is true for CoreCodexAdapter, CoreGrokAdapter, CoreOpenCodeAdapter, and CoreCursorAdapter", () => {
  const fakeCore = {} as never
  const codex = Object.create(CoreCodexAdapter.prototype) as AgentAdapter
  const grok = Object.create(CoreGrokAdapter.prototype) as AgentAdapter
  const opencode = Object.create(CoreOpenCodeAdapter.prototype) as AgentAdapter
  const cursor = Object.create(CoreCursorAdapter.prototype) as AgentAdapter
  const claude = Object.create(CoreClaudeAdapter.prototype) as AgentAdapter
  expect(isCoreBacked(codex)).toBe(true)
  expect(isCoreBacked(grok)).toBe(true)
  expect(isCoreBacked(opencode)).toBe(true)
  expect(isCoreBacked(cursor)).toBe(true)
  expect(isCoreBacked(claude)).toBe(true)
  void fakeCore
})

test("isCoreBacked is false for other adapters", () => {
  class Other extends EventEmitter implements AgentAdapter {
    kind = "claude" as const
    sessionName = "s"
    workdir = "/w"
    async start() {}
    async resume() {}
    async stop() {}
    async send() {}
    async interrupt() {}
  }
  expect(isCoreBacked(new Other())).toBe(false)
})
