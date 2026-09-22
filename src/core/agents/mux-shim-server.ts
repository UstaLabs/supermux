import type { McpServerSpec } from "../../../packages/supermux-core/src/environment/index.js"
import { SOCKETS_DIR } from "../../shared/paths"
import { shimSpawnSpec } from "../session-manager/shim-spawn"

/** Broker-owned mux-shim MCP content. The library only knows how to render it. */
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
