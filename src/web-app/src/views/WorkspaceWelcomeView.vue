<script setup lang="ts">
import { Terminal, Zap } from "lucide-vue-next"
import MuxLogo from "@/components/MuxLogo.vue"

const productName = __PRODUCT_NAME__
</script>

<template>
  <div class="flex h-full min-h-0 flex-col items-center justify-center gap-5 p-10 text-center text-foreground">
    <div class="flex size-16 items-center justify-center rounded-2xl bg-card ring-1 ring-border shadow-sm">
      <MuxLogo class="size-8 text-muted-foreground" />
    </div>
    <div class="max-w-md space-y-2">
      <h2 class="text-lg font-semibold tracking-tight">Pick a session. Start hacking.</h2>
      <p class="text-sm text-muted-foreground leading-relaxed">
        {{ productName }} is ready — your chats live in the sidebar.
        Click one to ship something, or spin up a fresh session with
        <kbd class="rounded border border-border bg-muted px-1.5 py-0.5 text-[11px] font-medium text-foreground">+</kbd>.
      </p>
    </div>
    <div class="flex flex-wrap items-center justify-center gap-3 text-xs text-muted-foreground">
      <span class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card/60 px-3 py-1.5">
        <Terminal class="size-3.5" />
        terminal · editor · display
      </span>
      <span class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card/60 px-3 py-1.5">
        <Zap class="size-3.5" />
        type <span class="font-mono text-foreground">/</span> anywhere for commands
      </span>
    </div>
  </div>
</template>
