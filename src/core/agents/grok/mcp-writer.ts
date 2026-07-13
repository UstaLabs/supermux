export function buildGrokMcpServers(opts: {
  shimCommand: string
  shimArgs: string[]
  sessionId: string
  sessionName: string
  socketsDir: string
}): unknown[] {
  // ACP `session/new` mcpServers entry shape (env is an array of {name,value},
  // matching the `_x.ai/mcp/servers_updated` frames observed live).
  return [{
    name: "mux-shim",
    type: "stdio",
    command: opts.shimCommand,
    args: opts.shimArgs,
    env: [
      { name: "MUX_SESSION_ID", value: opts.sessionId },
      { name: "MUX_DISPLAY_NAME", value: opts.sessionName },
      { name: "MUX_AGENT_KIND", value: "grok" },
      { name: "MUX_SOCKETS_DIR", value: opts.socketsDir },
    ],
  }]
}
