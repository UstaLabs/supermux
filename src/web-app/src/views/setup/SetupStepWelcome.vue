<script setup lang="ts">
import MuxLogo from "@/components/MuxLogo.vue"
// No props, no emits — pure intro screen
</script>

<template>
  <div class="flex flex-col items-center justify-center flex-1 px-6 py-12 text-center">
    <div class="mx-auto size-16 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-6">
      <MuxLogo class="size-9 text-primary" />
    </div>
    <h2 class="text-2xl font-bold tracking-tight mb-3">Welcome to supermux</h2>
    <p class="text-sm text-muted-foreground max-w-sm leading-relaxed">
      supermux drives coding agents from your phone or laptop. Let's connect one
      and get you set up in just a few steps.
    </p>
  </div>
</template>
