import { expect, test } from "bun:test"
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
