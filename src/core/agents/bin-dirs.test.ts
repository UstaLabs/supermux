import { describe, expect, test } from "bun:test"
import { delimiter } from "path"
import { agentBinDirs, withAgentBinDirs, nodeBinDirs, withNodeBinDirs } from "./bin-dirs"

test("agentBinDirs includes the dirs the official installers actually use", () => {
  const dirs = agentBinDirs("/home/u")
  // opencode installs to ~/.opencode/bin — NOT ~/.local/bin — and only edits
  // shell rc, so the broker never sees it unless we add it here.
  expect(dirs).toContain("/home/u/.opencode/bin")
  expect(dirs).toContain("/home/u/.local/bin") // claude, cursor
  expect(dirs).toContain("/home/u/.bun/bin")
})

test("withAgentBinDirs prepends the install dirs ahead of the existing PATH", () => {
  const out = withAgentBinDirs("/usr/bin:/bin", "/home/u")
  const parts = out.split(delimiter)
  expect(parts).toContain("/home/u/.opencode/bin")
  // existing entries preserved, in order, after the prepended dirs
  expect(parts[parts.length - 2]).toBe("/usr/bin")
  expect(parts[parts.length - 1]).toBe("/bin")
  expect(parts.indexOf("/home/u/.opencode/bin")).toBeLessThan(parts.indexOf("/usr/bin"))
})

test("withAgentBinDirs does not duplicate a dir already on PATH", () => {
  const out = withAgentBinDirs("/home/u/.local/bin:/usr/bin", "/home/u")
  const parts = out.split(delimiter)
  expect(parts.filter((p) => p === "/home/u/.local/bin").length).toBe(1)
})

test("withAgentBinDirs handles an empty/undefined PATH without stray separators", () => {
  const out = withAgentBinDirs(undefined, "/home/u")
  expect(out.split(delimiter)).toContain("/home/u/.opencode/bin")
  expect(out.startsWith(delimiter)).toBe(false)
  expect(out.endsWith(delimiter)).toBe(false)
})

test("nodeBinDirs includes common node/npm locations", () => {
  const dirs = nodeBinDirs("/home/u")
  expect(dirs).toContain("/opt/homebrew/bin")
  expect(dirs).toContain("/usr/local/bin")
  expect(dirs).toContain("/home/u/.volta/bin")
  expect(dirs).toContain("/home/u/.fnm")
  expect(dirs).toContain("/home/u/.local/share/fnm")
})

test("withNodeBinDirs prepends node dirs ahead of existing PATH", () => {
  const out = withNodeBinDirs("/usr/bin:/bin", "/home/u")
  const parts = out.split(delimiter)
  expect(parts).toContain("/opt/homebrew/bin")
  expect(parts).toContain("/home/u/.volta/bin")
  expect(parts[parts.length - 2]).toBe("/usr/bin")
  expect(parts[parts.length - 1]).toBe("/bin")
  expect(parts.indexOf("/opt/homebrew/bin")).toBeLessThan(parts.indexOf("/usr/bin"))
})

test("withNodeBinDirs does not duplicate a dir already on PATH", () => {
  const out = withNodeBinDirs("/opt/homebrew/bin:/usr/bin", "/home/u")
  const parts = out.split(delimiter)
  expect(parts.filter((p) => p === "/opt/homebrew/bin").length).toBe(1)
})

test("withNodeBinDirs handles undefined PATH", () => {
  const out = withNodeBinDirs(undefined, "/home/u")
  expect(out.split(delimiter)).toContain("/opt/homebrew/bin")
  expect(out.startsWith(delimiter)).toBe(false)
})

// ── per-OS install dirs, detection in them, the Windows registry refresh ─────────────────────
import { refreshPathFromRegistry } from "./bin-dirs"
import { resolveCommand } from "../process/launcher"

const WIN_HOME = "C:\\Users\\t"
const WIN_ENV = { LOCALAPPDATA: "C:\\Users\\t\\AppData\\Local", APPDATA: "C:\\Users\\t\\AppData\\Roaming", USERPROFILE: WIN_HOME }

test("agentBinDirs on macOS/Linux includes ~/.grok/bin beside ~/.local/bin and ~/.opencode/bin", () => {
  const dirs = agentBinDirs("/Users/u", "darwin", {})
  expect(dirs).toEqual(expect.arrayContaining(["/Users/u/.local/bin", "/Users/u/.opencode/bin", "/Users/u/.grok/bin"]))
})

