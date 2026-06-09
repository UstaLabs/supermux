<script setup lang="ts">
import { computed } from "vue"
import { useWS } from "@/api/ws"

const ws = useWS()

const tone = computed(() => {
  switch (ws.status) {
    case "connected": return { dot: "bg-emerald-400", glow: "shadow-emerald-400/40", text: "Online" }
    case "connecting": return { dot: "bg-amber-400 animate-pulse", glow: "shadow-amber-400/40", text: "Connecting" }
    case "reconnecting": return { dot: "bg-amber-400 animate-pulse", glow: "shadow-amber-400/40", text: "Reconnecting" }
    case "offline":
    default: return { dot: "bg-zinc-500", glow: "shadow-zinc-500/20", text: "Offline" }
  }
})
</script>

<template>
  <span class="inline-flex items-center gap-1.5 text-[11px] text-muted-foreground select-none" :title="tone.text">
    <span :class="['inline-block size-2 rounded-full shadow-[0_0_6px]', tone.dot, tone.glow]" />
    <span class="hidden sm:inline">{{ tone.text }}</span>
  </span>
</template>
