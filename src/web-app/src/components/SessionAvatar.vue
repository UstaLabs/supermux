<script setup lang="ts">
import { computed } from "vue"
import { Loader2Icon, PauseIcon } from "lucide-vue-next"
import AgentLogo from "@/components/AgentLogo.vue"

const props = defineProps<{
  name: string
  connected: boolean
  agent?: string
  working?: boolean
  suspended?: boolean
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
    <!-- Status badge (same slot): running spinner takes precedence over the
         suspended marker — a suspended session is never working, so in practice
         they don't co-occur. -->
    <span
      v-if="props.working"
      role="status"
      aria-label="Agent is working"
      class="absolute -bottom-1 -right-1 z-10 grid size-[18px] place-items-center rounded-full bg-card ring-1 ring-border/70"
    >
      <Loader2Icon class="size-3 animate-spin text-primary" aria-hidden="true" />
    </span>
    <span
      v-else-if="props.suspended"
      role="img"
      aria-label="Session suspended"
      class="absolute -bottom-1 -right-1 z-10 grid size-[18px] place-items-center rounded-full bg-card ring-1 ring-border/70"
    >
      <PauseIcon class="size-3 text-amber-500 fill-amber-500" aria-hidden="true" />
    </span>
  </div>
</template>
