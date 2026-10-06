/** Brand of `mcpServer()` objects, apart from server.ts so the core can recognise one without loading the MCP SDK. */
import type { HostMcpServer } from "./server.js"

export const HOST_SERVER_BRAND = Symbol.for("supermux-core.mcp.server")

export const isHostMcpServer = (value: unknown): value is HostMcpServer =>
  !!value && typeof value === "object" && (value as Record<symbol, unknown>)[HOST_SERVER_BRAND] === true
