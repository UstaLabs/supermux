<script setup lang="ts">
// The task list itself: PA rows pinned on top, then the three task-state
// sections — either flat (states only, a project tag per row) or nested under
// per-project headers with a soft-tinted body. Toggled by "Group by project".
import { computed, ref, reactive, provide, onMounted } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ChevronDown, Folder } from "lucide-vue-next"
import type { PathGroup } from "@/composables/usePathGroups"
import { usePathGroups } from "@/composables/usePathGroups"
import { useSortedSessions } from "@/composables/useSortedSessions"
import { useRenameRequest } from "@/composables/useRenameRequest"
import { useSessions } from "@/stores/sessions"
import { useLayout } from "@/stores/layout"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import TaskSection from "./TaskSection.vue"
import KillConfirmDialog from "./KillConfirmDialog.vue"

defineProps<{ mobile: boolean }>()

const sessions = useSessions()
const layout = useLayout()
const route = useRoute()
const router = useRouter()
const { consumeRenameRequest } = useRenameRequest()

const openSwipeRow = ref<string | null>(null)
provide("openSwipeRow", openSwipeRow)

const sortedSessions = useSortedSessions()
const { groups, paGroup, flatSections, toggle } = usePathGroups(sortedSessions)
const hasPAs = computed(() => paGroup.value.sessions.length > 0)
const paSection = computed(() => ({ key: "in_progress" as const, label: "Personal Assistants", sessions: paGroup.value.sessions }))
const activeId = computed(() => (typeof route.params.id === "string" ? route.params.id : ""))
const groupByProject = computed(() => layout.state.groupByProject)
const homeDir = computed(() => sessions.homeDir)

const renamingRow = ref<string | null>(null)
const killTarget = ref<{ id: string; name: string } | null>(null)
const showKillConfirm = ref(false)

onMounted(() => {
  const name = consumeRenameRequest()
  if (name) renamingRow.value = name
  if (!sessions.archivedLoaded) void sessions.fetchArchived()
})

// Count only non-settled sessions for a project header badge.
function activeCount(group: PathGroup): number {
  return group.sections.filter((s) => s.key !== "settled").reduce((n, s) => n + s.sessions.length, 0)
}

// Settled collapses: per-project in grouped mode, a single toggle in flat mode.
const settledExpanded = reactive(new Set<string>())
function toggleSettled(workdir: string) {
  if (settledExpanded.has(workdir)) settledExpanded.delete(workdir)
  else settledExpanded.add(workdir)
}
const flatSettledExpanded = ref(false)

function requestKill(id: string) {
  const s = sessions.list.find((x) => x.id === id)
  killTarget.value = { id, name: s?.name ?? id }
  showKillConfirm.value = true
}
async function confirmKill() {
  const target = killTarget.value
  if (!target) return
  showKillConfirm.value = false
  try {
    await api.killSession(target.id)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to kill session")
  }
  killTarget.value = null
}

async function handleMute(id: string) {
  const session = sessions.list.find((s) => s.id === id)
  if (!session) return
  try {
    await api.toggleMute(id, !session.mute)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to toggle mute")
  }
}

async function handleRename(id: string, newName: string) {
  renamingRow.value = null
  try {
    await api.renameSession(id, newName)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to rename session")
  }
}

function navigateToSession(id: string) {
  const s = sessions.list.find((x) => x.id === id)
  if (s?.userStatus === "draft") {
    router.push({ path: "/new", query: { draft: id } })
    return
  }
  router.push(`/s/${id}`)
}

// Mark an in_progress session settled. Backend archives + settles it and
// broadcasts session_removed; add it optimistically under Settled so it shows
// immediately, rolling back on failure.
async function handleSettle(id: string) {
  const s = sessions.list.find((x) => x.id === id)
  if (s) {
    sessions.addArchived({
      id: s.id,
      name: s.name,
      workdir: s.workdir,
      agent: s.agent ?? "claude",
      model: s.model,
      repo_root: s.repo_root,
      killed_at: new Date().toISOString(),
    })
  }
  try {
    await api.killSession(id)
  } catch (e: any) {
    sessions.removeArchived(id)
    toast.error(e?.message ?? "Failed to settle")
  }
}

async function handleDeleteDraft(id: string) {
  try {
    await api.killSession(id)
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to delete draft")
  }
}

async function handleResume(id: string) {
  try {
    await sessions.resumeSession(id)
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to resume")
  }
}

function handleOpenDraft(id: string) {
  router.push({ path: "/new", query: { draft: id } })
}

async function handleReorder(ids: string[]) {
  sessions.applyReorder(ids)
  try {
    await api.reorderSessions(ids)
  } catch (e: any) {
    toast.error(e?.message ?? "Reorder failed")
  }
}
</script>

