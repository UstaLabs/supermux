<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { HourglassIcon } from "lucide-vue-next"
import { useBgTasks, type BgTask } from "@/stores/bgTasks"

const props = defineProps<{ session: string }>()
const bgTasks = useBgTasks()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { if (timer) clearInterval(timer) })

// Only RUNNING tasks get a chip — a task's chip clears the moment it finishes, so
// chips never accumulate. The outcome (done/failed) lives in the chat stream.
const visible = computed<BgTask[]>(() => bgTasks.get(props.session).filter((t) => t.status === "running"))

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
      class="inline-flex items-center gap-1.5 rounded-full border border-border bg-muted/40 px-2.5 py-0.5 font-mono text-[11px] text-foreground/80"
      :title="t.summary ?? t.label"
    >
      <HourglassIcon class="size-3 shrink-0 text-amber-500 animate-pulse" />
      <span class="max-w-[16rem] truncate">{{ t.label }}</span>
      <span class="opacity-60">· {{ elapsed(t) }}</span>
    </span>
  </div>
</template>
