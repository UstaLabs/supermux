import type { McpServerSpec } from "../../../packages/supermux-core/src/environment/index.js"
import type { HostMcpServer } from "../../../packages/supermux-core/src/mcp/index.js"
import { SOCKETS_DIR } from "../../shared/paths"
import { shimSpawnSpec } from "../session-manager/shim-spawn"
import { muxShimModeFor } from "../mux-tools/mode"
import { muxShimHostServer } from "../mux-tools/server"

/** Broker-owned mux-shim MCP content (the external shim). The library only knows how to render it. */
export function muxShimServer(kind: string, sessionId: string, sessionName: string): McpServerSpec {
  const { shimCommand, shimArgs } = shimSpawnSpec()
  return {
    name: "mux-shim",
    command: shimCommand,
    args: shimArgs,
    env: {
      MUX_SESSION_ID: sessionId,
      MUX_DISPLAY_NAME: sessionName,
      MUX_AGENT_KIND: kind,
      MUX_SOCKETS_DIR: SOCKETS_DIR,
    },
  }
}

/**
 * The mux-shim entry of a session's context: the external shim ("external", and Cursor always) or
 * the broker's host server ("host", C3b). See mux-tools/mode.ts.
 */
export function muxShimContextServer(kind: string, sessionId: string, sessionName: string): McpServerSpec | HostMcpServer {
  return muxShimModeFor(kind) === "host" ? muxShimHostServer : muxShimServer(kind, sessionId, sessionName)
}
