import { AGENT_KINDS, AgentKind } from "../shared/agents"
import type { ToolOperation } from "../shared/socket-frames"
export type { AgentKind } from "../shared/agents"

/** Claude Code puts the agent's tool_use id in an MCP request's `_meta` under this key (seen in 2.1.308; not a documented contract). */
export const TOOL_USE_ID_META = "claudecode/toolUseId"

/** The agent-side id of a tool call, when its MCP request carries one (Claude only), so a delegate card can sit on that call. */
export function toolCallIdOf(meta: unknown): string | undefined {
  const id = meta && typeof meta === "object" ? (meta as Record<string, unknown>)[TOOL_USE_ID_META] : undefined
  return typeof id === "string" && id ? id : undefined
}

const OUTBOUND_TOOLS = [
  {
    name: "attach",
    // Files only: an agent's assistant text is streamed to the user as its reply, so there is no
    // text-sending tool. No chat_id: the broker routes to the chat this session last heard from
    // (core/routing/reply-target).
    description: "Send file attachments (image, video, recording, document, etc.) to the user. " +
      "REQUIRED: files[] with at least one local filesystem path; text is an optional caption. " +
      "Your normal assistant output is already relayed to the user — do not use this for plain text. " +
      "The broker picks the destination — do not name one.",
    inputSchema: {
      type: "object",
      properties: {
        files: { type: "array", items: { type: "string" } },
        text: { type: "string" },
      },
      required: ["files"],
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
    description: "Download a file the user sent, by file_id; returns the local path. A message can carry several files: the `attachments` meta lists every file_id (attachment_file_id is only the first).",
    inputSchema: {
      type: "object",
      properties: { file_id: { type: "string" } },
      required: ["file_id"],
    },
  },
]

const ORCHESTRATION_TOOLS = [
  { name: "spawn_session",  description: "Spawn a new agent session. agent defaults to 'claude'; pass 'codex', 'cursor', 'opencode', 'grok', or 'antigravity' for those CLIs. Pass chat_id to auto-switch that chat to the new session. It runs on the agent's account with the most room left.", inputSchema: { type: "object", properties: { workdir: { type: "string" }, name: { type: "string" }, agent: { type: "string", enum: AGENT_KINDS }, chat_id: { type: "string" } }, required: ["workdir"] } },
  { name: "kill_session",   description: "Kill a session by id: its agent is stopped and the session is archived. Its child sessions are killed too.", inputSchema: { type: "object", properties: { id: { type: "string", description: "Session id" } }, required: ["id"] } },
  { name: "rename_session", description: "Rename your OWN session (the one you are running in). You may rename only once, so pick a name that stays accurate for the whole session. Use a short, natural, human-readable display title with spaces and normal capitalization (for example, 'Fix Session Renaming'), not a lowercase slug. Names are preserved as entered after trimming; duplicate names are rejected.", inputSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "mute_session",   description: "Mute or unmute a session.",                        inputSchema: { type: "object", properties: { name: { type: "string" }, muted: { type: "boolean" } }, required: ["name", "muted"] } },
  { name: "list_sessions",  description: "List every session: id, name, agent, model, workdir, mute, state (working, idle, suspended or crashed), its parent and its children (session ids).", inputSchema: { type: "object", properties: {} } },
  {
    name: "list_models",
    description: "List every agent kind: whether it is ready (installed, and signed in where it needs a sign-in), its access levels " +
      "(permission modes, each with a level: full, edit, ask or read-only) and its models with their efforts " +
      "(reasoning levels). Also returns your own agent, model, effort and access. Pass agent to list only that kind.",
    inputSchema: { type: "object", properties: { agent: { type: "string", enum: AGENT_KINDS } } },
  },
  {
    name: "delegate",
    description: "Start a child session on any agent and model with a task. It is a session of its own, hidden from " +
      "the user's session list and shown in your subagents; its output stays in its own chat. It can message you " +
      "with message_session, and supermux messages you if it crashes, someone else kills it, or a turn of it ends in an error. access: one of that agent's " +
      "access levels from list_models. checkout: \"shared\" works in your folder; \"worktree\" makes a new git " +
      "worktree branched from your current branch, and fails if it can't. title: the child's session name (from " +
      "the task when omitted). It runs on the agent's account with the most room left.",
    inputSchema: {
      type: "object",
      properties: {
        task: { type: "string" },
        agent: { type: "string", enum: AGENT_KINDS },
        model: { type: "string" },
        effort: { type: "string" },
        access: { type: "string" },
        checkout: { type: "string", enum: ["shared", "worktree"] },
        title: { type: "string" },
      },
      required: ["task", "agent", "access", "checkout"],
    },
  },
  {
    name: "council",
    description: "Put one question to several agents at once. Each member is a session of its own (hidden from the user's " +
      "session list) that gets the question and answers with council_report; every answer is forwarded to you " +
      "as it arrives, with the member's letter and name. Start a follow-up round with council_round: the members then " +
      "see each other's answers by letter only. members: [{ agent, model?, effort?, access }], access being one of " +
      "that agent's access levels from list_models. checkout applies to every member: \"shared\" works in your folder; " +
      "\"worktree\" gives each member a new git worktree from your current branch, and starts no member if one can't be made. " +
      "Each member runs on its agent's account with the most room left. You are told when a member crashes, is killed " +
      "by someone else, or a turn of it ends in an error.",
    inputSchema: {
      type: "object",
      properties: {
        question: { type: "string" },
        members: {
          type: "array",
          minItems: 1,
          items: {
            type: "object",
            properties: { agent: { type: "string", enum: AGENT_KINDS }, model: { type: "string" }, effort: { type: "string" }, access: { type: "string" } },
            required: ["agent", "access"],
          },
        },
        checkout: { type: "string", enum: ["shared", "worktree"] },
      },
      required: ["question", "members", "checkout"],
    },
  },
  {
    name: "council_round",
    description: "Start the next round of a council you called: each member gets the focus and the other members' reports " +
      "from the round that just ended, labelled by letter. A member that hasn't answered by then has nothing in that round.",
    inputSchema: { type: "object", properties: { council: { type: "string" }, focus: { type: "string" } }, required: ["council", "focus"] },
  },
  {
    name: "council_report",
    description: "Report your answer to the council you are a member of, for its current round. You can report more than once.",
    inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
  },
  { name: "set_active",     description: "Set active session for a chat.",                   inputSchema: { type: "object", properties: { chat_id: { type: "string" }, name: { type: "string" } }, required: ["chat_id", "name"] } },
  { name: "get_active",     description: "Get active session for a chat.",                   inputSchema: { type: "object", properties: { chat_id: { type: "string" } }, required: ["chat_id"] } },
  { name: "create_agent",   description: "Create a new agent (a long-lived, named session with its own memory). Only agents can call this. provider defaults to 'claude'.", inputSchema: { type: "object", properties: { name: { type: "string" }, provider: { type: "string", enum: AGENT_KINDS }, model: { type: "string" } }, required: ["name"] } },
  { name: "expose_port", description: "Expose a local port to the web via reverse proxy. Returns the public URL. With a wildcard base domain configured (MUX_PROXY_BASE_DOMAIN), the app gets its own subdomain. Otherwise it is served under a sub-path (https://<broker>/p/<slug>/): the proxy strips the prefix, so this works for apps that use relative URLs, but apps that assume the site root (absolute /asset paths, fetch('/api'), root WebSocket, or a framework dev server such as Vite/Next) will not work under a sub-path — use a wildcard base domain (subdomain mode) for those. If domain is omitted a random slug is generated. Set public=true to skip device-pairing auth (anyone with the URL can access).", inputSchema: { type: "object", properties: { port: { type: "number", description: "Local port to expose (1-65535)" }, domain: { type: "string", description: "Optional slug (alphanumeric + hyphens, max 63 chars) used as the subdomain or sub-path segment" }, public: { type: "boolean", description: "When true, the proxy is reachable without pairing (default false)" } }, required: ["port"] } },
  { name: "unexpose_port", description: "Remove a previously exposed proxy by domain name.", inputSchema: { type: "object", properties: { domain: { type: "string", description: "Subdomain to remove" } }, required: ["domain"] } },
  { name: "set_proxy_public", description: "Toggle whether a proxy requires device pairing. public=true means anyone with the URL can access.", inputSchema: { type: "object", properties: { domain: { type: "string", description: "Subdomain to update" }, public: { type: "boolean", description: "true = open access, false = paired devices only" } }, required: ["domain", "public"] } },
  { name: "list_devices", description: "List attached Android adb devices/emulators available to stream via scrcpy (provider 'scrcpy').", inputSchema: { type: "object", properties: {}, required: [] } },
  { name: "start_display", description: "Start a streamable host display and return its stream id. On Linux this provisions a virtual Xvfb display; on macOS it streams the real screen (enable Screen Sharing). It does NOT launch apps — run apps with DISPLAY=<display> (Linux) to make them appear in the stream. View/control it from the supermux web app.", inputSchema: { type: "object", properties: { provider: { type: "string", description: "Optional: 'linux-xvfb' or 'macos-screen'. Defaults to the host platform." }, device: { type: "string", description: "adb serial for provider 'scrcpy' (e.g. emulator-5554)" }, width: { type: "number", description: "Virtual display width (Linux only, default 1280)" }, height: { type: "number", description: "Virtual display height (Linux only, default 800)" } }, required: [] } },
  { name: "stop_display", description: "Stop a display stream by id and tear down its virtual display/VNC server.", inputSchema: { type: "object", properties: { id: { type: "string", description: "Stream id returned by start_display" } }, required: ["id"] } },
  {
    name: "memory_search",
    description: "Search the shared knowledge base (~/.mux domains + digests) for facts/gotchas. An agent's search also covers its own memory. Returns ranked sections with file path, heading and a snippet.",
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
  {
    name: "walkthrough",
    description: "Publish or replace this session's current code walkthrough: an ordered slideshow of markdown steps anchored to diff regions. Call again to update. Returns per-step ok | not_in_diff so you can fix bad anchors. Text-only steps omit file. lines is new-side numbers: \"42\" or \"12-40\".",
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string" },
        base: { type: "string", description: "Diff base: omitted/session-start, head, commit:<sha>, branch:<name>" },
        steps: {
          type: "array",
          items: {
            type: "object",
            properties: {
              title: { type: "string" },
              body: { type: "string" },
              file: { type: "string" },
              repo: { type: "string" },
              lines: { type: "string" },
            },
            required: ["title", "body"],
          },
        },
      },
      required: ["title", "steps"],
    },
  },
  {
    name: "reply_comment",
    description: "Reply in-thread to a code-review / walkthrough comment. Pass comment_id of the comment you are answering. Optional resolve:true marks the root comment resolved.",
    inputSchema: {
      type: "object",
      properties: {
        comment_id: { type: "string" },
        body: { type: "string" },
        resolve: { type: "boolean" },
      },
      required: ["comment_id", "body"],
    },
  },
  {
    name: "message_session",
    description: "Send a message to other sessions' inboxes, by session id. The message wakes each receiving session. " +
      "interrupt=true interrupts its current turn; interrupt=false delivers after the current turn ends. " +
      "Your normal reply already reaches the user: this is only for other sessions.",
    inputSchema: {
      type: "object",
      properties: {
        to: { type: "array", items: { type: "string" }, minItems: 1, description: "Session ids" },
        text: { type: "string" },
        interrupt: { type: "boolean" },
      },
      required: ["to", "text", "interrupt"],
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

const ALL = [...OUTBOUND_TOOLS, ...ORCHESTRATION_TOOLS]
const OUTBOUND_NAMES = new Set(OUTBOUND_TOOLS.map(t => t.name))

export type ToolCallResult = { ok: boolean; value?: unknown; error?: string }
type ToolCaller = {
  callOutbound: (op: ToolOperation) => Promise<ToolCallResult>
  callOrchestration: (op: ToolOperation) => Promise<ToolCallResult>
}

/** council_report is only for council members (spec §5): listed for them, refused for anyone else. */
const MEMBER_ONLY = new Set(["council_report"])

export function listTools(rpcOnly = false, opts: { councilMember?: boolean } = {}) {
  if (rpcOnly) return RPC_TOOLS
  return opts.councilMember ? ALL : ALL.filter((t) => !MEMBER_ONLY.has(t.name))
}

export type ToolRoute = { kind: "outbound" | "orchestration"; op: string }

/**
 * Which broker handler a tool call goes to, and under which op name (rpc tools map to
 * rpc_resolve / rpc_reject). undefined: the tool is not offered (rpc-only vs full set). Shared by
 * this shim and the broker's host MCP server (src/core/mux-tools/server.ts).
 */
export function toolRoute(name: string, rpcOnly = false): ToolRoute | undefined {
  if (rpcOnly) return RPC_NAMES.has(name) ? { kind: "orchestration", op: RPC_OP[name as keyof typeof RPC_OP] } : undefined
  if (!ALL.some(t => t.name === name)) return undefined
  return { kind: OUTBOUND_NAMES.has(name) ? "outbound" : "orchestration", op: name }
}

/**
 * A broker handler result as the MCP tool result the agent sees. The ONE mapping for both
 * transports: ok:false → isError with the error text; attach → "sent" / "sent (id: …)"; anything
 * else → JSON of the value ("ok" when there is none).
 */
export function toolResult(name: string, result: ToolCallResult, rpcOnly = false): { isError?: boolean; content: Array<{ type: "text"; text: string }> } {
  if (!result.ok) return { isError: true, content: [{ type: "text", text: result.error ?? "unknown error" }] }
  if (!rpcOnly && name === "attach") {
    const value = result.value
    const id = value && typeof value === "object" && "message_id" in value ? value.message_id : undefined
    return { content: [{ type: "text", text: id != null ? `sent (id: ${id})` : "sent" }] }
  }
  return { content: [{ type: "text", text: JSON.stringify(result.value ?? "ok") }] }
}

export async function callTool(params: { name: string; arguments?: Record<string, unknown>; _meta?: unknown }, shim: ToolCaller, agentKind: AgentKind = AgentKind.Claude, rpcOnly = false): Promise<{ isError?: boolean; content: Array<{ type: "text"; text: string }> }> {
  const route = toolRoute(params.name, rpcOnly)
  if (!route) {
    const text = rpcOnly ? `tool ${params.name} not available on rpc worker` : `tool ${params.name} not available for agent kind ${agentKind}`
    return { isError: true, content: [{ type: "text", text }] }
  }
  const callId = toolCallIdOf(params._meta)
  const op = { name: route.op, args: params.arguments ?? {}, ...(callId ? { callId } : {}) }
  const result = route.kind === "outbound" ? await shim.callOutbound(op) : await shim.callOrchestration(op)
  return toolResult(params.name, result, rpcOnly)
}
