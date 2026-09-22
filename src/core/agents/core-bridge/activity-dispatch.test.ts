import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import { isCoreBacked } from "./activity-dispatch"
import { CoreAdapter } from "./core-adapter"
import type { AgentAdapter } from "../types"

test("isCoreBacked is true for CoreAdapter", () => {
  const adapter = Object.create(CoreAdapter.prototype) as AgentAdapter
  expect(isCoreBacked(adapter)).toBe(true)
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
