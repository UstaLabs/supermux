export const AgentKind = {
  Claude: "claude",
  Codex: "codex",
  Cursor: "cursor",
  OpenCode: "opencode",
} as const

export type AgentKind = (typeof AgentKind)[keyof typeof AgentKind]

export const AGENT_KINDS = [
  AgentKind.Claude,
  AgentKind.Codex,
  AgentKind.Cursor,
  AgentKind.OpenCode,
] as const

export function isAgentKind(value: unknown): value is AgentKind {
  return typeof value === "string" && (AGENT_KINDS as readonly string[]).includes(value)
}

export function parseAgentKind(value: unknown, fallback = AgentKind.Claude): AgentKind {
  if (value == null || value === "") return fallback
  if (isAgentKind(value)) return value
  throw new Error(`unsupported agent kind: ${String(value)}`)
}
