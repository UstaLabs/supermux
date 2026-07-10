import { defineStore } from "pinia"
import { ref } from "vue"
import type { UploadResult } from "@/composables/useUploader"

export type UploadState =
  | { status: "pending" }
  | { status: "uploading"; startedAt: number; progress: number }
  | { status: "uploaded"; result: UploadResult }
  | { status: "failed"; error: string }

export const useUploads = defineStore("uploads", () => {
  const byId = ref<Record<string, UploadState>>({})

  function get(id: string): UploadState | undefined {
    return byId.value[id]
  }
  function start(id: string): void {
    byId.value[id] = { status: "uploading", startedAt: Date.now(), progress: 0 }
  }
  function setProgress(id: string, fraction: number): void {
    const s = byId.value[id]
    if (s?.status === "uploading") s.progress = Math.max(0, Math.min(1, fraction))
  }
  function succeed(id: string, result: UploadResult): void {
    byId.value[id] = { status: "uploaded", result }
  }
  function fail(id: string, error: string): void {
    byId.value[id] = { status: "failed", error }
  }
  function reset(id: string): void {
    delete byId.value[id]
  }
  function clearAll(): void {
    byId.value = {}
  }

  return { byId, get, start, setProgress, succeed, fail, reset, clearAll }
})
