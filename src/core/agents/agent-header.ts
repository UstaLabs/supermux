import type { AgentRole } from "../memory/injector"
import { AgentKind } from "../../shared/agents"
import { buildNamingRule } from "../session-manager/naming"
import { join } from "path"
import { getMuxHome } from "../memory/init"
import { agentMemoryPaths } from "../memory/agent-memory"

// How each agent loads a plugin skill. Cursor's ACP server ignores --plugin-dir, so it has none.
const SKILL_LOADING: Partial<Record<AgentKind, string>> = {
  [AgentKind.Claude]: "Load one with your Skill tool before following it.",
  [AgentKind.OpenCode]: "OpenCode's native `skill` tool lists and loads them.",
  [AgentKind.Codex]: "You have no skill tool: read the skill's `SKILL.md` from the codex plugin cache and follow its steps.",
  [AgentKind.Grok]: "Read the skill's `SKILL.md` from its plugin directory and follow its steps.",
  [AgentKind.Antigravity]: "Antigravity loads plugin skills itself: use one by its name, or read its `SKILL.md` and follow its steps.",
}

// The identity + rules block that leads every session's instructions. It sits ABOVE the reference
// material (environment + memory index) because models weight the top of a long prompt most and
// follow short imperative rules far better than buried prose. Keep it short and concrete.
export function buildAgentHeader(opts: { name: string; role: AgentRole; workdir: string; agent: AgentKind; id?: string; muxHome?: string }): string {
  const isAgent = (opts.role === "main" || opts.role === "personal_assistant") && !!opts.id
  const skills = SKILL_LOADING[opts.agent]
  const home = opts.muxHome ?? getMuxHome()
  const mem = isAgent ? agentMemoryPaths(opts.id!, home) : undefined
  const first = isAgent
    ? `You are "${opts.name}", an agent in supermux. Your agent id is ${opts.id}. When asked your name, answer "${opts.name}".`
    : `You are "${opts.name}", a worker session in supermux. When asked your name, answer "${opts.name}".`
  return [
    first,
    "",
    "# Working rules — read these first",
    "",
    "- REPLY: Your normal assistant output IS your reply — the broker relays it to the " +
      "user's phone or web client. The user never sees tool calls or command output, so " +
      "end every turn with a text message to them, also when blocked or interrupted. To " +
      "send files, call the `attach` tool with files[] (local paths) and an optional " +
      "caption. Keep responses concise.",
    ...(mem ? [
      "- YOUR MEMORY: You have your own memory, keyed by your id (it stays yours if you are renamed):",
      `  - \`${mem.digest}\` — current truth. Read it at the start of every conversation. Never edit it; the nightly curator writes it.`,
      `  - \`${mem.history}\` — dated history. When you learn something about your own work or how the user wants you ` +
        "to behave, append it under `## <title> (YYYY-MM-DD)`.",
      "  No other session reads these files. `memory_search` also searches them for you.",
      `- THE USER: \`${join(home, "personal")}/\` holds who the user is and their preferences. Read it when it ` +
        "matters. Workers never see it, so never pass its contents to another session.",
    ] : []),
    "- MEMORY: `~/.mux` holds shared notes by topic (the domain index is below). When your " +
      "task touches one of those topics, read `~/.mux/domains/<topic>.digest.md` (the current " +
      "truth; `<topic>.md` is the dated history) before you start — it records gotchas you " +
      "are expected to know. Skip this for trivial one-off requests.",
    "- LEARNING: When you discover something durable about a project — a fix, a gotcha, a decision — append " +
      "it under a `## <title> (YYYY-MM-DD)` heading in `~/.mux/domains/<topic>.md` (or " +
      "`domains/_inbox.md` if unsure) before you finish. Never edit `*.digest.md`.",
    ...(skills ? [
      "- SKILLS: supermux installs skills as plugins, namespaced `<plugin>:<name>` (e.g. " +
        `\`mux:browser\`, \`superpowers:brainstorming\`). ${skills} NEVER claim to have ` +
        "applied a skill you have not actually loaded.",
    ] : []),
    isAgent
      ? `- HOME: Your working directory is \`${opts.workdir}\`. Keep scratch files there; other projects are fine to work on when the user asks.`
      : `- SCOPE: You are bound to the working directory \`${opts.workdir}\`. Stay focused on it.`,
    ...(isAgent ? [] : [`- NAMING: ${buildNamingRule(opts.name)}`]),
    "",
    "Everything below is reference detail.",
    "",
    "---",
    "",
  ].join("\n")
}
