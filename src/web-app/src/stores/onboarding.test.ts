import { test, expect, beforeEach } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { ONBOARDING_STEP_LABELS, useOnboarding } from "./onboarding"

beforeEach(() => setActivePinia(createPinia()))

test("onboarded starts null (unknown) so the guard won't redirect prematurely", () => {
  expect(useOnboarding().onboarded).toBeNull()
})
test("setOnboarded sets the boolean", () => {
  const o = useOnboarding(); o.setOnboarded(false); expect(o.onboarded).toBe(false); o.setOnboarded(true); expect(o.onboarded).toBe(true)
})
test("setAgentLoginState stores per-kind login state", () => {
  const o = useOnboarding()
  o.setAgentLoginState("codex", { kind: "codex", phase: "awaiting_user", url: "u", code: "C" })
  expect(o.loginState("codex")?.phase).toBe("awaiting_user")
  expect(o.loginState("cursor")).toBeUndefined()
})
test("step navigation: next/prev clamp within bounds", () => {
  const o = useOnboarding()
  expect(o.step).toBe(0); o.prev(); expect(o.step).toBe(0)
  o.next(); expect(o.step).toBe(1); o.setStep(3); o.next(); expect(o.step).toBe(3)
})
test("onboarding steps exclude assistant identity editing", () => {
  expect([...ONBOARDING_STEP_LABELS]).toEqual(["Welcome", "Agents", "Connectivity", "Done"])
  expect([...ONBOARDING_STEP_LABELS]).not.toContain("Identity")
})
