<script setup lang="ts">
import { BrainIcon } from "lucide-vue-next"
const props = defineProps<{ label?: string; live?: boolean }>()
</script>
<template>
  <div class="flex items-center gap-1.5 px-1 py-0.5 text-xs italic text-muted-foreground/80">
    <BrainIcon :class="['size-3.5 shrink-0', { 'animate-pulse': props.live }]" />
    {{ props.label ?? "Thinking…" }}
  </div>
</template>
