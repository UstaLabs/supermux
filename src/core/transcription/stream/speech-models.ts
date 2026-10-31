// Silero VAD and Smart Turn for the dictation stream, run in worker threads
// (speech-models-worker.ts) so neither ever blocks the broker's main thread.
//
// One worker per model. They shared one for a day to save ~87 MB, but on the loaded box a Smart
// Turn check took up to 5 s, and the speech frames queued behind it put the pause detector 1.4 s
// behind the audio — and the pause detector is what every transcription waits for. The workers
// start with the first dictation rather than at boot (the box is short of memory), warm up while
// the user says their first words, and stop after [IDLE_MS] with no dictation.

import { IS_COMPILED } from "../../../shared/build-info"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("speech-models")

export type ModelRole = "vad" | "turn"

/** Main thread → worker. */
export type ToWorker =
  | { op: "init"; role: ModelRole }
  | { op: "open"; id: string }
  | { op: "close"; id: string }
  | { op: "push"; id: string; seq: number; pcm: Int16Array }
  | { op: "turn"; reqId: number; audio: Float32Array }

/** Worker → main thread. */
export type FromWorker =
  | { op: "ready" }
  | { op: "failed"; message: string }
  | { op: "vad"; id: string; seq: number; probs: Float32Array }
  | { op: "turn"; reqId: number; p: number; ms: number }
  | { op: "error"; id?: string; reqId?: number; message: string }

/** One dictation's speech detector. */
export interface VadStream {
  /** Queue 16 kHz mono s16 audio; one probability per 512-sample frame arrives through `onProbs`, in order. */
  push(pcm: Int16Array): void
  /** Resolves once everything pushed so far has been scored (or the detector failed). */
  flush(): Promise<void>
  close(): void
}

export interface SpeechModels {
  /** A speech detector for one dictation. `onError` = it stopped working (the worker died). */
  openVad(onProbs: (probs: Float32Array) => void, onError: (err: Error) => void): VadStream
  /** Smart Turn: the probability that the speaker has finished, for 16 kHz audio ending now. */
  turn(audio: Float32Array): Promise<number>
}

/** The part of a Worker this module uses; a parameter so tests can run without threads. */
export interface WorkerLike {
  postMessage(msg: ToWorker, transfer?: ArrayBuffer[]): void
  onmessage: ((ev: MessageEvent<FromWorker>) => void) | null
  onerror: ((ev: ErrorEvent) => void) | null
  terminate(): void
}

/** Stop the workers after this long with no dictation and no turn check out. */
export const IDLE_MS = 30 * 60_000
/** After a worker failed to start, fail fast for this long instead of retrying on every dictation. */
const FAILED_BACKOFF_MS = 60_000

/**
 * Where the worker's code is. From source, next to this file. In a compiled binary this module is
 * bundled into the entry (src/cli.ts, at the root of $bunfs), and the worker — compiled as a
 * second entrypoint by scripts/build-binary.sh — sits at its path under src/, as .js.
 */
const workerUrl = (): string =>
  IS_COMPILED
    ? new URL("./core/transcription/stream/speech-models-worker.js", import.meta.url).href
    : new URL("./speech-models-worker.ts", import.meta.url).href

const spawnWorker = (): WorkerLike => new Worker(workerUrl()) as unknown as WorkerLike

interface VadEntry {
  onProbs: (probs: Float32Array) => void
  onError: (err: Error) => void
  pushed: number
  scored: number
  waiters: { seq: number; resolve: () => void }[]
}

/** One model's worker and whether it has died. */
class ModelWorker {
  readonly worker: WorkerLike
  dead: Error | null = null

  constructor(
    readonly role: ModelRole,
    spawn: () => WorkerLike,
    onMessage: (m: FromWorker) => void,
    private readonly onDeath: (err: Error) => void,
  ) {
    this.worker = spawn()
    this.worker.onmessage = (ev) => {
      const m = ev.data
      if (m.op === "ready") log.info("speech_model_ready", { role })
      else if (m.op === "failed") this.die(new Error(`${role} model failed to load: ${m.message}`))
      else onMessage(m)
    }
    this.worker.onerror = (ev) => this.die(new Error(`${role} worker error: ${ev?.message ?? "unknown"}`))
    this.worker.postMessage({ op: "init", role })
  }

  post(msg: ToWorker, transfer?: ArrayBuffer[]): void {
    if (!this.dead) this.worker.postMessage(msg, transfer)
  }

  die(err: Error): void {
    if (this.dead) return
    this.dead = err
    log.warn("speech_model_dead", { role: this.role, err: err.message })
    try { this.worker.terminate() } catch { /* already gone */ }
    this.onDeath(err)
  }
}

export class WorkerSpeechModels implements SpeechModels {
  private readonly workers: Record<ModelRole, ModelWorker | null> = { vad: null, turn: null }
  private readonly streams = new Map<string, VadEntry>()
  private readonly turns = new Map<number, { resolve: (p: number) => void; reject: (e: Error) => void }>()
  private nextId = 0
  private idle: ReturnType<typeof setTimeout> | null = null
  private readonly failedUntil: Record<ModelRole, number> = { vad: 0, turn: 0 }
  private readonly lastFailure: Record<ModelRole, Error | null> = { vad: null, turn: null }

