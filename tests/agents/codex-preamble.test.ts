import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeCodexPreamble } from "../../src/core/agents/codex/preamble-writer"

describe("writeCodexPreamble", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "codex-prm-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  test("writes AGENTS.md into CODEX_HOME with header, env and memory", () => {
    writeCodexPreamble({ codexHome: dir, sessionName: "alpha", workdir: "/srv/app" })
    const p = join(dir, "AGENTS.md")
    expect(existsSync(p)).toBe(true)
    const content = readFileSync(p, "utf8")
    // Identity header names the session and states its scope.
    expect(content).toContain('"alpha"')
    expect(content).toContain("/srv/app")
    expect(content).toContain("supermux")
    // Side-effect tools are documented (in the environment reference) so the
    // agent knows what it has.
    expect(content).toContain("react")
    expect(content).toContain("edit_message")
    expect(content).toContain("download_attachment")
    // Plain-text replies use normal assistant output, not the attachment tool.
    expect(content.toLowerCase()).toContain("write text in your turn")
    // Tool calls are not user-visible; every turn must conclude with explicit text.
    expect(content.toLowerCase()).toContain("user does not see tool-call output")
    expect(content.toLowerCase()).toContain("always end every turn")
    expect(content.toLowerCase()).toContain("non-empty text response")
    expect(content.toLowerCase()).toContain("blocked")
    // Claude-only skills mechanics must NOT leak into codex.
    expect(content).not.toContain("~/.claude/skills")
    // Skills now reach codex via the plugin host (namespaced <plugin>:<name>),
    // not a hand-built index pointing at ~/.mux/skills.
    expect(content).toContain("<plugin>:<name>")
    expect(content).not.toContain("~/.mux/skills/")
    // Order: commanding header → environment reference → memory index.
    const hdrIdx = content.indexOf("# Working rules")
    const envIdx = content.indexOf("You are running inside supermux")
    const memIdx = content.indexOf("# Shared Memory System")
    expect(hdrIdx).toBeGreaterThan(-1)
    expect(hdrIdx).toBeLessThan(envIdx)
    expect(envIdx).toBeLessThan(memIdx)
  })
})
