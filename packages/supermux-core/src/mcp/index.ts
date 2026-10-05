/** `supermux-core/mcp`: host MCP servers whose tools are TypeScript functions (see API.md "Host MCP servers"). */
export { mcpServer, tool, HostMcpServer, isHostMcpServer, toCallToolResult } from "./server.js"
export type { ToolContext, ConnectionContext, ToolDefinition, ToolResult, HostTool, McpServerOptions, ToolChanges, ToolChange } from "./server.js"
export { bridgeEntry, socketPath, BRIDGE_ENV } from "./host.js"
export type { ToolEvent } from "./host.js"
