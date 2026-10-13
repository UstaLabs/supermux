import { test, expect } from "bun:test"
import { authCredPath, detectAgent, hasStoredCredential } from "./detect"

const paths = { home: "/home/u" }

test("grok cred path is ~/.grok/auth.json", () => {
  expect(authCredPath("grok", paths)).toBe("/home/u/.grok/auth.json")
})

test("grok detects installed+authed from binary and auth file", () => {
  const probes = {
    hasBinary: (b: string) => b === "grok",
    fileExists: (p: string) => p === "/home/u/.grok/auth.json",
  }
  expect(detectAgent("grok", probes, paths)).toEqual({
    kind: "grok",
    installed: true,
    authed: true,
    capabilities: { supportsDeviceLogin: true, acceptsPastedKey: false, usableWithoutAuth: false },
  })
})

test("antigravity cred path is agy's OAuth token under ~/.gemini/antigravity-cli", () => {
  expect(authCredPath("antigravity", paths)).toBe("/home/u/.gemini/antigravity-cli/antigravity-oauth-token")
  expect(authCredPath("antigravity", { home: "C:\\Users\\u", platform: "win32" })).toBe("C:\\Users\\u\\.gemini\\antigravity-cli\\antigravity-oauth-token")
})

test("antigravity detects installed+authed from the agy binary and its token file", () => {
  const probes = {
    hasBinary: (b: string) => b === "agy",
    fileExists: (p: string) => p === "/home/u/.gemini/antigravity-cli/antigravity-oauth-token",
  }
  expect(detectAgent("antigravity", probes, paths)).toEqual({
    kind: "antigravity",
    installed: true,
    authed: true,
    capabilities: { supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false },
  })
})

test("antigravity counts a stored Gemini API key as authed", () => {
  const probes = {
    hasBinary: (b: string) => b === "agy",
    fileExists: () => false,
    hasCredential: (k: string) => k === "antigravity",
  }
  expect(detectAgent("antigravity", probes, paths).authed).toBe(true)
  expect(detectAgent("antigravity", { hasBinary: () => true, fileExists: () => false }, paths).authed).toBe(false)
  expect(hasStoredCredential("antigravity", { geminiApiKey: "k" })).toBe(true)
  expect(hasStoredCredential("antigravity", { geminiApiKey: "" })).toBe(false)
})

test("detectAgent always carries kind-derived auth capabilities", () => {
  const probes = { hasBinary: () => false, fileExists: () => false }
  expect(detectAgent("opencode", probes, paths).capabilities).toEqual({
    supportsDeviceLogin: false,
    acceptsPastedKey: false,
    usableWithoutAuth: true,
  })
  expect(detectAgent("claude", probes, paths).capabilities).toEqual({
    supportsDeviceLogin: true,
    acceptsPastedKey: true,
    usableWithoutAuth: false,
  })
})

test("grok's own `agent` binary does not make cursor look installed", () => {
  // Grok's installer puts `agent` in ~/.grok/bin; where it resolves is what marks it as Grok's.
  const probes = {
    hasBinary: (b: string) => b === "grok" || b === "agent",
    resolveBinary: (b: string) => (b === "agent" ? "/home/u/.grok/bin/agent" : b === "grok" ? "/home/u/.grok/bin/grok" : null),
    fileExists: () => false,
  }
  expect(detectAgent("cursor", probes, paths).installed).toBe(false)
  expect(detectAgent("grok", probes, paths).installed).toBe(true)
  expect(detectAgent("cursor", { hasBinary: (b: string) => b === "cursor-agent", fileExists: () => false }, paths).installed).toBe(true)
})
