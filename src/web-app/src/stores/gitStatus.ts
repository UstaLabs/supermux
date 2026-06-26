import { defineStore } from "pinia"
import { ref } from "vue"

export interface GitLiteStatus {
  mode: "base" | "remote"
  compareRef: string
  ahead: number
  behind: number
  dirty: number
  unpublished?: boolean
  touched?: boolean
  computedAt: number
}

export const useGitStatus = defineStore("gitStatus", () => {
  const bySession = ref<Record<string, GitLiteStatus>>({})

  function set(id: string, git: GitLiteStatus | null | undefined) {
    if (!git) { clear(id); return }
    bySession.value = { ...bySession.value, [id]: git }
  }
  function fromSnapshot(id: string, git?: GitLiteStatus | null) { if (git) set(id, git) }
  function clear(id: string) {
    if (id in bySession.value) { const n = { ...bySession.value }; delete n[id]; bySession.value = n }
  }
  function get(id: string): GitLiteStatus | undefined { return bySession.value[id] }

  return { bySession, set, fromSnapshot, clear, get }
})
