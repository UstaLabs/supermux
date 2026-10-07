/**
 * C3b: mux-shim (and mux-rpc) as host MCP servers inside the broker. The agent reaches them
 * through the core's stdio bridge; each tool call runs the broker's shared handler in-process
 * (SessionManager.outbound / orchestration, the same functions the external shim's socket path
 * calls), with the caller identified by the core's ctx.sessionId (bridge token: HMAC over
 * session + server), never by anything the agent says.
 *
 * Same server names as the external shim, so tool ids stay mcp__mux-shim__reply /
 * mcp__mux-rpc__resolve. Same tool list (listTools: Claude's full reply vs the streamed agents'
 * files-only reply), same JSON Schemas (fromJsonSchema: no zod in the broker), same result
 * mapping (toolResult). The PA gate, de-dup and error texts live in the shared handlers.
 *
 * Cancellation: the handler is NOT given ctx.signal. An agent's notifications/cancelled or a
 * session interrupt makes the core answer "Cancelled" while the broker handler finishes, so a
 * mutation (spawn, kill, expose) is applied completely or not at all; see
 * SessionManager.orchestration for the retry de-dup.
 */
import { mcpServer, McpServer, fromJsonSchema, type ConnectionContext } from "../../../packages/supermux-core/src/mcp/index.js"
import { listTools, toolResult, toolRoute, type ToolCallResult } from "../../shim/tools"
import { AGENT_KINDS, AgentKind } from "../../shared/agents"
import type { ToolOperation } from "../../shared/socket-frames"
import { makeLogger } from "../../shared/log"

const log = makeLogger("mux-tools")

export type MuxToolHandler = {
  outbound(sessionId: string, op: ToolOperation): Promise<ToolCallResult>
  orchestration(sessionId: string, op: ToolOperation): Promise<ToolCallResult>
}

let handler: MuxToolHandler | undefined

/** Main binds the SessionManager's shared handlers at boot; returns an unbind (tests). */
export function bindMuxTools(next: MuxToolHandler): () => void {
  handler = next
  return () => { if (handler === next) handler = undefined }
}

function agentKind(agent: string): AgentKind {
  return (AGENT_KINDS as readonly string[]).includes(agent) ? agent as AgentKind : AgentKind.Claude
}

/** The SDK server for one (session, connection). */
export function buildMuxToolsServer(ctx: ConnectionContext, rpcOnly: boolean): McpServer {
  const name = rpcOnly ? "mux-rpc" : "mux-shim"
  const server = new McpServer({ name, version: "0.0.1" }, { capabilities: { tools: {} } })
  for (const tool of listTools(agentKind(ctx.agent), rpcOnly)) {
    const route = toolRoute(tool.name, rpcOnly)!
    server.registerTool(tool.name, { description: tool.description, inputSchema: fromJsonSchema(tool.inputSchema as never) }, (async (args: Record<string, unknown> | undefined) => {
      const bound = handler
      if (!bound) return toolResult(tool.name, { ok: false, error: "supermux broker is not ready" }, rpcOnly)
      const op: ToolOperation = { name: route.op, args: args ?? {} }
      let result: ToolCallResult
      try {
        result = route.kind === "outbound" ? await bound.outbound(ctx.sessionId, op) : await bound.orchestration(ctx.sessionId, op)
      } catch (err) {
        // The socket path's wording for a handler that threw.
        log.warn("host_call_handler_threw", { session_id: ctx.sessionId, op_name: op.name, err: err instanceof Error ? err.message : String(err) })
        result = { ok: false, error: `handler threw: ${err instanceof Error ? err.message : String(err)}` }
      }
      return toolResult(tool.name, result, rpcOnly)
    }) as never)
  }
  return server
}

export const muxShimHostServer = mcpServer({ name: "mux-shim", create: ctx => buildMuxToolsServer(ctx, false) })
export const muxRpcHostServer = mcpServer({ name: "mux-rpc", create: ctx => buildMuxToolsServer(ctx, true) })

/** Registered with every agent's host in BOTH modes, so records that name them always resume (rollback included). */
export const MUX_HOST_SERVERS = [muxShimHostServer, muxRpcHostServer]
