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
    <div v-else-if="shouldLoad" class="flex items-center gap-2 max-w-full">
      <audio
        controls
        preload="metadata"
        :src="src"
        class="max-w-full min-w-0 flex-1"
        @error="failed = true"
      />
      <a
        :href="src"
        :download="name ?? file_id"
        class="p-2 rounded-md border border-border text-muted-foreground hover:bg-accent shrink-0"
        aria-label="Download audio"
        title="Download"
      >
        <Download class="size-4" />
      </a>
    </div>
    <div
      v-else
      class="h-10 w-48 bg-muted animate-pulse rounded-md"
      aria-hidden="true"
    />
  </div>
</template>
