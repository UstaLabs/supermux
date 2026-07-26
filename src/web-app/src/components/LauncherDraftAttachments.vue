<script setup lang="ts">
// Renderless: rehydrates draft attachments into the PromptInput composer and
// marks them as already-uploaded so submit reuses durable server file_ids.
// Must live INSIDE <PromptInput> so it can inject the composer context.
import { onMounted } from "vue"
import { nanoid } from "nanoid"
import { usePromptInput } from "@/components/ai-elements/prompt-input"
import { useUploads } from "@/stores/uploads"
import { fileUrl } from "@/lib/fileUrl"

const props = defineProps<{
  attachments?: Array<{ file_id: string; name?: string; mime?: string; size?: number }>
}>()

const { seedUploadedFiles } = usePromptInput()
const uploads = useUploads()

onMounted(() => {
  const items = (props.attachments ?? []).filter((a) => a?.file_id)
  if (!items.length) return
  const seeded = items.map((a) => {
    const id = nanoid()
    uploads.succeed(id, {
      file_id: a.file_id,
      size: a.size ?? 0,
      mime: a.mime ?? "",
      name: a.name ?? "file",
    })
    return {
      id,
      file_id: a.file_id,
      name: a.name,
      mime: a.mime,
      size: a.size,
      url: fileUrl(a.file_id),
    }
  })
  seedUploadedFiles(seeded)
})
</script>

<template>
  <!-- renderless -->
</template>
