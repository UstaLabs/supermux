import { ref, watch, type Ref } from "vue"
import { api } from "@/api/client"
import type { SlashCommand } from "@/stores/commands"

/** Agent slash commands for the /new launcher, keyed by selected agent + workdir. */
export function useLauncherCommands(
  agent: Ref<"claude" | "codex" | "cursor" | "opencode">,
  workdir: Ref<string>,
) {
  const commands = ref<SlashCommand[]>([])
  const loading = ref(false)
  let seq = 0

  watch([agent, workdir], async ([kind, path]) => {
    const trimmed = path.trim()
    if (!trimmed) {
      commands.value = []
      loading.value = false
      return
    }
    const mySeq = ++seq
    loading.value = true
    try {
      const result = await api.previewCommands(kind, trimmed)
      if (mySeq !== seq) return
      commands.value = (result.commands ?? []) as SlashCommand[]
      loading.value = !result.resolved
    } catch {
      if (mySeq !== seq) return
      commands.value = []
      loading.value = false
    }
  }, { immediate: true })

  return { commands, loading }
}
