<script setup lang="ts">
import { ref } from "vue"
import { fileUrl } from "@/lib/fileUrl"
import { useLazyInView } from "@/composables/useLazyInView"
import { useLightbox } from "@/composables/useLightbox"
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
const lightbox = useLightbox()
const src = fileUrl(props.file_id)

function open() {
  lightbox.open({ file_id: props.file_id, name: props.name })
}
</script>

<template>
  <div ref="root" class="max-w-full">
    <AttachmentUnavailable v-if="failed" :name="name" />
    <button
      v-else-if="shouldLoad"
      type="button"
      class="block max-w-full overflow-hidden rounded-md bg-muted/30 hover:bg-muted/50 transition"
      @click="open"
    >
      <img
        :src="src"
        :alt="name ?? 'attachment'"
        class="max-h-[250px] object-contain"
        decoding="async"
        loading="lazy"
        @error="failed = true"
      />
    </button>
    <div
      v-else
      class="h-[250px] w-full max-w-sm bg-muted animate-pulse rounded-md"
      aria-hidden="true"
    />
  </div>
</template>
