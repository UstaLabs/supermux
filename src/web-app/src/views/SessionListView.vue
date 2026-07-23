<script setup lang="ts">
import { computed, ref, reactive, provide, onMounted } from "vue"
import { useRoute, useRouter } from "vue-router"
import { PanelLeftClose, ChevronDown } from "lucide-vue-next"
import type { PathGroup, PathGroupSection as PathGroupSectionType } from "@/composables/usePathGroups"
import { useSessions } from "@/stores/sessions"
import { useUnread } from "@/stores/unread"
import { useLayout } from "@/stores/layout"
import { useSortedSessions } from "@/composables/useSortedSessions"
import { usePathGroups } from "@/composables/usePathGroups"
import { useRenameRequest } from "@/composables/useRenameRequest"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import SwipeableSessionRow from "@/components/SwipeableSessionRow.vue"
import PathGroupSection from "@/components/PathGroupSection.vue"
import SessionContextMenu from "@/components/SessionContextMenu.vue"
import SessionRow from "@/components/SessionRow.vue"
import MuxLogo from "@/components/MuxLogo.vue"
import NotificationsBanner from "@/components/NotificationsBanner.vue"
import KillConfirmDialog from "@/components/KillConfirmDialog.vue"
import NewSessionListRow from "@/components/NewSessionListRow.vue"
import MobileActionsSheet from "@/components/MobileActionsSheet.vue"
import SidebarActionsMenu from "@/components/SidebarActionsMenu.vue"
import AppVersionTag from "@/components/AppVersionTag.vue"

const props = defineProps<{ compact?: boolean }>()

const productName = __PRODUCT_NAME__

const sessions = useSessions()
const unread = useUnread()
const layout = useLayout()
const route = useRoute()
const router = useRouter()
const { consumeRenameRequest } = useRenameRequest()
const openSwipeRow = ref<string | null>(null)
provide("openSwipeRow", openSwipeRow)

const killTarget = ref<{ id: string; name: string } | null>(null)
const showKillConfirm = ref(false)
const renamingRow = ref<string | null>(null)

const sortedSessions = useSortedSessions()
const { groups, paGroup, toggle } = usePathGroups(sortedSessions)
const hasPAs = computed(() => paGroup.value.sessions.length > 0)
// PA group pinned first; path groups keep their recency order below it.
const displayGroups = computed(() => (hasPAs.value ? [paGroup.value, ...groups.value] : groups.value))
const activeId = computed(() => (typeof route.params.id === "string" ? route.params.id : ""))

onMounted(() => {
  const name = consumeRenameRequest()
  if (name) renamingRow.value = name
  if (!sessions.archivedLoaded) void sessions.fetchArchived()
})

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
  router.push(`/s/${id}`)
}

// Count only non-settled sessions for the group badge. PA groups have no
// sections, so fall back to their flat session list.
function activeCount(group: PathGroup): number {
  return group.sections.length
    ? group.sections.filter((s) => s.key !== "settled").reduce((n, s) => n + s.sessions.length, 0)
    : group.sessions.length
}

// Settled sub-sections start collapsed, expanded per project (keyed by workdir).
const settledExpanded = reactive(new Set<string>())
function toggleSettled(workdir: string) {
  if (settledExpanded.has(workdir)) settledExpanded.delete(workdir)
  else settledExpanded.add(workdir)
}
function sectionVisible(group: PathGroup, section: PathGroupSectionType): boolean {
  return section.key !== "settled" || settledExpanded.has(group.workdir)
}

const dragId = ref<string | null>(null)
function onDragStart(id: string) {
  dragId.value = id
}
async function onDrop(section: { key: string; sessions: { id: string }[] }, targetId: string) {
  const from = dragId.value
  dragId.value = null
  if (!from || from === targetId || section.key === "settled") return
  const ids = section.sessions.map((s) => s.id)
  const toIdx = ids.indexOf(targetId)
  if (toIdx < 0) return
  sessions.setLocalOrder(from, toIdx) // optimistic
  try {
    await api.setSessionOrder(from, toIdx)
  } catch (e: any) {
    toast.error(e?.message ?? "Reorder failed")
  }
}

</script>

