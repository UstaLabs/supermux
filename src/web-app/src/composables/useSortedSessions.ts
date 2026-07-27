import { computed, type ComputedRef } from "vue"
import { useSessions, type Session } from "@/stores/sessions"
import { useMessages } from "@/stores/messages"

/**
 * Active sessions in user-controlled list order (sortOrder ascending).
 * Message arrival must not reshuffle the sidebar rail or task list — only an
 * explicit user drag-reorder updates sortOrder.
 */
export function useSortedSessions(): ComputedRef<Session[]> {
  const sessions = useSessions()
  return computed(() => {
    return [...sessions.list].sort((a, b) => {
      const ao = a.sortOrder ?? 0
      const bo = b.sortOrder ?? 0
      if (ao !== bo) return ao - bo
      // Stable secondary: keep store insertion order via id when sortOrders match.
      return a.id.localeCompare(b.id)
    })
  })
}

/**
 * Sessions newest-message-first. For launcher "recent projects" only — not the
 * session task list (which must stay on user sortOrder).
 */
export function useSessionsByRecency(): ComputedRef<Session[]> {
  const sessions = useSessions()
  const messages = useMessages()
  return computed(() => {
    return [...sessions.list].sort((a, b) => {
      const aTs = messages.bySession[a.id]?.slice(-1)[0]?.ts ?? ""
      const bTs = messages.bySession[b.id]?.slice(-1)[0]?.ts ?? ""
      return bTs.localeCompare(aTs)
    })
  })
}
