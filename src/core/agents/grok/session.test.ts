import { describe, expect, test } from "bun:test"
import { applyConfig } from "./session"
import { GrokAdapter } from "./adapter"
import type { GrokRunner } from "./runner"
import type { ApplyConfigCtx } from "../session-types"

// Contract of the grok applyConfig DIALECT: model applies live on the adapter
// (the setter sends ACP session/set_model); effort has no ACP setter, so it
// goes through setEffort() (child relaunch). `changed` masks the halves the
// user did not touch. A missing live adapter is an explicit error — the
// component queues/rolls back on it.

const ctx = (adapter?: GrokAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function adapterWith(model?: string): { adapter: GrokAdapter; effortCalls: (string | undefined)[] } {
  const adapter = new GrokAdapter({
    sessionName: "t",
    workdir: "/tmp",
    runner: (() => { throw new Error("no child in this test") }) as unknown as GrokRunner,
    persistSessionId: async () => {},
    model,
  })
  const effortCalls: (string | undefined)[] = []
  adapter.setEffort = async (e) => { effortCalls.push(e) }
  return { adapter, effortCalls }
}

describe("grok applyConfig dialect", () => {
  test("no live adapter → the exact error", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "grok-4" })
    expect(r).toEqual({ ok: false, error: "grok session has no live adapter" })
  })

  test("model half applies live; a masked effort half is untouched", async () => {
    const { adapter, effortCalls } = adapterWith("grok-4")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "grok-4-fast", effort: "high", changed: { model: true, effort: false },
    })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("grok-4-fast")
    expect(effortCalls).toEqual([])
  })

  test("effort half goes through setEffort; a masked model half is untouched", async () => {
    const { adapter, effortCalls } = adapterWith("grok-4")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "grok-4-fast", effort: "low", changed: { model: false, effort: true },
    })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("grok-4")
    expect(effortCalls).toEqual(["low"])
  })

  test("without a changed mask, both halves apply (drain semantics)", async () => {
    const { adapter, effortCalls } = adapterWith("grok-4")
    const r = await applyConfig(ctx(adapter), row, "n", { model: "grok-4-fast", effort: "high" })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("grok-4-fast")
    expect(effortCalls).toEqual(["high"])
  })
})
