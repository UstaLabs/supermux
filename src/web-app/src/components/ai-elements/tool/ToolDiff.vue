<script setup lang="ts">
import { computed } from "vue"
import { cn } from "@/lib/utils"
import { FilePenIcon, Loader2Icon } from "lucide-vue-next"
import { parseDiffLines, diffStats } from "@/lib/diff-lines"

const props = defineProps<{
  path: string
  mode?: string
  diff?: string
  content?: string
  /** Human "why" label from the agent. */
  description?: string
  status: "running" | "done" | "error"
  truncated?: boolean
}>()

const renderedDiff = computed(() => {
  if (props.diff) return props.diff
  if (props.content) {
    return props.content.split("\n").map((l) => `+${l}`).join("\n")
  }
  return ""
})

const lines = computed(() => parseDiffLines(renderedDiff.value))
const stats = computed(() => diffStats(renderedDiff.value))

const modeLabel = computed(() => {
  const m = (props.mode || "").toLowerCase()
  if (m === "add" || m === "added") return "added"
  if (m === "delete" || m === "deleted") return "deleted"
  if (m === "move" || m === "renamed") return "moved"
  return "edited"
})

const statusLabel = computed(() => {
  if (props.status === "running") return "applying"
  if (props.status === "error") return "error"
  return "done"
})

function lineClass(type: string): string {
  if (type === "add") return "bg-emerald-500/10 text-emerald-300/95"
  if (type === "del") return "bg-red-500/10 text-red-300/90"
  if (type === "hunk") return "bg-sky-500/10 text-sky-300/80"
  if (type === "meta") return "text-zinc-500"
  return "text-zinc-300/90"
}

function prefix(type: string): string {
  if (type === "add") return "+"
  if (type === "del") return "-"
  if (type === "hunk" || type === "meta") return ""
  return " "
}
</script>

<template>
  <div
    :class="cn(
      'rounded-lg overflow-hidden border border-border/80 shadow-sm text-[12px] font-mono',
      'bg-[#0c0c0e] text-zinc-200',
    )"
  >
    <!-- Editor-like title bar -->
    <div class="flex items-center gap-2 px-3 py-1.5 bg-[#16161a] border-b border-white/5 select-none min-w-0">
      <FilePenIcon class="size-3.5 text-zinc-500 shrink-0" />
      <span class="text-[11px] text-zinc-200 font-sans font-medium truncate min-w-0" :title="description ? `${path} — ${description}` : path">
        {{ path }}
      </span>
      <span
        v-if="description"
        class="text-[10px] font-sans text-zinc-400 truncate min-w-0 max-w-[40%]"
        :title="description"
      >{{ description }}</span>
      <span class="text-[10px] font-sans text-zinc-500 shrink-0">{{ modeLabel }}</span>
      <span v-if="stats.added || stats.deleted" class="text-[10px] font-sans tabular-nums shrink-0">
        <span v-if="stats.added" class="text-emerald-400">+{{ stats.added }}</span>
        <span v-if="stats.added && stats.deleted" class="text-zinc-600"> </span>
        <span v-if="stats.deleted" class="text-red-400">−{{ stats.deleted }}</span>
      </span>
      <span class="ml-auto flex items-center gap-1.5 text-[10px] font-sans text-zinc-500 shrink-0">
        <Loader2Icon v-if="status === 'running'" class="size-3 animate-spin text-amber-400" />
        <span :class="status === 'error' ? 'text-red-400' : status === 'running' ? 'text-amber-400' : 'text-emerald-400/90'">
          {{ statusLabel }}
        </span>
      </span>
    </div>

    <div v-if="lines.length" class="max-h-72 overflow-auto">
      <div
        v-for="(ln, i) in lines"
        :key="i"
        class="flex leading-relaxed px-0 min-w-0"
        :class="lineClass(ln.type)"
      >
        <span
          class="w-5 shrink-0 text-center select-none opacity-60 border-r border-white/5"
        >{{ prefix(ln.type) }}</span>
        <pre class="flex-1 min-w-0 px-2 whitespace-pre-wrap break-words m-0">{{ ln.content }}</pre>
      </div>
      <div v-if="truncated" class="px-3 py-1 text-[10px] text-zinc-500 font-sans">… truncated</div>
    </div>
    <div v-else class="px-3 py-2 text-[11px] text-zinc-500 font-sans italic">
      <template v-if="status === 'running'">Preparing edit…</template>
      <template v-else>No diff content</template>
    </div>
  </div>
</template>
