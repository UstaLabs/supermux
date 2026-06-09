import { test, expect } from "bun:test"
import { resolveSelfRename, buildNamingRule } from "./naming"

// --- resolveSelfRename ------------------------------------------------------

test("resolveSelfRename accepts free-form names", () => {
  const r = resolveSelfRename("  My Cool Session 🎉  ", "project-api", ["project-api"])
  expect(r).toEqual({ ok: true, name: "My Cool Session 🎉" })
})

test("resolveSelfRename truncates to 80 chars", () => {
  const long = "a".repeat(100)
  const r = resolveSelfRename(long, "old", ["old"])
  expect(r.ok).toBe(true)
  if (r.ok) expect(r.name.length).toBe(80)
})

test("resolveSelfRename is a no-op when name equals current name", () => {
  const r = resolveSelfRename("project-api", "project-api", ["project-api", "other"])
  expect(r).toEqual({ ok: true, name: "project-api" })
})

test("resolveSelfRename rejects duplicate names", () => {
  const r = resolveSelfRename("review", "project-api", ["project-api", "review"])
  expect(r.ok).toBe(false)
  if (!r.ok) expect(r.error).toMatch(/already in use/i)
})

test("resolveSelfRename errors when name is empty", () => {
  const r = resolveSelfRename("  ", "project-api", ["project-api"])
  expect(r.ok).toBe(false)
  if (!r.ok) expect(r.error).toMatch(/empty/i)
})

// --- buildNamingRule --------------------------------------------------------

test("buildNamingRule names the current session and points at rename_session", () => {
  const rule = buildNamingRule("My Cool 🎉")
  expect(rule).toContain("My Cool 🎉")
  expect(rule).toContain("rename_session")
})
