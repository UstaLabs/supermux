import { test, expect } from "bun:test"
import { resolveSelfRename, buildNamingRule, ensureUnique } from "./naming"

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

test("resolveSelfRename blocks a name change once already renamed", () => {
  const r = resolveSelfRename("brand-new", "descriptive-name", ["descriptive-name"], true)
  expect(r.ok).toBe(false)
  if (!r.ok) expect(r.error).toMatch(/only.*once|already/i)
})

test("resolveSelfRename still allows a no-op rename once already renamed", () => {
  const r = resolveSelfRename("descriptive-name", "descriptive-name", ["descriptive-name"], true)
  expect(r).toEqual({ ok: true, name: "descriptive-name" })
})

test("resolveSelfRename allows the first name change when not yet renamed", () => {
  const r = resolveSelfRename("descriptive-name", "project-api", ["project-api"], false)
  expect(r).toEqual({ ok: true, name: "descriptive-name" })
})

// --- buildNamingRule --------------------------------------------------------

test("buildNamingRule names the current session and points at rename_session", () => {
  const rule = buildNamingRule("My Cool 🎉")
  expect(rule).toContain("My Cool 🎉")
  expect(rule).toContain("rename_session")
})

test("buildNamingRule tells the agent it may rename only once", () => {
  const rule = buildNamingRule("My Cool 🎉")
  expect(rule).toMatch(/only.*once|once/i)
  expect(rule).not.toMatch(/rename again/i)
})

// A session's name becomes its home folder (agents/<kind>/<name>). On macOS and Windows a file system ignores letter
// case: "Fix bug" and "fix bug" were two sessions sharing one home, their credentials and history mixed.
test("ensureUnique: a name in another letter case is taken (the same folder on macOS and Windows)", () => {
  expect(ensureUnique("Fix bug", new Set(["fix bug"]))).toBe("Fix bug-2")
  expect(ensureUnique("fix bug", new Set(["FIX BUG", "fix bug-2"]))).toBe("fix bug-3")
  expect(ensureUnique("other", new Set(["fix bug"]))).toBe("other")
})

test("ensureUnique: names are compared as the folders they become", () => {
  // "a/b" is the folder "a-b" (a / never nests a home).
  expect(ensureUnique("a/b", new Set(["a-b"]))).toBe("a/b-2")
})
