<script setup lang="ts">
import { computed } from "vue"
import { X } from "@lucide/vue"
import { uriToWorkdirPath, type SymbolLocation } from "@/lib/lsp-symbol-navigation"

const props = defineProps<{
  title: string
  locations: SymbolLocation[]
  workdir: string
}>()

const emit = defineEmits<{
  select: [location: SymbolLocation]
  close: []
}>()

const entries = computed(() => props.locations.flatMap((location) => {
  const path = uriToWorkdirPath(location.uri, props.workdir)
  return path == null ? [] : [{ location, path }]
}))
</script>

<template>
  <div class="absolute inset-x-0 bottom-0 z-20 max-h-[42%] overflow-hidden border-t border-border bg-[var(--cmux-header)] shadow-[0_-8px_24px_rgba(0,0,0,0.2)]">
    <div class="flex items-center gap-2 border-b border-border px-3 py-2">
      <span class="text-[12px] font-medium text-foreground">{{ title }}</span>
      <button class="cmux-icon-button ml-auto size-6" title="Close locations" @click="emit('close')">
        <X class="size-3.5" />
      </button>
    </div>
    <div class="max-h-[calc(42vh-38px)] overflow-y-auto py-1">
      <button
        v-for="({ location, path }, index) in entries"
        :key="`${location.uri}:${location.range.start.line}:${location.range.start.character}:${index}`"
        class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-[12px] hover:bg-foreground/5"
        @click="emit('select', location)"
      >
        <span class="min-w-0 flex-1 truncate font-mono text-foreground">{{ path }}</span>
        <span class="shrink-0 font-mono text-muted-foreground">
          {{ location.range.start.line + 1 }}:{{ location.range.start.character + 1 }}
        </span>
      </button>
    </div>
  </div>
</template>
