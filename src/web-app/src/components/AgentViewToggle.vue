<script setup lang="ts">
import { computed } from "vue"
import { MessageSquare, TerminalSquare } from "@lucide/vue"
import { useLayout } from "@/stores/layout"

const props = defineProps<{ sessionId: string }>()

const layout = useLayout()
const panel = computed(() => layout.panelsFor(props.sessionId))

function set(v: "chat" | "terminal") {
  panel.value.mainView = v
}
</script>

<template>
  <div
    class="inline-flex rounded-full border border-border bg-muted/40 p-0.5 text-xs"
    role="tablist"
    aria-label="Agent view"
  >
    <button
      type="button"
      role="tab"
      aria-label="Chat view"
      :aria-selected="panel.mainView === 'chat'"
      class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-medium transition-colors"
      :class="panel.mainView === 'chat' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
      @click="set('chat')"
    >
      <MessageSquare class="size-3.5" />
      Chat
    </button>
    <button
      type="button"
      role="tab"
      aria-label="Native view"
      :aria-selected="panel.mainView === 'terminal'"
      class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-medium transition-colors"
      :class="panel.mainView === 'terminal' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
      @click="set('terminal')"
    >
      <TerminalSquare class="size-3.5" />
      Native
    </button>
  </div>
</template>
