<script setup lang="ts">
import { ref } from "vue"
import { Download } from "lucide-vue-next"
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
    <div v-else-if="shouldLoad" class="relative inline-block max-w-full">
      <video
        controls
        playsinline
        preload="metadata"
        :src="src"
        class="max-h-[300px] max-w-full rounded-md bg-black block"
        @error="failed = true"
      />
      <a
        :href="src"
        :download="name ?? file_id"
        class="absolute bottom-2 right-2 p-1.5 rounded-full bg-black/50 text-white hover:bg-black/70"
        aria-label="Download video"
        title="Download"
      >
        <Download class="size-4" />
      </a>
    </div>
    <div
      v-else
      class="h-[300px] w-full max-w-md bg-muted animate-pulse rounded-md"
      aria-hidden="true"
    />
  </div>
</template>