<template>
  <!-- Sidebar mode (desktop) -->
  <div v-if="props.compact" class="h-dvh flex flex-col bg-[var(--cmux-session-list)] text-foreground border-r border-border">
    <header
      class="flex items-center justify-between px-3 py-3 min-h-[3.5rem] border-b border-border bg-[var(--cmux-header)] shrink-0"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.5rem)"
    >
      <div class="flex items-center gap-2 min-w-0">
        <div class="size-7 shrink-0 flex items-center justify-center text-foreground">
          <MuxLogo class="size-6" />
        </div>
        <div class="min-w-0">
          <h1 class="text-sm font-semibold tracking-tight">{{ productName }}</h1>
          <AppVersionTag />
        </div>
      </div>
      <div class="flex items-center gap-3 shrink-0">
        <button class="cmux-icon-button" aria-label="Collapse sidebar" @click="layout.toggleSidebarCollapsed()">
          <PanelLeftClose class="size-5" />
        </button>
        <SidebarActionsMenu />
      </div>
    </header>

    <div class="flex-1 overflow-y-auto">
      <NewSessionListRow />

      <template v-for="group in displayGroups" :key="group.workdir">
        <PathGroupSection
          :label="group.label"
          :collapsed="group.collapsed"
          :count="activeCount(group)"
          @toggle="toggle(group.workdir)"
        >
          <!-- PA group (no sections): render its flat session list. -->
          <template v-if="!group.sections.length">
            <template v-for="s in group.sessions" :key="s.id">
              <SessionContextMenu
                :name="s.name"
                :mute="s.mute"
                @kill="requestKill(s.id)"
                @mute="handleMute(s.id)"
                @rename="renamingRow = s.name"
              >
                <template #default="{ onContextmenu }">
                  <div @contextmenu="onContextmenu">
                    <SessionRow
                      :id="s.id"
                      :name="s.name"
                      :workdir="s.workdir"
                      :connected="s.connected"
                      :reserve-menu-space="true"
                      :active="s.id === activeId"
                      :unread="unread.isUnread(s.id)"
                      :agent="s.agent"
                      :model="s.model"
                      :status="s.status"
                      :renaming="renamingRow === s.name"
                      @navigate="navigateToSession(s.id)"
                      @rename="(newName) => handleRename(s.id, newName)"
                      @rename-cancel="renamingRow = null"
                    />
                  </div>
                </template>
              </SessionContextMenu>
            </template>
          </template>

          <!-- Path group: render each user-status section with a sub-header. -->
          <template v-else>
            <div v-for="section in group.sections" :key="section.key">
              <button
                v-if="section.key === 'settled'"
                type="button"
                class="flex items-center gap-1.5 w-full px-3 py-1 text-left hover:bg-muted/40 transition-colors"
                :aria-expanded="settledExpanded.has(group.workdir)"
                @click="toggleSettled(group.workdir)"
              >
                <ChevronDown
                  class="size-3 shrink-0 text-muted-foreground/70 transition-transform duration-150"
                  :class="{ '-rotate-90': !settledExpanded.has(group.workdir) }"
                />
                <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70 truncate">{{ section.label }}</span>
                <span class="text-[10px] tabular-nums text-muted-foreground/50">{{ section.sessions.length }}</span>
              </button>
              <div v-else class="flex items-center gap-1.5 px-3 py-1">
                <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70 truncate">{{ section.label }}</span>
                <span v-if="section.sessions.length > 1" class="text-[10px] tabular-nums text-muted-foreground/50">{{ section.sessions.length }}</span>
              </div>

              <template v-if="sectionVisible(group, section)">
                <div
                  v-for="s in section.sessions"
                  :key="s.id"
                  :draggable="section.key !== 'settled'"
                  @dragstart="onDragStart(s.id)"
                  @dragover.prevent
                  @drop="onDrop(section, s.id)"
                >
                  <SessionContextMenu
                    :name="s.name"
                    :mute="s.mute"
                    @kill="requestKill(s.id)"
                    @mute="handleMute(s.id)"
                    @rename="renamingRow = s.name"
                  >
                    <template #default="{ onContextmenu }">
                      <div @contextmenu="onContextmenu">
                        <SessionRow
                          :id="s.id"
                          :name="s.name"
                          :workdir="s.workdir"
                          :connected="s.connected"
                          :reserve-menu-space="true"
                          :active="s.id === activeId"
                          :unread="unread.isUnread(s.id)"
                          :agent="s.agent"
                          :model="s.model"
                          :status="s.status"
                          :renaming="renamingRow === s.name"
                          @navigate="navigateToSession(s.id)"
                          @rename="(newName) => handleRename(s.id, newName)"
                          @rename-cancel="renamingRow = null"
                        />
                      </div>
                    </template>
                  </SessionContextMenu>
                </div>
              </template>
            </div>
          </template>
        </PathGroupSection>
      </template>
    </div>
  </div>

  <!-- Full-page mode (mobile) -->
  <div v-else class="min-h-screen bg-[var(--cmux-session-list)] text-foreground">
    <header
      class="flex items-center justify-between px-4 py-3 border-b border-border sticky top-0 bg-[var(--cmux-header)]/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <div class="flex items-center gap-2 min-w-0">
        <div class="size-7 shrink-0 flex items-center justify-center text-foreground">
          <MuxLogo class="size-6" />
        </div>
        <div class="min-w-0">
          <h1 class="text-base font-semibold tracking-tight">{{ productName }}</h1>
          <AppVersionTag />
        </div>
      </div>
      <div class="flex items-center gap-3 shrink-0">
        <MobileActionsSheet />
      </div>
    </header>

    <NotificationsBanner />

    <NewSessionListRow class="mt-1" />

    <div v-if="sortedSessions.length === 0" class="px-6 py-8 text-center text-muted-foreground">
      <p class="text-xs">No sessions yet — start one above.</p>
    </div>

    <template v-for="group in displayGroups" :key="group.workdir">
      <PathGroupSection
        :label="group.label"
        :collapsed="group.collapsed"
        :count="activeCount(group)"
        @toggle="toggle(group.workdir)"
      >
        <!-- PA group (no sections): render its flat session list. -->
        <template v-if="!group.sections.length">
          <SwipeableSessionRow
            v-for="s in group.sessions"
            :key="s.id"
            :id="s.id"
            :name="s.name"
            :workdir="s.workdir"
            :connected="s.connected"
            :mute="s.mute"
            :unread="unread.isUnread(s.id)"
            :agent="s.agent"
            :model="s.model"
            :status="s.status"
            @kill="requestKill"
            @mute="handleMute"
            @rename="handleRename"
          />
        </template>

        <!-- Path group: render each user-status section with a sub-header. -->
        <template v-else>
          <div v-for="section in group.sections" :key="section.key">
            <button
              v-if="section.key === 'settled'"
              type="button"
              class="flex items-center gap-1.5 w-full px-4 py-1 text-left hover:bg-muted/40 transition-colors"
              :aria-expanded="settledExpanded.has(group.workdir)"
              @click="toggleSettled(group.workdir)"
            >
              <ChevronDown
                class="size-3 shrink-0 text-muted-foreground/70 transition-transform duration-150"
                :class="{ '-rotate-90': !settledExpanded.has(group.workdir) }"
              />
              <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70 truncate">{{ section.label }}</span>
              <span class="text-[10px] tabular-nums text-muted-foreground/50">{{ section.sessions.length }}</span>
            </button>
            <div v-else class="flex items-center gap-1.5 px-4 py-1">
              <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70 truncate">{{ section.label }}</span>
              <span v-if="section.sessions.length > 1" class="text-[10px] tabular-nums text-muted-foreground/50">{{ section.sessions.length }}</span>
            </div>

            <template v-if="sectionVisible(group, section)">
              <div
                v-for="s in section.sessions"
                :key="s.id"
                :draggable="section.key !== 'settled'"
                @dragstart="onDragStart(s.id)"
                @dragover.prevent
                @drop="onDrop(section, s.id)"
              >
                <SwipeableSessionRow
                  :id="s.id"
                  :name="s.name"
                  :workdir="s.workdir"
                  :connected="s.connected"
                  :mute="s.mute"
                  :unread="unread.isUnread(s.id)"
                  :agent="s.agent"
                  :model="s.model"
                  :status="s.status"
                  @kill="requestKill"
                  @mute="handleMute"
                  @rename="handleRename"
                />
              </div>
            </template>
          </div>
        </template>
      </PathGroupSection>
    </template>

  </div>

  <KillConfirmDialog
    :open="showKillConfirm"
    :session-name="killTarget?.name ?? ''"
    @update:open="showKillConfirm = $event"
    @confirm="confirmKill"
  />
</template>
