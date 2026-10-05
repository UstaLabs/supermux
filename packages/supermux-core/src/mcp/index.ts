/** `supermux-core/mcp`: host MCP servers whose tools are TypeScript functions (see API.md "Host MCP servers"). */
export { mcpServer, tool, HostMcpServer, isHostMcpServer, toCallToolResult } from "./server.js"
export type { ToolContext, ConnectionContext, ToolDefinition, ToolResult, HostTool, McpServerOptions, ToolChanges, ToolChange } from "./server.js"
export { bridgeEntry, socketPath, BRIDGE_ENV } from "./host.js"
export type { ToolEvent } from "./host.js"
/**
 * The SDK pieces a `create` server needs, re-exported so a host builds one without depending on
 * `@modelcontextprotocol/server` (or zod 4) itself: `fromJsonSchema` turns a plain JSON Schema into
 * the SDK's input schema (validated with the SDK's own validator).
 */
export { McpServer, fromJsonSchema } from "@modelcontextprotocol/server"
export type { CallToolResult } from "@modelcontextprotocol/server"
