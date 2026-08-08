import { describe, expect, test } from "bun:test"
import { applyConfig } from "./session"
import { CursorAdapter, type CursorRunner } from "./adapter"
import type { ApplyConfigCtx } from "../session-types"

// Contract of the cursor applyConfig DIALECT: a model change is a live typed
// field update on the adapter (cursor-agent spawns per turn and reads the
// field fresh) — no process restart, and no effort half at all.

const ctx = (adapter?: CursorAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function adapterWith(model?: string): CursorAdapter {
  return new CursorAdapter({
    sessionName: "t",
    workdir: "/tmp",
    runner: (async () => { throw new Error("no turns in this test") }) as unknown as CursorRunner,
    persistSessionId: async () => {},
    model,
  })
}

describe("cursor applyConfig dialect", () => {
  test("sets the model through the typed adapter setter", async () => {
    const adapter = adapterWith("old-model")
    const r = await applyConfig(ctx(adapter), row, "n", { model: "new-model", changed: { model: true, effort: false } })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("new-model")
  })

  test("changed.model === false masks the update", async () => {
    const adapter = adapterWith("old-model")
    const r = await applyConfig(ctx(adapter), row, "n", { model: "new-model", changed: { model: false, effort: true } })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("old-model")
  })

  test("no live adapter → success no-op (the registry already holds the value)", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "new-model" })
    expect(r).toEqual({ ok: true })
  })
})
