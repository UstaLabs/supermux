import { defineStore } from "pinia"
import { ref } from "vue"

export type FinishAction = "merge" | "pr" | "keep" | "discard"
export interface FinishJob {
  sessionId: string
  action: FinishAction
  status: "running" | "done" | "failed"
  stage?: string
  outcome?: { status: string; [k: string]: unknown }
  startedAt: number
  endedAt?: number
}

export const useFinishJob = defineStore("finishJob", () => {
  const bySession = ref<Record<string, FinishJob>>({})
  const ackedAt = ref<Record<string, number>>({})   // sessionId -> startedAt the user has seen

  function set(id: string, job: FinishJob) { bySession.value = { ...bySession.value, [id]: job } }
  function fromSnapshot(id: string, job?: FinishJob | null) { if (job) set(id, job) }
  function clear(id: string) {
    if (id in bySession.value) { const next = { ...bySession.value }; delete next[id]; bySession.value = next }
  }
  function ack(id: string) { const j = bySession.value[id]; if (j) ackedAt.value = { ...ackedAt.value, [id]: j.startedAt } }
  function isUnacked(id: string): boolean {
    const j = bySession.value[id]
    return !!j && j.status !== "running" && ackedAt.value[id] !== j.startedAt
  }
  return { bySession, set, fromSnapshot, clear, ack, isUnacked }
})
