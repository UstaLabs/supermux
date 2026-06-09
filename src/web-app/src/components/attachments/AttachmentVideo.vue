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
    <video
      v-else-if="shouldLoad"
      controls
      playsinline
      preload="metadata"
      :src="src"
      class="max-h-[300px] max-w-full rounded-md bg-black"
      @error="failed = true"
    />
    <div
      v-else
      class="h-[300px] w-full max-w-md bg-muted animate-pulse rounded-md"
      aria-hidden="true"
    />
  </div>
</template>
