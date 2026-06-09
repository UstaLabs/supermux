import { test, expect } from "bun:test"
import { POLICY, canOrchestrate, isPersistent, isFallbackEligible, type SessionRole } from "../src/core/session-manager/policy"

test("personal_assistant has all capabilities", () => {
  expect(canOrchestrate("personal_assistant")).toBe(true)
  expect(isPersistent("personal_assistant")).toBe(true)
  expect(isFallbackEligible("personal_assistant")).toBe(true)
})

test("worker has no capabilities", () => {
  expect(canOrchestrate("worker")).toBe(false)
  expect(isPersistent("worker")).toBe(false)
  expect(isFallbackEligible("worker")).toBe(false)
})

test("POLICY covers exactly the two roles", () => {
  expect(Object.keys(POLICY).sort()).toEqual(["personal_assistant", "worker"])
})
