// One live dictation streamed from an app: the broker hears the pauses, transcribes, and owns the
// dictated text.
//
// The app streams 16 kHz mono s16 audio as it records ([pushAudio]). Silero scores every 32 ms
// frame; 0.3 s of silence after speech is a pause, and each pause transcribes the CURRENT SENTENCE
// — from its start to now — while Smart Turn judges whether that sentence is finished. The text
// goes back at once either way. The verdict only decides where the next pass starts: after a
// finished sentence, or (not finished) at the same start again, so the sentence is transcribed in
// one piece next time. Cuts fall in silence, so no word is ever split and nothing needs stitching.
//
// Why here and not in the app (2026-10-31): the apps judged pauses by loudness against a noise
// floor that took ~10 s to learn, so in ~1 recording in 6 they heard no pause at all in the first
// 6 s, and every pass re-uploaded the whole recording (785 MB sent for 133 MB of audio).
//
// The text sent to the app is always the WHOLE dictated text; the app puts it in place of
// everything it dictated, so the broker can change any of it — a sentence transcribed again in one
// piece, a spoken edit ("remove the last sentence") applied after the fact.

import { makeLogger } from "../../../shared/log"
import type { VadStream } from "./speech-models"

const log = makeLogger("dictation-stream")

/** 16 kHz: samples per millisecond. */
const PER_MS = 16
/** Silero's frame at 16 kHz. */
const FRAME = 512
const FRAME_MS = FRAME / PER_MS

/** Broker → app. */
export type StreamEvent =
  /** The complete dictated text so far — it replaces everything dictated. */
  | { type: "text"; text: string }
  /** Stop's answer. `pendingEdit` = a spoken-edit check is still out; a `text` may follow before `done`. */
  | { type: "final"; text: string; pendingEdit?: boolean }
  /** Nothing more will come; the app may close the socket. */
  | { type: "done" }
  /** `fatal` = the stream cannot continue; the app should fall back to uploading its recording. */
  | { type: "error"; message: string; fatal: boolean }

export interface StreamTiming {
  /** A frame scoring at least this starts speech. */
  speechThreshold: number
  /** While speaking, a frame below this counts as silence (Silero's own hysteresis: 0.15 lower). */
  silenceThreshold: number
  /** Silence this long after speech = a pause → a pass. */
  pauseMs: number
  /** A pass needs this much speech since the last one: a breath or a click is where speech models invent sentences. */
  minSpeechMs: number
  /** Talking without a pause: a temporary pass this often, so the text keeps up. */
  runPassMs: number
  /** A sentence this long is locked at its next pause whatever Smart Turn says, so passes stay short. */
  maxSentenceMs: number
  /** Smart Turn at or above this = the sentence is finished. */
  turnThreshold: number
  /** How much audio Smart Turn hears (its model reads at most 8 s). */
  turnWindowMs: number
  /** A verdict slower than this counts as "finished" (lock and move on). */
  turnTimeoutMs: number
  /** At Stop, this much speech after the shown text means there is more to transcribe. */
  stopSpeechMs: number
  /** No more audio is kept past this (a forgotten open mic). */
  maxAudioMs: number
  /** Stop waits this long for a spoken-edit check still out before saying `done`. */
  lateEditMs: number
}

export const DEFAULT_TIMING: StreamTiming = {
  speechThreshold: 0.5,
  silenceThreshold: 0.35,
  pauseMs: 300,
  minSpeechMs: 250,
  runPassMs: 5_000,
  maxSentenceMs: 15_000,
  turnThreshold: 0.5,
  turnWindowMs: 8_000,
  turnTimeoutMs: 2_000,
  stopSpeechMs: 64,
  maxAudioMs: 30 * 60_000,
  lateEditMs: 8_000,
}

