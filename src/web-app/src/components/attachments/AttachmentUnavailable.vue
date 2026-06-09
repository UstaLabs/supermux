<script setup lang="ts">
import { FileWarning } from "lucide-vue-next"

defineProps<{ name?: string }>()
</script>

<template>
  <div class="flex items-center gap-3 p-3 border border-border rounded-md bg-muted/40 text-muted-foreground max-w-sm">
    <FileWarning class="size-5" />
    <div class="min-w-0 flex-1 text-sm">
      <div class="truncate">{{ name ?? "Attachment unavailable" }}</div>
      <div class="text-xs opacity-70">file no longer accessible</div>
    </div>
  </div>
</template>
