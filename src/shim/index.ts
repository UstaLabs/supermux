import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { connectShim } from "./socket-client"
import { listTools, callTool } from "./tools"
import type { AgentKind } from "./tools"
import { createInboundGate } from "./inbound-gate"
import { SOCKETS_DIR } from "../shared/paths"
import { randomBytes } from "crypto"
import { makeLogger } from "../shared/log"
const log = makeLogger("shim")

// How long after the MCP initialize handshake completes before flushing
// deferred inbound. Claude wires its channel-notification handler shortly
// AFTER initialize ("Channel notifications registered", observed 0.6–1.0s
// later); a notification sent inside that window is still dropped. Tunable
// for slow/loaded hosts; sessions past the window never wait.
const CHANNEL_INJECT_GRACE_MS = Number(process.env.MUX_CHANNEL_INJECT_GRACE_MS ?? 2500)
// Last-resort flush if the client NEVER completes initialize (hung/dead
// Claude). If it is dead the write is lost either way; a pathologically slow
// boot still gets the message. Kept generous — the old 2s version of this
// fallback is what raced Claude's startup and silently ate first messages.
const CHANNEL_INIT_TIMEOUT_MS = Number(process.env.MUX_CHANNEL_INIT_TIMEOUT_MS ?? 30_000)

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
const RPC_ONLY = process.env.MUX_RPC_ONLY === "1"

async function main() {
  const mcp = new Server(
    { name: RPC_ONLY ? "mux-rpc" : (CHANNEL_ONLY ? "mux-channel" : "mux-shim"), version: "0.0.1" },
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

  // Claude silently drops channel notifications sent before it finished the
  // MCP initialize handshake + wired its channel handler. The gate buffers
  // inbound until `oninitialized` + grace, then passes through. See
  // inbound-gate.ts for the full story (this replaced two wall-clock flush
  // timers that raced Claude's startup and lost fresh sessions' first message).
  const gate = createInboundGate({
    graceMs: CHANNEL_INJECT_GRACE_MS,
    initTimeoutMs: CHANNEL_INIT_TIMEOUT_MS,
    notify: (payload, trigger) => {
      if (trigger === "init_timeout") {
        log.error("on_inbound.init_timeout_flush", {
          preview: payload.content.slice(0, 60),
          waited_ms: CHANNEL_INIT_TIMEOUT_MS,
        })
      } else if (trigger === "initialized_grace") {
        log.info("on_inbound.deferred_flush", { content: payload.content.slice(0, 80) })
      }
      void (mcp.notification as any)({
        method: "notifications/claude/channel",
        params: { content: payload.content, meta: payload.meta },
      }).then(() => log.debug("on_inbound.dispatched", { trigger }))
        .catch((err: unknown) => log.error("on_inbound.failed", { trigger, err: String(err) }))
    },
  })
  mcp.oninitialized = () => {
    log.info("mcp_initialized", { pending: gate.pendingCount() })
    gate.initialized()
  }

  const shim = await connectShim({
    socketsDir: SOCKETS_DIR,
    sessionId: SESSION_ID,
    workdir: WORKDIR,
    pid: process.pid,
    requestedName: process.env.MUX_DISPLAY_NAME,
    displayName: process.env.MUX_DISPLAY_NAME,
    agentSessionId: CLAUDE_SESSION_ID,
    onInbound: (payload) => {
      log.debug("on_inbound.firing", { content: payload.content.slice(0, 80), open: gate.isOpen() })
      if (!gate.isOpen()) {
        log.info("on_inbound.deferred", {
          channel_only: CHANNEL_ONLY,
          preview: payload.content.slice(0, 60),
          pending: gate.pendingCount() + 1,
        })
      }
      gate.inbound(payload)
    },
  })

  log.info("registered", { name: shim.assignedName, agent_kind: AGENT_KIND })

  mcp.setRequestHandler(ListToolsRequestSchema, () => {
    // Channel-only instance advertises ZERO tools (the tools instance is the sole
    // provider). The tools capability is still declared so Claude still calls
    // ListTools here — it just gets an empty list.
    return { tools: CHANNEL_ONLY ? [] : listTools(AGENT_KIND, RPC_ONLY) }
  })
  mcp.setRequestHandler(CallToolRequestSchema, async (req) => callTool(req.params, shim, AGENT_KIND, RPC_ONLY))

  await mcp.connect(new StdioServerTransport())
}

main().catch(err => {
  log.error("fatal", { err: String(err) })
  process.exit(1)
})
