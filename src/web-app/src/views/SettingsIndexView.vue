<script setup lang="ts">
import { useRouter } from "vue-router"
import { ArrowLeft, ChevronRight, Sparkle, FileCode, Keyboard, Bot, UserRoundCog, Server } from "lucide-vue-next"

const router = useRouter()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/")
}

const items = [
  { label: "Assistant", desc: "PA name and soul.md", icon: UserRoundCog, path: "/settings/assistant" },
  { label: "Agents", desc: "CLI authorization and API key fallback", icon: Bot, path: "/settings/agents" },
  { label: "Curator", desc: "Nightly knowledge curation schedule", icon: Sparkle, path: "/settings/curator" },
  { label: "Editor", desc: "Font, wrap, and language servers", icon: FileCode, path: "/settings/editor" },
  { label: "Keyboard", desc: "Workspace shortcuts and custom bindings", icon: Keyboard, path: "/settings/keyboard" },
  { label: "System", desc: "Broker restart and status", icon: Server, path: "/settings/system" },
]
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header
      class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="goBack">
        <ArrowLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold tracking-tight">Settings</h1>
    </header>

    <ul class="divide-y divide-border">
      <li v-for="it in items" :key="it.path">
        <button
          class="w-full flex items-center justify-between gap-3 px-4 py-3.5 text-left hover:bg-accent transition"
          @click="router.push(it.path)"
        >
          <div class="flex items-center gap-3 min-w-0">
            <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
              <component :is="it.icon" class="size-4 text-muted-foreground" />
            </div>
            <div class="min-w-0">
              <div class="font-medium">{{ it.label }}</div>
              <div class="text-[11px] text-muted-foreground">{{ it.desc }}</div>
            </div>
          </div>
          <ChevronRight class="size-4 text-muted-foreground shrink-0" />
        </button>
      </li>
    </ul>
  </div>
</template>
