import { AGENT_KINDS, AgentKind } from "../shared/agents"
import type { ToolOperation } from "../shared/socket-frames"
export type { AgentKind } from "../shared/agents"

const OUTBOUND_TOOLS = [
  {
    name: "reply",
    description: "Send a reply to the user via the active channel (Telegram or web). Args: chat_id, text, optional reply_to/files/keyboard/format.",
    inputSchema: {
      type: "object",
      properties: {
        chat_id: { type: "string" },
        text: { type: "string" },
        reply_to: { type: "string" },
        files: { type: "array", items: { type: "string" } },
        keyboard: { type: "array", items: { type: "string" } },
        format: { type: "string", enum: ["text", "markdownv2"] },
      },
      required: ["chat_id", "text"],
    },
  },
  {
    name: "react",
    description: "Add an emoji reaction to a user message. Telegram only — the web channel does not support reactions.",
    inputSchema: {
      type: "object",
      properties: { chat_id: { type: "string" }, message_id: { type: "string" }, emoji: { type: "string" } },
      required: ["chat_id", "message_id", "emoji"],
    },
  },
  {
    name: "edit_message",
    description: "Edit a message you previously sent (no push notification). Telegram only — the web channel does not support edits.",
    inputSchema: {
      type: "object",
      properties: { chat_id: { type: "string" }, message_id: { type: "string" }, text: { type: "string" }, format: { type: "string", enum: ["text", "markdownv2"] } },
      required: ["chat_id", "message_id", "text"],
    },
  },
  {
    name: "download_attachment",
    description: "Download a file the user sent, by file_id; returns the local path.",
    inputSchema: {
      type: "object",
      properties: { file_id: { type: "string" } },
      required: ["file_id"],
    },
  },
]

const ORCHESTRATION_TOOLS = [
  { name: "spawn_session",  description: "Spawn a new agent session. agent defaults to 'claude'; pass 'codex', 'cursor', 'opencode', or 'grok' for those CLIs. Pass chat_id to auto-switch that chat to the new session.", inputSchema: { type: "object", properties: { workdir: { type: "string" }, name: { type: "string" }, agent: { type: "string", enum: AGENT_KINDS }, chat_id: { type: "string" } }, required: ["workdir"] } },
  { name: "kill_session",   description: "Kill a session by name.",                          inputSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "rename_session", description: "Rename your OWN session (the one you are running in). You may rename only once, so pick a name that stays accurate for the whole session. Use a short, natural, human-readable display title with spaces and normal capitalization (for example, 'Fix Session Renaming'), not a lowercase slug. Names are preserved as entered after trimming; duplicate names are rejected.", inputSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "mute_session",   description: "Mute or unmute a session.",                        inputSchema: { type: "object", properties: { name: { type: "string" }, muted: { type: "boolean" } }, required: ["name", "muted"] } },
  { name: "list_sessions",  description: "List all sessions.",                               inputSchema: { type: "object", properties: {} } },
  { name: "set_active",     description: "Set active session for a chat.",                   inputSchema: { type: "object", properties: { chat_id: { type: "string" }, name: { type: "string" } }, required: ["chat_id", "name"] } },
  { name: "get_active",     description: "Get active session for a chat.",                   inputSchema: { type: "object", properties: { chat_id: { type: "string" } }, required: ["chat_id"] } },
  { name: "expose_port", description: "Expose a local port to the web via reverse proxy. Returns the public URL. With a wildcard base domain configured (MUX_PROXY_BASE_DOMAIN), the app gets its own subdomain. Otherwise it is served under a sub-path (https://<broker>/p/<slug>/): the proxy strips the prefix, so this works for apps that use relative URLs, but apps that assume the site root (absolute /asset paths, fetch('/api'), root WebSocket, or a framework dev server such as Vite/Next) will not work under a sub-path — use a wildcard base domain (subdomain mode) for those. If domain is omitted a random slug is generated. Set public=true to skip device-pairing auth (anyone with the URL can access).", inputSchema: { type: "object", properties: { port: { type: "number", description: "Local port to expose (1-65535)" }, domain: { type: "string", description: "Optional slug (alphanumeric + hyphens, max 63 chars) used as the subdomain or sub-path segment" }, public: { type: "boolean", description: "When true, the proxy is reachable without pairing (default false)" } }, required: ["port"] } },
  { name: "unexpose_port", description: "Remove a previously exposed proxy by domain name.", inputSchema: { type: "object", properties: { domain: { type: "string", description: "Subdomain to remove" } }, required: ["domain"] } },
  { name: "set_proxy_public", description: "Toggle whether a proxy requires device pairing. public=true means anyone with the URL can access.", inputSchema: { type: "object", properties: { domain: { type: "string", description: "Subdomain to update" }, public: { type: "boolean", description: "true = open access, false = paired devices only" } }, required: ["domain", "public"] } },
  { name: "list_devices", description: "List attached Android adb devices/emulators available to stream via scrcpy (provider 'scrcpy').", inputSchema: { type: "object", properties: {}, required: [] } },
  { name: "start_display", description: "Start a streamable host display and return its stream id. On Linux this provisions a virtual Xvfb display; on macOS it streams the real screen (enable Screen Sharing). It does NOT launch apps — run apps with DISPLAY=<display> (Linux) to make them appear in the stream. View/control it from the supermux web app.", inputSchema: { type: "object", properties: { provider: { type: "string", description: "Optional: 'linux-xvfb' or 'macos-screen'. Defaults to the host platform." }, device: { type: "string", description: "adb serial for provider 'scrcpy' (e.g. emulator-5554)" }, width: { type: "number", description: "Virtual display width (Linux only, default 1280)" }, height: { type: "number", description: "Virtual display height (Linux only, default 800)" } }, required: [] } },
  { name: "stop_display", description: "Stop a display stream by id and tear down its virtual display/VNC server.", inputSchema: { type: "object", properties: { id: { type: "string", description: "Stream id returned by start_display" } }, required: ["id"] } },
  {
    name: "memory_search",
    description: "Search the shared knowledge base (~/.mux domains + digests) for facts/gotchas. Returns ranked sections with file path, heading and a snippet.",
    inputSchema: {
      type: "object",
      properties: { query: { type: "string" }, limit: { type: "number" } },
      required: ["query"],
    },
  },
  {
    name: "find_sessions",
    description: "Find past agent sessions by what was discussed in them. Returns ranked sessions with id, name, workdir, agent, dates and a transcript_path. Filters: project (workdir), since (ISO), agent.",
    inputSchema: {
      type: "object",
      properties: { query: { type: "string" }, project: { type: "string" }, since: { type: "string" }, agent: { type: "string" }, limit: { type: "number" } },
      required: ["query"],
    },
  },
  {
    name: "read_session",
    description: "Read a past session's full transcript by its session id (from find_sessions). Renders user/assistant text plus tool calls. Options: include_tool_calls (default true), grep (filter lines), to inspect how a session did something.",
    inputSchema: {
      type: "object",
      properties: { session_id: { type: "string" }, include_tool_calls: { type: "boolean" }, grep: { type: "string" } },
      required: ["session_id"],
    },
  },
]

