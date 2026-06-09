<script setup lang="ts">
import { Star, Zap, Trash2 } from "lucide-vue-next"
import AgentLogo from "@/components/AgentLogo.vue"
import { Button } from "@/components/ui/button"

const props = defineProps<{
  id: string
  name: string
  agent?: string
  model?: string
  workdir: string
  connected: boolean
  isDefault?: boolean
}>()

const emit = defineEmits<{
  (e: "switch", id: string): void
  (e: "kill", id: string, name: string): void
}>()

function onSwitch() {
  emit("switch", props.id)
}

function onKill() {
  emit("kill", props.id, props.name)
}
</script>

<template>
  <div class="rounded-lg border border-border bg-card p-4 flex flex-col gap-3">
    <div class="flex items-start justify-between gap-3">
      <div class="flex items-center gap-3 min-w-0">
        <div class="size-10 rounded-lg bg-background ring-1 ring-border flex items-center justify-center shrink-0">
          <AgentLogo :agent="agent" class="size-6" />
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-1.5">
            <span class="font-semibold truncate">{{ name }}</span>
            <Star v-if="isDefault" class="size-3.5 text-amber-400 fill-amber-400 shrink-0" title="Default PA" />
          </div>
          <div class="text-[11px] text-muted-foreground truncate">
            {{ model || "Default model" }}
          </div>
        </div>
      </div>
      <span
        class="inline-flex items-center gap-1.5 text-[11px] font-medium shrink-0"
        :class="connected ? 'text-emerald-500' : 'text-muted-foreground'"
      >
        <span class="inline-block size-1.5 rounded-full" :class="connected ? 'bg-emerald-500' : 'bg-muted-foreground'" />
        {{ connected ? "Online" : "Offline" }}
      </span>
    </div>

    <div class="text-[11px] text-muted-foreground font-mono truncate">
      {{ workdir }}
    </div>

    <div class="flex items-center gap-2">
      <Button size="xs" variant="outline" class="gap-1" @click="onSwitch">
        <Zap class="size-3" />
        Switch
      </Button>
      <Button size="xs" variant="ghost" class="text-red-400 hover:text-red-300 hover:bg-red-500/10 gap-1" @click="onKill">
        <Trash2 class="size-3" />
        Kill
      </Button>
    </div>
  </div>
</template>
