import { defineStore } from "pinia"
import { ref } from "vue"

export type AgentPhase = "idle" | "sending" | "thinking" | "running"
export interface AgentStateEntry {
  phase: AgentPhase
  tool?: string
  since: number
  workingSince?: number
}

const IDLE: AgentStateEntry = { phase: "idle", since: 0 }

export const useAgentState = defineStore("agentState", () => {
  const bySession = ref<Record<string, AgentStateEntry>>({})

  function set(session: string, state: AgentStateEntry | undefined) {
    if (state && typeof state.phase === "string") bySession.value[session] = state
  }
  function get(session: string): AgentStateEntry {
    return bySession.value[session] ?? IDLE
  }

  return { bySession, set, get }
})
