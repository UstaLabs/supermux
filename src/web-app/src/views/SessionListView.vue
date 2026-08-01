<script setup lang="ts">
import { computed } from "vue"
import { PanelLeftClose } from "lucide-vue-next"
import { useLayout } from "@/stores/layout"
import { useSortedSessions } from "@/composables/useSortedSessions"
import MuxLogo from "@/components/MuxLogo.vue"
import NotificationsBanner from "@/components/NotificationsBanner.vue"
import NewSessionListRow from "@/components/NewSessionListRow.vue"
import MobileActionsSheet from "@/components/MobileActionsSheet.vue"
import SidebarActionsMenu from "@/components/SidebarActionsMenu.vue"
import AppVersionTag from "@/components/AppVersionTag.vue"
import SessionTaskList from "@/components/SessionTaskList.vue"

const props = defineProps<{ compact?: boolean }>()

const productName = __PRODUCT_NAME__

const layout = useLayout()
const sortedSessions = useSortedSessions()
const isEmpty = computed(() => sortedSessions.value.length === 0)
</script>

<template>
  <!-- Sidebar mode (desktop) -->
  <div v-if="props.compact" data-testid="session-list" class="h-dvh flex flex-col bg-[var(--cmux-session-list)] text-foreground border-r border-border">
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
      <SessionTaskList :mobile="false" />
    </div>
  </div>

  <!-- Full-page mode (mobile) -->
  <div v-else data-testid="session-list" class="min-h-screen bg-[var(--cmux-session-list)] text-foreground">
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

    <div v-if="isEmpty" class="px-6 py-8 text-center text-muted-foreground">
      <p class="text-xs">No sessions yet — start one above.</p>
    </div>

    <SessionTaskList :mobile="true" />
  </div>
</template>
