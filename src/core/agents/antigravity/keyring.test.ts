import { expect, test } from "bun:test"
import { antigravityKeyringLogin } from "./keyring"

// The Credential Manager round trip itself (CredReadW against a real credential) is
// packages/platform/tests/credentials.test.ts's; these hold the wrapper's own rules.

test("agy's Windows login is the generic credential gemini:antigravity (go-keyring's service:user)", () => {
  const asked: string[] = []
  expect(antigravityKeyringLogin("win32", (target) => { asked.push(target); return true })).toBe(true)
  expect(asked).toEqual(["gemini:antigravity"])
  expect(antigravityKeyringLogin("win32", () => false)).toBe(false)
})

test("any error reads as no: a failing read, or a Windows runtime without Bun's ffi", () => {
  expect(antigravityKeyringLogin("win32", () => { throw new Error("CredReadW failed with Windows error 5") })).toBe(false)
  expect(antigravityKeyringLogin("win32", null)).toBe(false)
})

test("only Windows asks Credential Manager", () => {
  const never = () => { throw new Error("must not be asked") }
  let asked = false
  const spy = () => { asked = true; return true }
  expect(antigravityKeyringLogin("linux", never)).toBe(false)
  expect(antigravityKeyringLogin("darwin", spy)).toBe(false)
  expect(asked).toBe(false)
})
