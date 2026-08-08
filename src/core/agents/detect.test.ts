import { test, expect } from "bun:test"
import { authCredPath, detectAgent } from "./detect"

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
