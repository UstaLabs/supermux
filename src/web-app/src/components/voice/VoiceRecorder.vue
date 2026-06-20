<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue"
import { X, Square, Loader2 } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { useMediaRecorder, type RecordedClip } from "@/composables/useMediaRecorder"
import { useWaveform } from "@/composables/useWaveform"
import { useTranscriber } from "@/composables/useTranscriber"
import { useVoicePreviews } from "@/stores/voice-previews"
import { usePromptInput } from "@/components/ai-elements/prompt-input/context"

const props = defineProps<{ sessionId?: string }>()

const waveform = useWaveform()
const previews = useVoicePreviews()
const promptInput = usePromptInput()
const { addFiles, setTextInput } = promptInput

const canvasRef = ref<HTMLCanvasElement | null>(null)
// While transcription is in flight we swap the recorder bar for a small spinner
// and ignore further Stop clicks.
const cleaning = ref(false)

const emit = defineEmits<{ (e: "done"): void }>()

const recorder = useMediaRecorder({
  maxSeconds: Number(import.meta.env.VITE_WEB_VOICE_MAX_SEC ?? 600),
  onAutoStop: (clip) => {
    toast.info(props.sessionId ? "Max recording length reached." : "Max recording length reached — saved to composer.")
    void finalize(clip)
  },
})

function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${s.toString().padStart(2, "0")}`
}

function extFromMime(mime: string): string {
  if (mime.includes("webm")) return "webm"
  if (mime.includes("mp4")) return "m4a"
  if (mime.includes("ogg")) return "ogg"
  return "audio"
}

watch(() => recorder.state.value, (s) => {
  if (s.kind === "recording" && canvasRef.value) {
    waveform.attach(s.stream, canvasRef.value)
  }
  if (s.kind === "error") {
    toast.error("Mic unavailable", { description: s.message })
    emit("done")
  }
})

onMounted(() => {
  void recorder.start()
})

onBeforeUnmount(() => {
  waveform.detach()
})

async function finalize(clip: RecordedClip) {
  const snapshot = waveform.snapshot()
  waveform.detach()
  // Pause any currently-playing <audio> elements so they don't pick up our output
  document.querySelectorAll("audio").forEach((a) => a.pause())

  const now = new Date()
  const stamp = `${now.getFullYear()}${(now.getMonth() + 1).toString().padStart(2, "0")}${now.getDate().toString().padStart(2, "0")}-${now.getHours().toString().padStart(2, "0")}${now.getMinutes().toString().padStart(2, "0")}${now.getSeconds().toString().padStart(2, "0")}-${now.getMilliseconds().toString().padStart(3, "0")}`
  const filename = `voice-${stamp}.${extFromMime(clip.mime)}`

  // DICTATE: when mounted with a session, transcribe the clip and drop the text
  // into the composer (not sent). No waveform snapshot / attachment is needed.
  if (props.sessionId) {
    cleaning.value = true
    try {
      const { text } = await useTranscriber().transcribe(props.sessionId, clip.blob, filename)
      const prev = promptInput.textInput.value
      setTextInput(prev ? prev + " " + text : text)
    } catch (err: any) {
      toast.error("Transcription failed", { description: err?.message })
    } finally {
      cleaning.value = false
      emit("done")
    }
    return
  }

  // LEGACY (launcher, no session id): attach the recording as a voice file.
  const file = new File([clip.blob], filename, { type: clip.mime })
  ;(file as any)._cmuxKind = "voice"
  ;(file as any)._cmuxDurationMs = clip.durationMs
  previews.set(filename, snapshot)
  addFiles([file])
  emit("done")
}

async function onStop() {
  if (cleaning.value) return
  const clip = await recorder.stop()
  if (!clip) {
    waveform.detach()
    emit("done")
    return
  }
  void finalize(clip)
}

function onCancel() {
  waveform.detach()
  recorder.cancel()
  emit("done")
}
</script>

<template>
  <div
    role="status"
    aria-live="polite"
    class="flex items-center gap-3 w-full bg-card/40 rounded-md px-3 py-2"
  >
    <!-- Transcription in flight: small spinner + label, no controls -->
    <div v-if="cleaning" class="flex-1 min-w-0 flex items-center gap-3 h-10">
      <Loader2 class="size-4 shrink-0 animate-spin text-primary" />
      <span class="text-sm text-muted-foreground">Cleaning up…</span>
    </div>

    <template v-else>
      <button
        type="button"
        class="size-8 rounded-full hover:bg-muted text-muted-foreground hover:text-foreground transition flex items-center justify-center shrink-0"
        aria-label="Cancel recording"
        @click="onCancel"
      >
        <X class="size-4" />
      </button>

      <div class="flex-1 min-w-0 flex items-center gap-3">
        <span class="size-2.5 rounded-full bg-red-500 animate-pulse shrink-0" aria-hidden="true" />
        <canvas
          ref="canvasRef"
          class="flex-1 h-10 text-foreground"
          style="image-rendering: pixelated;"
        />
        <span class="font-mono text-sm tabular-nums shrink-0">{{ formatDuration(recorder.durationMs.value) }}</span>
      </div>

      <button
        type="button"
        class="size-8 rounded-full bg-foreground text-background hover:bg-foreground/90 transition flex items-center justify-center shrink-0"
        aria-label="Stop recording"
        @click="onStop"
      >
        <Square class="size-3" />
      </button>
    </template>
  </div>
</template>
