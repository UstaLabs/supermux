import { test, expect } from "bun:test"
import { authCredPath, detectAgent, detectAllAgents, type DetectProbes } from "../src/core/agents/detect"
import { agentAuthCapabilities } from "../src/core/agents/capabilities"

const PATHS = { home: "/home/u", xdgConfigHome: undefined as string | undefined }

test("authCredPath builds the per-kind credential paths", () => {
  expect(authCredPath("claude", PATHS)).toBe("/home/u/.claude/.credentials.json")
  expect(authCredPath("codex", PATHS)).toBe("/home/u/.codex/auth.json")
  expect(authCredPath("cursor", PATHS)).toBe("/home/u/.config/cursor/auth.json")
  expect(authCredPath("opencode", PATHS)).toBe("/home/u/.local/share/opencode/auth.json")
})

test("authCredPath honors XDG_CONFIG_HOME for cursor", () => {
  expect(authCredPath("cursor", { home: "/home/u", xdgConfigHome: "/xdg" })).toBe("/xdg/cursor/auth.json")
})

test("authCredPath honors XDG_DATA_HOME for opencode", () => {
  expect(authCredPath("opencode", { home: "/home/u", xdgDataHome: "/xdgdata" })).toBe("/xdgdata/opencode/auth.json")
})

test("detectAgent: installed + cred present ⇒ authed", () => {
  const probes: DetectProbes = { hasBinary: () => true, fileExists: () => true }
  expect(detectAgent("claude", probes, PATHS)).toEqual({ kind: "claude", installed: true, authed: true, capabilities: agentAuthCapabilities("claude") })
})

test("detectAgent: installed but no cred ⇒ not authed", () => {
  const probes: DetectProbes = { hasBinary: () => true, fileExists: () => false }
  expect(detectAgent("codex", probes, PATHS)).toEqual({ kind: "codex", installed: true, authed: false, capabilities: agentAuthCapabilities("codex") })
})

test("detectAgent: not installed ⇒ authed false even if a cred file exists", () => {
  const probes: DetectProbes = { hasBinary: () => false, fileExists: () => true }
  expect(detectAgent("cursor", probes, PATHS)).toEqual({ kind: "cursor", installed: false, authed: false, capabilities: agentAuthCapabilities("cursor") })
})

test("detectAgent checks Cursor's official names, never the IDE's cursor binary", () => {
  const seen: string[] = []
  const probes: DetectProbes = { hasBinary: (b) => { seen.push(b); return false }, fileExists: () => false }
  detectAgent("cursor", probes, PATHS)
  expect(seen).toEqual(["cursor-agent", "agent"])
})

test("detectAgent accepts Cursor's official agent.exe alias after cursor-agent", () => {
  const seen: string[] = []
  const probes: DetectProbes = { hasBinary: (b) => { seen.push(b); return b === "agent" }, fileExists: () => false }
  expect(detectAgent("cursor", probes, PATHS).installed).toBe(true)
  expect(seen).toEqual(["cursor-agent", "agent"])
})

test("authCredPath uses native Windows credential roots", () => {
  const paths = {
    home: "C:\\Users\\u",
    appData: "C:\\Users\\u\\AppData\\Roaming",
    localAppData: "C:\\Users\\u\\AppData\\Local",
    platform: "win32" as const,
  }
  expect(authCredPath("cursor", paths)).toBe("C:\\Users\\u\\AppData\\Roaming\\cursor\\auth.json")
  expect(authCredPath("opencode", paths)).toBe("C:\\Users\\u\\AppData\\Local\\opencode\\auth.json")
  expect(authCredPath("codex", paths)).toBe("C:\\Users\\u\\.codex\\auth.json")
  expect(authCredPath("grok", paths)).toBe("C:\\Users\\u\\.grok\\auth.json")
})

test("detectAllAgents returns every kind", () => {
  const probes: DetectProbes = { hasBinary: () => true, fileExists: () => true }
  const all = detectAllAgents(probes, PATHS)
  expect(all.map((a) => a.kind).sort()).toEqual(["claude", "codex", "cursor", "grok", "opencode"])
})

test("detectAgent: grok credential lives at ~/.grok/auth.json", () => {
  const seen: string[] = []
  const probes: DetectProbes = { hasBinary: () => true, fileExists: (p) => { seen.push(p); return true } }
  expect(detectAgent("grok", probes, PATHS)).toEqual({ kind: "grok", installed: true, authed: true, capabilities: agentAuthCapabilities("grok") })
  expect(seen[0]).toBe(`${PATHS.home}/.grok/auth.json`)
})

test("detectAgent: opencode free tier (installed, no auth.json) ⇒ installed but NOT authed", () => {
  const probes: DetectProbes = { hasBinary: () => true, fileExists: () => false }
  expect(detectAgent("opencode", probes, PATHS)).toEqual({ kind: "opencode", installed: true, authed: false, capabilities: agentAuthCapabilities("opencode") })
})

test("detectAgent: opencode with auth.json ⇒ authed (a provider is connected)", () => {
  const probes: DetectProbes = { hasBinary: () => true, fileExists: () => true }
  expect(detectAgent("opencode", probes, PATHS)).toEqual({ kind: "opencode", installed: true, authed: true, capabilities: agentAuthCapabilities("opencode") })
})

test("detectAgent: opencode not installed ⇒ not authed", () => {
  const probes: DetectProbes = { hasBinary: () => false, fileExists: () => true }
  expect(detectAgent("opencode", probes, PATHS).authed).toBe(false)
})

test("detectAgent: a stored credential makes authed true even with no cred file", () => {
  const probes = { hasBinary: () => true, fileExists: () => false, hasCredential: (k: string) => k === "codex" } as DetectProbes
  expect(detectAgent("codex", probes, PATHS).authed).toBe(true)
  expect(detectAgent("claude", probes, PATHS).authed).toBe(false)
})

test("detectAgent: hasCredential ignored when not installed", () => {
  const probes = { hasBinary: () => false, fileExists: () => false, hasCredential: () => true } as DetectProbes
  expect(detectAgent("cursor", probes, PATHS).authed).toBe(false)
})
