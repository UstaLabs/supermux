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
  Loader2Icon,
} from 'lucide-vue-next'

const props = defineProps<{
  toolName: string
  summary?: string
  /** Human "why" label when the agent provided one. */
  description?: string
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

/** Prefer description (why); fall back to primary arg summary. */
const primaryText = computed(() => props.description || props.summary || '')

const secondaryText = computed(() => {
  if (props.description && props.summary && props.description !== props.summary) {
    return props.summary
  }
  return ''
})

const hasContent = computed(() => !!(props.input || props.output))

const titleAttr = computed(() => {
  const parts = [props.description, props.summary].filter(Boolean)
  return parts.length ? parts.join(' · ') : displayLabel.value
})
</script>

<template>
  <!-- Subtle inline activity row — not a chip/card -->
  <div class="group/tool min-w-0 pl-1">
    <button
      type="button"
      :class="cn(
        'flex items-center gap-1.5 w-full min-w-0 max-w-full text-left bg-transparent border-0 p-0',
        'py-0.5 -my-0.5 rounded-sm',
        'text-[12px] leading-snug text-muted-foreground/80',
        'transition-colors',
        hasContent ? 'cursor-pointer hover:text-muted-foreground' : 'cursor-default',
        status === 'error' && 'text-destructive/80 hover:text-destructive',
      )"
      :disabled="!hasContent"
      :aria-expanded="hasContent ? open : undefined"
      :title="titleAttr"
      @click="hasContent && (open = !open)"
    >
      <Loader2Icon
        v-if="status === 'running'"
        class="size-3 shrink-0 animate-spin text-muted-foreground/70"
      />
      <component
        v-else
        :is="toolIcon"
        class="size-3 shrink-0 opacity-60"
        :class="status === 'error' ? 'text-destructive/70' : ''"
      />

      <span class="shrink-0 font-medium opacity-90">{{ displayLabel }}</span>

      <span
        v-if="primaryText"
        class="min-w-0 truncate opacity-70"
      >{{ primaryText }}</span>

      <span
        v-if="secondaryText && open"
        class="min-w-0 truncate opacity-50 hidden sm:inline"
      >· {{ secondaryText }}</span>

      <span
        v-if="status === 'error' && !open"
        class="shrink-0 text-[11px] opacity-80"
      >failed</span>
    </button>

    <!-- Expanded detail: quiet pre blocks, no card chrome -->
    <div
      v-if="open && hasContent"
      class="mt-1 mb-1 ml-4 pl-2 border-l border-border/50 space-y-1.5 text-[11px] leading-relaxed"
    >
      <pre
        v-if="input"
        class="m-0 whitespace-pre-wrap break-words text-muted-foreground/75 font-mono max-h-48 overflow-auto"
      >{{ input }}</pre>
      <pre
        v-if="output"
        class="m-0 whitespace-pre-wrap break-words font-mono max-h-48 overflow-auto"
        :class="status === 'error' ? 'text-destructive/80' : 'text-muted-foreground/70'"
      >{{ output }}<span v-if="truncated" class="opacity-50"> …</span></pre>
    </div>
  </div>
</template>
