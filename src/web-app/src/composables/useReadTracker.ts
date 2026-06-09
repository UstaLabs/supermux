import { computed, ref, watch } from "vue"
import { useRoute } from "vue-router"
import { useMessages } from "../stores/messages"
import { useUnread } from "../stores/unread"

export function useReadTracker(): void {
  const route = useRoute()
  const messages = useMessages()
  const unread = useUnread()

  const session = computed<string | null>(() => {
    if (!route.path.startsWith("/s/")) return null
    const id = route.params.id
    return typeof id === "string" ? id : null
  })
  const visible = ref(typeof document !== "undefined" && document.visibilityState === "visible")
  document.addEventListener("visibilitychange", () => {
    visible.value = document.visibilityState === "visible"
  })

  const messageCount = computed(() => {
    const s = session.value
    if (!s) return 0
    return messages.bySession[s]?.length ?? 0
  })

  watch(
    [session, visible, messageCount],
    () => {
      const s = session.value
      if (s && visible.value) unread.markRead(s)
    },
    { immediate: true },
  )
}
