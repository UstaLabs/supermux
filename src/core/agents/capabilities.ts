// Capability flags the broker derives from the agent kind, so web clients do
// not branch on kind for behavior (web audit B29-B33). The flags travel on two
// DTOs:
//   - Session DTO `capabilities`: the WS snapshot and session_added frames
//     (main.ts) plus GET /sessions.
//   - AgentStatus DTO `capabilities`: GET /agents/status (the login panel).
// All behavior kind-checks live here, in one broker-side place. Display-only
// kind use in clients (logos, labels, help text) is intentionally not covered.
import { AgentKind } from "../../shared/agents"
import { isPersistentRuntimeSession } from "../session-manager/types"

export interface SessionCapabilities {
  /** The session runs in a persistent runtime with an attachable agent
   *  terminal, so the client can offer a chat/terminal main-view toggle
   *  (claude today). */
  hasAgentTerminal: boolean
  /** A queued model/effort change can be applied immediately by a restart —
   *  the "Change now (ends current turn)" button (non-claude today; claude
   *  applies config changes live without a restart). */
  supportsLiveConfigChange: boolean
}

export interface AgentAuthCapabilities {
  /** The CLI has a device-code/browser login flow the broker can drive
   *  ("Authorize via link"). */
  supportsDeviceLogin: boolean
  /** The broker accepts a pasted key/token for this agent (a matching
   *  app-config field exists). */
  acceptsPastedKey: boolean
  /** The agent is usable with zero credentials (opencode free tier). */
  usableWithoutAuth: boolean
}

export function sessionCapabilities(agent: AgentKind): SessionCapabilities {
  const persistent = isPersistentRuntimeSession({ agent })
  return {
    hasAgentTerminal: persistent,
    supportsLiveConfigChange: !persistent,
  }
}

export function agentAuthCapabilities(kind: AgentKind): AgentAuthCapabilities {
  return {
    // opencode authenticates through its own multi-provider connect UI, not a
    // broker-driven device login.
    supportsDeviceLogin: kind !== AgentKind.OpenCode,
    // Only these kinds have a paste-a-key app-config field (see main.ts
    // getAgentStatuses hasCredential wiring).
    acceptsPastedKey:
      kind === AgentKind.Claude || kind === AgentKind.Codex || kind === AgentKind.Cursor,
    usableWithoutAuth: kind === AgentKind.OpenCode,
  }
}
