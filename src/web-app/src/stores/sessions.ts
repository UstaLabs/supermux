import { defineStore } from "pinia"
import { ref } from "vue"
import { api } from "@/api/client"

export interface Session {
  id: string
  name: string
  workdir: string
  mute: boolean
  connected: boolean
  agent?: string
  role?: "personal_assistant" | "worker"
  isDefault?: boolean
  model?: string
  reasoningLevel?: string
  status?: string
  session_branch?: string
  repo_root?: string
  finish_job?: import("./finishJob").FinishJob
  userStatus?: "draft" | "in_progress" | "settled"
  sortOrder?: number
  draftPayload?: { text?: string; attachments?: Array<{ file_id: string; name?: string; mime?: string }> }
}

export interface ArchivedSession {
  id: string
  name: string
  workdir: string
  agent: string
  model?: string
  killed_at?: string
  repo_root?: string
}

export const useSessions = defineStore("sessions", () => {
  const list = ref<Session[]>([])
  const archivedSessions = ref<ArchivedSession[]>([])
  const archivedLoaded = ref(false)
  const homeDir = ref<string | null>(null)

  function setHomeDir(dir: string) {
    homeDir.value = dir
  }

  function replace(next: Session[]) { list.value = next }
  function add(s: Session) {
    const existing = list.value.find((x) => x.id === s.id)
    if (!existing) { list.value.push(s); return }
    // A worktree session is added first without repo_root (optimistic add /
    // early onRegister broadcast); the authoritative post-spawn broadcast
    // re-adds it with repo_root. Backfill any newly-defined fields instead of
    // dropping the duplicate — but never clobber an existing value with
    // undefined (the optimistic add omits repo_root).
    for (const key of Object.keys(s) as (keyof Session)[]) {
      const v = s[key]
      if (v !== undefined) (existing as Record<string, unknown>)[key] = v
    }
  }
  function remove(id: string) { list.value = list.value.filter((x) => x.id !== id) }
  function rename(id: string, newName: string) {
    const s = list.value.find((x) => x.id === id)
    if (s) s.name = newName
  }
  function updateState(id: string, patch: Partial<Session>) {
    const s = list.value.find((x) => x.id === id)
    if (s) Object.assign(s, patch)
  }
  function setLocalOrder(id: string, order: number) {
    const s = list.value.find((x) => x.id === id)
    if (s) s.sortOrder = order
  }

  function byId(id: string): Session | ArchivedSession | undefined {
    return list.value.find((s) => s.id === id) ?? archivedSessions.value.find((s) => s.id === id)
  }

  function displayName(id: string): string | undefined {
    return byId(id)?.name
  }

  async function fetchArchived() {
    const data = await api.listArchivedSessions()
    archivedSessions.value = data ?? []
    archivedLoaded.value = true
  }

  async function resumeSession(id: string) {
    await api.resumeSession(id)
    archivedSessions.value = archivedSessions.value.filter((s) => s.id !== id)
  }

  return {
    list, archivedSessions, archivedLoaded, homeDir,
    replace, add, remove, rename, updateState, setLocalOrder, setHomeDir,
    byId, displayName, fetchArchived, resumeSession,
  }
})