export interface DictationStreamDeps {
  /** A speech detector for this dictation. */
  vad: (onProbs: (probs: Float32Array) => void, onError: (err: Error) => void) => VadStream
  /** Smart Turn's probability that the speaker finished, for 16 kHz audio ending at the pause. */
  turn: (audio: Float32Array) => Promise<number>
  /**
   * Whether the engine is quick enough for live passes. When false, nothing is transcribed until
   * Stop, which hands the whole recording to [transcribeWhole].
   */
  livePasses: boolean
  /** One live pass: 16 kHz mono s16 audio → its text. [context] = the dictated text before it. */
  transcribe: (pcm: Int16Array, opts: { context: string; final: boolean }) => Promise<string>
  /** A slow engine's Stop: the whole recording → its (cleaned) text. */
  transcribeWhole?: (pcm: Int16Array) => Promise<string>
  /** Spoken edits: (text so far, the new words) → the edited text, or null when they were dictation. */
  checkEdit?: (text: string, newWords: string) => Promise<string | null>
  /** Close the engine's connection for this dictation (Stop, cancel). */
  endStream?: () => void
  send: (event: StreamEvent) => void
  /** For logs only. */
  id?: string
  timing?: Partial<StreamTiming>
  now?: () => number
}

type PassKind = "pause" | "run"

const join = (...parts: string[]): string => parts.map((p) => p.trim()).filter(Boolean).join(" ")

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T | null> {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(null), ms)
    p.then((v) => { clearTimeout(t); resolve(v) }, () => { clearTimeout(t); resolve(null) })
  })
}

export class DictationStream {
  private readonly t: StreamTiming
  private readonly now: () => number
  private readonly vad: VadStream

  // Audio, as received.
  private pcm = new Int16Array(16_000 * 30)
  private samples = 0
  /** A lone byte of a sample split across two chunks. */
  private oddByte: number | null = null

  // The speech detector's view, one entry per 512-sample frame.
  private speech = new Uint8Array(1_024)
  private frames = 0
  private speaking = false
  private silentFrames = 0
  private speechFramesSinceTrigger = 0

  // The text. `locked` = finished sentences (and the user's own edits); `provisional` = the
  // current sentence as last transcribed, from `sentenceStart` up to `shownEnd`.
  private locked = ""
  private provisional = ""
  private sentenceStart = 0
  private shownEnd = 0
  private lastSent = ""

  // Passes: one at a time, at most one waiting.
  private inFlight: Promise<void> | null = null
  private pending: { kind: PassKind; end: number } | null = null
  /** Bumped by a hand edit and by cancel: a pass from before it must not write its answer. */
  private generation = 0

  // Spoken-edit checks, one at a time, in order.
  private edits: Promise<void> = Promise.resolve()
  private editsOut = 0

  private state: "live" | "stopping" | "done" = "live"
  private passes = 0

  constructor(private readonly deps: DictationStreamDeps) {
    this.t = { ...DEFAULT_TIMING, ...deps.timing }
    this.now = deps.now ?? Date.now
    this.vad = deps.vad((p) => this.onProbs(p), (e) => this.fail(e))
  }

  /** Raw 16 kHz mono s16le audio from the app, as it records. */
  pushAudio(bytes: Uint8Array): void {
    if (this.state !== "live" || bytes.length === 0) return
    let b = bytes
    if (this.oddByte !== null) {
      const joined = new Uint8Array(b.length + 1)
      joined[0] = this.oddByte
      joined.set(b, 1)
      b = joined
      this.oddByte = null
    }
    if (b.length % 2 === 1) {
      this.oddByte = b[b.length - 1]!
      b = b.subarray(0, b.length - 1)
    }
    let n = b.length / 2
    const room = this.t.maxAudioMs * PER_MS - this.samples
    if (n > room) n = Math.max(0, room)
    if (n === 0) return
    const chunk = new Int16Array(n)
    const view = new DataView(b.buffer, b.byteOffset, n * 2)
    for (let i = 0; i < n; i++) chunk[i] = view.getInt16(i * 2, true)
    if (this.samples + n > this.pcm.length) {
      const grown = new Int16Array(Math.max(this.pcm.length * 2, this.samples + n))
      grown.set(this.pcm.subarray(0, this.samples))
      this.pcm = grown
    }
    this.pcm.set(chunk, this.samples)
    this.samples += n
    this.vad.push(chunk)
  }

  /**
   * The user changed the dictated text by hand while recording. Their version wins: it becomes
   * the text so far, and transcription carries on with the audio after what they were shown.
   */
  edit(text: string): void {
    if (this.state === "done") return
    this.generation++
    this.locked = text.trim()
    this.provisional = ""
    this.sentenceStart = this.shownEnd
    this.lastSent = this.locked
  }

