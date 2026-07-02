<script setup lang="ts">
import type { AttachmentRef } from "@/stores/messages"
import AttachmentImage from "./AttachmentImage.vue"
import AttachmentAudio from "./AttachmentAudio.vue"
import AttachmentVideo from "./AttachmentVideo.vue"
import AttachmentFile from "./AttachmentFile.vue"

defineProps<{ attachments: AttachmentRef[] | undefined }>()

function rendererFor(kind: AttachmentRef["kind"]) {
  if (kind === "photo") return AttachmentImage
  if (kind === "audio" || kind === "voice") return AttachmentAudio
  if (kind === "video" || kind === "video_note") return AttachmentVideo
  return AttachmentFile
}
</script>

<template>
  <div v-if="attachments?.length" class="space-y-2">
    <component
      v-for="a in attachments"
      :key="a.file_id"
      :is="rendererFor(a.kind)"
      :file_id="a.file_id"
      :name="a.name"
      :mime="a.mime"
      :size="a.size"
    />
  </div>
</template>
