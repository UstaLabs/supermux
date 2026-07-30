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
/** Pending codex audio pieces (already arrived; play sequentially). */
let playQueue: { blob: Blob; url: string }[] = []
let queuePlaying = false
let abortCtl: AbortController | null = null

function platformSupported(): boolean {
  return typeof window !== "undefined" && typeof window.speechSynthesis !== "undefined"
}

function stopPlatform() {
  try {
    window.speechSynthesis?.cancel()
  } catch { /* ignore */ }
}

function revokeUrl(url: string | null) {
  if (url) {
    try { URL.revokeObjectURL(url) } catch { /* ignore */ }
  }
}

function stopAudio() {
  abortCtl?.abort()
  abortCtl = null
  queuePlaying = false
  for (const q of playQueue) revokeUrl(q.url)
  playQueue = []
  if (audioEl) {
    try {
      audioEl.pause()
      audioEl.removeAttribute("src")
      audioEl.load()
    } catch { /* ignore */ }
    audioEl = null
  }
  revokeUrl(audioUrl)
  audioUrl = null
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

function playNextInQueue(gen: number) {
  if (gen !== speakGen) return
  if (queuePlaying) return
  const next = playQueue.shift()
  if (!next) {
    // Queue empty — if stream still open, speaking stays true until stream ends
    // and queue drains. Caller clears speaking when stream done AND queue empty.
    return
  }
  queuePlaying = true
  revokeUrl(audioUrl)
  audioUrl = next.url
  const el = new Audio(next.url)
  audioEl = el
  el.onended = () => {
    queuePlaying = false
    audioEl = null
    if (gen !== speakGen) return
    if (playQueue.length) {
      playNextInQueue(gen)
    } else if (!abortCtl) {
      // Stream finished and queue drained
      speaking.value = false
      revokeUrl(audioUrl)
      audioUrl = null
    }
  }
  el.onerror = () => {
    queuePlaying = false
    audioEl = null
    if (gen === speakGen) {
      speaking.value = false
      toast.error("Couldn't play read aloud audio")
    }
    stopAudio()
  }
  void el.play().catch(() => {
    if (gen === speakGen) {
      speaking.value = false
      toast.error("Couldn't play read aloud audio")
    }
    stopAudio()
  })
}

async function startCodex() {
  stopPlatform()
  stopAudio()
  const gen = ++speakGen
  speaking.value = true
  const ctl = new AbortController()
  abortCtl = ctl
  try {
    let got = 0
    for await (const chunk of api.speakStream(props.text, { engine: "codex", signal: ctl.signal })) {
      if (gen !== speakGen) return
      got++
      const url = URL.createObjectURL(chunk.blob)
      playQueue.push({ blob: chunk.blob, url })
      // Start playback as soon as the first chunk arrives.
      playNextInQueue(gen)
    }
    abortCtl = null
    if (gen !== speakGen) return
    if (got === 0) {
      speaking.value = false
      toast.error("Nothing to read")
      return
    }
    // If the last chunk already finished playing, onended cleared speaking.
    // If still playing / queued, onended of the last item will clear it.
    if (!queuePlaying && playQueue.length === 0) {
      speaking.value = false
    }
  } catch (e: any) {
    if (ctl.signal.aborted || gen !== speakGen) return
    speaking.value = false
    stopAudio()
    toast.error(e?.message ?? "Couldn't start ChatGPT read aloud")
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
