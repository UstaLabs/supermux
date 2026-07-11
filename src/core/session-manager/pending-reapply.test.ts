import { test, expect } from "bun:test"
import { PendingReapply, shouldDeferReapply, changedSince } from "./pending-reapply"

test("shouldDeferReapply: defer only when busy and not applyNow", () => {
  expect(shouldDeferReapply("idle", false)).toBe(false)
  expect(shouldDeferReapply("idle", true)).toBe(false)
  expect(shouldDeferReapply("thinking", false)).toBe(true)
  expect(shouldDeferReapply("running", false)).toBe(true)
  expect(shouldDeferReapply("dead", false)).toBe(true)
  expect(shouldDeferReapply("thinking", true)).toBe(false) // applyNow overrides
})

test("mark captures pre-change values once; has/take reflect it", () => {
  const p = new PendingReapply()
  expect(p.has("s1")).toBe(false)
  p.mark("s1", { oldModel: "m1", oldReasoningLevel: "low" })
  expect(p.has("s1")).toBe(true)
  // a second mark before draining keeps the ORIGINAL olds
  p.mark("s1", { oldModel: "m2", oldReasoningLevel: "high" })
  expect(p.take("s1")).toEqual({ oldModel: "m1", oldReasoningLevel: "low" })
  expect(p.has("s1")).toBe(false) // take() removed it
})

test("changedSince: model changed only", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: "high" },
                      { model: "claude-opus-4-8", reasoningLevel: "high" }))
    .toEqual({ model: true, effort: false })
})

test("changedSince: effort changed only", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: "high" },
                      { model: "claude-sonnet-5", reasoningLevel: "max" }))
    .toEqual({ model: false, effort: true })
})

test("changedSince: both changed (queued model switch then effort switch)", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: undefined },
                      { model: "claude-opus-4-8", reasoningLevel: "low" }))
    .toEqual({ model: true, effort: true })
})

test("changedSince: nothing changed", () => {
  expect(changedSince({ oldModel: "m", oldReasoningLevel: "high" },
                      { model: "m", reasoningLevel: "high" }))
    .toEqual({ model: false, effort: false })
})

test("take returns undefined when nothing pending", () => {
  const p = new PendingReapply()
  expect(p.take("s1")).toBeUndefined()
})

test("clear drops a session", () => {
  const p = new PendingReapply()
  p.mark("s1", { oldModel: "m1" })
  p.clear("s1")
  expect(p.has("s1")).toBe(false)
})
