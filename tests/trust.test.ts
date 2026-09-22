import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { preAcceptTrust } from "../src/core/session-manager/trust"

let homeDir: string
let origHome: string | undefined

beforeEach(() => {
  homeDir = mkdtempSync(join(tmpdir(), "agentmux-home-"))
  origHome = process.env.HOME
  process.env.HOME = homeDir
})
afterEach(() => {
  process.env.HOME = origHome
  rmSync(homeDir, { recursive: true, force: true })
})

test("creates projects entry with hasTrustDialogAccepted=true when none existed", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {} }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.projects["/home/u/foo"].hasTrustDialogAccepted).toBe(true)
  expect(c.projects["/home/u/foo"].allowedTools).toEqual([])
})

test("preserves existing projects entry, just flips trust flag", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({
    projects: {
      "/home/u/foo": { allowedTools: ["Bash"], hasTrustDialogAccepted: false, lastSessionId: "abc" },
    },
  }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.projects["/home/u/foo"].hasTrustDialogAccepted).toBe(true)
  expect(c.projects["/home/u/foo"].allowedTools).toEqual(["Bash"])
  expect(c.projects["/home/u/foo"].lastSessionId).toBe("abc")
})

test("idempotent — second call is a no-op once trust + shim are settled", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({
    projects: { "/home/u/foo": { hasTrustDialogAccepted: true } },
  }))
  preAcceptTrust("/home/u/foo") // first call settles trust + registers the shim
  const before = readFileSync(`${homeDir}/.claude.json`, "utf8")
  preAcceptTrust("/home/u/foo")
  const after = readFileSync(`${homeDir}/.claude.json`, "utf8")
  expect(after).toBe(before)
})

test("registers BOTH the tools (mux-shim) and channel-only (mux-channel) servers", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {} }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  // tools provider: no MUX_CHANNEL_ONLY
  expect(c.mcpServers["mux-shim"].command).toBe("bun")
  expect(c.mcpServers["mux-shim"].args.some((a: string) => a.endsWith("src/shim/index.ts"))).toBe(true)
  expect(c.mcpServers["mux-shim"].env?.MUX_CHANNEL_ONLY).toBeUndefined()
  // channel-only provider: MUX_CHANNEL_ONLY=1, zero tools
  expect(c.mcpServers["mux-channel"].command).toBe("bun")
  expect(c.mcpServers["mux-channel"].env.MUX_CHANNEL_ONLY).toBe("1")
})

test("skips the first-run wizard: sets hasCompletedOnboarding + a theme", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {} }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.hasCompletedOnboarding).toBe(true)
  expect(typeof c.theme).toBe("string")
})

test("pre-accepts Bypass Permissions mode (so the consent Enter can't quit claude)", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {} }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.bypassPermissionsModeAccepted).toBe(true)
})

test("does not clobber an existing user theme", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {}, theme: "light" }))
  preAcceptTrust("/home/u/foo")
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.theme).toBe("light")
})

test("does not throw when ~/.claude.json is missing", () => {
  expect(() => preAcceptTrust("/home/u/foo")).not.toThrow()
  expect(existsSync(`${homeDir}/.claude.json`)).toBe(true)
  const c = JSON.parse(readFileSync(`${homeDir}/.claude.json`, "utf8"))
  expect(c.projects["/home/u/foo"].hasTrustDialogAccepted).toBe(true)
})

test("does not throw when ~/.claude.json is malformed — logs and skips", () => {
  writeFileSync(`${homeDir}/.claude.json`, "{not json")
  expect(() => preAcceptTrust("/home/u/foo")).not.toThrow()
  expect(readFileSync(`${homeDir}/.claude.json`, "utf8")).toBe("{not json")
})

test("does not leave a .tmp file behind on success", () => {
  writeFileSync(`${homeDir}/.claude.json`, JSON.stringify({ projects: {} }))
  preAcceptTrust("/home/u/foo")
  const { readdirSync } = require("fs")
  const stragglers = readdirSync(homeDir).filter((f: string) => f.startsWith(".claude.json.tmp."))
  expect(stragglers.length).toBe(0)
})
