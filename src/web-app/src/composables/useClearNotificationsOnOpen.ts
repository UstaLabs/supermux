import { watch, onScopeDispose } from "vue"
import { useRoute } from "vue-router"
import { clearChatNotifications } from "../lib/notifications"

// When the user opens (and is looking at) a chat, drop that chat's already-delivered push
// notifications — the web parity for the native apps' clear-on-open. This only CLOSES
// notifications already shown on this client; it never suppresses a push (which on iOS
// Safari PWAs would get the subscription revoked — see the ios-webkit notes). Fires on chat
// navigation and whenever the tab becomes visible again on an open chat.
export function useClearNotificationsOnOpen(): void {
  const route = useRoute()

  const openSessionId = (): string | null => {
    if (!route.path.startsWith("/s/")) return null
    const id = route.params.id
    return typeof id === "string" ? id : null
  }

  const clear = async () => {
    const id = openSessionId()
    if (!id) return
    if (typeof document !== "undefined" && document.visibilityState !== "visible") return
    if (typeof navigator === "undefined" || !navigator.serviceWorker) return
    const reg = await navigator.serviceWorker.ready
    await clearChatNotifications(reg, id)
  }

  watch(() => route.path, () => { void clear() }, { immediate: true })

  const onVisible = () => { if (document.visibilityState === "visible") void clear() }
  if (typeof document !== "undefined") document.addEventListener("visibilitychange", onVisible)
  onScopeDispose(() => {
    if (typeof document !== "undefined") document.removeEventListener("visibilitychange", onVisible)
  })
}
