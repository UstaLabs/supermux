<script setup lang="ts">
import { computed } from "vue"
import AgentLogo from "@/components/AgentLogo.vue"

const props = defineProps<{
  name: string
  connected: boolean
  agent?: string
}>()

const initials = computed(() => props.name.slice(0, 2).toUpperCase())

const avatarStyle = computed(() => {
  if (props.agent) return { backgroundColor: "#f7f4ee", color: "#111111" }
  return { backgroundColor: "var(--primary)", color: "var(--primary-foreground)" }
})
</script>

<template>
  <div class="relative shrink-0">
    <div
      :style="avatarStyle"
      class="size-10 rounded-xl flex items-center justify-center text-[12px] font-semibold tracking-wide ring-1 ring-border/70"
    >
      <AgentLogo v-if="props.agent" :agent="props.agent" :invert-on-dark="false" class="size-5 object-contain" />
      <span v-else>{{ initials }}</span>
    </div>
  </div>
</template>
