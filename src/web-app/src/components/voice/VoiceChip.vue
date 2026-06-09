<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watchEffect } from "vue"
import { Mic, Play, Pause, X, Loader2, Check, AlertCircle } from "lucide-vue-next"
import { useVoicePreviews } from "@/stores/voice-previews"
import { useUploads } from "@/stores/uploads"
import { usePromptInput } from "@/components/ai-elements/prompt-input/context"

interface Props {
  id: string
  filename?: string
  url?: string                // ai-elements blob URL
  durationMs?: number
}
const props = defineProps<Props>()

const previews = useVoicePreviews()
const uploads = useUploads()
const { removeFile } = usePromptInput()

const audioEl = ref<HTMLAudioElement | null>(null)
const playing = ref(false)
const canvasRef = ref<HTMLCanvasElement | null>(null)

const status = computed(() => uploads.byId[props.id])
const samples = computed(() => (props.filename ? previews.get(props.filename) : undefined) ?? [])

function formatDuration(ms?: number): string {
  if (!ms) return "0:00"
  const total = Math.floor(ms / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${s.toString().padStart(2, "0")}`
}

function drawSnapshot() {
  const canvas = canvasRef.value
  if (!canvas) return
  canvas.width = canvas.clientWidth * (window.devicePixelRatio || 1)
  canvas.height = canvas.clientHeight * (window.devicePixelRatio || 1)
  const ctx = canvas.getContext("2d")
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = getComputedStyle(canvas).color || "#fff"
  const arr = samples.value
  const barW = arr.length > 0 ? canvas.width / arr.length : 0
  for (let i = 0; i < arr.length; i++) {
    const amp = arr[i]!
    const barH = Math.max(2, amp * canvas.height)
    const x = i * barW
    const y = (canvas.height - barH) / 2
    ctx.fillRect(x, y, Math.max(1, barW - 1), barH)
  }
}

onMounted(() => {
  drawSnapshot()
})

watchEffect(drawSnapshot)

onBeforeUnmount(() => {
  if (audioEl.value) {
    audioEl.value.pause()
    audioEl.value = null
  }
})

function togglePlay() {
  if (!props.url) return
  if (!audioEl.value) {
    audioEl.value = new Audio(props.url)
    audioEl.value.addEventListener("ended", () => { playing.value = false })
  }
  if (playing.value) {
    audioEl.value.pause()
    playing.value = false
  } else {
    void audioEl.value.play()
    playing.value = true
  }
}

function onRemove() {
  if (audioEl.value) audioEl.value.pause()
  removeFile(props.id)
  if (props.filename) previews.remove(props.filename)
  uploads.reset(props.id)
}
</script>

<template>
  <div
    class="relative flex items-center gap-2 pr-7 rounded-md border border-border bg-card/60 overflow-hidden"
    :class="status?.status === 'failed' ? 'border-destructive' : ''"
  >
    <Mic class="ml-2 size-4 text-muted-foreground shrink-0" />

    <button
      type="button"
      class="size-6 rounded-full bg-foreground/10 hover:bg-foreground/20 transition flex items-center justify-center shrink-0"
      :aria-label="playing ? 'Pause voice memo' : 'Play voice memo'"
      @click="togglePlay"
    >
      <Pause v-if="playing" class="size-3" />
      <Play v-else class="size-3" />
    </button>

    <canvas ref="canvasRef" class="h-6 w-[60px] text-muted-foreground shrink-0" />

    <span class="text-xs font-mono tabular-nums text-muted-foreground py-1.5 pr-1 shrink-0">
      {{ formatDuration(durationMs) }}
    </span>

    <div
      v-if="status?.status === 'uploading'"
      class="absolute inset-0 bg-background/60 flex items-center justify-center pointer-events-none"
    >
      <Loader2 class="size-4 animate-spin text-foreground" />
    </div>
    <div
      v-else-if="status?.status === 'uploaded'"
      class="absolute top-0.5 right-7 size-3 rounded-full bg-emerald-500 flex items-center justify-center pointer-events-none"
    >
      <Check class="size-2 text-background" />
    </div>
    <div
      v-else-if="status?.status === 'failed'"
      class="absolute top-0.5 right-7 size-3 rounded-full bg-destructive flex items-center justify-center pointer-events-none"
    >
      <AlertCircle class="size-2 text-background" />
    </div>

    <button
      type="button"
      class="absolute top-1/2 -translate-y-1/2 right-1 size-5 rounded-full hover:bg-muted text-muted-foreground hover:text-foreground transition flex items-center justify-center"
      aria-label="Remove voice memo"
      @click="onRemove"
    >
      <X class="size-3" />
    </button>
  </div>
</template>
