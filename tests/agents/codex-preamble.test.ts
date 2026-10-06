import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { codexInstructions } from "../../src/core/agents/codex/preamble-writer"
import { prepareCodexEnvironment } from "../../packages/supermux-core/src/environment/index.js"

describe("codexInstructions + prepareCodexEnvironment", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "codex-prm-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  test("writes AGENTS.md into CODEX_HOME with header, env and memory", async () => {
    const body = codexInstructions({ sessionName: "alpha", workdir: "/srv/app" })
    await prepareCodexEnvironment({
      home: dir,
      workdir: "/srv/app",
      mcpServers: [],
      skillsPaths: [],
      instructions: body,
      credentials: { apiKey: "sk-test", canonicalHome: join(dir, "canonical") },
      nativeMemory: false,
    })
    const p = join(dir, "AGENTS.md")
    expect(existsSync(p)).toBe(true)
    const content = readFileSync(p, "utf8")
    expect(content).toContain('"alpha"')
    expect(content).toContain("/srv/app")
    expect(content).toContain("supermux")
    expect(content).toContain("react")
    expect(content).toContain("edit_message")
    expect(content).toContain("download_attachment")
    expect(content.toLowerCase()).toContain("write text in your turn")
    expect(content.toLowerCase()).toContain("user does not see tool-call output")
    expect(content.toLowerCase()).toContain("always end every turn")
    expect(content.toLowerCase()).toContain("non-empty text response")
    expect(content.toLowerCase()).toContain("blocked")
    expect(content).not.toContain("~/.claude/skills")
    expect(content).toContain("<plugin>:<name>")
    expect(content).not.toContain("~/.mux/skills/")
    const hdrIdx = content.indexOf("# Working rules")
    const envIdx = content.indexOf("You are running inside supermux")
    const memIdx = content.indexOf("# Shared Memory System")
    expect(hdrIdx).toBeGreaterThan(-1)
    expect(hdrIdx).toBeLessThan(envIdx)
    expect(envIdx).toBeLessThan(memIdx)
  })
})