  /** Stop: finish the text, send `final`, then `done` (after any late spoken edit). */
  async stop(): Promise<void> {
    if (this.state !== "live") return
    this.state = "stopping"
    const t0 = this.now()
    await this.vad.flush()
    await this.whenIdle()
    if (this.isDone()) return
    if (!this.deps.livePasses) {
      this.locked = await this.wholeText()
    } else if (this.speechMs(this.shownEnd, this.frames * FRAME) >= this.t.stopSpeechMs) {
      // Words after the shown text: one more pass, over the whole current sentence.
      const gen = this.generation
      const text = await this.runStt(this.sentenceStart, this.samples, true)
      if (this.isDone() || gen !== this.generation) return
      if (text !== null) {
        const before = this.locked
        this.locked = join(this.locked, text)
        this.launchEditCheck(before, text)
      } else {
        // The live engine failed on the last words: the whole recording through the fallback chain.
        const whole = this.deps.transcribeWhole ? await this.wholeText() : ""
        if (this.isDone()) return
        this.locked = whole || join(this.locked, this.provisional)
      }
    } else {
      this.locked = join(this.locked, this.provisional)
    }
    this.provisional = ""
    this.deps.endStream?.()
    this.vad.close()
    this.state = "done"
    const final = this.locked
    this.lastSent = final
    const pendingEdit = this.editsOut > 0
    this.deps.send({ type: "final", text: final, ...(pendingEdit ? { pendingEdit: true } : {}) })
    log.info("dictation_stream_stop", { id: this.deps.id ?? null, ms: this.now() - t0, audioMs: Math.round(this.samples / PER_MS), passes: this.passes, chars: final.length, pendingEdit })
    if (pendingEdit) await withTimeout(this.edits, this.t.lateEditMs)
    this.deps.send({ type: "done" })
  }

  /** Discard: no more text, no final. Also what a dropped socket does. */
  cancel(): void {
    if (this.state === "done") return
    this.state = "done"
    this.generation++
    this.pending = null
    this.vad.close()
    this.deps.endStream?.()
  }

  /** Resolves when no pass is out or waiting. */
  async whenIdle(): Promise<void> {
    while (this.inFlight) await this.inFlight
  }

  // ---- speech detection ----

  private onProbs(probs: Float32Array): void {
    if (this.state === "done") return
    for (const p of probs) {
      const i = this.frames++
      if (i >= this.speech.length) {
        const grown = new Uint8Array(this.speech.length * 2)
        grown.set(this.speech)
        this.speech = grown
      }
      if (this.speaking ? p >= this.t.silenceThreshold : p >= this.t.speechThreshold) {
        this.speaking = true
        this.silentFrames = 0
        this.speech[i] = 1
        this.speechFramesSinceTrigger++
      } else if (this.speaking) {
        this.silentFrames++
        if (this.silentFrames * FRAME_MS >= this.t.pauseMs) {
          this.speaking = false
          if (this.speechFramesSinceTrigger * FRAME_MS >= this.t.minSpeechMs) this.trigger("pause", this.frames * FRAME)
        }
      }
      // Talking on without a pause: counted in speech, so a silence before it does not count.
      if (this.speaking && this.speechFramesSinceTrigger * FRAME_MS >= this.t.runPassMs) {
        this.trigger("run", this.frames * FRAME)
      }
    }
  }

  /** Milliseconds of speech in the frames covering samples [from, to). */
  private speechMs(from: number, to: number): number {
    let n = 0
    const last = Math.min(this.frames, Math.ceil(to / FRAME))
    for (let i = Math.floor(from / FRAME); i < last; i++) n += this.speech[i]!
    return n * FRAME_MS
  }

  // ---- passes ----

  private trigger(kind: PassKind, end: number): void {
    this.speechFramesSinceTrigger = 0
    if (this.state === "done") return
    if (this.inFlight) {
      // A waiting pause keeps its place: it is the one that can lock a sentence at a silence.
      if (!(this.pending?.kind === "pause" && kind === "run")) this.pending = { kind, end }
      return
    }
    this.start(kind, end)
  }

  private start(kind: PassKind, end: number): void {
    this.inFlight = this.pass(kind, end)
      .catch((e) => log.warn("dictation_stream_pass_failed", { id: this.deps.id ?? null, err: String(e) }))
      .finally(() => {
        this.inFlight = null
        const next = this.pending
        this.pending = null
        if (next && this.state !== "done") this.start(next.kind, next.end)
      })
  }

