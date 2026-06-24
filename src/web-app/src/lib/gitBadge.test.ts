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
