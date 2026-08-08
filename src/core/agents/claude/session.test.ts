import { describe, expect, test } from "bun:test"
import { applyConfig } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import type { SessionBackend } from "../../runtime/session-backend"

// Contract of the claude applyConfig DIALECT: it types into the live TUI via
// applyClaudeLiveSwitch (never restarts), narrows to the halves the user
// actually changed, and fails explicitly when the window is unknown. The
// type-in mechanics themselves are covered by live-switch.test.ts.

const ctx = (extra?: Partial<ApplyConfigCtx>): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  ...extra,
})

const row = { id: "s1", workdir: "/tmp" }

describe("claude applyConfig dialect", () => {
  test("no window id → explicit error, nothing typed", async () => {
    const r = await applyConfig(ctx(), row, "n", { model: "m1" })
    expect(r).toEqual({ ok: false, error: "session window not found" })
  })

  test("changed:false masks both halves → success without touching the pane", async () => {
    const backend = {
      capture: async () => { throw new Error("must not capture when nothing changed") },
    } as unknown as SessionBackend
    const r = await applyConfig(ctx({ windowId: "@1", backend }), row, "n", {
      model: "m1", effort: "high", changed: { model: false, effort: false },
    })
    expect(r).toEqual({ ok: true })
  })

  test("an unmasked model switch types into the live pane (backend consulted)", async () => {
    let captures = 0
    const backend = {
      capture: async () => { captures++; return null },
    } as unknown as SessionBackend
    const r = await applyConfig(ctx({ windowId: "@1", backend }), row, "n", {
      model: "m1", changed: { model: true, effort: false },
    })
    expect(captures).toBeGreaterThan(0)
    // A vanished pane is an explicit failure (the caller rolls back) — proof
    // the dialect went for the live type-in rather than any restart path.
    expect(r).toEqual({ ok: false, error: "session window gone (no pane to capture)" })
  })
})
