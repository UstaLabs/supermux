import { test, expect } from "bun:test"
import { codexResetNote } from "./codex-reset"

test("codexResetNote: reset uses singular/plural windows", () => {
  expect(codexResetNote("reset", 1)).toBe("✓ Reset — cleared 1 window")
  expect(codexResetNote("reset", 2)).toBe("✓ Reset — cleared 2 windows")
})

test("codexResetNote: known non-reset codes", () => {
  expect(codexResetNote("nothing_to_reset", 0)).toContain("Nothing to reset")
  expect(codexResetNote("no_credit", 0)).toContain("No banked resets")
  expect(codexResetNote("already_redeemed", 0)).toContain("already redeemed")
})

test("codexResetNote: unknown code falls back", () => {
  expect(codexResetNote("future_code", 0)).toBe("Reset request completed")
})
