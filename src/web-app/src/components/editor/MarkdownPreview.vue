<script setup lang="ts">
// Rendered, read-only preview for markdown files. Reuses MessageText (the chat
// renderer) so we inherit the sanitized markdown pipeline, file-link handling,
// code copy buttons, and prose styling — just wrapped in document chrome.
import MessageText from "@/components/MessageText.vue"

defineProps<{ content: string }>()
const emit = defineEmits<{ openFile: [path: string, line?: number, endLine?: number] }>()

function onOpenFile(path: string, line?: number, endLine?: number) {
  emit("openFile", path, line, endLine)
}
</script>

<template>
  <div class="h-full overflow-y-auto bg-[var(--cmux-code)] text-foreground">
    <div class="mx-auto max-w-[820px] px-5 py-5">
      <MessageText :content="content" @open-file="onOpenFile" />
    </div>
  </div>
</template>
