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
 * chat-list running spinner. Mirrors the chat view's "Working…" indicator
 * EXACTLY: true for the working phases (`thinking` / `running`), excludes
 * `idle` / `sending` / `stalled`. Deliberately NOT gated on connection — a
 * session that's working but whose adapter heartbeat dropped still shows
 * "Working…" in its chat, so it must show in the list too (the list and the
 * chat must never disagree about who's working).
 */
export function isAgentWorking(phase: AgentPhase | undefined): boolean {
  return phase === "thinking" || phase === "running"
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