test("agentBinDirs on Windows lists every agent's per-user install dir", () => {
  expect(agentBinDirs(WIN_HOME, "win32", WIN_ENV)).toEqual(expect.arrayContaining([
    "C:\\Users\\t\\.local\\bin",
    "C:\\Users\\t\\AppData\\Local\\Programs\\OpenAI\\Codex\\bin",
    "C:\\Users\\t\\AppData\\Local\\cursor-agent",
    "C:\\Users\\t\\AppData\\Local\\Programs\\opencode",
    "C:\\Users\\t\\.grok\\bin",
  ]))
})

test("withAgentBinDirs on Windows uses ';' and dedups case-insensitively", () => {
  const out = withAgentBinDirs("C:\\USERS\\T\\.LOCAL\\BIN\;C:\\WINDOWS", WIN_HOME, "win32", WIN_ENV)
  const parts = out.split(";")
  expect(parts.filter((p) => p.toLowerCase().startsWith("c:\\users\\t\\.local\\bin")).length).toBe(1)
  expect(parts[parts.length - 1]).toBe("C:\\WINDOWS")
  expect(out.includes(":C:")).toBe(false)
})

test("withNodeBinDirs leaves a Windows PATH alone", () => {
  expect(withNodeBinDirs("C:\\WINDOWS", WIN_HOME, "win32")).toBe("C:\\WINDOWS")
})

describe("detection finds an agent installed into the new dirs (no restart)", () => {
  const cases: Array<[string, string]> = [
    ["claude", "C:\\Users\\t\\.local\\bin\\claude.exe"],
    ["codex", "C:\\Users\\t\\AppData\\Local\\Programs\\OpenAI\\Codex\\bin\\codex.exe"],
    ["cursor-agent", "C:\\Users\\t\\AppData\\Local\\cursor-agent\\cursor-agent.cmd"],
    ["opencode", "C:\\Users\\t\\AppData\\Local\\Programs\\opencode\\opencode.exe"],
    ["grok", "C:\\Users\\t\\.grok\\bin\\grok.exe"],
  ]
  for (const [bin, file] of cases) {
    test(`Windows: ${bin}`, () => {
      const env = { ...WIN_ENV, Path: withAgentBinDirs("C:\\WINDOWS\\system32", WIN_HOME, "win32", WIN_ENV) }
      expect(resolveCommand([bin], env, "win32", { fileExists: (p) => p === file })).toBe(file)
    })
  }
  for (const [bin, dir] of [["opencode", ".opencode/bin"], ["grok", ".grok/bin"], ["agent", ".local/bin"], ["codex", ".local/bin"]] as const) {
    test(`macOS/Linux: ${bin}`, () => {
      const file = `/home/u/${dir}/${bin}`
      const env = { PATH: withAgentBinDirs("/usr/bin", "/home/u", "linux", {}) }
      expect(resolveCommand([bin], env, "linux", { fileExists: (p) => p === file })).toBe(file)
    })
  }
})

describe("refreshPathFromRegistry", () => {
  test("appends the registry's new dirs (vars expanded, deduped) to the Windows Path key", async () => {
    const env: Record<string, string | undefined> = { ...WIN_ENV, Path: "C:\\WINDOWS;C:\\Users\\t\\.local\\bin" }
    const added = await refreshPathFromRegistry(env, async (scope) =>
      scope === "user"
        ? "%LOCALAPPDATA%\\cursor-agent;C:\\USERS\\T\\.local\\bin\;;%NOPE%\\x"
        : "C:\\WINDOWS;C:\\Program Files\\Git\\cmd", "win32")
    expect(added).toEqual(["C:\\Users\\t\\AppData\\Local\\cursor-agent", "C:\\Program Files\\Git\\cmd"])
    expect(env.Path).toBe("C:\\WINDOWS;C:\\Users\\t\\.local\\bin;C:\\Users\\t\\AppData\\Local\\cursor-agent;C:\\Program Files\\Git\\cmd")
    expect(env.PATH).toBeUndefined()
  })

  test("a failing registry read changes nothing; other OSes never read it", async () => {
    const env: Record<string, string | undefined> = { Path: "C:\\WINDOWS" }
    expect(await refreshPathFromRegistry(env, async () => { throw new Error("reg") }, "win32")).toEqual([])
    expect(env.Path).toBe("C:\\WINDOWS")
    let read = false
    expect(await refreshPathFromRegistry({ PATH: "/usr/bin" }, async () => { read = true; return "x" }, "darwin")).toEqual([])
    expect(read).toBe(false)
  })
})
