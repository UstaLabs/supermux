import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { connectShim } from "./socket-client"
import { listTools, callTool } from "./tools"
import type { AgentKind } from "./tools"
import { SOCKETS_DIR } from "../shared/paths"
import { randomBytes } from "crypto"
import { makeLogger } from "../shared/log"
const log = makeLogger("shim")

const SESSION_ID = process.env.MUX_SESSION_ID ?? randomBytes(8).toString("hex")
const CLAUDE_SESSION_ID = process.env.CLAUDE_SESSION_ID ?? undefined
const WORKDIR = process.cwd()
const AGENT_KIND: AgentKind =
  (process.env.MUX_AGENT_KIND as AgentKind | undefined) ?? "claude"
// Claude loads this shim twice per session (tools via mcpServers + inbound channel
// via --dangerously-load-development-channels). If BOTH advertise tools, one agent
// tool-call is dispatched to both → spawn_session runs twice. So the channel
// instance runs CHANNEL-ONLY: zero tools, just the inbound pipe. Tools come solely
// from the tools instance.
const CHANNEL_ONLY = process.env.MUX_CHANNEL_ONLY === "1"

async function main() {
  const mcp = new Server(
    { name: CHANNEL_ONLY ? "mux-channel" : "mux-shim", version: "0.0.1" },
    {
      capabilities: {
        tools: {},
        // Required for Claude Code to actually subscribe to
        // `notifications/claude/channel` from this server. Without this,
        // claude treats the server as tools-only and drops inbound channel
        // notifications on the floor.
        experimental: { "claude/channel": {} },
      },
    },
  )

  let mcpReady = false
  let mcpReadyResolve: () => void
  const mcpReadyPromise = new Promise<void>(r => { mcpReadyResolve = r })
  const pendingInbound: Array<{ content: string; meta: Record<string, string> }> = []

  const shim = await connectShim({
    socketsDir: SOCKETS_DIR,
    sessionId: SESSION_ID,
    workdir: WORKDIR,
    pid: process.pid,
    requestedName: process.env.MUX_DISPLAY_NAME,
    displayName: process.env.MUX_DISPLAY_NAME,
    agentSessionId: CLAUDE_SESSION_ID,
    onInbound: (payload) => {
      log.debug("on_inbound.firing", { content: payload.content.slice(0, 80), mcpReady })
      if (mcpReady) {
        void (mcp.notification as any)({
          method: "notifications/claude/channel",
          params: { content: payload.content, meta: payload.meta },
        }).then(() => log.debug("on_inbound.dispatched"))
          .catch((err: unknown) => log.error("on_inbound.failed", { err: String(err) }))
      } else {
        log.info("on_inbound.deferred", {
          channel_only: CHANNEL_ONLY,
          preview: payload.content.slice(0, 60),
          pending: pendingInbound.length + 1,
        })
        pendingInbound.push(payload)
      }
    },
  })

  log.info("registered", { name: shim.assignedName, agent_kind: AGENT_KIND })

  mcp.setRequestHandler(ListToolsRequestSchema, () => {
    if (!mcpReady) {
      mcpReady = true
      mcpReadyResolve!()
      for (const p of pendingInbound.splice(0)) {
        log.info("on_inbound.deferred_flush", { content: p.content.slice(0, 80) })
        void (mcp.notification as any)({
          method: "notifications/claude/channel",
          params: { content: p.content, meta: p.meta },
        }).catch((err: unknown) => log.error("on_inbound.deferred_failed", { err: String(err) }))
      }
    }
    // Channel-only instance advertises ZERO tools (the tools instance is the sole
    // provider). The tools capability is still declared so Claude calls ListTools
    // here and mcpReady fires to flush pending inbound — it just gets an empty list.
    return { tools: CHANNEL_ONLY ? [] : listTools(AGENT_KIND) }
  })
  mcp.setRequestHandler(CallToolRequestSchema, async (req) => callTool(req.params, shim, AGENT_KIND))

  await mcp.connect(new StdioServerTransport())
  // Fallback: on --resume, Claude may skip ListTools (tools cached), so
  // mcpReady never fires from the handler. Flush after a short grace period.
  if (!mcpReady) setTimeout(() => {
    if (mcpReady) return
    mcpReady = true
    mcpReadyResolve!()
    for (const p of pendingInbound.splice(0)) {
      log.info("on_inbound.fallback_flush", { content: p.content.slice(0, 80) })
      void (mcp.notification as any)({
        method: "notifications/claude/channel",
        params: { content: p.content, meta: p.meta },
      }).catch((err: unknown) => log.error("on_inbound.fallback_failed", { err: String(err) }))
    }
  }, 2000)
}

main().catch(err => {
  log.error("fatal", { err: String(err) })
  process.exit(1)
})
