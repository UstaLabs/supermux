<script setup lang="ts">
import { Download, File as FileIcon } from "lucide-vue-next"
import { fileUrl } from "@/lib/fileUrl"

defineProps<{
  file_id: string
  name?: string
  mime?: string
  size?: number
}>()

function fmtSize(n?: number): string {
  if (n === undefined) return ""
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <a
    :href="fileUrl(file_id)"
    :download="name ?? file_id"
    class="flex items-center gap-3 p-3 border border-border rounded-md hover:bg-accent transition w-full max-w-sm text-left"
  >
    <FileIcon class="size-6 text-muted-foreground shrink-0" />
    <div class="min-w-0 flex-1">
      <div class="font-medium truncate">{{ name ?? "file" }}</div>
      <div class="text-xs text-muted-foreground">{{ fmtSize(size) }}</div>
    </div>
    <Download class="size-4 text-muted-foreground shrink-0" />
  </a>
</template>
