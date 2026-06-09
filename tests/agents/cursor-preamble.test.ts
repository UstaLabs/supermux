import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, existsSync, mkdirSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeCursorPreamble } from "../../src/core/agents/cursor/preamble-writer"

describe("writeCursorPreamble", () => {
  let wd: string
  beforeEach(() => { wd = mkdtempSync(join(tmpdir(), "cursor-wd-")) })
  afterEach(() => { rmSync(wd, { recursive: true, force: true }) })

  test("writes the rule into the WORKSPACE as .cursor/rules/mux.mdc", () => {
    // Cursor loads project rules from <workspace>/.cursor/rules/*.mdc, NOT from
    // $HOME/.cursor/rules — verified empirically against cursor-agent.
    writeCursorPreamble({ workdir: wd, sessionName: "beta" })
    const p = join(wd, ".cursor", "rules", "mux.mdc")
    expect(existsSync(p)).toBe(true)
  })

  test("the .mdc has alwaysApply frontmatter so cursor always loads it", () => {
    writeCursorPreamble({ workdir: wd, sessionName: "beta" })
    const content = readFileSync(join(wd, ".cursor", "rules", "mux.mdc"), "utf8")
    expect(content.startsWith("---\n")).toBe(true)
    expect(content).toContain("alwaysApply: true")
  })

  test("the rule contains the header, env reference and memory, no Claude-only skills", () => {
    writeCursorPreamble({ workdir: wd, sessionName: "beta" })
    const content = readFileSync(join(wd, ".cursor", "rules", "mux.mdc"), "utf8")
    expect(content).toContain('"beta"')
    expect(content).toContain(wd)
    expect(content).toContain("supermux")
    expect(content).toContain("react")
    expect(content).toContain("download_attachment")
    expect(content.toLowerCase()).toContain("use the reply tool only to send file attachments")
    expect(content).not.toContain("~/.claude/skills")
    // Skills reach cursor via the plugin host (namespaced <plugin>:<name>),
    // not a hand-built index pointing at ~/.mux/skills.
    expect(content).toContain("<plugin>:<name>")
    expect(content).not.toContain("~/.mux/skills/")
    const hdrIdx = content.indexOf("# Working rules")
    const envIdx = content.indexOf("You are running inside supermux")
    const memIdx = content.indexOf("# Shared Memory System")
    expect(hdrIdx).toBeGreaterThan(-1)
    expect(hdrIdx).toBeLessThan(envIdx)
    expect(envIdx).toBeLessThan(memIdx)
  })

  test("registers a local git exclude so the rule does not pollute the user's repo", () => {
    mkdirSync(join(wd, ".git", "info"), { recursive: true })
    writeCursorPreamble({ workdir: wd, sessionName: "beta" })
    const exclude = readFileSync(join(wd, ".git", "info", "exclude"), "utf8")
    expect(exclude).toContain(".cursor/rules/mux.mdc")
  })

  test("is a no-op for git exclude when the workspace is not a git repo", () => {
    // No .git dir — must not throw, and must still write the rule.
    expect(() => writeCursorPreamble({ workdir: wd, sessionName: "beta" })).not.toThrow()
    expect(existsSync(join(wd, ".cursor", "rules", "mux.mdc"))).toBe(true)
  })
})