  private async pass(kind: PassKind, end: number): Promise<void> {
    if (!this.deps.livePasses) return
    const start = this.sentenceStart
    // Too little audio for an engine (the realtime API refuses < 100 ms), or nothing but noise.
    if (end - start < 100 * PER_MS) return
    if (this.speechMs(start, end) < this.t.minSpeechMs) {
      if (kind === "pause" && !this.provisional) this.sentenceStart = end
      return
    }
    const gen = this.generation
    const t0 = this.now()
    const verdict = kind === "pause" ? withTimeout(this.deps.turn(this.window(end)), this.t.turnTimeoutMs) : null
    const text = await this.runStt(start, end, false)
    if (this.isDone() || gen !== this.generation) return
    if (text !== null) {
      this.provisional = text
      this.shownEnd = end
      this.emit()
    }
    let p: number | null = null
    let locked = false
    if (verdict) {
      p = await verdict
      if (this.isDone() || gen !== this.generation) return
      const long = (end - start) / PER_MS >= this.t.maxSentenceMs
      // An unknown verdict locks too: a short pass is worth more than a perfect boundary.
      if (text !== null && (p === null || p >= this.t.turnThreshold || long)) {
        const before = this.locked
        this.locked = join(this.locked, text)
        this.provisional = ""
        this.sentenceStart = end
        locked = true
        this.launchEditCheck(before, text)
      }
    }
    this.passes++
    log.info("dictation_stream_pass", {
      id: this.deps.id ?? null, kind, audioMs: Math.round((end - start) / PER_MS), ms: this.now() - t0,
      turn: p === null ? null : Math.round(p * 1000) / 1000, locked, chars: text?.length ?? null,
    })
  }

  /** Transcribe samples [start, end); null = the engine failed (the audio stays for the next pass). */
  private async runStt(start: number, end: number, final: boolean): Promise<string | null> {
    try {
      return (await this.deps.transcribe(this.pcm.slice(start, end), { context: this.locked, final })).trim()
    } catch (e) {
      log.warn("dictation_stream_stt_failed", { id: this.deps.id ?? null, final, err: String(e) })
      return null
    }
  }

  private async wholeText(): Promise<string> {
    if (!this.deps.transcribeWhole || this.speechMs(0, this.frames * FRAME) < this.t.stopSpeechMs) return ""
    try {
      return (await this.deps.transcribeWhole(this.pcm.slice(0, this.samples))).trim()
    } catch (e) {
      log.warn("dictation_stream_whole_failed", { id: this.deps.id ?? null, err: String(e) })
      return ""
    }
  }

  /** The audio Smart Turn hears for a pause at [end]: the last few seconds, as -1..1 floats. */
  private window(end: number): Float32Array {
    const from = Math.max(0, end - this.t.turnWindowMs * PER_MS)
    const out = new Float32Array(end - from)
    for (let i = 0; i < out.length; i++) out[i] = this.pcm[from + i]! / 32768
    return out
  }

  // ---- text ----

  private emit(): void {
    const text = join(this.locked, this.provisional)
    if (text === this.lastSent) return
    this.lastSent = text
    this.deps.send({ type: "text", text })
  }

  /**
   * Were [added] (just locked after [base]) a spoken edit — "remove the last sentence"? Checked in
   * the background, one at a time; an edit replaces the text it was checked against, keeping
   * whatever came after it, and is dropped if that text has changed since.
   */
  private launchEditCheck(base: string, added: string): void {
    const check = this.deps.checkEdit
    if (!check || !base.trim() || !added.trim()) return
    const snapshot = this.locked
    this.editsOut++
    this.edits = this.edits.then(async () => {
      try {
        const edited = await check(base, added).catch(() => null)
        if (edited === null || !this.locked.startsWith(snapshot)) return
        this.locked = join(edited, this.locked.slice(snapshot.length))
        // After Stop the app is still listening for this one (`pendingEdit`).
        if (this.state === "done") {
          this.lastSent = this.locked
          this.deps.send({ type: "text", text: this.locked })
        } else {
          this.emit()
        }
      } finally {
        this.editsOut--
      }
    })
  }

  private fail(err: Error): void {
    if (this.state === "done") return
    log.warn("dictation_stream_vad_failed", { id: this.deps.id ?? null, err: err.message })
    this.cancel()
    this.deps.send({ type: "error", message: `speech detection failed: ${err.message}`, fatal: true })
  }

  private isDone(): boolean {
    return this.state === "done"
  }
}
