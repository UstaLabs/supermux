<script setup lang="ts">
import { ArrowUp, ArrowDown, ArrowLeft, ArrowRight } from "lucide-vue-next"
import type { Component } from "vue"
import type { SpecialKey, ModState, KeyPress } from "@/lib/terminal-keys"

// A scrollable row of keys the on-screen keyboard lacks (Esc, Tab, Ctrl, Alt,
// arrows, …). Purely presentational: it reports each press up to TerminalPane,
// which owns the modifier state machine and byte-sending. Ctrl/Alt render their
// tri-state (off / armed-once / locked) so the user can see what's active.
const props = defineProps<{ ctrl: ModState; alt: ModState }>()
const emit = defineEmits<{ press: [KeyPress] }>()

// Buttons in on-screen order. Icons for arrows; short labels for the rest.
type Btn =
  | { kind: "modifier"; mod: "ctrl" | "alt"; label: string }
  | { kind: "special"; key: SpecialKey; label?: string; icon?: Component }
  | { kind: "printable"; ch: string }
  | { kind: "gap" }

const buttons: Btn[] = [
  { kind: "special", key: "Escape", label: "Esc" },
  { kind: "special", key: "Tab", label: "Tab" },
  { kind: "gap" },
  { kind: "modifier", mod: "ctrl", label: "Ctrl" },
  { kind: "modifier", mod: "alt", label: "Alt" },
  { kind: "gap" },
  { kind: "special", key: "ArrowLeft", icon: ArrowLeft },
  { kind: "special", key: "ArrowDown", icon: ArrowDown },
  { kind: "special", key: "ArrowUp", icon: ArrowUp },
  { kind: "special", key: "ArrowRight", icon: ArrowRight },
  { kind: "gap" },
  { kind: "special", key: "Home", label: "Home" },
  { kind: "special", key: "End", label: "End" },
  { kind: "special", key: "PageUp", label: "PgUp" },
  { kind: "special", key: "PageDown", label: "PgDn" },
  { kind: "gap" },
  { kind: "printable", ch: "|" },
  { kind: "printable", ch: "~" },
  { kind: "printable", ch: "/" },
  { kind: "printable", ch: "-" },
]

function press(btn: Btn) {
  switch (btn.kind) {
    case "modifier":
      emit("press", { type: "modifier", mod: btn.mod })
      break
    case "special":
      emit("press", { type: "special", key: btn.key })
      break
    case "printable":
      emit("press", { type: "printable", ch: btn.ch })
      break
  }
}

function modStateFor(btn: Btn): ModState {
  return btn.kind === "modifier" ? (btn.mod === "ctrl" ? props.ctrl : props.alt) : "off"
}
</script>

<template>
  <div
    class="flex items-center gap-1 px-1.5 py-1.5 border-t border-border bg-[var(--cmux-header)]/95 overflow-x-auto shrink-0 select-none"
    style="padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 0.375rem); -webkit-overflow-scrolling: touch"
    role="toolbar"
    aria-label="Terminal keys"
  >
    <template v-for="(btn, i) in buttons" :key="i">
      <span v-if="btn.kind === 'gap'" class="w-px h-5 bg-border/70 shrink-0 mx-0.5" aria-hidden="true" />
      <button
        v-else
        type="button"
        tabindex="-1"
        :aria-pressed="btn.kind === 'modifier' ? modStateFor(btn) !== 'off' : undefined"
        :class="[
          'relative shrink-0 h-9 min-w-[2.25rem] px-2.5 rounded-md text-sm font-medium inline-flex items-center justify-center transition-colors active:scale-95',
          btn.kind === 'printable' ? 'font-mono' : '',
          btn.kind === 'modifier' && modStateFor(btn) !== 'off'
            ? 'bg-primary text-[var(--primary-foreground,#050605)] border border-primary'
            : 'bg-foreground/[0.07] text-foreground/90 border border-transparent active:bg-foreground/15',
        ]"
        @pointerdown.prevent="press(btn)"
      >
        <component :is="btn.icon" v-if="btn.kind === 'special' && btn.icon" class="size-4" />
        <span v-else-if="btn.kind === 'special'">{{ btn.label }}</span>
        <span v-else-if="btn.kind === 'printable'">{{ btn.ch }}</span>
        <span v-else>{{ btn.label }}</span>
        <!-- Lock pip: modifier held down (locked) vs armed for one key (once) -->
        <span
          v-if="btn.kind === 'modifier' && modStateFor(btn) === 'locked'"
          class="absolute -top-0.5 -right-0.5 size-1.5 rounded-full bg-[var(--primary-foreground,#050605)]"
          aria-hidden="true"
        />
      </button>
    </template>
  </div>
</template>
