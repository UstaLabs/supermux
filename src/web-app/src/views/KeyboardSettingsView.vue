<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, RotateCcw } from "lucide-vue-next"
import {
  formatChord,
  KEYBINDING_COMMANDS,
  parseRecordedKeydown,
  type KeybindingCommandId,
} from "@/lib/keybindings"
import { useKeybindings } from "@/stores/keybindings"

const router = useRouter()
const keybindings = useKeybindings()

const recordingId = ref<KeybindingCommandId | null>(null)
const recordError = ref<string | null>(null)

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

function startRecording(id: KeybindingCommandId) {
  recordingId.value = id
  recordError.value = null
}

function cancelRecording() {
  recordingId.value = null
  recordError.value = null
}

function onRecordKeydown(e: KeyboardEvent) {
  if (!recordingId.value) return
  e.preventDefault()
  e.stopPropagation()
  if (e.key === "Escape") {
    cancelRecording()
    return
  }
  const chord = parseRecordedKeydown(e)
  if (!chord) {
    recordError.value = "Use Ctrl or ⌘ plus a letter or `"
    return
  }
  const conflict = keybindings.setOverride(recordingId.value, chord)
  if (conflict) {
    recordError.value = `Already used by “${conflict}”`
    return
  }
  cancelRecording()
}

function resetOne(id: KeybindingCommandId) {
  keybindings.clearOverride(id)
  if (recordingId.value === id) cancelRecording()
}

function resetAll() {
  keybindings.resetAll()
  cancelRecording()
}

const rows = computed(() =>
  KEYBINDING_COMMANDS.map((cmd) => ({
    ...cmd,
    chord: keybindings.chordFor(cmd.id),
    defaultLabel: formatChord(cmd.defaultChord),
    overridden: keybindings.isOverridden(cmd.id),
  })),
)

onMounted(() => document.addEventListener("keydown", onRecordKeydown, true))
onBeforeUnmount(() => document.removeEventListener("keydown", onRecordKeydown, true))
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
      <h1 class="text-base font-semibold tracking-tight">Keyboard</h1>
    </header>

    <div class="px-4 py-4 space-y-4 max-w-lg mx-auto">
      <p class="text-sm text-muted-foreground leading-relaxed">
        Desktop workspace shortcuts. While supermux is focused, these override some browser
        shortcuts (for example <span class="font-mono text-xs">Ctrl+L</span> / <span class="font-mono text-xs">⌘L</span>
        for chat, <span class="font-mono text-xs">Ctrl+T</span> / <span class="font-mono text-xs">⌘T</span>
        for terminal, and <span class="font-mono text-xs">Ctrl+D</span> / <span class="font-mono text-xs">⌘D</span>
        for display instead of browser defaults).
      </p>

      <ul class="divide-y divide-border rounded-lg border border-border overflow-hidden">
        <li
          v-for="row in rows"
          :key="row.id"
          class="px-4 py-3.5 bg-card/40"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <div class="font-medium">{{ row.label }}</div>
              <div class="text-[11px] text-muted-foreground mt-0.5">{{ row.description }}</div>
              <div v-if="row.overridden" class="text-[10px] text-muted-foreground/70 mt-1 font-mono">
                Default: {{ row.defaultLabel }}
              </div>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <kbd
                class="inline-flex min-w-[4.5rem] justify-center items-center px-2 py-1 rounded-md bg-muted text-xs font-mono border border-border"
                :class="{ 'ring-2 ring-primary': recordingId === row.id }"
              >
                {{ recordingId === row.id ? "…" : formatChord(row.chord) }}
              </kbd>
              <button
                class="text-xs px-2 py-1 rounded-md border border-border hover:bg-accent transition"
                :class="{ 'bg-primary text-primary-foreground border-primary': recordingId === row.id }"
                @click="recordingId === row.id ? cancelRecording() : startRecording(row.id)"
              >
                {{ recordingId === row.id ? "Cancel" : "Record" }}
              </button>
              <button
                v-if="row.overridden"
                class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition"
                aria-label="Reset to default"
                @click="resetOne(row.id)"
              >
                <RotateCcw class="size-3.5" />
              </button>
            </div>
          </div>
          <p
            v-if="recordingId === row.id && recordError"
            class="text-[11px] text-destructive mt-2"
          >
            {{ recordError }}
          </p>
        </li>
      </ul>

      <button
        class="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-md border border-border text-sm hover:bg-accent transition"
        @click="resetAll"
      >
        <RotateCcw class="size-4" />
        Reset all to defaults
      </button>
    </div>
  </div>
</template>
