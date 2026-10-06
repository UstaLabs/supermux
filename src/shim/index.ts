// The EXTERNAL mux-shim: a stdio MCP server that forwards tool calls to the broker over the
// session socket. Used in muxShim "external" mode (C3a launch), and for Cursor in every mode; in
// "host" mode the broker serves the same tools itself (src/core/mux-tools/server.ts).
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { connectShim } from "./socket-client"
import { listTools, callTool } from "./tools"
import type { AgentKind } from "./tools"
import { SOCKETS_DIR, socketPathForSession } from "../shared/paths"
import { randomBytes } from "crypto"
import { makeLogger } from "../shared/log"
const log = makeLogger("shim")

const SESSION_ID = process.env.MUX_SESSION_ID ?? randomBytes(8).toString("hex")
const CLAUDE_SESSION_ID = process.env.CLAUDE_SESSION_ID ?? undefined
const WORKDIR = process.cwd()
const AGENT_KIND: AgentKind =
  (process.env.MUX_AGENT_KIND as AgentKind | undefined) ?? "claude"
// `mux-channel` in ~/.claude.json (external mode) runs this code with MUX_CHANNEL_ONLY=1. It used
// to carry inbound turns to tmux-era Claude (a development channel); core sessions get their input
// through the driver, so it now only has to stay HARMLESS: zero tools, so a Claude that still
// starts it never sees each tool twice (an agent tool call would run on both servers).
const CHANNEL_ONLY = process.env.MUX_CHANNEL_ONLY === "1"
const RPC_ONLY = process.env.MUX_RPC_ONLY === "1"

async function main() {
  const mcp = new Server(
    { name: RPC_ONLY ? "mux-rpc" : (CHANNEL_ONLY ? "mux-channel" : "mux-shim"), version: "0.0.1" },
    { capabilities: { tools: {} } },
  )

  const shim = await connectShim({
    socketsDir: SOCKETS_DIR,
    socketPath: socketPathForSession(SESSION_ID),
    sessionId: SESSION_ID,
    workdir: WORKDIR,
    pid: process.pid,
    requestedName: process.env.MUX_DISPLAY_NAME,
    displayName: process.env.MUX_DISPLAY_NAME,
    agentSessionId: CLAUDE_SESSION_ID,
  })

  log.info("registered", { name: shim.assignedName, agent_kind: AGENT_KIND })

  mcp.setRequestHandler(ListToolsRequestSchema, () => {
    // Channel-only instance advertises ZERO tools (the tools instance is the sole provider).
    return { tools: CHANNEL_ONLY ? [] : listTools(AGENT_KIND, RPC_ONLY) }
  })
  mcp.setRequestHandler(CallToolRequestSchema, async (req) => callTool(req.params, shim, AGENT_KIND, RPC_ONLY))

  await mcp.connect(new StdioServerTransport())
}

main().catch(err => {
  log.error("fatal", { err: String(err) })
  process.exit(1)
})
