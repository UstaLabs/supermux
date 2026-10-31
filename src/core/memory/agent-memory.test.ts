import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { agentMemoryPaths, appendAgentMemory, ensureAgentMemoryFiles } from "./agent-memory"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
const home = () => { const d = mkdtempSync(join(tmpdir(), "agent-mem-")); dirs.push(d); return d }

test("paths are keyed by the agent id under agent-memory/", () => {
  const h = home()
  expect(agentMemoryPaths("3f9c", h)).toEqual({
    dir: join(h, "agent-memory"),
    history: join(h, "agent-memory", "3f9c.md"),
    digest: join(h, "agent-memory", "3f9c.digest.md"),
  })
})

test("ensureAgentMemoryFiles creates both files empty, in a private folder, and never clobbers", () => {
  const h = home()
  const p = ensureAgentMemoryFiles("3f9c", h)
  expect(readFileSync(p.history, "utf8")).toBe("")
  expect(readFileSync(p.digest, "utf8")).toBe("")
  expect(statSync(p.dir).mode & 0o777).toBe(0o700)
  appendAgentMemory("3f9c", "Identity (migrated from soul.md)", "be blunt", h)
  ensureAgentMemoryFiles("3f9c", h)
  expect(readFileSync(p.history, "utf8")).toContain("be blunt")
})

test("appendAgentMemory adds a heading and body, separated from what was there", () => {
  const h = home()
  ensureAgentMemoryFiles("a1", h)
  appendAgentMemory("a1", "First", "one", h)
  appendAgentMemory("a1", "Second", "two", h)
  expect(readFileSync(agentMemoryPaths("a1", h).history, "utf8")).toBe("## First\n\none\n\n## Second\n\ntwo\n")
})

test("ids that are not plain words are refused", () => {
  expect(() => agentMemoryPaths("../x", home())).toThrow()
  expect(() => agentMemoryPaths("", home())).toThrow()
})

test("appendAgentMemory creates missing files", () => {
  const h = home()
  appendAgentMemory("b2", "Only", "x", h)
  expect(existsSync(agentMemoryPaths("b2", h).digest)).toBe(true)
})
