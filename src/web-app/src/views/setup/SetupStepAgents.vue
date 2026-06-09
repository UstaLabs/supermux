<script setup lang="ts">
import AgentLoginPanel from "@/components/AgentLoginPanel.vue"

const emit = defineEmits<{
  (e: "update:canProceed", v: boolean): void
}>()
</script>

<template>
  <AgentLoginPanel
    title="Connect an agent"
    subtitle="At least one agent must be authenticated before proceeding."
    @update:canProceed="(v) => emit('update:canProceed', v)"
  />
</template>
