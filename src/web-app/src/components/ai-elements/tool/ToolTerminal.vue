<script setup lang="ts">
import { computed } from "vue"
import { cn } from "@/lib/utils"
import { Loader2Icon, TerminalIcon } from "lucide-vue-next"

const props = defineProps<{
  command?: string
  output?: string
  exitCode?: number | null
  /** Human "why" label from the agent. */
  description?: string
  status: "running" | "done" | "error"
  truncated?: boolean
}>()

const statusLabel = computed(() => {
  if (props.status === "running") return "running"
  if (props.status === "error") {
    return props.exitCode != null ? `exit ${props.exitCode}` : "error"
  }
  if (props.exitCode != null) return `exit ${props.exitCode}`
  return "done"
})

const statusClass = computed(() => {
  if (props.status === "running") return "text-amber-400"
  if (props.status === "error") return "text-red-400"
  return "text-emerald-400/90"
})
</script>

<template>
  <div
    :class="cn(
      'rounded-lg overflow-hidden border border-border/80 shadow-sm text-[12px] font-mono',
      'bg-[#0c0c0e] text-zinc-200',
    )"
  >
    <!-- Title bar -->
    <div class="flex items-center gap-2 px-3 py-1.5 bg-[#16161a] border-b border-white/5 select-none">
      <span class="flex items-center gap-1 shrink-0">
        <span class="size-2 rounded-full bg-[#ff5f57]/span>
        <span class="size-2 rounded-full bg-[#febc2e] opacity-80" />
        <span class="size-2 rounded-full bg-[#28c840] opacity-80" />
      </span>
      <TerminalIcon class="size-3.5 text-zinc-500 shrink-0 ml-1" />
      <span class="text-[11px] text-zinc-400 font-sans font-medium tracking-wide shrink-0">terminal</span>
      <span
        v-if="description"
        class="text-[11px] text-zinc-300 font-sans truncate min-w-0 flex-1"
        :title="description"
      >{{ description }}</span>
      <span class="ml-auto flex items-center gap-1.5 text-[10px] font-sans tabular-nums shrink-0" :class="statusClass">
        <Loader2Icon v-if="status === 'running'" class="size-3 animate-spin" />
        {{ statusLabel }}
      </span>
    </div>

    <!-- Scrollable body -->
    <div class="max-h-72 overflow-auto px-3 py-2 space-y-1.5 leading-relaxed">
      <div v-if="command" class="flex gap-2 min-w-0">
        <span class="text-emerald-400/90 shrink-0 select-none">$</span>
        <pre class="whitespace-pre-wrap break-words text-zinc-100 m-0 flex-1 min-w-0">{{ command }}</pre>
      </div>
      <pre
        v-if="output"
        class="whitespace-pre-wrap break-words m-0 text-zinc-300/95"
        :class="status === 'error' ? 'text-red-300/90' : ''"
      >{{ output }}<span v-if="truncated" class="opacity-50"> …</span></pre>
      <div
        v-else-if="status === 'running' && !command"
        class="text-zinc-500 italic font-sans text-[11px]"
      >
        Running…
      </div>
    </div>
  </div>
</template>