  constructor(
    private readonly spawn: () => WorkerLike = spawnWorker,
    private readonly idleMs: number = IDLE_MS,
    private readonly now: () => number = Date.now,
  ) {}

  openVad(onProbs: (probs: Float32Array) => void, onError: (err: Error) => void): VadStream {
    const id = `v${++this.nextId}`
    const entry: VadEntry = { onProbs, onError, pushed: 0, scored: 0, waiters: [] }
    let worker: ModelWorker | null = null
    try {
      worker = this.worker("vad")
    } catch (e) {
      queueMicrotask(() => onError(e as Error))
    }
    if (worker) {
      this.streams.set(id, entry)
      worker.post({ op: "open", id })
    }
    // A dictation that will pause wants its turn model warm by then.
    try { this.worker("turn") } catch { /* its failure shows on the first turn check */ }
    this.touch()
    return {
      push: (pcm) => {
        if (!worker || worker.dead || !this.streams.has(id) || pcm.length === 0) return
        const copy = pcm.slice()
        worker.post({ op: "push", id, seq: ++entry.pushed, pcm: copy }, [copy.buffer])
      },
      flush: () => {
        if (!worker || worker.dead || !this.streams.has(id) || entry.scored >= entry.pushed) return Promise.resolve()
        return new Promise<void>((resolve) => entry.waiters.push({ seq: entry.pushed, resolve }))
      },
      close: () => {
        if (!this.streams.delete(id)) return
        for (const w of entry.waiters.splice(0)) w.resolve()
        worker?.post({ op: "close", id })
        this.touch()
      },
    }
  }

  turn(audio: Float32Array): Promise<number> {
    let worker: ModelWorker
    try {
      worker = this.worker("turn")
    } catch (e) {
      return Promise.reject(e)
    }
    const reqId = ++this.nextId
    const copy = audio.slice()
    const p = new Promise<number>((resolve, reject) => this.turns.set(reqId, { resolve, reject }))
    this.touch()
    worker.post({ op: "turn", reqId, audio: copy }, [copy.buffer])
    return p.finally(() => this.touch())
  }

  /** Stop both workers now (tests, shutdown). */
  dispose(): void {
    if (this.idle) clearTimeout(this.idle)
    this.idle = null
    for (const role of ["vad", "turn"] as const) {
      const w = this.workers[role]
      this.workers[role] = null
      if (w && !w.dead) {
        w.dead = new Error("disposed")
        try { w.worker.terminate() } catch { /* gone */ }
      }
    }
  }

  private worker(role: ModelRole): ModelWorker {
    const current = this.workers[role]
    if (current && !current.dead) return current
    const failed = this.lastFailure[role]
    if (failed && this.now() < this.failedUntil[role]) throw failed
    const w = new ModelWorker(role, this.spawn, (m) => this.onMessage(m), (err) => {
      if (this.workers[role] === w) this.workers[role] = null
      // A worker that never came up backs off; one that died mid-dictation is retried at once.
      if (/failed to load|worker error/.test(err.message)) {
        this.lastFailure[role] = err
        this.failedUntil[role] = this.now() + FAILED_BACKOFF_MS
      }
      if (role === "vad") {
        for (const [id, e] of this.streams) {
          this.streams.delete(id)
          for (const waiter of e.waiters.splice(0)) waiter.resolve()
          e.onError(err)
        }
      } else {
        for (const [id, t] of this.turns) {
          this.turns.delete(id)
          t.reject(err)
        }
      }
    })
    this.workers[role] = w
    return w
  }

  private onMessage(m: FromWorker): void {
    if (m.op === "vad") {
      const e = this.streams.get(m.id)
      if (!e) return
      e.scored = m.seq
      e.onProbs(m.probs)
      const left: VadEntry["waiters"] = []
      for (const w of e.waiters) (w.seq <= e.scored ? w.resolve() : left.push(w))
      e.waiters = left
    } else if (m.op === "turn") {
      const t = this.turns.get(m.reqId)
      this.turns.delete(m.reqId)
      t?.resolve(m.p)
    } else if (m.op === "error") {
      if (m.id) {
        const e = this.streams.get(m.id)
        if (!e) return
        this.streams.delete(m.id)
        for (const w of e.waiters.splice(0)) w.resolve()
        e.onError(new Error(m.message))
      } else if (m.reqId !== undefined) {
        const t = this.turns.get(m.reqId)
        this.turns.delete(m.reqId)
        t?.reject(new Error(m.message))
      }
    }
  }

  /** (Re)start the idle countdown when nothing is using the workers; cancel it otherwise. */
  private touch(): void {
    if (this.idle) clearTimeout(this.idle)
    this.idle = null
    if (this.streams.size > 0 || this.turns.size > 0) return
    if (!this.workers.vad && !this.workers.turn) return
    this.idle = setTimeout(() => {
      log.info("speech_models_idle_stop", {})
      this.dispose()
    }, this.idleMs)
    ;(this.idle as any)?.unref?.()
  }
}

let shared: WorkerSpeechModels | null = null

/** The broker's speech-model workers. */
export function speechModels(): SpeechModels {
  return (shared ??= new WorkerSpeechModels())
}
