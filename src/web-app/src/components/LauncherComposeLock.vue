<script setup lang="ts">
// Renderless. Lives INSIDE <PromptInput> so it can inject the composer context.
// Emits `engaged` the first time the user puts content in the composer (text or
// an attachment). The launcher uses that to stop auto-switching the project
// default once the user has started composing — otherwise a message arriving in
// another session reshuffles the recency order and swaps the project out from
// under them mid-compose.
import { watch } from "vue"
import { usePromptInput } from "@/components/ai-elements/prompt-input"

const emit = defineEmits<{ (e: "engaged"): void }>()
const { textInput, files } = usePromptInput()

const stop = watch([textInput, files], ([text, fs]) => {
  if (text.trim().length === 0 && fs.length === 0) return
  emit("engaged")
  stop()
})
</script>

<template>
  <!-- renderless -->
</template>
