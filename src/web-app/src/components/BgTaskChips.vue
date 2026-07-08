<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { CheckIcon, HourglassIcon, XIcon } from "lucide-vue-next"
import { useBgTasks, type BgTask } from "@/stores/bgTasks"
import { useAgentState } from "@/stores/agentState"

const props = defineProps<{ session: string }>()
const bgTasks = useBgTasks()
const agentState = useAgentState()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { if (timer) clearInterval(timer) })

// Closed chips get their ✓/✕ moment only while claude is still reacting (any
// task open, or the agent working). Idle with nothing open → chips are done;
// the story lives in the chat stream.
const visible = computed<BgTask[]>(() => {
  const tasks = bgTasks.get(props.session)
  const open = tasks.filter((t) => t.status === "running")
  if (open.length > 0 || agentState.get(props.session).working) return tasks
  return []
})

function elapsed(t: BgTask): string {
  const ms = (t.endedAt ?? now.value) - t.startedAt
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`
}
</script>

<template>
  <div v-if="visible.length" class="flex flex-wrap gap-1.5 px-1 py-1 ml-2" data-testid="bg-task-chips">
    <span
      v-for="t in visible" :key="t.id"
      class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 font-mono text-[11px]"
      :class="t.status === 'failed'
        ? 'border-destructive/40 bg-destructive/10 text-destructive'
        : t.status === 'completed'
          ? 'border-border bg-muted/40 text-muted-foreground'
          : 'border-border bg-muted/40 text-foreground/80'"
      :title="t.summary ?? t.label"
    >
      <HourglassIcon v-if="t.status === 'running'" class="size-3 shrink-0 text-amber-500 animate-pulse" />
      <CheckIcon v-else-if="t.status === 'completed'" class="size-3 shrink-0 text-emerald-500" />
      <XIcon v-else class="size-3 shrink-0" />
      <span class="max-w-[16rem] truncate">{{ t.label }}</span>
      <span class="opacity-60">· {{ t.status === 'running' ? elapsed(t) : t.status }}</span>
    </span>
  </div>
</template>
