export const AgentKind = {
  Claude: "claude",
  Codex: "codex",
  Cursor: "cursor",
  OpenCode: "opencode",
  Grok: "grok",
} as const

export type AgentKind = (typeof AgentKind)[keyof typeof AgentKind]

export const AGENT_KINDS = [
  AgentKind.Claude,
  AgentKind.Codex,
  AgentKind.Cursor,
  AgentKind.OpenCode,
  AgentKind.Grok,
] as const

export function isAgentKind(value: unknown): value is AgentKind {
  return typeof value === "string" && (AGENT_KINDS as readonly string[]).includes(value)
}

export function parseAgentKind(value: unknown, fallback: AgentKind = AgentKind.Claude): AgentKind {
  if (value == null || value === "") return fallback
  if (isAgentKind(value)) return value
  throw new Error(`unsupported agent kind: ${String(value)}`)
}

// Human-readable label per kind, derived from the AgentKind keys so a new
// kind gets a label for free (claude → "Claude", opencode → "OpenCode").
const AGENT_LABELS = Object.fromEntries(
  Object.entries(AgentKind).map(([label, kind]) => [kind, label]),
) as Record<AgentKind, string>

export function agentDisplayName(kind: AgentKind): string {
  return AGENT_LABELS[kind]
}

/**
 * The slash command that spawns a session of the given kind. Claude is the
 * default agent: it keeps the bare `spawn` command. Every other kind gets a
 * `spawn_<kind>` alias. Kind names use only [a-z0-9], so the generated
 * aliases satisfy Telegram's bot-command format (^[a-z0-9_]{1,32}$).
 */
export function spawnCommandForAgent(kind: AgentKind): string {
  return kind === AgentKind.Claude ? "spawn" : `spawn_${kind}`
}
