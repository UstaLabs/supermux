import { computed, type ComputedRef } from "vue"
import { useSessions, type Session } from "@/stores/sessions"
import { useMessages } from "@/stores/messages"

/**
 * Active sessions sorted by most-recent message timestamp (newest first).
 * Shared by the full session list and the collapsed sidebar rail so both
 * show the same order.
 */
export function useSortedSessions(): ComputedRef<Session[]> {
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
