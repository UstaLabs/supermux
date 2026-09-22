import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import { isCoreBacked } from "./activity-dispatch"
import { CoreCodexAdapter } from "../codex/core-adapter"
import { CoreGrokAdapter } from "../grok/core-adapter"
import type { AgentAdapter } from "../types"

test("isCoreBacked is true for CoreCodexAdapter and CoreGrokAdapter", () => {
  const fakeCore = {} as never
  const codex = Object.create(CoreCodexAdapter.prototype) as AgentAdapter
  const grok = Object.create(CoreGrokAdapter.prototype) as AgentAdapter
  expect(isCoreBacked(codex)).toBe(true)
  expect(isCoreBacked(grok)).toBe(true)
  void fakeCore
})

test("isCoreBacked is false for other adapters", () => {
  class Other extends EventEmitter implements AgentAdapter {
    kind = "cursor" as const
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
