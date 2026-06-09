import { defineStore } from "pinia"
import { ref } from "vue"

/** Tracks warm session chat workspaces (KeepAlive pool) for this browser tab. */
export const useSessionCache = defineStore("sessionCache", () => {
  const liveIds = ref<string[]>([])
  const droppedIds = ref(new Set<string>())

  function isDropped(id: string): boolean {
    return droppedIds.value.has(id)
  }

  /** Register a session id the user navigated to (opens a cache slot). */
  function visit(id: string) {
    if (!id || isDropped(id)) return
    if (!liveIds.value.includes(id)) liveIds.value = [...liveIds.value, id]
  }

  /** Evict a killed session from the warm pool. */
  function drop(id: string) {
    if (!id) return
    if (!droppedIds.value.has(id)) {
      droppedIds.value = new Set([...droppedIds.value, id])
    }
    liveIds.value = liveIds.value.filter((x) => x !== id)
  }

  return { liveIds, droppedIds, isDropped, visit, drop }
})
