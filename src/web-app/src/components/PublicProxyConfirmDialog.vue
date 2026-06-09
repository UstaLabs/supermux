<script setup lang="ts">
import { DialogRoot, DialogPortal, DialogOverlay, DialogContent } from "reka-ui"
import { Button } from "@/components/ui/button"

const props = defineProps<{ open: boolean; domain: string }>()
const emit = defineEmits<{
  (e: "update:open", v: boolean): void
  (e: "confirm"): void
}>()
</script>

<template>
  <DialogRoot :open="props.open" @update:open="(v) => emit('update:open', v)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/50 z-50 data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0" />
      <DialogContent
        class="fixed top-1/2 left-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-[calc(100%-2rem)] max-w-sm bg-popover text-popover-foreground rounded-xl p-5 ring-1 ring-foreground/10 outline-none data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
        @pointer-down-outside="emit('update:open', false)"
      >
        <h3 class="font-semibold text-base">Make proxy public?</h3>
        <p class="text-sm text-muted-foreground mt-2">
          Anyone with the link to
          <span class="font-mono font-semibold text-foreground">{{ props.domain }}</span>
          can reach the app on that port without pairing this device. Only enable if you intend to share it.
        </p>
        <div class="flex gap-3 mt-5 justify-end">
          <Button variant="outline" size="sm" @click="emit('update:open', false)">Cancel</Button>
          <Button size="sm" @click="emit('confirm')">Make public</Button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
