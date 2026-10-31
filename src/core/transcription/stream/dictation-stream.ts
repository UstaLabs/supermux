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
  /**
   * The complete dictated text so far — it replaces everything dictated. [seq] numbers it, so a
   * hand edit can say which text it was made on (`edit(text, seq)`).
   */
  | { type: "text"; text: string; seq: number }
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
  /**
   * While talking: a provisional pass after this much speech since the last one, so the words
   * show up while the user is still speaking. It runs on its own connection ([DictationStreamDeps.
   * transcribe]'s `lane: "live"`), so the pass at the next pause never waits behind it.
   */
  runPassMs: number
  /** A sentence this long is locked at its next pause whatever Smart Turn says, so passes stay short. */
  maxSentenceMs: number
  /** Smart Turn at or above this = the sentence is finished. */
  turnThreshold: number
  /** How much audio Smart Turn hears (its model reads at most 8 s). */
  turnWindowMs: number
  /**
   * How long past its transcript a pass waits for Smart Turn. The verdict normally lands first
   * (~0.4 s against ~0.7 s); a later one is not waited for — it would hold up the next pass — and
   * counts as "not finished", so the sentence is simply transcribed again, whole, next time.
   */
  turnGraceMs: number
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
  runPassMs: 1_200,
  maxSentenceMs: 15_000,
  turnThreshold: 0.5,
  turnWindowMs: 8_000,
  turnGraceMs: 500,
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
  transcribe: (pcm: Int16Array, opts: { context: string; final: boolean; lane: PassLane }) => Promise<string>
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
/** Which connection a pass uses: "main" for pauses and Stop, "live" for the passes while talking. */
export type PassLane = "main" | "live"

const join = (...parts: string[]): string => parts.map((p) => p.trim()).filter(Boolean).join(" ")

const norm = (w: string) => w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "")

/**
 * [full] without the words of [prefix] at its front — matched word by word, ignoring case and
 * punctuation ("screen." = "screen,") — or null when [full] does not begin with them. How a
 * sentence found finished late is split off a newer text that already runs past it.
 */
export function stripLeadingWords(full: string, prefix: string): string | null {
  const want = prefix.split(/\s+/).map(norm).filter(Boolean)
  const words = full.trim().split(/\s+/)
  let i = 0
  for (const w of want) {
    while (i < words.length && !norm(words[i]!)) i++
    if (i >= words.length || norm(words[i]!) !== w) return null
    i++
  }
  return words.slice(i).join(" ")
}

