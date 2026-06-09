<script setup lang="ts">
import { ref } from "vue"
import { fileUrl } from "@/lib/fileUrl"
import { useLazyInView } from "@/composables/useLazyInView"
import AttachmentUnavailable from "./AttachmentUnavailable.vue"

const props = defineProps<{
  file_id: string
  name?: string
  mime?: string
  size?: number
}>()

const root = ref<HTMLElement | null>(null)
const shouldLoad = useLazyInView(root)
const failed = ref(false)
const src = fileUrl(props.file_id)
</script>

<template>
  <div ref="root" class="max-w-full">
    <AttachmentUnavailable v-if="failed" :name="name" />
    <audio
      v-else-if="shouldLoad"
      controls
      preload="metadata"
      :src="src"
      class="max-w-full"
      @error="failed = true"
    />
    <div
      v-else
      class="h-10 w-48 bg-muted animate-pulse rounded-md"
      aria-hidden="true"
    />
  </div>
</template>
