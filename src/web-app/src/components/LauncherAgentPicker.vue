<script setup lang="ts">
import { ref } from "vue"
import { Check, ChevronDown } from "lucide-vue-next"
import AgentLogo from "@/components/AgentLogo.vue"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"

const agent = defineModel<"claude" | "codex" | "cursor" | "opencode" | "grok">("agent", { required: true })

const open = ref(false)
const agents = ["claude", "codex", "cursor", "opencode", "grok"] as const

function pick(a: "claude" | "codex" | "cursor" | "opencode" | "grok") {
  agent.value = a
}

// When the pill is focused (menu closed), Up/Down cycle the agent inline.
// Handled on an ancestor in the capture phase so it runs before — and can
// suppress — Reka's default "ArrowDown opens the menu" behaviour on the trigger.
function onTriggerArrows(e: KeyboardEvent) {
  if (open.value) return
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return
  e.preventDefault()
  e.stopPropagation()
  const dir = e.key === "ArrowDown" ? 1 : -1
  const i = agents.indexOf(agent.value)
  agent.value = agents[(i + dir + agents.length) % agents.length]
}
</script>

<template>
  <DropdownMenu v-model:open="open">
    <div class="contents" @keydown.capture="onTriggerArrows">
      <DropdownMenuTrigger as-child>
        <button
          type="button"
          class="inline-flex max-w-full items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          aria-label="Agent"
          aria-keyshortcuts="ArrowUp ArrowDown"
        >
          <AgentLogo :agent="agent" class="size-3.5 shrink-0 opacity-80" />
          <span class="capitalize">{{ agent }}</span>
          <ChevronDown class="size-3 shrink-0 opacity-60" />
        </button>
      </DropdownMenuTrigger>
    </div>

    <DropdownMenuContent align="start" class="w-56 p-1">
      <p class="px-2 pt-1 pb-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        Agent
      </p>
      <button
        v-for="a in agents"
        :key="a"
        type="button"
        class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
        @click="pick(a)"
      >
        <AgentLogo :agent="a" class="size-4 shrink-0" />
        <span class="flex-1 capitalize">{{ a }}</span>
        <Check v-if="agent === a" class="size-4 shrink-0 text-primary" />
      </button>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
