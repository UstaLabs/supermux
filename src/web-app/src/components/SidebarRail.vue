<script setup lang="ts">
import { computed, ref } from "vue"
import { useRoute, useRouter } from "vue-router"
import { PanelLeft, Plus } from "lucide-vue-next"
import { useSessions } from "@/stores/sessions"
import { useUnread } from "@/stores/unread"
import { useLayout } from "@/stores/layout"
import { useAgentState, isAgentWorking } from "@/stores/agentState"
import { useSortedSessions } from "@/composables/useSortedSessions"
import { useRenameRequest } from "@/composables/useRenameRequest"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import MuxLogo from "@/components/MuxLogo.vue"
import SidebarActionsMenu from "@/components/SidebarActionsMenu.vue"
import SessionAvatar from "@/components/SessionAvatar.vue"
import SessionContextMenu from "@/components/SessionContextMenu.vue"
import KillConfirmDialog from "@/components/KillConfirmDialog.vue"

const sessions = useSessions()
const unread = useUnread()
const route = useRoute()
const router = useRouter()
const layout = useLayout()
const agentState = useAgentState()
const sortedSessions = useSortedSessions()
const { requestRename } = useRenameRequest()

const activeId = computed(() => (typeof route.params.id === "string" ? route.params.id : ""))
const launcherActive = computed(() => route.path === "/new")

const killTarget = ref<{ id: string; name: string } | null>(null)
const showKillConfirm = ref(false)

function navigate(id: string) {
  router.push(`/s/${id}`)
}

function openLauncher() {
  void router.push("/new")
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

function handleRename(name: string) {
  requestRename(name)
  layout.expandSidebar()
}

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
</script>

<template>
  <div class="h-dvh flex flex-col items-center py-3 gap-2 border-r border-border bg-[var(--cmux-rail)] text-foreground">
    <div class="size-7 flex items-center justify-center text-foreground shrink-0">
      <MuxLogo class="size-6" />
    </div>

    <button class="cmux-icon-button" aria-label="Expand sidebar" @click="layout.toggleSidebarCollapsed()">
      <PanelLeft class="size-5" />
    </button>

    <div class="flex-1 overflow-y-auto w-full flex flex-col items-center gap-2 py-1">
      <button
        type="button"
        class="cmux-icon-button shrink-0"
        :class="launcherActive ? 'ring-2 ring-primary ring-offset-2 ring-offset-[var(--cmux-rail)] rounded-xl' : ''"
        aria-label="Start a new session"
        title="Start a new session (⌘N)"
        @click="openLauncher"
      >
        <Plus class="size-5" />
      </button>

      <SessionContextMenu
        v-for="s in sortedSessions"
        :key="s.id"
        triggerless
        :name="s.name"
        :mute="s.mute"
        @navigate="navigate(s.id)"
        @kill="requestKill(s.id)"
        @mute="handleMute(s.id)"
        @rename="handleRename(s.name)"
      >
        <div class="relative cursor-pointer" :title="s.name">
          <div
            class="rounded-xl"
            :class="s.id === activeId ? 'ring-2 ring-primary ring-offset-2 ring-offset-[var(--cmux-rail)]' : ''"
          >
            <SessionAvatar :name="s.name" :connected="s.connected" :agent="s.agent" :working="isAgentWorking(agentState.get(s.id).phase, s.connected)" />
          </div>
          <span
            v-if="unread.isUnread(s.id)"
            class="absolute top-1/2 -right-1 h-5 w-1 -translate-y-1/2 rounded-full bg-primary/70"
            aria-label="unread"
          />
        </div>
      </SessionContextMenu>
    </div>

    <SidebarActionsMenu />
  </div>

  <KillConfirmDialog
    :open="showKillConfirm"
    :session-name="killTarget?.name ?? ''"
    @update:open="showKillConfirm = $event"
    @confirm="confirmKill"
  />
</template>
