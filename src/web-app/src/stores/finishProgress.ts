import { defineStore } from "pinia"
import { ref } from "vue"

// Live "Finish" stage label per session (e.g. "Syncing main…", "Running tests…",
// "Merging…"), pushed from the broker over the WS while a finish is in flight.
export const useFinishProgress = defineStore("finishProgress", () => {
  const stageBySession = ref<Record<string, string>>({})

  function set(session: string, stage: string) {
    stageBySession.value = { ...stageBySession.value, [session]: stage }
  }
  function clear(session: string) {
    if (session in stageBySession.value) {
      const next = { ...stageBySession.value }
      delete next[session]
      stageBySession.value = next
    }
  }

  return { stageBySession, set, clear }
})
