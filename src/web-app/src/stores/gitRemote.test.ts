import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { isActionableResult, sessionsSharingCheckout, useGitRemote } from "./gitRemote"

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

test("switch results: switched and invalid_name are not card-actionable, refusals are", () => {
  expect(isActionableResult({ status: "switched", branch: "dev" })).toBe(false)
  expect(isActionableResult({ status: "invalid_name", message: "bad" })).toBe(false)
  expect(isActionableResult({ status: "clobber", files: ["a"] })).toBe(true)
  expect(isActionableResult({ status: "checked_out_elsewhere", path: "/x" })).toBe(true)
  expect(isActionableResult({ status: "merge_in_progress" })).toBe(true)
})

test("sessionsSharingCheckout: same checkout and subdirs count, self and outsiders don't", () => {
  const sessions = [
    { id: "me", name: "me", workdir: "/repo" },
    { id: "a", name: "alpha", workdir: "/repo" },
    { id: "b", name: "beta", workdir: "/repo/packages/web" },
    { id: "c", name: "gamma", workdir: "/repo-other" },          // sibling, not inside
    { id: "d", name: "delta", workdir: "/home/u/.mux/worktrees/repo/x" }, // worktree session elsewhere
  ]
  const r = sessionsSharingCheckout(sessions, "me", "/repo")
  expect(r.map((s) => s.name).sort()).toEqual(["alpha", "beta"])
  expect(sessionsSharingCheckout(sessions, "me", null)).toEqual([])
})
