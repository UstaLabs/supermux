import { test, expect } from "bun:test"
import { buildGrokMcpServers } from "./mcp-writer"

test("buildGrokMcpServers returns an ACP mcpServers entry for mux-shim", () => {
  const servers = buildGrokMcpServers({
    shimCommand: "bun", shimArgs: ["run", "/app/src/shim/index.ts"],
    sessionId: "s1", sessionName: "My Session", socketsDir: "/run/mux",
  })
  expect(servers).toEqual([{
    name: "mux-shim", type: "stdio", command: "bun", args: ["run", "/app/src/shim/index.ts"],
    env: [
      { name: "MUX_SESSION_ID", value: "s1" },
      { name: "MUX_DISPLAY_NAME", value: "My Session" },
      { name: "MUX_AGENT_KIND", value: "grok" },
      { name: "MUX_SOCKETS_DIR", value: "/run/mux" },
    ],
  }])
})
