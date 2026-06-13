import { defineStore } from "pinia"
import { ref } from "vue"

// Pure synced state for unsent composer text, one draft per session. Seeded
// from the snapshot's `drafts` map and kept in sync via `draft_set` /
// `draft_clear` frames. The network send + per-session debounce live in the
// PromptInputDraftSync component, which keeps this store free of the ws/router
// graph (and trivially unit-testable).
export const useDrafts = defineStore("drafts", () => {
  const bySession = ref<Record<string, string>>({})

  function get(session: string): string {
    return bySession.value[session] ?? ""
  }

  function setLocal(session: string, text: string): void {
    bySession.value = { ...bySession.value, [session]: text }
  }

  // A draft pushed from another device, or a server-side clear (empty text).
  function applyRemote(session: string, text: string): void {
    setLocal(session, text)
  }

  function clear(session: string): void {
    setLocal(session, "")
  }

  function seed(map: Record<string, string>): void {
    bySession.value = { ...map }
  }

  return { get, setLocal, applyRemote, clear, seed }
})
