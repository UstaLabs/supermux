import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { isActionableResult, useGitRemote } from "./gitRemote"

beforeEach(() => setActivePinia(createPinia()))

test("success results are not actionable", () => {
  expect(isActionableResult({ status: "pushed" })).toBe(false)
  expect(isActionableResult({ status: "up_to_date" })).toBe(false)
  expect(isActionableResult({ status: "clean" })).toBe(false)
})

test("failure results are actionable (shown as a card)", () => {
  expect(isActionableResult({ status: "rejected_non_ff" })).toBe(true)
  expect(isActionableResult({ status: "conflict", files: ["a"] })).toBe(true)
  expect(isActionableResult({ status: "dirty", files: ["a"] })).toBe(true)
  expect(isActionableResult({ status: "auth_failed", message: "x" })).toBe(true)
  expect(isActionableResult({ status: "error", message: "x" })).toBe(true)
})

test("initial state is empty; dismiss clears a session's result", () => {
  const g = useGitRemote()
  expect(g.statusBySession).toEqual({})
  g.resultBySession["s1"] = { status: "conflict", files: ["a"] }
  g.dismiss("s1")
  expect(g.resultBySession["s1"]).toBeNull()
})
