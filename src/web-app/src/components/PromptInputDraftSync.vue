<script setup lang="ts">
// Renderless bridge between the composer's textInput and the synced drafts
// store. Must live INSIDE <PromptInput> so it can inject the composer context.
//   - restores the saved draft when the open session changes
//   - mirrors local edits into the store and debounces a `draft_set` per session
//   - applies a draft pushed from another device, unless the user is typing here
import { onBeforeUnmount, watch } from "vue"
import { usePromptInput } from "@/components/ai-elements/prompt-input"
import { useDrafts } from "@/stores/drafts"
import { useWS } from "@/api/ws"

const props = defineProps<{ sessionId: string }>()
const { textInput, focused } = usePromptInput()
const drafts = useDrafts()
const ws = useWS()

const DEBOUNCE_MS = 800
const timers = new Map<string, ReturnType<typeof setTimeout>>()
let applying = false // true only while we set textInput programmatically

function applyToTextarea(text: string) {
  applying = true
  textInput.value = text
  applying = false
}

function scheduleSync(session: string, text: string) {
  const prev = timers.get(session)
  if (prev) clearTimeout(prev)
  timers.set(session, setTimeout(() => {
    timers.delete(session)
    ws.send(text.length > 0 ? { type: "draft_set", session, text } : { type: "draft_clear", session })
  }, DEBOUNCE_MS))
}

// Restore the saved draft when the open session changes (and on mount).
watch(() => props.sessionId, (id) => applyToTextarea(drafts.get(id)), { immediate: true })

// Local edits → reflect in the store immediately, debounce the network sync.
// flush:'sync' makes `applying` reliable so programmatic applies don't echo back.
watch(textInput, (text) => {
  if (applying) return
  drafts.setLocal(props.sessionId, text)
  scheduleSync(props.sessionId, text)
}, { flush: "sync" })

// A draft pushed from another device → show it, unless this composer is focused
// (don't yank text out from under the user mid-type).
watch(() => drafts.get(props.sessionId), (text) => {
  if (text === textInput.value) return
  if (focused.value) return
  applyToTextarea(text)
})

onBeforeUnmount(() => {
  for (const t of timers.values()) clearTimeout(t)
  timers.clear()
})
</script>

<template>
  <!-- renderless -->
</template>
