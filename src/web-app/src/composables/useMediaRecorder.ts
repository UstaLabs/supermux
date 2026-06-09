// src/web-app/src/composables/useMediaRecorder.ts
import { ref, onBeforeUnmount, type Ref } from "vue"

export type RecorderState =
  | { kind: "idle" }
  | { kind: "requesting" }
  | { kind: "recording"; startedAt: number; stream: MediaStream }
  | { kind: "stopping" }
  | { kind: "error"; message: string }

export interface RecordedClip {
  blob: Blob
  mime: string
  durationMs: number
}

const PREFERRED_MIMES = [
  "audio/webm;codecs=opus",
  "audio/webm",
  "audio/mp4;codecs=mp4a.40.2",
  "audio/mp4",
  "audio/ogg;codecs=opus",
]

function pickMime(): string | null {
  if (typeof MediaRecorder === "undefined") return null
  for (const m of PREFERRED_MIMES) {
    if (MediaRecorder.isTypeSupported(m)) return m
  }
  return ""  // empty string lets the browser pick its own default
}

export interface UseMediaRecorder {
  state: Ref<RecorderState>
  durationMs: Ref<number>
  start: () => Promise<void>
  stop: () => Promise<RecordedClip | null>
  cancel: () => void
}

export function useMediaRecorder(
  opts: { maxSeconds?: number; onAutoStop?: (clip: RecordedClip) => void } = {},
): UseMediaRecorder {
  const state = ref<RecorderState>({ kind: "idle" })
  const durationMs = ref(0)
  let recorder: MediaRecorder | null = null
  let chunks: BlobPart[] = []
  let raf: number | null = null
  let timeoutMaxDuration: ReturnType<typeof setTimeout> | null = null
  let attemptSeq = 0

  function tick() {
    if (state.value.kind !== "recording") return
    durationMs.value = Date.now() - state.value.startedAt
    raf = requestAnimationFrame(tick)
  }

  async function start(): Promise<void> {
    if (state.value.kind !== "idle" && state.value.kind !== "error") return
    const myAttempt = ++attemptSeq
    state.value = { kind: "requesting" }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (err: any) {
      if (myAttempt !== attemptSeq) return  // cancelled in flight; nothing to clean up
      state.value = { kind: "error", message: err?.message ?? "Microphone access denied" }
      return
    }
    if (myAttempt !== attemptSeq) {
      // cancelled while permission was being granted; release tracks and bail
      stream.getTracks().forEach((t) => t.stop())
      return
    }
    const mime = pickMime()
    if (mime === null) {
      stream.getTracks().forEach((t) => t.stop())
      state.value = { kind: "error", message: "Browser does not support audio recording" }
      return
    }
    chunks = []
    try {
      recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
    } catch (err: any) {
      stream.getTracks().forEach((t) => t.stop())
      state.value = { kind: "error", message: err?.message ?? "Recorder could not start" }
      return
    }
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data)
    }
    recorder.start(250)  // emit chunks every 250ms so waveform has timely data
    state.value = { kind: "recording", startedAt: Date.now(), stream }
    durationMs.value = 0
    raf = requestAnimationFrame(tick)
    const maxSec = opts.maxSeconds ?? 600
    timeoutMaxDuration = setTimeout(() => {
      void stop().then((clip) => {
        if (clip && opts.onAutoStop) opts.onAutoStop(clip)
      })
    }, maxSec * 1000)
  }

  function teardown() {
    if (raf !== null) { cancelAnimationFrame(raf); raf = null }
    if (timeoutMaxDuration) { clearTimeout(timeoutMaxDuration); timeoutMaxDuration = null }
    if (state.value.kind === "recording") {
      state.value.stream.getTracks().forEach((t) => t.stop())
    }
  }

  async function stop(): Promise<RecordedClip | null> {
    if (state.value.kind !== "recording") return null
    const startedAt = state.value.startedAt
    const stream = state.value.stream
    state.value = { kind: "stopping" }
    return new Promise<RecordedClip | null>((resolve) => {
      if (!recorder) { resolve(null); return }
      const finalize = () => {
        const mime = recorder?.mimeType || "audio/webm"
        const blob = new Blob(chunks, { type: mime })
        chunks = []
        stream.getTracks().forEach((t) => t.stop())
        teardown()
        recorder = null
        state.value = { kind: "idle" }
        resolve({ blob, mime, durationMs: Date.now() - startedAt })
      }
      recorder.onstop = finalize
      try { recorder.stop() } catch { finalize() }
    })
  }

  function cancel(): void {
    // bump attempt counter so any in-flight start() sees the change
    attemptSeq++
    if (recorder && state.value.kind === "recording") {
      try { recorder.stop() } catch { /* ignore */ }
    }
    teardown()
    chunks = []
    recorder = null
    state.value = { kind: "idle" }
    durationMs.value = 0
  }

  onBeforeUnmount(() => { cancel() })

  return { state, durationMs, start, stop, cancel }
}
