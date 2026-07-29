<script setup lang="ts">
defineOptions({ name: "MessageReadAloudButton" })

import { onBeforeUnmount, onMounted, ref } from "vue"
import { Volume2, Square } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { cn } from "@/lib/utils"
import { plainTextForSpeech } from "@/lib/speech-text"
import { api } from "@/api/client"
import { getVoiceTtsEngineCached, loadVoiceTtsEngine } from "@/lib/voice-tts-pref"

const props = defineProps<{ text: string; class?: string }>()

const speaking = ref(false)
/** Token so a finished utterance from an earlier click can't clear a newer session. */
let speakGen = 0
let audioEl: HTMLAudioElement | null = null
let audioUrl: string | null = null

function platformSupported(): boolean {
  return typeof window !== "undefined" && typeof window.speechSynthesis !== "undefined"
}

function stopPlatform() {
  try {
    window.speechSynthesis?.cancel()
  } catch { /* ignore */ }
}

function stopAudio() {
  if (audioEl) {
    try {
      audioEl.pause()
      audioEl.removeAttribute("src")
      audioEl.load()
    } catch { /* ignore */ }
    audioEl = null
  }
  if (audioUrl) {
    URL.revokeObjectURL(audioUrl)
    audioUrl = null
  }
}

function stop() {
  speakGen++
  stopPlatform()
  stopAudio()
  speaking.value = false
}

function startPlatform(plain: string) {
  if (!platformSupported()) {
    toast.error("Read aloud isn't supported in this browser")
    return
  }
  stopPlatform()
  stopAudio()
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

async function startCodex() {
  stopPlatform()
  stopAudio()
  const gen = ++speakGen
  speaking.value = true
  try {
    const blob = await api.speak(props.text, { engine: "codex" })
    if (gen !== speakGen) return
    audioUrl = URL.createObjectURL(blob)
    const el = new Audio(audioUrl)
    audioEl = el
    el.onended = () => {
      if (gen === speakGen) speaking.value = false
      stopAudio()
    }
    el.onerror = () => {
      if (gen === speakGen) {
        speaking.value = false
        toast.error("Couldn't play read aloud audio")
      }
      stopAudio()
    }
    await el.play()
  } catch (e: any) {
    if (gen === speakGen) {
      speaking.value = false
      toast.error(e?.message ?? "Couldn't start ChatGPT read aloud")
    }
  }
}

async function toggle() {
  if (speaking.value) {
    stop()
    return
  }
  const engine = await loadVoiceTtsEngine(() => api.getAppConfig())
  if (engine === "codex") {
    await startCodex()
    return
  }
  const plain = plainTextForSpeech(props.text)
  if (!plain) {
    toast.error("Nothing to read")
    return
  }
  startPlatform(plain)
}

onMounted(() => {
  // Warm the cache; ignore errors (defaults to platform).
  void loadVoiceTtsEngine(() => api.getAppConfig())
  void getVoiceTtsEngineCached()
})

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
