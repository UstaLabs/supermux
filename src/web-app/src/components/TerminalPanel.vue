<script setup lang="ts">
import { ref, watch, onMounted } from "vue"
import { Plus, X, TerminalSquare } from "lucide-vue-next"
import TerminalPane from "./TerminalPane.vue"
import { api } from "@/api/client"

// A per-session strip of terminal tabs. Each tab is one tmux-backed terminal;
// the set of tabs is rebuilt from the broker (live tmux sessions) on mount, so
// both the shells AND the tabs survive a reload. Only the active tab is mounted
// (and therefore connected) — background shells keep running headless in tmux.
const props = defineProps<{
  sessionName: string
  active: boolean
}>()

type Tab = { id: string }

const tabs = ref<Tab[]>([])
const activeId = ref<string>("")
const loading = ref(true)

function genId(): string {
  try {
    return crypto.randomUUID().replace(/-/g, "").slice(0, 16)
  } catch {
    return "t" + Math.random().toString(16).slice(2, 12)
  }
}

function pickActiveAfterRemoval(removedIdx: number) {
  if (activeId.value && tabs.value.some((t) => t.id === activeId.value)) return
  const next = tabs.value[removedIdx] ?? tabs.value[removedIdx - 1] ?? tabs.value[0]
  activeId.value = next ? next.id : ""
}

function addTab() {
  const id = genId()
  tabs.value.push({ id })
  activeId.value = id
}

async function refresh() {
  loading.value = true
  let ids: string[] = []
  try {
    const { terminals } = await api.listTerminals(props.sessionName)
    ids = terminals.map((t) => t.id)
  } catch {
    ids = []
  }
  tabs.value = ids.map((id) => ({ id }))
  if (tabs.value.length === 0) {
    // First open for this session → start with one terminal so it's usable.
    addTab()
  } else if (!tabs.value.some((t) => t.id === activeId.value)) {
    activeId.value = tabs.value[0]!.id
  }
  loading.value = false
}

async function closeTab(id: string) {
  const idx = tabs.value.findIndex((t) => t.id === id)
  tabs.value = tabs.value.filter((t) => t.id !== id)
  pickActiveAfterRemoval(idx)
  try {
    await api.closeTerminal(props.sessionName, id)
  } catch {
    /* best-effort; tmux session is gone or never existed */
  }
}

// The active pane reported its shell exited (tmux session already ended) — drop
// the tab. No close call needed: the session is already gone.
function onPaneExit(id: string) {
  const idx = tabs.value.findIndex((t) => t.id === id)
  tabs.value = tabs.value.filter((t) => t.id !== id)
  pickActiveAfterRemoval(idx)
}

onMounted(refresh)

// Reload the tab set when the host session changes (ChatView reuse).
watch(
  () => props.sessionName,
  () => {
    activeId.value = ""
    refresh()
  },
)
</script>

<template>
  <div class="flex flex-col w-full h-full bg-[var(--cmux-terminal)]">
    <!-- Tab strip -->
    <div
      class="flex items-center gap-1 px-1.5 py-1 border-b border-border bg-[var(--cmux-header)]/60 overflow-x-auto shrink-0"
    >
      <button
        v-for="(t, i) in tabs"
        :key="t.id"
        type="button"
        @click="activeId = t.id"
        :class="[
          'group inline-flex items-center gap-1.5 pl-2.5 pr-1 py-1 rounded text-xs font-medium whitespace-nowrap select-none transition-colors',
          activeId === t.id
            ? 'bg-[var(--cmux-terminal)] text-foreground border border-border'
            : 'text-muted-foreground hover:text-foreground hover:bg-foreground/5 border border-transparent',
        ]"
      >
        <span>Terminal {{ i + 1 }}</span>
        <span
          role="button"
          aria-label="Close terminal"
          class="inline-flex items-center justify-center size-4 rounded hover:bg-foreground/15 opacity-50 group-hover:opacity-100 transition-opacity"
          @click.stop="closeTab(t.id)"
        >
          <X class="size-3" />
        </span>
      </button>
      <button
        type="button"
        aria-label="New terminal"
        class="inline-flex items-center justify-center size-7 rounded text-muted-foreground hover:text-foreground hover:bg-foreground/5 shrink-0"
        @click="addTab"
      >
        <Plus class="size-4" />
      </button>
    </div>

    <!-- Active terminal (only the active tab is mounted → connected) -->
    <div class="relative flex-1 min-h-0">
      <TerminalPane
        v-if="activeId"
        :key="`${props.sessionName}:${activeId}`"
        :session-name="props.sessionName"
        :terminal-id="activeId"
        :active="props.active"
        @exit="onPaneExit(activeId)"
      />
      <!-- Empty state: no terminals (user closed them all) -->
      <div
        v-else-if="!loading"
        class="absolute inset-0 flex flex-col items-center justify-center gap-3 text-muted-foreground"
      >
        <TerminalSquare class="size-8 opacity-50" />
        <button
          type="button"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border text-sm hover:bg-foreground/5 hover:text-foreground transition-colors"
          @click="addTab"
        >
          <Plus class="size-4" />
          New terminal
        </button>
      </div>
    </div>
  </div>
</template>
