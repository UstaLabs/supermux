import type { AgentState } from "./agent-state-store"

export type AgentStateFrame = {
  type: "agent_state"
  session: string
  state: "idle" | "working" | "dead"
  working: boolean
  /** "starting": a session that was not up is being woken (card #75); computed, never stored. */
  detail: "thinking" | "running" | "starting" | null
  tool?: string
  since: number
  workingSince?: number
  waiting: boolean   // idle but background tasks still open (turn will resume)
  bgOpen: number     // open background-task count (session-list badges)
  // TODO(clients-plan): remove once all clients read state/working/detail directly
  phase: "idle" | "thinking" | "running" | "stalled"  // legacy alias for pre-clients-plan clients
}

/**
 * `startingSince`: when the session's chain began waking it (SessionChain.wakingSince). While a wake is
 * in flight the session shows as starting, whatever the store holds; older clients see it working.
 */
export function toAgentStateFrame(session: string, st: AgentState, bgOpen = 0, startingSince?: number): AgentStateFrame {
  if (startingSince !== undefined) {
    return { type: "agent_state", session, state: "working", working: true, detail: "starting", tool: undefined, since: startingSince, workingSince: startingSince, waiting: false, bgOpen, phase: "thinking" }
  }
  const working = st.phase === "thinking" || st.phase === "running"
  const state = st.phase === "idle" ? "idle" : st.phase === "dead" ? "dead" : "working"
  const detail = working ? (st.phase as "thinking" | "running") : null
  const phase = st.phase === "dead" ? "stalled" : st.phase
  const waiting = st.phase === "idle" && bgOpen > 0
  return { type: "agent_state", session, state, working, detail, tool: st.tool, since: st.since, workingSince: st.workingSince, waiting, bgOpen, phase }
}
