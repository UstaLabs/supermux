import type { AgentRole } from "../memory/injector"
import { AgentKind } from "../../shared/agents"
import { buildNamingRule } from "../session-manager/naming"

// How each agent loads a plugin skill. Cursor's ACP server ignores --plugin-dir, so it has none.
const SKILL_LOADING: Partial<Record<AgentKind, string>> = {
  [AgentKind.Claude]: "Load one with your Skill tool before following it.",
  [AgentKind.OpenCode]: "OpenCode's native `skill` tool lists and loads them.",
  [AgentKind.Codex]: "You have no skill tool: read the skill's `SKILL.md` from the codex plugin cache and follow its steps.",
  [AgentKind.Grok]: "Read the skill's `SKILL.md` from its plugin directory and follow its steps.",
}

// The identity + rules block that leads every session's instructions. It sits ABOVE the reference
// material (environment + memory index) because models weight the top of a long prompt most and
// follow short imperative rules far better than buried prose. Keep it short and concrete.
export function buildAgentHeader(opts: { name: string; role: AgentRole; workdir: string; agent: AgentKind }): string {
  const pa = opts.role === "main" || opts.role === "personal_assistant"
  const who = pa ? "the personal-assistant session (orchestrator)" : "a worker session"
  const skills = SKILL_LOADING[opts.agent]
  return [
    `You are "${opts.name}", ${who} in supermux. When asked your name, answer "${opts.name}".`,
    "",
    "# Working rules — read these first",
    "",
    "- REPLY: Your normal assistant output IS your reply — the broker relays it to the " +
      "user's phone or web client. The user never sees tool calls or command output, so " +
      "end every turn with a text message to them, also when blocked or interrupted. To " +
      "send files, call the `attach` tool with files[] (local paths) and an optional " +
      "caption. Keep responses concise.",
    "- MEMORY: `~/.mux` holds shared notes by topic (the domain index is below). When your " +
      "task touches one of those topics, read `~/.mux/domains/<topic>.digest.md` (the current " +
      "truth; `<topic>.md` is the dated history) before you start — it records gotchas you " +
      "are expected to know. Skip this for trivial one-off requests.",
    "- LEARNING: When you discover something durable — a fix, a gotcha, a decision — append " +
      "it under a `## <title> (YYYY-MM-DD)` heading in `~/.mux/domains/<topic>.md` (or " +
      "`domains/_inbox.md` if unsure) before you finish. Never edit `*.digest.md`.",
    ...(skills ? [
      "- SKILLS: supermux installs skills as plugins, namespaced `<plugin>:<name>` (e.g. " +
        `\`mux:browser\`, \`superpowers:brainstorming\`). ${skills} NEVER claim to have ` +
        "applied a skill you have not actually loaded.",
    ] : []),
    `- SCOPE: You are bound to the working directory \`${opts.workdir}\`. Stay focused on it.`,
    ...(pa ? [] : [`- NAMING: ${buildNamingRule(opts.name)}`]),
    "",
    "Everything below is reference detail.",
    "",
    "---",
    "",
  ].join("\n")
}
