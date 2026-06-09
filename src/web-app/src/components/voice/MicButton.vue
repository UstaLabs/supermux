<script setup lang="ts">
import { Mic } from "lucide-vue-next"

interface Props { disabled?: boolean }
defineProps<Props>()
const emit = defineEmits<{ (e: "start"): void }>()
</script>

<template>
  <button
    type="button"
    class="size-8 rounded-full text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50 disabled:pointer-events-none transition flex items-center justify-center"
    aria-label="Record voice message"
    :disabled="disabled"
    @click="emit('start')"
  >
    <Mic class="size-4" />
  </button>
</template>
