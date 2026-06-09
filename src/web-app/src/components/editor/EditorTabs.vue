<script setup lang="ts">
import { X } from "@lucide/vue"
import type { EditorTab } from "@/composables/useEditor"

defineProps<{
  tabs: EditorTab[]
  activeTabPath: string | null
  dirtyTabs: Set<string>
}>()

const emit = defineEmits<{
  select: [path: string]
  close: [path: string]
}>()

function displayName(path: string): string {
  return path.split("/").pop() ?? path
}

function dirHint(path: string): string {
  const parts = path.split("/")
  if (parts.length <= 2) return ""
  return parts.slice(0, -1).join("/").replace(/^\//, "")
}
</script>

<template>
  <div class="flex items-end overflow-x-auto bg-[var(--cmux-session-list)] border-b border-border min-h-[34px] px-2 gap-1 scrollbar-none">
    <button
      v-for="tab in tabs"
      :key="tab.path"
      class="flex items-center gap-1.5 px-2.5 h-[28px] text-[12px] border border-b-0 border-border rounded-t-md shrink-0 transition-colors group"
      :class="tab.path === activeTabPath
        ? 'bg-card text-foreground'
        : 'bg-transparent text-muted-foreground hover:text-foreground hover:bg-card/60'"
      @click="emit('select', tab.path)"
      @mousedown.middle.prevent="emit('close', tab.path)"
    >
      <span v-if="tab.path === activeTabPath" class="size-1.5 rounded-full bg-primary shrink-0" />
      <span v-if="dirtyTabs.has(tab.path)" class="size-1.5 rounded-full bg-amber-400 shrink-0" />
      <span class="truncate max-w-[120px]">{{ displayName(tab.path) }}</span>
      <span v-if="dirHint(tab.path)" class="text-[10px] text-muted-foreground/60 truncate max-w-[80px]">{{ dirHint(tab.path) }}</span>
      <span
        class="ml-1 p-0.5 rounded opacity-60 hover:opacity-100 hover:bg-foreground/10 transition-opacity"
        @click.stop="emit('close', tab.path)"
      >
        <X class="size-3" />
      </span>
    </button>
  </div>
</template>
