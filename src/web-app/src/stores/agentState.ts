import { defineStore } from "pinia"
import { ref } from "vue"

export type AgentPhase = "idle" | "sending" | "thinking" | "running" | "stalled"
export interface AgentStateEntry {
  phase: AgentPhase
  tool?: string
  since: number
  workingSince?: number
}

const IDLE: AgentStateEntry = { phase: "idle", since: 0 }

/**
 * Whether a session's agent is actively working — the signal behind the
 * chat-list running spinner. True only for the working phases (`thinking` /
 * `running`) on a still-connected session. The `connected` gate matters because
 * the store keeps the last-known phase, so a session that drops mid-run would
 * otherwise read as perpetually working. Excludes `sending` and `stalled`.
 */
export function isAgentWorking(phase: AgentPhase | undefined, connected: boolean): boolean {
  return connected && (phase === "thinking" || phase === "running")
}

export const useAgentState = defineStore("agentState", () => {
  const bySession = ref<Record<string, AgentStateEntry>>({})

  function set(session: string, state: AgentStateEntry | undefined) {
    if (state && typeof state.phase === "string") bySession.value[session] = state
  }
  function get(session: string): AgentStateEntry {
    return bySession.value[session] ?? IDLE
  }

  function markSending(session: string, now: number = Date.now()) {
    bySession.value[session] = { phase: "sending", since: now }
  }

  return { bySession, set, get, markSending }
})