const sleep = (ms: number): Promise<null> => new Promise((r) => setTimeout(() => r(null), ms))

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
  /** Speech since the last pause pass (does a pause deserve one?) and since the last pass of any
   *  kind (is a while-talking pass due?). One counter for both skipped the pause pass whenever a
   *  sentence ended < 250 ms after a while-talking pass. */
  private speechSincePause = 0
  private speechSinceRun = 0

  // The text. `locked` = finished sentences (and the user's own edits); `provisional` = the
  // current sentence as last transcribed, from `sentenceStart` up to `shownEnd`.
  private locked = ""
  private provisional = ""
  private sentenceStart = 0
  private shownEnd = 0
  private lastSent = ""
  /** Each sent text's number, and how far into the audio that text reached. */
  private textSeq = 0
  private readonly covered = new Map<number, number>()

  // Passes: one at a time, at most one waiting.
  // Passes: one at a time per lane, at most one waiting. The pauses' lane ("main") and the
  // while-talking lane ("live") run side by side; the newest result wins.
  private inFlight: Promise<void> | null = null
  private pending: { kind: PassKind; end: number } | null = null
  private runInFlight: Promise<void> | null = null
  private runPending = false
  /** The newest pass whose text is shown, and whether that was a while-talking pass. */
  private appliedPassNo = 0
  private shownByRun = false
  /** Bumped by a hand edit and by cancel: a pass from before it must not write its answer. */
  private generation = 0

  // Spoken-edit checks, one at a time, in order.
  private edits: Promise<void> = Promise.resolve()
  private editsOut = 0

  private state: "live" | "stopping" | "done" = "live"
  /** Resolves when Stop begins: a pass stops waiting for Smart Turn then (Stop keeps every word anyway). */
  private stopping!: () => void
  private readonly stopSignal = new Promise<void>((r) => { this.stopping = r })
  private passes = 0
  /** Numbers each pass as it starts: a late verdict may lock only the newest pass's sentence. */
  private passSeq = 0

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
   * the text so far, and transcription carries on with the audio after what they were shown —
   * text [seq] when the app says which one (a newer text may have crossed their edit on the way),
   * else the last one sent.
   */
  edit(text: string, seq?: number): void {
    // Not once Stop has begun: the text is being finished, and starting over would drop `final`.
    if (this.state !== "live") return
    this.generation++
    this.locked = text.trim()
    this.provisional = ""
    this.sentenceStart = (seq !== undefined ? this.covered.get(seq) : undefined) ?? this.shownEnd
    // The user's text covers the audio up to there; anything said after it is still to come.
    this.shownEnd = this.sentenceStart
    this.shownByRun = false
    this.lastSent = this.locked
  }

  /** Stop: finish the text, send `final`, then `done` (after any late spoken edit). */
  async stop(): Promise<void> {
    if (this.state !== "live") return
    this.state = "stopping"
    this.stopping()
    const t0 = this.now()
    await this.vad.flush()
    // Not the while-talking lane: from here on its answers are dropped, and Stop's own pass covers
    // whatever it was transcribing.
    await this.whenIdle(false)
    if (this.isDone()) return
    if (!this.deps.livePasses) {
      this.locked = await this.wholeText()
    } else if (this.shownByRun || this.speechMs(this.shownEnd, this.frames * FRAME) >= this.t.stopSpeechMs) {
      // Words after the shown text (or a while-talking pass's, which may end mid-word): one more
      // pass, over the whole current sentence.
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
    this.runPending = false
    this.vad.close()
    this.deps.endStream?.()
  }

  /** Resolves when no pass is out or waiting (on the pauses' lane only, without [includeLive]). */
  async whenIdle(includeLive = true): Promise<void> {
    while (this.inFlight || (includeLive && this.runInFlight)) {
      await (this.inFlight ?? this.runInFlight)
    }
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
        this.speechSincePause++
        this.speechSinceRun++
      } else if (this.speaking) {
        this.silentFrames++
        if (this.silentFrames * FRAME_MS >= this.t.pauseMs) {
          this.speaking = false
          if (this.speechSincePause * FRAME_MS >= this.t.minSpeechMs) this.trigger("pause", this.frames * FRAME)
        }
      }
      // Talking on without a pause: counted in speech, so a silence before it does not count.
      if (this.speaking && this.speechSinceRun * FRAME_MS >= this.t.runPassMs) {
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
    this.speechSinceRun = 0
    if (kind === "pause") this.speechSincePause = 0
    if (this.state === "done") return
    if (kind === "run") {
      // Behind one already out, the next simply takes whatever audio there is by then.
      if (this.runInFlight) this.runPending = true
      else this.startRun()
      return
    }
    if (this.inFlight) {
      this.pending = { kind, end }
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

  private startRun(): void {
    this.runInFlight = this.pass("run", this.frames * FRAME)
      .catch((e) => log.warn("dictation_stream_pass_failed", { id: this.deps.id ?? null, err: String(e) }))
      .finally(() => {
        this.runInFlight = null
        if (this.runPending && this.state === "live") {
          this.runPending = false
          this.startRun()
        }
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
    const passNo = ++this.passSeq
    const t0 = this.now()
    // Diagnostics: how far the detector lagged the audio when it called this pass, and how long
    // Smart Turn and the engine each took (a late verdict is logged when it finally lands).
    const vadLagMs = Math.round((this.samples - end) / PER_MS)
    let turnMs: number | null = null
    let turnLate = false
    const verdict = kind === "pause"
      ? this.deps.turn(this.window(end)).then(
          (v) => {
            turnMs = this.now() - t0
            if (turnLate) log.info("dictation_stream_turn_late", { id: this.deps.id ?? null, turnMs, turn: Math.round(v * 1000) / 1000 })
            return v
          },
          (e) => {
            log.warn("dictation_stream_turn_failed", { id: this.deps.id ?? null, err: String(e) })
            return null
          },
        )
      : null
    const text = await this.runStt(start, end, false, kind === "run" ? "live" : "main")
    const sttMs = this.now() - t0
    if (this.isDone() || gen !== this.generation) return
    // A while-talking answer that lands once Stop has begun is dropped: Stop transcribes it all.
    if (kind === "run" && this.state !== "live") return
    // Only the newest answer on the current sentence is shown: an older one landing late, or one
    // for a sentence locked meanwhile, would take words back.
    const fresh = text !== null && passNo > this.appliedPassNo && this.sentenceStart === start
    if (fresh) {
      this.appliedPassNo = passNo
      this.shownByRun = kind === "run"
      this.provisional = text
      this.shownEnd = end
      this.emit()
    }
    let p: number | null = null
    let locked = false
    if (verdict) {
      // Nor past Stop: the verdict only places the next pass, and there is none after Stop.
      p = await Promise.race([verdict, sleep(this.t.turnGraceMs), this.stopSignal.then(() => null)])
      if (turnMs === null) {
        turnLate = true
        // Too late for this pass, but not wasted: when it lands and nothing has started since, it
        // still locks the sentence, so the next pass starts after it instead of redoing it. (On
        // the loaded box Smart Turn took 1.6–2.1 s against ~1 s of transcription, so no sentence
        // locked and passes grew to 17 s of audio and 3.9 s of transcription.)
        if (text !== null) void verdict.then((late) => this.lockLate(passNo, gen, start, end, text, late))
      }
      if (this.isDone() || gen !== this.generation) return
      const long = (end - start) / PER_MS >= this.t.maxSentenceMs
      // No verdict in time = not finished: a fragment locked mid-sentence costs more than a
      // sentence transcribed twice (seen on the loaded box: "I think we should. Move both models
      // into." when late verdicts counted as finished).
      // And only while this pass's text is still the newest shown: words shown since by a
      // while-talking pass belong to the same, still open, sentence.
      const newest = this.appliedPassNo === passNo && this.sentenceStart === start
      // A finished verdict with newer words already shown splits the sentence off them instead.
      if (text !== null && !newest && p !== null && p >= this.t.turnThreshold) this.lockLate(passNo, gen, start, end, text, p)
      if (text !== null && newest && ((p !== null && p >= this.t.turnThreshold) || long)) {
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
      sttMs, turnMs, vadLagMs,
      turn: p === null ? null : Math.round(p * 1000) / 1000, locked, chars: text?.length ?? null,
    })
  }

  /** A verdict that came after its pass: lock that pass's sentence if it is still the newest word on it. */
  private lockLate(passNo: number, gen: number, start: number, end: number, text: string, p: number | null): void {
    if (p === null || p < this.t.turnThreshold) return
    if (this.state !== "live" || gen !== this.generation || this.sentenceStart !== start) return
    let rest = ""
    if (this.appliedPassNo !== passNo) {
      // The user talked on and a newer text, from the same start, is already shown. It usually
      // begins with this sentence's words: lock them, and keep the rest as the open sentence (a
      // pass merely started is fine too — its answer, now for an old start, is dropped). On this
      // box Smart Turn answers 2.6–5.5 s late, so without this, sentences spoken back to back
      // never locked and the passes grew to 15 s of audio.
      const tail = this.shownEnd > end ? stripLeadingWords(this.provisional, text) : null
      if (tail === null) return
      rest = tail
    }
    const before = this.locked
    this.locked = join(this.locked, text)
    this.provisional = rest
    this.sentenceStart = end
    if (!rest) this.shownEnd = Math.max(this.shownEnd, end)
    log.info("dictation_stream_locked_late", { id: this.deps.id ?? null, audioMs: Math.round((end - start) / PER_MS), split: rest !== "" })
    this.emit()
    this.launchEditCheck(before, text)
  }

  /** Transcribe samples [start, end); null = the engine failed (the audio stays for the next pass). */
  private async runStt(start: number, end: number, final: boolean, lane: PassLane = "main"): Promise<string | null> {
    try {
      return (await this.deps.transcribe(this.pcm.slice(start, end), { context: this.locked, final, lane })).trim()
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
    this.sendText(text)
  }

  private sendText(text: string): void {
    this.lastSent = text
    const seq = ++this.textSeq
    this.covered.set(seq, this.shownEnd)
    this.covered.delete(seq - 32)
    this.deps.send({ type: "text", text, seq })
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
          this.sendText(this.locked)
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
