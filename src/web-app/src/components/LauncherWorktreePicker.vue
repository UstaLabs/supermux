<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { Check, ChevronDown, GitBranch, Loader2Icon } from "lucide-vue-next"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"

const props = defineProps<{
  branches?: { local: string[]; remote: string[] }
  currentBranch?: string
  loading?: boolean
}>()

const emit = defineEmits<{ refresh: [] }>()

const useWorktree = defineModel<boolean>("useWorktree", { required: true })
const baseBranch = defineModel<string>("baseBranch", { required: true })

const open = ref(false)
const query = ref("")
const searchInput = ref<HTMLInputElement | null>(null)

const allBranches = computed(() => [
  ...(props.branches?.local ?? []),
  ...(props.branches?.remote ?? []),
])
const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return q ? allBranches.value.filter((b) => b.toLowerCase().includes(q)) : allBranches.value
})
const label = computed(() =>
  useWorktree.value ? (baseBranch.value || props.currentBranch || "HEAD") : "No worktree",
)

// Fetch fresh branches from origin whenever the menu opens.
watch(open, (isOpen) => {
  if (isOpen) { query.value = ""; emit("refresh") }
})

function onOpenAutoFocus(e: Event) {
  // Keep keyboard focus on the search box instead of the first menu item.
  if (!useWorktree.value) return
  e.preventDefault()
  nextTick(() => searchInput.value?.focus())
}

function toggle() {
  useWorktree.value = !useWorktree.value
}
function pickBranch(b: string) {
  baseBranch.value = b
  useWorktree.value = true
  open.value = false
}
</script>

<template>
  <DropdownMenu v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="inline-flex max-w-full items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        aria-label="Worktree"
      >
        <GitBranch class="size-3.5 shrink-0" :class="useWorktree ? 'opacity-80' : 'opacity-40'" />
        <span class="truncate max-w-32" :class="{ 'opacity-60': !useWorktree }">{{ label }}</span>
        <ChevronDown class="size-3 shrink-0 opacity-60" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent
      align="center"
      class="flex max-h-80 w-60 flex-col overflow-hidden p-1"
      @open-auto-focus="onOpenAutoFocus"
    >
      <p class="px-2 pt-1 pb-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        Worktree
      </p>
      <button
        type="button"
        class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
        @click="toggle"
      >
        <GitBranch class="size-4 shrink-0 opacity-80" />
        <span class="flex-1">Run in isolated worktree</span>
        <Check v-if="useWorktree" class="size-4 shrink-0 text-primary" />
      </button>

      <template v-if="useWorktree">
        <div class="my-1 border-t border-border" />
        <div class="flex items-center justify-between px-2 pb-1">
          <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">Base branch</span>
          <Loader2Icon v-if="loading" class="size-3 animate-spin text-muted-foreground" />
        </div>
        <input
          ref="searchInput"
          v-model="query"
          type="text"
          placeholder="Search branches…"
          class="mx-1 mb-1 rounded-md border border-border bg-card px-2 py-1 text-[12px] outline-none focus:border-primary/50"
          @keydown.stop
        />
        <div class="min-h-0 flex-1 overflow-y-auto">
          <div v-if="loading && !allBranches.length" class="px-2 py-3 text-center text-xs text-muted-foreground">Fetching…</div>
          <div v-else-if="!filtered.length" class="px-2 py-3 text-center text-xs text-muted-foreground">
            {{ allBranches.length ? "No match" : "No branches" }}
          </div>
          <button
            v-for="b in filtered"
            :key="b"
            type="button"
            class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
            @click="pickBranch(b)"
          >
            <span class="flex-1 truncate font-mono text-[12px]">{{ b }}</span>
            <Check v-if="baseBranch === b" class="size-4 shrink-0 text-primary" />
          </button>
        </div>
      </template>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
