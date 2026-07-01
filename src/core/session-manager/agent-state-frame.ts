import type { AgentState } from "./agent-state-store"

export type AgentStateFrame = {
  type: "agent_state"
  session: string
  state: "idle" | "working" | "dead"
  working: boolean
  detail: "thinking" | "running" | null
  tool?: string
  since: number
  workingSince?: number
  // TODO(clients-plan): remove once all clients read state/working/detail directly
  phase: "idle" | "thinking" | "running" | "stalled"  // legacy alias for pre-clients-plan clients
}

export function toAgentStateFrame(session: string, st: AgentState): AgentStateFrame {
  const working = st.phase === "thinking" || st.phase === "running"
  const state = st.phase === "idle" ? "idle" : st.phase === "dead" ? "dead" : "working"
  const detail = working ? (st.phase as "thinking" | "running") : null
  const phase = st.phase === "dead" ? "stalled" : st.phase
  return { type: "agent_state", session, state, working, detail, tool: st.tool, since: st.since, workingSince: st.workingSince, phase }
}
