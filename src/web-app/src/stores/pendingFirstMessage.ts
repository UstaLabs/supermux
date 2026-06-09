import { ref } from "vue"
import type { PromptInputMessage } from "@/components/ai-elements/prompt-input"

const pending = ref<{ sessionId: string; payload: PromptInputMessage } | null>(null)

export function usePendingFirstMessage() {
  function set(sessionId: string, payload: PromptInputMessage) {
    pending.value = { sessionId, payload }
  }

  function consume(sessionId: string): PromptInputMessage | null {
    if (pending.value?.sessionId !== sessionId) return null
    const payload = pending.value.payload
    pending.value = null
    return payload
  }

  function clear() {
    pending.value = null
  }

  return { set, consume, clear }
}
