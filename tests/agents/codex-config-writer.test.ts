import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { prepareCodexEnvironment } from "../../packages/supermux-core/src/environment/index.js"

describe("prepareCodexEnvironment config", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "codex-cfg-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  test("writes config.toml with shim MCP block", async () => {
    await prepareCodexEnvironment({
      home: dir,
      workdir: dir,
      mcpServers: [{
        name: "mux-shim",
        command: "bun",
        args: ["run", "/path/to/shim/index.ts"],
        env: {
          MUX_SESSION_ID: "zoom",
          MUX_DISPLAY_NAME: "zoom",
          MUX_AGENT_KIND: "codex",
          MUX_SOCKETS_DIR: "/sockets",
        },
      }],
      skillsPaths: [],
      instructions: null,
      credentials: { apiKey: "sk-test", canonicalHome: join(dir, "canonical") },
      nativeMemory: false,
    })
    const content = readFileSync(join(dir, "config.toml"), "utf8")
    expect(content).toContain("[mcp_servers.mux-shim]")
    expect(content).toContain('command = "bun"')
    expect(content).toContain('MUX_SESSION_ID = "zoom"')
    expect(content).toContain('MUX_DISPLAY_NAME = "zoom"')
    expect(content).toContain('MUX_AGENT_KIND = "codex"')
    expect(content).toContain('MUX_SOCKETS_DIR = "/sockets"')
    expect(content).toContain("[features]")
    expect(content).toContain("memories = false")
  })
})
