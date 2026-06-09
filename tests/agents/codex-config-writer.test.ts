import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeCodexConfig } from "../../src/core/agents/codex/config-writer"

describe("writeCodexConfig", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "codex-cfg-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  test("writes config.toml with shim MCP block", () => {
    writeCodexConfig({
      codexHome: dir,
      shimCommand: "bun",
      shimArgs: ["run", "/path/to/shim/index.ts"],
      sessionName: "zoom",
      socketsDir: "/sockets",
    })
    const content = readFileSync(join(dir, "config.toml"), "utf8")
    expect(content).toContain("[mcp_servers.mux-shim]")
    expect(content).toContain('command = "bun"')
    expect(content).toContain('MUX_SESSION_ID = "zoom"')
    // MUX_DISPLAY_NAME is the session display name.
    expect(content).toContain('MUX_DISPLAY_NAME = "zoom"')
    expect(content).toContain('MUX_AGENT_KIND = "codex"')
    expect(content).toContain('MUX_SOCKETS_DIR = "/sockets"')
    // Native codex memory must be off so ~/.mux is the sole memory.
    // `features.memories` is the master switch (verified against codex 0.133:
    // `codex features list` reflects the value); inner [memory] settings only
    // apply when the feature is enabled.
    expect(content).toContain("[features]")
    expect(content).toContain("memories = false")
  })
})