export const RPC_TOOLS = [
  {
    name: "resolve",
    description: "Complete the current task. Pass the result as `data` (any JSON). Call this exactly once.",
    inputSchema: { type: "object", properties: {
      request_id: { type: "string", description: "Echo the request_id from the task prompt verbatim." },
      data: { type: "object", description: "The task result as a JSON object." },
    }, required: ["request_id", "data"] },
  },
  {
    name: "reject",
    description: "Fail the current task with a short reason (e.g. input was empty/unintelligible).",
    inputSchema: { type: "object", properties: {
      request_id: { type: "string", description: "Echo the request_id from the task prompt verbatim." },
      error: { type: "string", description: "Short failure reason." },
    }, required: ["request_id", "error"] },
  },
]
const RPC_NAMES = new Set(RPC_TOOLS.map(t => t.name))
const RPC_OP = { resolve: "rpc_resolve", reject: "rpc_reject" } as const

const REPLY_FOR_STREAMED_AGENTS =
  "Deliver a file attachment to the user (image, video, recording, etc.). " +
  "REQUIRED: files[] with at least one local filesystem path; text is an optional caption (may be empty). " +
  "Use ONLY when sending files — your normal assistant output is relayed automatically; " +
  "do not call reply for plain text (the broker rejects text-only reply from codex/cursor). " +
  "Args: chat_id, text, files[]."

const ALL = [...OUTBOUND_TOOLS, ...ORCHESTRATION_TOOLS]
const OUTBOUND_NAMES = new Set(OUTBOUND_TOOLS.map(t => t.name))

type ToolCallResult = { ok: boolean; value?: unknown; error?: string }
type ToolCaller = {
  callOutbound: (op: ToolOperation) => Promise<ToolCallResult>
  callOrchestration: (op: ToolOperation) => Promise<ToolCallResult>
}

export function listTools(agentKind: AgentKind = AgentKind.Claude, rpcOnly = false) {
  if (rpcOnly) return RPC_TOOLS
  if (agentKind === AgentKind.Claude) return ALL
  return ALL.map((t) =>
    t.name === "reply" ? { ...t, description: REPLY_FOR_STREAMED_AGENTS } : t,
  )
}

export async function callTool(params: { name: string; arguments?: Record<string, unknown> }, shim: ToolCaller, agentKind: AgentKind = AgentKind.Claude, rpcOnly = false): Promise<{ isError?: boolean; content: Array<{ type: "text"; text: string }> }> {
  if (rpcOnly) {
    if (!RPC_NAMES.has(params.name)) {
      return { isError: true, content: [{ type: "text", text: `tool ${params.name} not available on rpc worker` }] }
    }
    const opName = RPC_OP[params.name as keyof typeof RPC_OP]
    const result = await shim.callOrchestration({ name: opName, args: params.arguments ?? {} })
    if (!result.ok) return { isError: true, content: [{ type: "text", text: result.error ?? "unknown error" }] }
    return { content: [{ type: "text", text: JSON.stringify(result.value ?? "ok") }] }
  }
  const allowed = new Set(listTools(agentKind).map(t => t.name))
  if (!allowed.has(params.name)) {
    return { isError: true, content: [{ type: "text", text: `tool ${params.name} not available for agent kind ${agentKind}` }] }
  }
  const args = params.arguments ?? {}
  const isOutbound = OUTBOUND_NAMES.has(params.name)
  const result = isOutbound
    ? await shim.callOutbound({ name: params.name, args })
    : await shim.callOrchestration({ name: params.name, args })

  if (!result.ok) {
    return { isError: true, content: [{ type: "text", text: result.error ?? "unknown error" }] }
  }
  if (params.name === "reply") {
    const value = result.value
    const id = value && typeof value === "object" && "message_id" in value ? value.message_id : undefined
    return { content: [{ type: "text", text: id != null ? `sent (id: ${id})` : "sent" }] }
  }
  return { content: [{ type: "text", text: JSON.stringify(result.value ?? "ok") }] }
}
