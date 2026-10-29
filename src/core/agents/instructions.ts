import type { AgentKind } from "../../shared/agents"
import { SESSION_INBOX_DIR, inboxFilePath } from "../../shared/paths"
import { shellWord } from "../../shared/shell-word"
import { shellReadsFor, type ShellReads } from "./shell-reads"
import { buildMemoryPreamble } from "../memory/preamble"
import { readEnvironmentMd } from "./environment"
import { buildAgentHeader } from "./agent-header"

/**
 * Inbox spec §1.5: the session's id, its inbox file, how to read it, and how to message another session.
 * How to read it depends on the agent: where shell reads of the file need no prompt (shellReadsFor: Codex's rules,
 * Claude's exact rules, Grok's built-in list) the commands are named, exactly as the rules allow them; every other
 * agent reads it with its file-read tool. `hostPrograms` are the absolute binaries Codex's rules were written for.
 */
export function inboxInstructions(sessionId: string, inboxDir: string = SESSION_INBOX_DIR, agent?: AgentKind, hostPrograms?: ShellReads): string {
  const file = inboxFilePath(sessionId, inboxDir)
  const reads = shellReadsFor(agent, file, hostPrograms)
  const commands = [reads.cat, reads.tail].flatMap(program => (program ? [`\`${program} ${shellWord(file)}\``] : []))
  return [
    "## Inbox",
    `Your session id: ${sessionId}. Your inbox: ${file} (one JSON message per line; read it like any file).`,
    commands.length > 0 ? `From a shell, read it with exactly ${commands.join(" or ")}.` : "Read it with your file-read tool.",
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
export function sessionInstructions(opts: { agent: AgentKind; sessionName: string; workdir: string; pa?: boolean; sessionId?: string; inboxDir?: string; shellReads?: ShellReads }): string {
  const role = opts.pa ? "personal_assistant" : "worker"
  return [
    buildAgentHeader({ name: opts.sessionName, role, workdir: opts.workdir, agent: opts.agent }),
    readEnvironmentMd(),
    buildMemoryPreamble(role, opts.workdir),
    opts.sessionId ? inboxInstructions(opts.sessionId, opts.inboxDir, opts.agent, opts.shellReads) : "",
  ].filter(s => s && s.trim()).join("\n")
}
