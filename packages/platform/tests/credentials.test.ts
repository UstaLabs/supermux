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

// #29's review: "not found" (ERROR_NOT_FOUND, 1168) is the only failure that means no. Any other (an SSH key logon has
// no credential vault: ERROR_NO_SUCH_LOGON_SESSION, 1312) means this process could not look.
test("CredReadW failing with ERROR_NOT_FOUND is a no; any other Windows error throws", () => {
  const failing = (lastError: number) => ({ credRead: () => false, lastError: () => lastError })
  expect(credentials.readGenericCredential(failing(1168), "gemini:antigravity")).toBe(false)
  expect(() => credentials.readGenericCredential(failing(1312), "gemini:antigravity")).toThrow("Windows error 1312")
  expect(credentials.readGenericCredential({ credRead: () => true, lastError: () => { throw new Error("not asked") } }, "gemini:antigravity")).toBe(true)
})

// #101: Claude Code's Windows build keeps its login through Bun.secrets, whose Windows generic credential is named
// `service/name` ("Claude Code-credentials/claude-code-user"), not go-keyring's `service:account`.
test("readSecret hands Bun.secrets the service and name, and gives back what it holds", async () => {
  const asked: unknown[] = []
  const get = async (query: { service: string; name: string }) => {
    asked.push(query)
    return query.name === "claude-code-user" ? "{\"claudeAiOauth\":{}}" : null
  }
  expect(await credentials.readSecret("Claude Code-credentials", "claude-code-user", { platform: "win32", get })).toBe("{\"claudeAiOauth\":{}}")
  expect(await credentials.readSecret("Claude Code-credentials", "claude-code-user#m", { platform: "win32", get })).toBeNull()
  expect(asked).toEqual([
    { service: "Claude Code-credentials", name: "claude-code-user" },
    { service: "Claude Code-credentials", name: "claude-code-user#m" },
  ])
})

test("readSecret: a store this layer does not read answers Unsupported, and a store's own failure is thrown", async () => {
  for (const platform of ["linux", "darwin"] as const) {
    await expect(credentials.readSecret("Claude Code-credentials", "claude-code-user", { platform })).rejects.toThrow(Unsupported)
  }
  // Node has no Bun.secrets.
  await expect(credentials.readSecret("Claude Code-credentials", "claude-code-user", { platform: "win32", get: null })).rejects.toThrow(Unsupported)
  const failing = async () => { throw new Error("A specified logon session does not exist.") }
  await expect(credentials.readSecret("s", "n", { platform: "win32", get: failing })).rejects.toThrow("logon session")
})

test.skipIf(process.platform !== "win32")("Credential Manager: readSecret reads back what Bun.secrets stored, and null once it is gone", async () => {
  const name = randomUUID()
  expect(await credentials.readSecret("supermux-test", name)).toBeNull()
  await Bun.secrets.set({ service: "supermux-test", name, value: "s3cret" })
  try {
    expect(await credentials.readSecret("supermux-test", name)).toBe("s3cret")
  } finally {
    await Bun.secrets.delete({ service: "supermux-test", name })
  }
  expect(await credentials.readSecret("supermux-test", name)).toBeNull()
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
