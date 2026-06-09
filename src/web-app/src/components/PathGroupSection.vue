<script setup lang="ts">
import { ChevronDown } from "lucide-vue-next"

defineProps<{
  label: string
  collapsed: boolean
  count: number
}>()

defineEmits<{ (e: "toggle"): void }>()
</script>

<template>
  <div class="path-group">
    <button
      type="button"
      class="flex items-center gap-1.5 w-full px-3 py-1.5 text-left hover:bg-muted/40 transition-colors"
      :aria-expanded="!collapsed"
      @click="$emit('toggle')"
    >
      <ChevronDown
        class="size-3.5 shrink-0 text-muted-foreground transition-transform duration-150"
        :class="{ '-rotate-90': collapsed }"
      />
      <span class="text-[11px] font-medium text-muted-foreground font-mono truncate min-w-0 flex-1">
        {{ label }}
      </span>
      <span v-if="count > 1" class="text-[10px] tabular-nums text-muted-foreground/60 shrink-0">
        {{ count }}
      </span>
    </button>
    <div v-show="!collapsed">
      <slot />
    </div>
  </div>
</template>
