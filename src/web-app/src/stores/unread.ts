import { defineStore } from "pinia"
import { ref, computed } from "vue"
import { useMessages } from "./messages"

export const useUnread = defineStore("unread", () => {
  const messages = useMessages()
  // Server is the source of truth: seeded from the snapshot's `reads` map and
  // updated by `session_read` frames. No localStorage — read status is global
  // across the user's devices.
  const lastRead = ref<Record<string, string>>({})

  const unreadSessions = computed<Set<string>>(() => {
    const set = new Set<string>()
    for (const [session, entries] of Object.entries(messages.bySession)) {
      const lastTs = entries[entries.length - 1]?.ts
      if (!lastTs) continue
      const readTs = lastRead.value[session]
      if (!readTs || lastTs > readTs) set.add(session)
    }
    return set
  })

  function isUnread(session: string): boolean {
    return unreadSessions.value.has(session)
  }

  // Monotonic: only ever advances a session's read pointer, so an optimistic
  // local mark (now()) is never undone by a slightly-older server timestamp.
  function setLastRead(session: string, ts: string): void {
    const cur = lastRead.value[session]
    if (cur && cur >= ts) return
    lastRead.value = { ...lastRead.value, [session]: ts }
  }

  function seed(map: Record<string, string>): void {
    for (const [session, ts] of Object.entries(map)) setLastRead(session, ts)
  }

  // Optimistic local clear when the user opens/looks at a session. The server
  // confirms and syncs the user's other devices via the `session_read` frame.
  function markRead(session: string): void {
    setLastRead(session, new Date().toISOString())
  }

  return { isUnread, setLastRead, seed, markRead }
})
