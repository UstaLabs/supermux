import { onMounted, onBeforeUnmount } from "vue"
import { useRoute, useRouter } from "vue-router"
import { KEYBINDING_COMMAND_MAP, matchKeydown } from "@/lib/keybindings"
import { isEditableTarget, isPaneKeyboardTarget } from "@/lib/is-editable-target"
import { useIsDesktop } from "./useIsDesktop"
import { useLayout } from "@/stores/layout"
import { useKeybindings } from "@/stores/keybindings"
import { useSessions } from "@/stores/sessions"

export function useWorkspaceShortcuts() {
  const isDesktop = useIsDesktop()
  const route = useRoute()
  const router = useRouter()
  const layout = useLayout()
  const keybindings = useKeybindings()
  const sessions = useSessions()

  function onKeydown(e: KeyboardEvent) {
    if (!isDesktop.value) return
    if (isEditableTarget(e.target)) return
    if (isPaneKeyboardTarget(e.target)) return

    const commandId = matchKeydown(e, keybindings.state.overrides)
    if (!commandId) return

    const cmd = KEYBINDING_COMMAND_MAP.get(commandId)
    if (!cmd) return

    const sessionId = route.name === "session-chat" ? String(route.params.id) : undefined
    const isSessionArchived = sessionId
      ? !sessions.list.some((s) => s.id === sessionId)
      : undefined

    if (
      commandId !== "workspace.toggleSidebar"
      && commandId !== "workspace.newSession"
      && !sessionId
    ) return

    e.preventDefault()
    cmd.handler({
      layout,
      router,
      route,
      sessionId,
      isSessionArchived,
    })
  }

  onMounted(() => document.addEventListener("keydown", onKeydown))
  onBeforeUnmount(() => document.removeEventListener("keydown", onKeydown))
}
