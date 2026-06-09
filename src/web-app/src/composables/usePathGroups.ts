import { computed, ref, type ComputedRef } from "vue"
import { useMessages } from "@/stores/messages"
import { useSessions, type Session } from "@/stores/sessions"
import { workdirDisplay } from "@/lib/workdir-display"

const COLLAPSED_KEY = "cmux:collapsed-paths"

export interface PathGroup {
  workdir: string
  label: string
  sessions: Session[]
  collapsed: boolean
}

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw)
    return new Set(Array.isArray(arr) ? arr.filter((x) => typeof x === "string") : [])
  } catch {
    return new Set()
  }
}

function persistCollapsed(set: Set<string>) {
  try {
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...set]))
  } catch {}
}

function lastMessageTs(session: Session, messages: ReturnType<typeof useMessages>): string {
  return messages.bySession[session.id]?.slice(-1)[0]?.ts ?? ""
}

export function usePathGroups(sortedSessions: ComputedRef<Session[]>) {
  const sessionsStore = useSessions()
  const messages = useMessages()
  const collapsedSet = ref(loadCollapsed())

  function toggle(workdir: string) {
    if (collapsedSet.value.has(workdir)) collapsedSet.value.delete(workdir)
    else collapsedSet.value.add(workdir)
    persistCollapsed(collapsedSet.value)
  }

  const groups = computed<PathGroup[]>(() => {
    const byPath = new Map<string, Session[]>()
    const homeDir = sessionsStore.homeDir
    for (const s of sortedSessions.value) {
      // Worktree-backed sessions group under their project (repo_root), not the
      // internal worktree path.
      const key = workdirDisplay(s.repo_root ?? s.workdir, homeDir).key
      const list = byPath.get(key) ?? []
      list.push(s)
      byPath.set(key, list)
    }

    const result: PathGroup[] = []

    for (const [workdir, list] of byPath) {
      list.sort((a, b) => lastMessageTs(b, messages).localeCompare(lastMessageTs(a, messages)))
      const display = workdirDisplay(workdir, homeDir)
      result.push({
        workdir: display.key,
        label: display.label,
        sessions: list,
        collapsed: collapsedSet.value.has(display.key),
      })
    }

    result.sort((a, b) => {
      const aTs = a.sessions.map((s) => lastMessageTs(s, messages)).sort().reverse()[0] ?? ""
      const bTs = b.sessions.map((s) => lastMessageTs(s, messages)).sort().reverse()[0] ?? ""
      return bTs.localeCompare(aTs)
    })

    return result
  })

  return { groups, toggle }
}
