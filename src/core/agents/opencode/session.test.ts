import { describe, expect, test } from "bun:test"
import { applyConfig } from "./session"
import { OpenCodeAdapter, type OpenCodeClientLike } from "./adapter"
import type { ApplyConfigCtx } from "../session-types"

// Contract of the opencode applyConfig DIALECT: a model change is a live typed
// field update on the adapter — send() re-parses the field on every turn
// (parseModel), so no `opencode serve` restart is needed.

const ctx = (adapter?: OpenCodeAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function adapterWith(model?: string): OpenCodeAdapter {
  return new OpenCodeAdapter({
    sessionName: "t",
    workdir: "/tmp",
    client: {} as unknown as OpenCodeClientLike,
    persistSessionId: async () => {},
    model,
  })
}

describe("opencode applyConfig dialect", () => {
  test("sets the model through the typed adapter accessor", async () => {
    const adapter = adapterWith("openai/gpt-5")
    const r = await applyConfig(ctx(adapter), row, "n", { model: "anthropic/claude-sonnet-5", changed: { model: true, effort: false } })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("anthropic/claude-sonnet-5")
  })

  test("changed.model === false masks the update", async () => {
    const adapter = adapterWith("openai/gpt-5")
    const r = await applyConfig(ctx(adapter), row, "n", { model: "x/y", changed: { model: false, effort: false } })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("openai/gpt-5")
  })

  test("no live adapter → success no-op (the registry already holds the value)", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "x/y" })
    expect(r).toEqual({ ok: true })
  })
})
