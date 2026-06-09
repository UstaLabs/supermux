import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { preAcceptTrust } from "../src/core/session-manager/trust"
import { sendChannelConsentEnter } from "../src/core/session-manager/post-spawn-keys"

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

// sendChannelConsentEnter — polls the tmux pane for the consent prompt marker
// ("Enter to confirm") then sends Enter to dismiss it.

test("sendChannelConsentEnter sends Enter when consent prompt is detected, then stops once it clears", async () => {
  const calls: Array<{ target: string; keys: string[] }> = []
  let captureCount = 0
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 0,
    maxWaitMs: 1000,
    retryAfterMs: 0,
    sendKeysFn: async (target, keys) => { calls.push({ target, keys }) },
    capturePane: async () => {
      captureCount++
      // 1st capture shows the prompt; once we Enter, it clears to the working UI.
      if (captureCount === 1) return "some text\nEnter to confirm\nmore text"
      return "⏵⏵ bypass permissions on"
    },
  })
  expect(calls).toHaveLength(1)
  expect(calls[0]!.target).toBe("mux:ana")
  expect(calls[0]!.keys).toContain("Enter")
  expect(captureCount).toBeGreaterThanOrEqual(2)
})

test("sendChannelConsentEnter re-sends Enter when the first keystrokes are dropped", async () => {
  // Simulates the Ink input-handler race: the prompt stays up through the first
  // few Enters (dropped), then clears once one finally lands.
  const calls: string[][] = []
  let captureCount = 0
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 0,
    maxWaitMs: 1000,
    retryAfterMs: 0,
    sendKeysFn: async (_target, keys) => { calls.push(keys) },
    capturePane: async () => {
      captureCount++
      // prompt persists for 3 captures (Enters 1-2 dropped), then clears
      return captureCount <= 3 ? "Enter to confirm" : "⏵⏵ bypass permissions on"
    },
  })
  expect(calls.length).toBe(3)
  expect(calls.every((k) => k.includes("Enter"))).toBe(true)
})

test("sendChannelConsentEnter exits early if Claude already past consent", async () => {
  const calls: Array<{ target: string; keys: string[] }> = []
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 0,
    maxWaitMs: 1000,
    sendKeysFn: async (target, keys) => { calls.push({ target, keys }) },
    capturePane: async () => "Listening for channel messages from: server:mux-shim",
  })
  expect(calls).toHaveLength(0)
})

test("sendChannelConsentEnter dismisses --resume menu before channel consent", async () => {
  const calls: Array<{ target: string; keys: string[] }> = []
  let captureCount = 0
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 0,
    maxWaitMs: 2000,
    sendKeysFn: async (target, keys) => { calls.push({ target, keys }) },
    capturePane: async () => {
      captureCount++
      if (captureCount === 1) {
        return "Resuming the full session\n  ❯ 1. Resume from summary\nEnter to confirm · Esc to cancel"
      }
      if (captureCount === 2) return "Listening for channel messages from: server:mux-channel"
      return ""
    },
  })
  expect(calls).toHaveLength(1)
  expect(calls[0]!.keys).toEqual(["2", "Enter"])
})

test("sendChannelConsentEnter accepts the Bypass Permissions warning with Down+Enter, never a bare Enter", async () => {
  // Some claude versions re-show the bypass warning (default "No, exit") when
  // loading dev-channels; a bare Enter would QUIT claude. Must select option 2.
  const calls: string[][] = []
  let captureCount = 0
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 0,
    maxWaitMs: 2000,
    retryAfterMs: 0,
    keyDelayMs: 0,
    sendKeysFn: async (_t, keys) => { calls.push(keys) },
    capturePane: async () => {
      captureCount++
      // bypass warning (also shows "Enter to confirm") for 1 capture, then cleared
      if (captureCount === 1) return "WARNING: Bypass Permissions mode\n  ❯ 1. No, exit\n    2. Yes, I accept\nEnter to confirm"
      return "Listening for channel messages from: server:mux-channel"
    },
  })
  const flat = calls.flat()
  // Down moves to "2. Yes, I accept" BEFORE Enter confirms — Enter must never
  // come first (that confirms the default "1. No, exit" and quits claude).
  expect(flat).toContain("Down")
  expect(flat).toContain("Enter")
  expect(flat.indexOf("Down")).toBeLessThan(flat.indexOf("Enter"))
})

test("sendChannelConsentEnter times out gracefully if prompt never appears", async () => {
  const calls: Array<{ target: string; keys: string[] }> = []
  await sendChannelConsentEnter("mux:ana", {
    pollIntervalMs: 10,
    maxWaitMs: 50,
    sendKeysFn: async (target, keys) => { calls.push({ target, keys }) },
    capturePane: async () => "some other content",
  })
  expect(calls).toHaveLength(0)
})
