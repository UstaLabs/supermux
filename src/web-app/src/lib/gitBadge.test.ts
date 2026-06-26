import { test, expect } from "bun:test"
import { gitBadge } from "./gitBadge"

test("undefined → null (no badge)", () => { expect(gitBadge(undefined)).toBeNull() })

test("clean → muted in-sync", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })
  expect(b).toEqual({ text: "✓ in sync", title: "In sync with main", tone: "muted", kind: "insync" })
})

test("base mode → +/− glyphs, branch kind, active tone", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 2, behind: 1, dirty: 3, computedAt: 0 })
  expect(b?.text).toBe("+2 −1 ·3")
  expect(b?.kind).toBe("base")
  expect(b?.tone).toBe("active")
  expect(b?.title).toBe("2 ahead / 1 behind main · 3 uncommitted")
})

test("remote mode → ↑/↓ arrows, remote kind, origin label", () => {
  const b = gitBadge({ mode: "remote", compareRef: "origin/x", ahead: 1, behind: 0, dirty: 0, computedAt: 0 })
  expect(b?.text).toBe("↑1")
  expect(b?.kind).toBe("remote")
  expect(b?.title).toBe("1 ahead origin")
})

test("unpublished remote → muted unpublished", () => {
  const b = gitBadge({ mode: "remote", compareRef: "x", ahead: 0, behind: 0, dirty: 0, unpublished: true, computedAt: 0 })
  expect(b).toEqual({ text: "unpublished", title: "Not published", tone: "muted", kind: "unpublished" })
})

test("base dirty-only → ·N, branch kind, active", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 3, computedAt: 0 })
  expect(b).toEqual({ text: "·3", title: "3 uncommitted", tone: "active", kind: "base" })
})

import { sessionDoneState } from "./gitBadge"

test("sessionDoneState: done when in dev + clean", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })).toBe("done")
})
test("sessionDoneState: not-done when ahead or dirty", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 2, behind: 0, dirty: 0, computedAt: 0 })).toBe("not-done")
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 1, computedAt: 0 })).toBe("not-done")
})
test("sessionDoneState: behind-only still done", () => {
  expect(sessionDoneState({ mode: "base", compareRef: "dev", ahead: 0, behind: 3, dirty: 0, computedAt: 0 })).toBe("done")
})
test("sessionDoneState: null for undefined or remote", () => {
  expect(sessionDoneState(undefined)).toBeNull()
  expect(sessionDoneState({ mode: "remote", compareRef: "origin/x", ahead: 1, behind: 0, dirty: 0, computedAt: 0 })).toBeNull()
})

import { sessionStatus } from "./gitBadge"

test("sessionStatus worktree pristine/done/not-done", () => {
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, touched: false, computedAt: 0 })).toEqual({ kind: "worktree", level: "pristine" })
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 0, behind: 0, dirty: 0, touched: true, computedAt: 0 })).toEqual({ kind: "worktree", level: "done" })
  expect(sessionStatus({ mode: "base", compareRef: "dev", ahead: 1, behind: 0, dirty: 0, touched: true, computedAt: 0 })).toEqual({ kind: "worktree", level: "not-done" })
})
test("sessionStatus remote synced/not", () => {
  expect(sessionStatus({ mode: "remote", compareRef: "origin/x", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })).toEqual({ kind: "remote", level: "done" })
  expect(sessionStatus({ mode: "remote", compareRef: "origin/x", ahead: 0, behind: 1, dirty: 0, computedAt: 0 })).toEqual({ kind: "remote", level: "not-done" })
})
test("sessionStatus null", () => { expect(sessionStatus(undefined)).toBeNull() })
