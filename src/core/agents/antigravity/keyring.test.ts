import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { randomUUID } from "node:crypto"
import { antigravityKeyringLogin, windowsCredentialExists } from "./keyring"

test("agy's Windows login is the generic credential gemini:antigravity (go-keyring's service:user)", () => {
  const asked: string[] = []
  expect(antigravityKeyringLogin("win32", (target) => { asked.push(target); return true })).toBe(true)
  expect(asked).toEqual(["gemini:antigravity"])
  expect(antigravityKeyringLogin("win32", () => false)).toBe(false)
  expect(antigravityKeyringLogin("win32", () => { throw new Error("advapi32 unavailable") })).toBe(false)
})

test("only Windows asks Credential Manager", () => {
  const never = () => { throw new Error("must not be asked") }
  expect(antigravityKeyringLogin("linux", never)).toBe(false)
  expect(antigravityKeyringLogin("darwin", never)).toBe(false)
})

test.skipIf(process.platform !== "win32")("CredReadW sees a generic credential only while it exists", () => {
  const target = `supermux-test-${randomUUID()}`
  expect(windowsCredentialExists(target)).toBe(false)
  expect(spawnSync("cmdkey", [`/generic:${target}`, "/user:supermux", "/pass:test"], { windowsHide: true }).status).toBe(0)
  try {
    expect(windowsCredentialExists(target)).toBe(true)
  } finally {
    spawnSync("cmdkey", [`/delete:${target}`], { windowsHide: true })
  }
  expect(windowsCredentialExists(target)).toBe(false)
})
