// src/web-app/src/stores/voice-previews.ts
import { defineStore } from "pinia"
import { ref } from "vue"

export const useVoicePreviews = defineStore("voice-previews", () => {
  const byFilename = ref<Record<string, number[]>>({})

  function set(filename: string, samples: number[]): void {
    byFilename.value[filename] = samples
  }

  function get(filename: string): number[] | undefined {
    return byFilename.value[filename]
  }

  function remove(filename: string): void {
    delete byFilename.value[filename]
  }

  function clearAll(): void {
    byFilename.value = {}
  }

  return { byFilename, set, get, remove, clearAll }
})
