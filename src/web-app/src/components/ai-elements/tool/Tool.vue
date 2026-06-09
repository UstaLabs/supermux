<script setup lang="ts">
import { ref, computed } from 'vue'
import { cn } from '@/lib/utils'
import {
  TerminalIcon,
  FileTextIcon,
  FilePenIcon,
  SearchIcon,
  FolderSearchIcon,
  BotIcon,
  BookOpenIcon,
  GlobeIcon,
  WrenchIcon,
  ChevronRightIcon,
} from 'lucide-vue-next'

const props = defineProps<{
  toolName: string
  summary?: string
  input?: string
  output?: string
  status: 'running' | 'done' | 'error'
  truncated?: boolean
}>()

const open = ref(false)

const toolIcon = computed(() => {
  const name = props.toolName
  if (name === 'Bash') return TerminalIcon
  if (name === 'Read') return FileTextIcon
  if (name === 'Edit' || name === 'Write') return FilePenIcon
  if (name === 'Grep') return SearchIcon
  if (name === 'Glob') return FolderSearchIcon
  if (name === 'Task' || name === 'Agent') return BotIcon
  if (name === 'Skill') return BookOpenIcon
  if (name === 'WebFetch' || name === 'WebSearch') return GlobeIcon
  if (name.startsWith('mcp__')) return WrenchIcon
  return WrenchIcon
})

const displayLabel = computed(() => {
  const name = props.toolName
  if (name.startsWith('mcp__')) {
    const parts = name.split('__')
    return parts[parts.length - 1]
  }
  return name
})

const statusDotClass = computed(() => {
  if (props.status === 'running') return 'bg-primary animate-pulse'
  if (props.status === 'done') return 'bg-primary/70'
  if (props.status === 'error') return 'bg-destructive'
  return ''
})

const statusLabel = computed(() => {
  if (props.status === 'running') return 'Running'
  if (props.status === 'done') return 'Done'
  if (props.status === 'error') return 'Error'
  return ''
})

const hasContent = computed(() => !!(props.input || props.output))
</script>

<template>
  <div :class="cn('rounded-md border border-border bg-[var(--cmux-header)]/70 overflow-hidden text-sm shadow-sm')">
    <!-- Header -->
    <button
      type="button"
      class="flex items-center gap-2 w-full px-3 py-2 text-left hover:bg-accent/60 transition-colors"
      @click="hasContent && (open = !open)"
      :aria-expanded="hasContent ? open : undefined"
    >
      <!-- Tool icon -->
      <component
        :is="toolIcon"
        class="size-4 shrink-0 text-muted-foreground"
      />

      <!-- Tool name -->
      <span class="font-medium text-foreground shrink-0">{{ displayLabel }}</span>

      <!-- Summary -->
      <span
        v-if="summary"
        class="flex-1 min-w-0 truncate text-xs text-muted-foreground"
      >{{ summary }}</span>
      <span v-else class="flex-1" />

      <!-- Status badge -->
      <div class="flex items-center gap-1.5 text-xs shrink-0 ml-auto">
        <span class="size-1.5 rounded-full" :class="statusDotClass" />
        <span :class="status === 'error' ? 'text-destructive' : 'text-muted-foreground'">
          {{ statusLabel }}
        </span>
      </div>

      <!-- Chevron -->
      <ChevronRightIcon
        v-if="hasContent"
        class="size-4 text-muted-foreground transition-transform"
        :class="{ 'rotate-90': open }"
      />
    </button>

    <!-- Collapsible content -->
    <div
      v-if="open && hasContent"
      class="border-t border-border px-3 py-2 flex flex-col gap-2 text-xs"
    >
      <!-- Input block -->
      <div v-if="input">
        <div class="font-medium text-muted-foreground mb-1">Input</div>
        <pre class="bg-muted/50 rounded-md p-2 overflow-auto max-h-64 whitespace-pre-wrap break-words text-foreground/80">{{ input }}</pre>
      </div>

      <!-- Output block -->
      <div v-if="output">
        <div class="font-medium text-muted-foreground mb-1">Output</div>
        <pre
          class="bg-muted/50 rounded-md p-2 overflow-auto max-h-64 whitespace-pre-wrap break-words"
          :class="status === 'error' ? 'text-destructive' : 'text-foreground/80'"
        >{{ output }}<span v-if="truncated" class="opacity-60"> …</span></pre>
      </div>
    </div>
  </div>
</template>
