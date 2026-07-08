// src/web-app/src/stores/bgTasks.ts
// Per-session background tasks (bg shells / subagents / workflows) mirrored
// from the broker's bg_tasks frames + snapshot. Render-only.
import { defineStore } from "pinia"
import { ref } from "vue"

export interface BgTask {
  id: string
  kind: "shell" | "agent" | "workflow" | "task"
  label: string
  startedAt: number
  status: "running" | "completed" | "failed"
  endedAt?: number
  summary?: string
}

export const useBgTasks = defineStore("bgTasks", () => {
  const bySession = ref<Record<string, BgTask[]>>({})

  function set(session: string, tasks: BgTask[] | undefined) {
    if (Array.isArray(tasks)) bySession.value[session] = tasks
  }
  function get(session: string): BgTask[] {
    return bySession.value[session] ?? []
  }
  function openCount(session: string): number {
    return get(session).filter((t) => t.status === "running").length
  }
  function clear(session: string) {
    delete bySession.value[session]
  }

  return { bySession, set, get, openCount, clear }
})
