import { ref, computed, watch, onScopeDispose } from "vue"
import { useRoute } from "vue-router"
import { useWS } from "../api/ws"

// Re-assert the viewing state this often. Must stay well under the server's
// viewing TTL (5 min) so the entry never expires while the user sits on a chat.
const HEARTBEAT_MS = 60_000

export function useViewing(): void {
  const route = useRoute()
  const ws = useWS()

  const session = computed<string | null>(() => {
    if (!route.path.startsWith("/s/")) return null
    const id = route.params.id
    return typeof id === "string" ? id : null
  })
  const visible = ref(typeof document !== "undefined" && document.visibilityState === "visible")

  let lastSent: { session: string | null; visible: boolean } | null = null
  let timer: number | null = null

  const flush = () => {
    timer = null
    const next = { session: session.value, visible: visible.value }
    if (lastSent && lastSent.session === next.session && lastSent.visible === next.visible) return
    lastSent = next
    ws.send({ type: "viewing", session: next.session, visible: next.visible })
  }

  const schedule = () => {
    if (timer != null) return
    timer = window.setTimeout(flush, 50)
  }

  watch([session, visible], schedule)

  const onVis = () => { visible.value = document.visibilityState === "visible" }
  document.addEventListener("visibilitychange", onVis)

  watch(() => ws.status, (s, prev) => {
    if (s === "connected" && prev !== "connected") {
      lastSent = null
      schedule()
    }
  })

  // Heartbeat: the event-driven sends above only fire on route/visibility/
  // reconnect changes. A user reading a long, quiet agent turn produces none of
  // those, so without this the server's viewing entry would expire after 5 min
  // and push notifications would fire even though the chat is open and focused.
  const heartbeat = window.setInterval(() => {
    if (visible.value && ws.status === "connected") {
      ws.send({ type: "viewing", session: session.value, visible: visible.value })
    }
  }, HEARTBEAT_MS)

  onScopeDispose(() => {
    window.clearInterval(heartbeat)
    document.removeEventListener("visibilitychange", onVis)
  })

  schedule()
}