<template>
  <!-- Group by project toggle -->
  <div
    class="flex items-center justify-between gap-2 border-b border-border/60"
    :class="mobile ? 'px-3 py-2.5' : 'px-3 py-2'"
  >
    <span class="flex items-center gap-2 text-xs text-muted-foreground min-w-0">
      <Folder class="size-3.5 shrink-0" />
      <span class="truncate">Group by project</span>
    </span>
    <button
      type="button"
      role="switch"
      :aria-checked="groupByProject"
      :aria-label="groupByProject ? 'Group by project on' : 'Group by project off'"
      class="relative h-[22px] w-[38px] shrink-0 rounded-full transition-colors"
      :class="groupByProject ? 'bg-primary' : 'bg-muted-foreground/35'"
      @click="layout.toggleGroupByProject()"
    >
      <span
        class="absolute top-0.5 left-0.5 size-[18px] rounded-full bg-white shadow transition-transform"
        :class="{ 'translate-x-4': groupByProject }"
      />
    </button>
  </div>

  <!-- Personal assistants: pinned, flat, no tint -->
  <TaskSection
    v-if="hasPAs"
    :section="paSection"
    :mobile="mobile"
    :show-project-label="false"
    :active-id="activeId"
    :renaming-name="renamingRow"
    :expanded="true"
    :reorderable="false"
    @navigate="navigateToSession"
    @kill="requestKill"
    @mute="handleMute"
    @rename-start="(name) => (renamingRow = name)"
    @rename="handleRename"
    @rename-cancel="renamingRow = null"
    @settle="handleSettle"
    @resume="handleResume"
    @open-draft="handleOpenDraft"
    @delete-draft="handleDeleteDraft"
  />

  <!-- FLAT: three task sections across every project -->
  <template v-if="!groupByProject">
    <TaskSection
      v-for="section in flatSections"
      :key="section.key"
      :section="section"
      :mobile="mobile"
      :show-project-label="true"
      :home-dir="homeDir"
      :active-id="activeId"
      :renaming-name="renamingRow"
      :expanded="flatSettledExpanded"
      :reorderable="true"
      @navigate="navigateToSession"
      @reorder="handleReorder"
      @kill="requestKill"
      @mute="handleMute"
      @rename-start="(name) => (renamingRow = name)"
      @rename="handleRename"
      @rename-cancel="renamingRow = null"
      @settle="handleSettle"
      @resume="handleResume"
      @open-draft="handleOpenDraft"
      @delete-draft="handleDeleteDraft"
      @toggle-expanded="flatSettledExpanded = !flatSettledExpanded"
    />
  </template>

  <!-- GROUPED: per-project header + soft-tinted body -->
  <template v-else>
    <div v-for="group in groups" :key="group.workdir">
      <div class="flex items-baseline gap-2 px-3" :class="mobile ? 'pt-2.5 pb-1' : 'pt-3 pb-1'">
        <button
          type="button"
          class="flex min-w-0 flex-1 items-baseline gap-2 text-left"
          :aria-expanded="!group.collapsed"
          @click="toggle(group.workdir)"
        >
          <ChevronDown
            class="size-3 shrink-0 self-center text-muted-foreground/70 transition-transform duration-150"
            :class="{ '-rotate-90': group.collapsed }"
          />
          <span class="truncate font-bold tracking-tight" :class="mobile ? 'text-[14px]' : 'text-[13px]'">{{ group.label }}</span>
          <span class="shrink-0 font-mono text-[10px] text-muted-foreground/60">{{ activeCount(group) }}</span>
        </button>
      </div>
      <div
        v-show="!group.collapsed"
        class="mb-1 rounded-[10px] pb-1"
        :class="mobile ? 'mx-1.5' : 'mx-2'"
        style="background: color-mix(in oklab, var(--primary) 4%, transparent)"
      >
        <TaskSection
          v-for="section in group.sections"
          :key="section.key"
          :section="section"
          :mobile="mobile"
          :show-project-label="false"
          :active-id="activeId"
          :renaming-name="renamingRow"
          :expanded="settledExpanded.has(group.workdir)"
          :reorderable="true"
          @navigate="navigateToSession"
          @kill="requestKill"
          @mute="handleMute"
          @rename-start="(name) => (renamingRow = name)"
          @rename="handleRename"
          @rename-cancel="renamingRow = null"
          @settle="handleSettle"
          @resume="handleResume"
          @open-draft="handleOpenDraft"
          @delete-draft="handleDeleteDraft"
          @toggle-expanded="toggleSettled(group.workdir)"
          @reorder="handleReorder"
        />
      </div>
    </div>
  </template>

  <KillConfirmDialog
    :open="showKillConfirm"
    :session-name="killTarget?.name ?? ''"
    @update:open="showKillConfirm = $event"
    @confirm="confirmKill"
  />
</template>
