import { defineStore } from "pinia"
import { ref } from "vue"

export interface AgentStateEntry {
  state: "idle" | "working" | "dead"
  working: boolean
  detail?: "thinking" | "running" | null
  tool?: string
  since: number
  workingSince?: number
}

// Frozen so the shared default returned by get() for unknown sessions can never
// be mutated through a caller's reference.
const IDLE: AgentStateEntry = Object.freeze<AgentStateEntry>({ state: "idle", working: false, since: 0 })

/** True iff the broker says this session is working. Render-only — no client logic. */
export function isAgentWorking(entry: AgentStateEntry | undefined): boolean {
  return entry?.working === true
}

export const useAgentState = defineStore("agentState", () => {
  const bySession = ref<Record<string, AgentStateEntry>>({})
  const pendingSend = ref<Record<string, boolean>>({})   // client-local "Sending…"

  function set(session: string, state: AgentStateEntry | undefined) {
    if (state && typeof state.state === "string") {
      bySession.value[session] = state
      delete pendingSend.value[session]                  // first real state clears Sending…
    }
  }
  function get(session: string): AgentStateEntry {
    return bySession.value[session] ?? IDLE
  }
  function markSending(session: string) {
    pendingSend.value[session] = true
  }
  function isSending(session: string): boolean {
    return pendingSend.value[session] === true && !get(session).working
  }

  return { bySession, pendingSend, set, get, markSending, isSending }
})
