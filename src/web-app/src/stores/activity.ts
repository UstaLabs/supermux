import { defineStore } from "pinia"
import { ref } from "vue"
import type { ActivityToolBody } from "@/lib/activity-body"

export interface ActivityEntry {
  ts: string
  kind: "thinking" | "tool" | "tool_result"
  tool?: string
  title: string
  detail?: string
  /** Human "why" label from the agent when present. */
  description?: string
  phase?: "started" | "completed"
  truncated?: boolean
  seq?: number
  callId?: string
  body?: ActivityToolBody
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
