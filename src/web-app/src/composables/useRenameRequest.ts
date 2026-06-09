import { ref } from "vue"

// Module-level shared signal so the collapsed rail can ask the full session
// list to start its inline rename. The rail expands the sidebar and sets the
// pending name; SessionListView consumes it once mounted, then clears it.
const pending = ref<string | null>(null)

export function useRenameRequest() {
  function requestRename(name: string) {
    pending.value = name
  }
  function consumeRenameRequest(): string | null {
    const v = pending.value
    pending.value = null
    return v
  }
  return { pending, requestRename, consumeRenameRequest }
}
