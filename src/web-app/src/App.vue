<script setup lang="ts">
import { onMounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { useAuth } from "./stores/auth"
import { useOnboarding } from "./stores/onboarding"
import { useWS } from "./api/ws"
import { api } from "./api/client"
import { useIsDesktop } from "./composables/useIsDesktop"
import { usePanelResize } from "./composables/usePanelResize"
import { useViewing } from "./composables/useViewing"
import { useClearNotificationsOnOpen } from "./composables/useClearNotificationsOnOpen"
import { useReadTracker } from "./composables/useReadTracker"
import { useWorkspaceShortcuts } from "./composables/useWorkspaceShortcuts"
import { useLayout, SIDEBAR, SIDEBAR_RAIL } from "./stores/layout"
import SessionListView from "./views/SessionListView.vue"
import ArchivedListView from "./views/ArchivedListView.vue"
import SidebarRail from "./components/SidebarRail.vue"
import PairDialog from "./components/PairDialog.vue"
import AttachmentLightbox from "@/components/attachments/AttachmentLightbox.vue"
import Sonner from "@/components/ui/sonner/Sonner.vue"
import CachedRouterView from "@/components/CachedRouterView.vue"
import AppUpdateBanner from "@/components/AppUpdateBanner.vue"

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const onboarding = useOnboarding()
// The router guard only redirects on navigation; on first load `onboarded` is
// still null and the snapshot arrives AFTER. So reactively send the user to the
// wizard the moment we learn onboarding isn't finished (and away is handled by
// the Done step's own push to "/").
watch(
  () => onboarding.onboarded,
  (v) => { if (v === false && route.path !== "/setup") router.replace("/setup") },
  { immediate: true },
)
const ws = useWS()
useViewing()
useClearNotificationsOnOpen()
useReadTracker()
useWorkspaceShortcuts()
const isDesktop = useIsDesktop()
const needsPair = ref(false)

const layout = useLayout()
const sidebarResize = usePanelResize({
  orientation: "horizontal",
  unit: "px",
  min: SIDEBAR.min,
  max: SIDEBAR.max,
  get: () => layout.state.sidebarWidth,
  set: (v) => { layout.state.sidebarWidth = v },
  onReset: () => layout.resetSidebar(),
})

onMounted(async () => {
  // Cookie-only auth: ask the server whether this browser is paired (the
  // HttpOnly cookie is sent automatically). Pairing happens by navigating to
  // /pair?t=… which sets the cookie and redirects back here.
  if (await auth.refresh()) {
    ws.connect()
  } else {
    try {
      await api.claimPair()        // fresh broker (0 devices, !onboarded) → self-pairs + sets cookie
      await auth.refresh()
      ws.connect()                  // snapshot reports onboarded:false → the watch above sends us to /setup
    } catch {
      needsPair.value = true        // 403 ⇒ existing instance → normal <PairDialog>
    }
  }
})
</script>

<template>
  <PairDialog v-if="needsPair" />

  <template v-else>
    <div class="flex flex-col h-dvh bg-background text-foreground">
    <AppUpdateBanner />
    <!-- Split layout: ≥1024px and not a fullScreen route -->
    <div v-if="isDesktop && !route.meta.fullScreen" class="flex flex-1 min-h-0 bg-background text-foreground">
      <aside
        class="shrink-0 overflow-hidden"
        :style="{ width: (layout.state.sidebarCollapsed ? SIDEBAR_RAIL : layout.state.sidebarWidth) + 'px' }"
      >
        <SidebarRail v-if="layout.state.sidebarCollapsed" />
        <ArchivedListView v-else-if="layout.state.sidebarPage === 'archived'" compact />
        <SessionListView v-else compact />
      </aside>
      <div class="relative w-px bg-border shrink-0">
        <div
          v-if="!layout.state.sidebarCollapsed"
          class="absolute inset-y-0 -left-1 -right-1 z-10 cursor-col-resize hover:bg-primary/25 transition-colors"
          @pointerdown="sidebarResize.onPointerDown"
          @dblclick="sidebarResize.onDblClick"
        />
      </div>
      <main class="flex-1 min-w-0 overflow-hidden bg-[var(--cmux-workspace)]">
        <CachedRouterView />
      </main>
    </div>

    <!-- Full-bleed: mobile, or fullScreen routes (/devices) -->
    <div v-else class="flex-1 min-h-0 overflow-hidden">
      <CachedRouterView />
    </div>
    </div>
  </template>

  <Sonner position="top-center" rich-colors />
  <AttachmentLightbox />
</template>
