import { defineStore } from "pinia"
import { ref, computed } from "vue"
import { useMessages } from "./messages"

const KEY = "cmux:unread:lastRead"

function loadLastRead(): Record<string, string> {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === "object" ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

function persist(map: Record<string, string>): void {
  try { localStorage.setItem(KEY, JSON.stringify(map)) } catch {}
}

export const useUnread = defineStore("unread", () => {
  const messages = useMessages()
  const lastRead = ref<Record<string, string>>(loadLastRead())

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

  function markRead(session: string): void {
    lastRead.value = { ...lastRead.value, [session]: new Date().toISOString() }
    persist(lastRead.value)
  }

  return { isUnread, markRead }
})
