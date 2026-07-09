<script setup lang="ts">
// The "+" attachment menu (Files / Camera / Record video), factored out of the
// composer so it can be rendered identically in the compact inline row, the
// expanded footer, and the desktop footer without duplicating the item list.
import type { HTMLAttributes } from "vue"
import {
  PromptInputActionMenu,
  PromptInputActionMenuTrigger,
  PromptInputActionMenuContent,
  PromptInputActionAddAttachments,
} from "@/components/ai-elements/prompt-input"
import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
import PromptInputActionAddRecordVideo from "@/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue"

defineProps<{ triggerClass?: HTMLAttributes["class"] }>()
</script>

<template>
  <PromptInputActionMenu>
    <PromptInputActionMenuTrigger :class="triggerClass" />
    <PromptInputActionMenuContent>
      <PromptInputActionAddAttachments label="Files" />
      <PromptInputActionAddCamera />
      <PromptInputActionAddRecordVideo />
    </PromptInputActionMenuContent>
  </PromptInputActionMenu>
</template>
