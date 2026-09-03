import { defineStore } from "pinia"
import { ref } from "vue"

export type UsageProvider = "claude" | "codex" | "cursor" | "opencode" | "grok"
export type UsageSource = "live" | "agent" | "local" | "cache"

export interface UsageSnapshot {
  claude: unknown
  codex: unknown
  cursor: unknown
  opencode: unknown
  grok: unknown
  errors: Record<string, string>
  fetchedAt?: Partial<Record<UsageProvider, string | null>>
  source?: Partial<Record<UsageProvider, UsageSource | null>>
  refreshing?: string[]
}

export const useUsage = defineStore("usage", () => {
  const snapshot = ref<UsageSnapshot | null>(null)

  function set(snap: UsageSnapshot | null | undefined) {
    if (!snap || typeof snap !== "object") return
    snapshot.value = snap
  }

  function fetchedAt(provider: UsageProvider): string | null {
    return snapshot.value?.fetchedAt?.[provider] ?? null
  }

  function isRefreshing(provider: UsageProvider): boolean {
    return snapshot.value?.refreshing?.includes(provider) === true
  }

  return { snapshot, set, fetchedAt, isRefreshing }
})
