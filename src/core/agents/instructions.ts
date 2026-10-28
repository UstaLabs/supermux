import { join } from "path"
import type { AgentKind } from "../../shared/agents"
import { SESSION_INBOX_DIR } from "../../shared/paths"
import { buildMemoryPreamble } from "../memory/preamble"
import { readEnvironmentMd } from "./environment"
import { buildAgentHeader } from "./agent-header"

/** Inbox spec §1.5: the session's id, its inbox file, and how to message another session. */
export function inboxInstructions(sessionId: string, inboxDir: string = SESSION_INBOX_DIR): string {
  return [
    "## Inbox",
    `Your session id: ${sessionId}. Your inbox: ${join(inboxDir, `${sessionId}.jsonl`)} (one JSON message per line; read it like any file).`,
    'A message from another session arrives as a turn that starts with "Message from [name:id]".',
    'Send a message with message_session({ to: ["<session-id>"], text, interrupt }).',
  ].join("\n")
}

/**
 * A session's instructions: ONE value, handed to the core as session context (Claude: system
 * prompt; Codex: developerInstructions; Cursor: leading prompt block; Grok: session/new rules;
 * OpenCode: context instructions). Nothing is written to the workdir or the session home.
 * Order: rules header, environment.md (reference), memory index (+ soul / focus), inbox.
 */
export function sessionInstructions(opts: { agent: AgentKind; sessionName: string; workdir: string; pa?: boolean; sessionId?: string }): string {
  const role = opts.pa ? "personal_assistant" : "worker"
  return [
    buildAgentHeader({ name: opts.sessionName, role, workdir: opts.workdir, agent: opts.agent }),
    readEnvironmentMd(),
    buildMemoryPreamble(role, opts.workdir),
    opts.sessionId ? inboxInstructions(opts.sessionId) : "",
  ].filter(s => s && s.trim()).join("\n")
}
