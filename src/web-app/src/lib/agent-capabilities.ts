// Broker-served capability flags, with kind-derived fallbacks.
//
// The broker attaches a `capabilities` object to the session DTO (WS snapshot
// and session_added frames) and to each AgentStatus row (GET /agents/status).
// Components read behavior from these flags instead of branching on the agent
// kind. Display-only kind use (logos, labels, help text) stays kind-based.
//
// Compatibility rule: the web app must tolerate an OLD broker that does not
// send the flags yet (rollout window). Both helpers prefer the server flags
// and fall back to kind-derived defaults identical to the pre-flag behavior.

/** Shared agent-kind union for the web app (mirrors src/shared/agents.ts). */
export type AgentKind = "claude" | "codex" | "cursor" | "opencode" | "grok"

export interface SessionCapabilities {
  /** The session has an attachable agent terminal (chat/terminal toggle). */
  hasAgentTerminal: boolean
  /** A queued model/effort change can be applied now via restart ("Change now"). */
  supportsLiveConfigChange: boolean
}

export interface AgentAuthCapabilities {
  /** The broker can drive a device-code/browser login ("Authorize via link"). */
  supportsDeviceLogin: boolean
  /** The broker accepts a pasted key/token for this agent. */
  acceptsPastedKey: boolean
  /** The agent works with zero credentials (opencode free tier). */
  usableWithoutAuth: boolean
}

/**
 * Session behavior flags. Prefers the broker's `capabilities`; falls back to
 * the kind rules the web app used before the flags existed (claude has the
 * agent terminal, non-claude gets the Change-now restart).
 */
export function capabilitiesOf(
  session?: { agent?: string; capabilities?: Partial<SessionCapabilities> } | null,
): SessionCapabilities {
  const agent = session?.agent
  const server = session?.capabilities
  return {
    hasAgentTerminal: server?.hasAgentTerminal ?? agent === "claude",
    supportsLiveConfigChange:
      server?.supportsLiveConfigChange ?? (agent !== undefined && agent !== "claude"),
  }
}

// Kind-derived fallbacks for an old broker — identical to the lists the login
// panel hard-coded before the flags existed.
const DEVICE_LOGIN_KINDS: readonly string[] = ["claude", "codex", "cursor", "grok"]
const PASTED_KEY_KINDS: readonly string[] = ["claude", "codex", "cursor"]

/**
 * Login-panel behavior flags for one AgentStatus row. Prefers the broker's
 * `capabilities`; falls back to the kind lists above.
 */
export function agentAuthCapabilitiesOf(
  status: { kind: string; capabilities?: Partial<AgentAuthCapabilities> },
): AgentAuthCapabilities {
  const server = status.capabilities
  return {
    supportsDeviceLogin: server?.supportsDeviceLogin ?? DEVICE_LOGIN_KINDS.includes(status.kind),
    acceptsPastedKey: server?.acceptsPastedKey ?? PASTED_KEY_KINDS.includes(status.kind),
    usableWithoutAuth: server?.usableWithoutAuth ?? status.kind === "opencode",
  }
}
