import { defineStore } from "pinia"
import { ref } from "vue"

export interface ActivityEntry {
  ts: string
  kind: "thinking" | "tool" | "tool_result"
  tool?: string
  title: string
  detail?: string
  phase?: "started" | "completed"
  truncated?: boolean
  seq?: number
  callId?: string
}

export const useActivity = defineStore("activity", () => {
  const bySession = ref<Record<string, ActivityEntry[]>>({})

  function replace(session: string, entries: ActivityEntry[]) {
    bySession.value[session] = entries ?? []
  }
  function append(session: string, entry: ActivityEntry) {
    const list = bySession.value[session] ?? (bySession.value[session] = [])
    list.push(entry)
    if (list.length > 500) list.splice(0, list.length - 500)
  }

  return { bySession, replace, append }
})
