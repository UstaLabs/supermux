import { test, expect } from "bun:test"
import { deriveName, ensureUnique } from "../src/core/session-manager/naming"

test("derives name from workdir basename, lowercased", () => {
  expect(deriveName("/home/user/MyProject")).toBe("myproject")
})

test("sanitizes characters outside [a-z0-9_-]", () => {
  expect(deriveName("/tmp/foo.bar baz")).toBe("foo-bar-baz")
})

test("truncates to 24 chars", () => {
  expect(deriveName("/tmp/this-is-a-very-long-project-name")).toHaveLength(24)
})

test("strips trailing/leading dashes after sanitize", () => {
  expect(deriveName("/tmp/.hidden.")).toBe("hidden")
})

test("ensureUnique returns base when no collision", () => {
  expect(ensureUnique("mobileapp", new Set())).toBe("mobileapp")
})

test("ensureUnique suffixes on collision", () => {
  expect(ensureUnique("mobileapp", new Set(["mobileapp"]))).toBe("mobileapp-2")
  expect(ensureUnique("mobileapp", new Set(["mobileapp", "mobileapp-2"]))).toBe("mobileapp-3")
})
