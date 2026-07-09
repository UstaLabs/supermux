<script setup lang="ts">
import { ref, watch } from "vue"
import { Brain, Check, ChevronDown } from "lucide-vue-next"
import { api } from "@/api/client"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  type ReasoningLevelOption,
  resolveReasoningLevel,
  showReasoningPicker,
} from "@/lib/reasoning-levels"

const props = defineProps<{
  level: string
  agent: string
  model: string
}>()

const emit = defineEmits<{
  "update:level": [value: string]
}>()

const open = ref(false)
const levels = ref<ReasoningLevelOption[]>([])
const visible = ref(false)

// Levels come from the broker (Claude static, Codex per-model, others none), so
// this refetches whenever the agent or model changes. After each fetch it makes
// sure the bound value is valid for what's on offer and defaults a new session
// to High — clearing to "" when the agent/model has no levels so the launcher
// doesn't send a stale one. Idempotent once the value is already valid.
async function refresh() {
  try {
    const res = await api.getReasoningLevels(props.agent, props.model || undefined)
    levels.value = res.levels ?? []
    visible.value = res.visible !== false && showReasoningPicker(levels.value)
  } catch {
    levels.value = []
    visible.value = false
  }
  const resolved = visible.value ? resolveReasoningLevel(levels.value, props.level) : undefined
  const next = resolved ?? ""
  if (next !== props.level) emit("update:level", next)
}

watch(() => [props.agent, props.model], refresh, { immediate: true })

function pick(id: string) {
  emit("update:level", id)
  open.value = false
}
</script>

<template>
  <DropdownMenu v-if="visible" v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="inline-flex max-w-full items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        aria-label="Thinking level"
        :title="`Thinking level: ${level}`"
      >
        <Brain class="size-3 shrink-0 opacity-70" />
        <span class="truncate capitalize">{{ level }}</span>
        <ChevronDown class="size-3 shrink-0 opacity-60" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent align="start" class="w-56 max-h-72 overflow-y-auto p-1">
      <p class="px-2 pt-1 pb-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        Thinking level
      </p>
      <button
        v-for="l in levels"
        :key="l.id"
        type="button"
        class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
        @click="pick(l.id)"
      >
        <span class="min-w-0 flex-1">
          <span class="block truncate capitalize">{{ l.id }}</span>
          <span v-if="l.description" class="block truncate text-xs text-muted-foreground">{{ l.description }}</span>
        </span>
        <Check v-if="level === l.id" class="size-4 shrink-0 text-primary" />
      </button>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
