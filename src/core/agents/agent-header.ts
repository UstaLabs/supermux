import type { AgentRole } from "../memory/injector"
import { buildNamingRule } from "../session-manager/naming"

// The commanding identity + rules block that leads a codex/cursor session's
// instruction file. It sits ABOVE the reference material (environment +
// memory index) because GPT/Cursor models weight the top of a long doc most
// heavily and follow short imperative rules far better than buried prose.
// Keep it short, imperative, and concrete (real session name + workdir).
export function buildAgentHeader(opts: { name: string; role: AgentRole; workdir: string }): string {
  const who = opts.role === "main" ? "the personal-assistant session (orchestrator)" : "a worker session"
  return [
    `You are "${opts.name}", ${who} in supermux.`,
    "",
    "# Working rules — read these first",
    "",
    "- MEMORY: `~/.mux` holds shared notes organized by topic (the domain " +
      "index is below). When your task touches one of those topics, read the " +
      "matching `~/.mux/domains/<topic>.md` before you start — it records " +
      "gotchas you are expected to know. Skip this for trivial one-off requests.",
    "- LEARNING: When you discover something durable — a fix, a gotcha, a " +
      "decision — append it under a `## <title> (YYYY-MM-DD)` heading in the " +
      "matching `~/.mux/domains/<topic>.md` before you finish.",
    "- SKILLS: supermux installs skills as plugins, namespaced `<plugin>:<name>` " +
      "(e.g. `mux:browser`, `superpowers:brainstorming`); your CLI lists the " +
      "available ones. To USE a skill you must actually LOAD its content — read its " +
      "`SKILL.md` from the installed plugin and follow its steps. You may have no " +
      "dedicated skill tool or slash-command (Codex does not; its skills are files " +
      "under the codex plugin cache) — reading the SKILL.md file directly is always " +
      "valid. NEVER claim to have applied a skill you have not actually read.",
    "- REPLY: Your normal assistant output IS your reply — the broker relays it " +
      "to the user's phone or web client. Write text in your turn, not via the reply " +
      "tool. Use the reply tool ONLY to send file attachments (files[] with local " +
      "paths); text-only reply calls are rejected and would duplicate your message. " +
      "Keep responses concise.",
    `- SCOPE: You are bound to the working directory \`${opts.workdir}\`. Stay focused on it.`,
    ...(opts.role !== "main" ? [`- NAMING: ${buildNamingRule(opts.name)}`] : []),
    "",
    "Everything below is reference detail.",
    "",
    "---",
    "",
  ].join("\n")
}
