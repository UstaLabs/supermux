<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { ChevronLeft, Folder } from "lucide-vue-next"
import { useSessions } from "@/stores/sessions"
import { useLayout } from "@/stores/layout"
import { toast } from "vue-sonner"
import { archivedProjects, filterByProject, projectLabel } from "@/lib/archived-projects"
import ArchivedProjectFilter from "@/components/ArchivedProjectFilter.vue"

const props = defineProps<{ compact?: boolean }>()

const sessions = useSessions()
const layout = useLayout()
const router = useRouter()
const loading = ref(false)

const selectedProjectKey = ref<string | null>(null)
const projects = computed(() => archivedProjects(sessions.archivedSessions, sessions.homeDir))
const visible = computed(() => filterByProject(sessions.archivedSessions, selectedProjectKey.value, sessions.homeDir))

// If the selected project disappears (e.g. its last session was resumed), clear the filter.
watch(projects, (list) => {
  if (selectedProjectKey.value && !list.some((p) => p.key === selectedProjectKey.value)) {
    selectedProjectKey.value = null
  }
})

onMounted(async () => {
  if (!sessions.archivedLoaded) {
    loading.value = true
    try {
      await sessions.fetchArchived()
    } catch (err: any) {
      toast.error(err?.message ?? "Failed to load archived sessions")
    } finally {
      loading.value = false
    }
  }
})

function formatKillDate(ts?: string): string {
  if (!ts) return ""
  const d = new Date(ts)
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })
}

function openSession(id: string) {
  router.push(`/s/${id}`)
}

function goBack() {
  if (props.compact) {
    layout.showSessionsPage()
  } else {
    router.push("/")
  }
}
</script>

<template>
  <!-- Sidebar compact (desktop) -->
  <div v-if="props.compact" class="h-dvh flex flex-col bg-[var(--cmux-session-list)] text-foreground border-r border-border">
    <header
      class="flex items-center gap-2 px-3 py-3 min-h-[3.5rem] border-b border-border bg-[var(--cmux-header)] shrink-0"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.5rem)"
    >
      <button class="cmux-icon-button -ml-1" aria-label="Back to sessions" @click="goBack">
        <ChevronLeft class="size-5" />
      </button>
      <h1 class="text-sm font-semibold tracking-tight">Archived</h1>
    </header>

    <div v-if="sessions.archivedSessions.length > 0" class="px-3 py-2 border-b border-border/50 shrink-0">
      <ArchivedProjectFilter v-model="selectedProjectKey" :projects="projects" />
    </div>

    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="px-4 py-10 text-center text-xs text-muted-foreground">Loading…</div>
      <div v-else-if="sessions.archivedSessions.length === 0" class="px-4 py-10 text-center text-xs text-muted-foreground">
        No archived sessions.
      </div>
      <button
        v-for="s in visible"
        :key="s.id"
        class="w-full text-left px-4 py-2.5 border-b border-border/50 hover:bg-muted/30 transition"
        @click="openSession(s.id)"
      >
        <div class="flex items-baseline justify-between gap-1">
          <span class="text-xs font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[10px] shrink-0 text-primary/70">{{ s.agent }}</span>
        </div>
        <div class="flex items-center gap-1 text-[10px] text-muted-foreground mt-0.5">
          <Folder class="size-3 shrink-0 opacity-70" />
          <span class="truncate font-mono">{{ projectLabel(s.repo_root ?? s.workdir, sessions.homeDir) }}</span>
        </div>
        <div v-if="s.killed_at" class="text-[10px] text-muted-foreground/60 mt-0.5">{{ formatKillDate(s.killed_at) }}</div>
      </button>
    </div>
  </div>

  <!-- Full-page (mobile) -->
  <div v-else class="min-h-screen bg-[var(--cmux-session-list)] text-foreground">
    <header
      class="flex items-center gap-2 px-4 py-3 border-b border-border sticky top-0 bg-[var(--cmux-header)]/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="goBack">
        <ChevronLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold tracking-tight">Archived</h1>
    </header>

    <div v-if="sessions.archivedSessions.length > 0" class="px-4 py-2 border-b border-border">
      <ArchivedProjectFilter v-model="selectedProjectKey" :projects="projects" />
    </div>

    <div v-if="loading" class="px-6 py-12 text-center text-sm text-muted-foreground">Loading…</div>
    <div v-else-if="sessions.archivedSessions.length === 0" class="px-6 py-12 text-center text-sm text-muted-foreground">
      No archived sessions.
    </div>
    <button
      v-for="s in visible"
      :key="s.id"
      class="w-full text-left px-4 py-3 border-b border-border flex items-start gap-3 hover:bg-muted/30 transition"
      @click="openSession(s.id)"
    >
      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2">
          <span class="text-sm font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[11px] shrink-0 text-primary/70">{{ s.agent }}{{ s.model ? `:${s.model}` : '' }}</span>
        </div>
        <div class="flex items-center gap-1 text-[12px] text-muted-foreground mt-0.5">
          <Folder class="size-3 shrink-0 opacity-70" />
          <span class="truncate font-mono">{{ projectLabel(s.repo_root ?? s.workdir, sessions.homeDir) }}</span>
        </div>
        <div v-if="s.killed_at" class="text-[11px] text-muted-foreground/60 mt-0.5">Archived {{ formatKillDate(s.killed_at) }}</div>
      </div>
    </button>
  </div>
</template>
