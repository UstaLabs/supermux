import { test, expect } from "bun:test"
import { canSkipTests } from "../src/lib/finish"

test("merge can always skip tests", () => {
  expect(canSkipTests("merge", false)).toBe(true)
  expect(canSkipTests("merge", true)).toBe(true)
})

test("pr can skip tests only when prRequiresGreen is false", () => {
  expect(canSkipTests("pr", false)).toBe(true)
  expect(canSkipTests("pr", true)).toBe(false)
})
