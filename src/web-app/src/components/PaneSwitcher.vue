<script setup lang="ts">
import { computed, type Component } from "vue"
import { MessageSquare, TerminalSquare, FileCode2, Monitor } from "@lucide/vue"
import { useLayout, type ChatPanelTab } from "@/stores/layout"

const props = defineProps<{ sessionId: string; mode: "bottom-bar" | "header-cluster" }>()

const layout = useLayout()
const panel = computed(() => layout.panelsFor(props.sessionId))

type PaneDef = { key: ChatPanelTab; label: string; icon: Component }
const panes: PaneDef[] = [
  { key: "chat", label: "Chat", icon: MessageSquare },
  { key: "terminal", label: "Terminal", icon: TerminalSquare },
  { key: "editor", label: "Editor", icon: FileCode2 },
  { key: "display", label: "Display", icon: Monitor },
]

function isOpen(key: ChatPanelTab): boolean {
  switch (key) {
    case "chat": return panel.value.chatOpen
    case "terminal": return panel.value.terminalOpen
    case "editor": return panel.value.editorOpen
    case "display": return panel.value.displayOpen
  }
}

const chatToggleDisabled = computed(
  () => !panel.value.editorOpen && !panel.value.terminalOpen && !panel.value.displayOpen,
)

function onBarClick(key: ChatPanelTab) {
  layout.selectTab(props.sessionId, key)
}

function onClusterClick(key: ChatPanelTab) {
  if (key === "chat") layout.toggleChat(props.sessionId)
  else if (key === "terminal") layout.toggleTerminal(props.sessionId)
  else if (key === "editor") layout.toggleEditor(props.sessionId)
  else if (key === "display") layout.toggleDisplay(props.sessionId)
}
</script>

<template>
  <!-- Mobile: fixed bottom tab bar — pick exactly one pane -->
  <nav
    v-if="mode === 'bottom-bar'"
    class="flex border-t border-border bg-[var(--cmux-header)]"
    style="padding-bottom: env(safe-area-inset-bottom, 0px)"
    role="tablist"
    aria-label="Panes"
  >
    <button
      v-for="p in panes"
      :key="p.key"
      type="button"
      role="tab"
      :aria-label="p.label"
      :aria-selected="panel.activeTab === p.key"
      class="flex-1 flex flex-col items-center gap-0.5 py-2 text-[10px] font-medium transition-colors"
      :class="panel.activeTab === p.key ? 'text-primary' : 'text-muted-foreground'"
      @click="onBarClick(p.key)"
    >
      <component :is="p.icon" class="size-5" />
      {{ p.label }}
    </button>
  </nav>

  <!-- Desktop: header toggle cluster — show/hide tiled panes -->
  <div
    v-else
    class="inline-flex shrink-0 items-center gap-0.5 rounded-md border border-border bg-muted/30 p-0.5"
    role="group"
    aria-label="Panes"
  >
    <button
      v-for="p in panes"
      :key="p.key"
      type="button"
      :aria-label="p.key === 'chat' ? 'Toggle chat' : `Toggle ${p.label.toLowerCase()}`"
      :aria-pressed="isOpen(p.key)"
      :disabled="p.key === 'chat' && chatToggleDisabled"
      class="cmux-icon-button disabled:opacity-40 disabled:cursor-not-allowed"
      :class="{ 'cmux-icon-button-active': isOpen(p.key) }"
      @click="onClusterClick(p.key)"
    >
      <component :is="p.icon" class="size-4" />
    </button>
  </div>
</template>
