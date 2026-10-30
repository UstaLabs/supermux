// The EXTERNAL mux-shim: a stdio MCP server that forwards tool calls to the broker over the
// session socket. Used in muxShim "external" mode (C3a launch), and for Cursor in every mode; in
// "host" mode the broker serves the same tools itself (src/core/mux-tools/server.ts).
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { connectShim, type ShimRegistration } from "./socket-client"
import { callTool, shimTools } from "./tools"
import type { AgentKind } from "./tools"
import type { ShimTool } from "../shared/socket-frames"
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

const SERVER = RPC_ONLY ? "mux-rpc" : (CHANNEL_ONLY ? "mux-channel" : "mux-shim")

/**
 * The tools to serve: ZERO for the channel-only instance (the tools instance is the sole provider);
 * else the broker's list from its register reply, or this build's own from a broker that predates it.
 */
function servedTools(registration: ShimRegistration): ShimTool[] {
  if (CHANNEL_ONLY) return []
  return registration.tools ?? shimTools(SERVER, { councilMember: registration.councilMember }) ?? []
}

/** What the agent sees of a list: the MCP definitions, not the routes. */
const definitions = (tools: ShimTool[]) => tools.map(({ route: _, ...tool }) => tool)

async function main() {
  // listChanged: the agent re-lists when a broker restart or update brings another tool list (an
  // agent like Claude Code only listens for the notification from a server that declares it).
  const mcp = new Server({ name: SERVER, version: "0.0.1" }, { capabilities: { tools: { listChanged: true } } })
  let tools: ShimTool[] = []
  /** The agent has a list (it listed at least once), so it must hear about a change. */
  let listed = false

  const shim = await connectShim({
    socketsDir: SOCKETS_DIR,
    socketPath: socketPathForSession(SESSION_ID),
    sessionId: SESSION_ID,
    workdir: WORKDIR,
    pid: process.pid,
    requestedName: process.env.MUX_DISPLAY_NAME,
    displayName: process.env.MUX_DISPLAY_NAME,
    agentSessionId: CLAUDE_SESSION_ID,
    server: SERVER,
    onReregistered: (registration) => {
      const next = servedTools(registration)
      const changed = JSON.stringify(definitions(next)) !== JSON.stringify(definitions(tools))
      tools = next
      if (!changed) return
      log.info("tools_changed", { count: tools.length, notify: listed })
      if (listed) mcp.sendToolListChanged().catch(err => log.warn("tools_list_changed_failed", { err: String(err) }))
    },
  })
  tools = servedTools(shim)

  log.info("registered", { name: shim.assignedName, agent_kind: AGENT_KIND, tools: tools.length, from_broker: shim.tools !== undefined })

  mcp.setRequestHandler(ListToolsRequestSchema, () => {
    listed = true
    return { tools: definitions(tools) }
  })
  mcp.setRequestHandler(CallToolRequestSchema, async (req) => callTool(req.params, shim, AGENT_KIND, RPC_ONLY, tools))

  await mcp.connect(new StdioServerTransport())
}

main().catch(err => {
  log.error("fatal", { err: String(err) })
  process.exit(1)
})
