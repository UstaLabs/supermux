import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { randomUUID } from "node:crypto"
import { credentials, Unsupported } from "@supermux/platform"

// #70: agy keeps its Google login in the OS keyring through go-keyring, which names a Windows generic credential
// `service:account` ("gemini:antigravity"), and then writes no token file.
test("Windows asks Credential Manager for the target go-keyring writes: service:account", () => {
  const asked: string[] = []
  const windowsRead = (target: string) => { asked.push(target); return true }
  expect(credentials.hasGenericCredential("gemini", "antigravity", { platform: "win32", windowsRead })).toBe(true)
  expect(asked).toEqual(["gemini:antigravity"])
  expect(credentials.hasGenericCredential("gemini", "antigravity", { platform: "win32", windowsRead: () => false })).toBe(false)
})

test("a credential store this layer cannot read answers Unsupported, never a silent no", () => {
  for (const platform of ["linux", "darwin"] as const) {
    expect(() => credentials.hasGenericCredential("gemini", "antigravity", { platform })).toThrow(Unsupported)
  }
  // Windows without Bun's ffi (Node, or Bun's Windows ARM64 build): nothing can call CredReadW.
  expect(() => credentials.hasGenericCredential("gemini", "antigravity", { platform: "win32", windowsRead: null })).toThrow(Unsupported)
})

test.skipIf(process.platform !== "win32")("Credential Manager: a generic credential is seen only while it exists", () => {
  const account = randomUUID()
  const target = `supermux-test:${account}`
  expect(credentials.hasGenericCredential("supermux-test", account)).toBe(false)
  expect(spawnSync("cmdkey", [`/generic:${target}`, "/user:supermux", "/pass:test"], { windowsHide: true }).status).toBe(0)
  try {
    expect(credentials.hasGenericCredential("supermux-test", account)).toBe(true)
  } finally {
    spawnSync("cmdkey", [`/delete:${target}`], { windowsHide: true })
  }
  expect(credentials.hasGenericCredential("supermux-test", account)).toBe(false)
})
