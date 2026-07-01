<script setup lang="ts">
// Renderless bridge between the launcher composer's textInput and the local
// draft store. Must live INSIDE <PromptInput> so it can inject the composer
// context (see usePromptInput). Mirrors PromptInputDraftSync.vue's debounce,
// but there's no session yet to sync over the wire — just localStorage via
// the launcherDraft store, no cross-device remote-apply branch.
import { onBeforeUnmount, watch } from "vue"
import { usePromptInput } from "@/components/ai-elements/prompt-input"
import { useLauncherDraft } from "@/stores/launcherDraft"

const { textInput } = usePromptInput()
const draft = useLauncherDraft()

const DEBOUNCE_MS = 800
let timer: ReturnType<typeof setTimeout> | undefined

watch(textInput, (text) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { draft.setText(text) }, DEBOUNCE_MS)
})

onBeforeUnmount(() => {
  if (timer) {
    clearTimeout(timer)
    draft.setText(textInput.value)
  }
})
</script>

<template>
  <!-- renderless -->
</template>
