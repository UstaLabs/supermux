import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeCursorMcpConfig } from "../../src/core/agents/cursor/mcp-writer"

describe("writeCursorMcpConfig", () => {
  let home: string
  beforeEach(() => { home = mkdtempSync(join(tmpdir(), "cursor-mcp-")) })
  afterEach(() => { rmSync(home, { recursive: true, force: true }) })

  test("writes mcp.json with shim block including agent kind env", () => {
    writeCursorMcpConfig({
      sessionHome: home,
      shimCommand: "bun",
      shimArgs: ["run", "/x/shim/index.ts"],
      sessionName: "refactor",
      socketsDir: "/sockets",
    })
    const cfg = JSON.parse(readFileSync(join(home, ".cursor", "mcp.json"), "utf8"))
    expect(cfg.mcpServers["mux-shim"].command).toBe("bun")
    expect(cfg.mcpServers["mux-shim"].env.MUX_SESSION_ID).toBe("refactor")
    expect(cfg.mcpServers["mux-shim"].env.MUX_AGENT_KIND).toBe("cursor")
    expect(cfg.mcpServers["mux-shim"].env.MUX_SOCKETS_DIR).toBe("/sockets")
  })

  // MUX_DISPLAY_NAME carries the session display name for the shim.
  test("includes MUX_DISPLAY_NAME for session identity", () => {
    writeCursorMcpConfig({
      sessionHome: home,
      shimCommand: "bun",
      shimArgs: ["run", "/x/shim/index.ts"],
      sessionName: "myname",
      socketsDir: "/sockets",
    })
    const cfg = JSON.parse(readFileSync(join(home, ".cursor", "mcp.json"), "utf8"))
    expect(cfg.mcpServers["mux-shim"].env.MUX_DISPLAY_NAME).toBe("myname")
  })
})
