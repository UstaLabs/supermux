import { defineStore } from "pinia"
import { ref } from "vue"

export interface DisplayStream {
  id: string
  sessionName: string
  provider: "linux-xvfb" | "macos-screen"
  transport: "vnc" | "h264"
  display: string
  status: "running" | "errored"
  createdAt: string
}

export const useDisplays = defineStore("displays", () => {
  const list = ref<DisplayStream[]>([])
  function replace(next: DisplayStream[]) { list.value = next }
  function add(d: DisplayStream) {
    const idx = list.value.findIndex((x) => x.id === d.id)
    if (idx >= 0) list.value[idx] = d
    else list.value.push(d)
  }
  function remove(id: string) { list.value = list.value.filter((x) => x.id !== id) }
  function runningForSession(name: string): DisplayStream | undefined {
    return list.value
      .filter((d) => d.sessionName === name && d.status === "running")
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
  }
  return { list, replace, add, remove, runningForSession }
})
