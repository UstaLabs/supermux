<script setup lang="ts">
import { ref } from "vue"
import { Trash2, VolumeX, Volume2, Pencil, MoreVertical } from "lucide-vue-next"
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuPortal,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from "reka-ui"

import { useLongPress } from "@/composables/useLongPress"

const props = defineProps<{
  name: string
  mute: boolean
  // Rail mode: no visible ⋮ button. The wrapped element (an avatar) anchors the
  // menu, which opens via right-click or long-press; a plain tap emits `navigate`.
  triggerless?: boolean
}>()

const emit = defineEmits<{
  (e: "kill"): void
  (e: "mute"): void
  (e: "rename"): void
  (e: "navigate"): void
}>()

const open = ref(false)

function onContextMenu(e: MouseEvent) {
  e.preventDefault()
  open.value = true
}

const longPress = useLongPress(() => { open.value = true })

function onTap() {
  // A long-press already opened the menu — don't also navigate.
  if (longPress.fired.value) return
  emit("navigate")
}

defineExpose({ onContextMenu })
</script>

<template>
  <DropdownMenuRoot v-model:open="open">
    <!-- Rail mode: avatar anchors the menu; open via long-press / right-click, tap navigates -->
    <div
      v-if="props.triggerless"
      class="relative"
      @contextmenu="onContextMenu"
      @click="onTap"
      @pointerdown="longPress.onPointerdown"
      @pointermove="longPress.onPointermove"
      @pointerup="longPress.onPointerup"
      @pointerleave="longPress.onPointerleave"
      @pointercancel="longPress.onPointercancel"
    >
      <slot :on-contextmenu="onContextMenu" />
      <DropdownMenuTrigger as-child>
        <span class="absolute inset-0 pointer-events-none" aria-hidden="true" />
      </DropdownMenuTrigger>
    </div>
    <!-- List mode: visible ⋮ button is the trigger; right-click on the row also opens it -->
    <div v-else class="relative">
      <slot :on-contextmenu="onContextMenu" />
      <DropdownMenuTrigger as-child>
        <button
          class="absolute right-1 top-1/2 -translate-y-1/2 flex items-center justify-center size-8 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition outline-none"
          aria-label="Session menu"
          @click.stop
          @pointerdown.stop
        >
          <MoreVertical class="size-4" />
        </button>
      </DropdownMenuTrigger>
    </div>
    <DropdownMenuPortal>
      <DropdownMenuContent
        class="z-50 min-w-[160px] overflow-hidden rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
        :side-offset="5"
        :side="props.triggerless ? 'right' : 'bottom'"
        :align="props.triggerless ? 'start' : 'end'"
      >
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="emit('mute')"
        >
          <VolumeX v-if="!props.mute" class="size-4" />
          <Volume2 v-else class="size-4" />
          {{ props.mute ? 'Unmute' : 'Mute' }}
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="emit('rename')"
        >
          <Pencil class="size-4" />
          Rename
        </DropdownMenuItem>
        <DropdownMenuSeparator class="mx-1 my-1 h-px bg-border" />
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-sm text-destructive outline-none focus:bg-destructive/10"
          @select="emit('kill')"
        >
          <Trash2 class="size-4" />
          Kill
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>
