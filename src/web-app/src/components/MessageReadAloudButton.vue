<script setup lang="ts">
defineOptions({ name: "MessageReadAloudButton" })

import { onBeforeUnmount, ref } from "vue"
import { Volume2, Square } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { cn } from "@/lib/utils"
import { plainTextForSpeech } from "@/lib/speech-text"

const props = defineProps<{ text: string; class?: string }>()

const speaking = ref(false)
/** Token so a finished utterance from an earlier click can't clear a newer session. */
let speakGen = 0

function supported(): boolean {
  return typeof window !== "undefined" && typeof window.speechSynthesis !== "undefined"
}

function stop() {
  speakGen++
  try {
    window.speechSynthesis?.cancel()
  } catch { /* ignore */ }
  speaking.value = false
}

function start() {
  if (!supported()) {
    toast.error("Read aloud isn't supported in this browser")
    return
  }
  const plain = plainTextForSpeech(props.text)
  if (!plain) {
    toast.error("Nothing to read")
    return
  }

  // Cancel any other message that's currently speaking.
  try {
    window.speechSynthesis.cancel()
  } catch { /* ignore */ }

  const gen = ++speakGen
  const u = new SpeechSynthesisUtterance(plain)
  u.onend = () => {
    if (gen === speakGen) speaking.value = false
  }
  u.onerror = () => {
    if (gen === speakGen) speaking.value = false
  }
  speaking.value = true
  try {
    window.speechSynthesis.speak(u)
  } catch {
    speaking.value = false
    toast.error("Couldn't start read aloud")
  }
}

function toggle() {
  if (speaking.value) stop()
  else start()
}

onBeforeUnmount(() => {
  if (speaking.value) stop()
})
</script>

<template>
  <button
    type="button"
    :class="cn(
      'inline-flex items-center gap-1 self-start rounded-md px-1.5 py-0.5',
      'text-[11px] text-muted-foreground/70 transition-colors',
      'hover:bg-muted/60 hover:text-foreground active:scale-95',
      props.class,
    )"
    :aria-label="speaking ? 'Stop reading' : 'Read aloud'"
    :aria-pressed="speaking"
    @click="toggle"
  >
    <Square v-if="speaking" class="size-3.5 text-emerald-500" />
    <Volume2 v-else class="size-3.5" />
    <span>{{ speaking ? "Stop" : "Read aloud" }}</span>
  </button>
</template>
