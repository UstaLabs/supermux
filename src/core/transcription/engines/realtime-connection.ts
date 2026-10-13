// One OpenAI Realtime transcription WebSocket kept open for a whole dictation.
//
// A cold pass costs ~1–1.5 s before the model even starts (mint an ephemeral key, open the
// socket); on an open socket a pass is just append → commit → completed, ~0.4 s (measured
// 2026-10-13). The live dictation sends a pass at every pause, so each recording gets one
// connection, keyed by the client's dictation id, opened when the mic starts (warm) and reused
// by every pass until Stop or an idle timeout.
//
// Verified against the live API (2026-10-13): one session takes any number of commits, each
// answered by its own `…transcription.completed` carrying the committed item's id, and a
// `session.update` changes the transcription prompt between them.

import type { RealtimeWs } from "./codex-realtime"

/** Transcription settings of a session: what was minted, then what `session.update` set. */
export interface TranscriptionConfig {
  model: string
  language?: string
  prompt?: string
}

/** ~100 ms of 24 kHz mono s16le. */
const CHUNK_BYTES = 4_800

function sameConfig(a: TranscriptionConfig, b: TranscriptionConfig): boolean {
  return a.model === b.model && a.language === b.language && a.prompt === b.prompt
}

/** An OPEN realtime socket that transcribes one committed buffer at a time, in order. */
export class RealtimeConnection {
  private queue: Promise<unknown> = Promise.resolve()
  private dead: Error | null = null
  /** The pass waiting for its transcript: resolves on its item's completion. */
  private waiting: { itemId?: string; resolve: (t: string) => void; reject: (e: Error) => void } | null = null

  private constructor(private readonly ws: RealtimeWs, private config: TranscriptionConfig) {
    ws.addEventListener("message", (ev: any) => this.onMessage(ev))
    ws.addEventListener("error", (ev: any) => this.fail(new Error(`websocket error ${ev?.message ?? ""}`.trim())))
    ws.addEventListener("close", (ev: any) => this.fail(new Error(`websocket closed ${ev?.code ?? "?"}`)))
  }

  /** Connect and resolve once the socket is open. */
  static open(ws: RealtimeWs, config: TranscriptionConfig, timeoutMs: number): Promise<RealtimeConnection> {
    return new Promise((resolve, reject) => {
      let settled = false
      const timer = setTimeout(() => done(new Error(`connect timeout after ${timeoutMs}ms`)), timeoutMs)
      const done = (err?: Error) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        if (err) {
          try { ws.close() } catch { /* ignore */ }
          reject(err)
        } else resolve(new RealtimeConnection(ws, config))
      }
      ws.addEventListener("open", () => done())
      ws.addEventListener("error", (ev: any) => done(new Error(`websocket error ${ev?.message ?? ""}`.trim())))
      ws.addEventListener("close", (ev: any) => done(new Error(`websocket closed ${ev?.code ?? "?"} before open`)))
    })
  }

  get alive(): boolean {
    return this.dead === null
  }

  /**
   * Transcribe [pcm] (24 kHz mono s16le) with [config], after any pass already queued. Rejects
   * when the socket fails or the pass takes longer than [timeoutMs]; the connection is then dead.
   */
  transcribe(pcm: Uint8Array, config: TranscriptionConfig, timeoutMs: number): Promise<string> {
    const run = () => this.runPass(pcm, config, timeoutMs)
    const p = this.queue.then(run, run)
    this.queue = p.catch(() => {})
    return p
  }

  close(): void {
    this.fail(new Error("closed"))
    try { this.ws.close() } catch { /* ignore */ }
  }

  private runPass(pcm: Uint8Array, config: TranscriptionConfig, timeoutMs: number): Promise<string> {
    if (this.dead) return Promise.reject(this.dead)
    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        // A pass that never answers leaves the socket in an unknown state: retire it.
        this.fail(new Error(`pass timeout after ${timeoutMs}ms`))
      }, timeoutMs)
      this.waiting = {
        resolve: (t) => { clearTimeout(timer); resolve(t) },
        reject: (e) => { clearTimeout(timer); reject(e) },
      }
      try {
        if (!sameConfig(this.config, config)) {
          this.send({
            type: "session.update",
            session: { type: "transcription", audio: { input: { transcription: { ...config } } } },
          })
          this.config = config
        }
        for (let i = 0; i < pcm.length; i += CHUNK_BYTES) {
          const chunk = pcm.subarray(i, Math.min(i + CHUNK_BYTES, pcm.length))
          this.send({ type: "input_audio_buffer.append", audio: Buffer.from(chunk).toString("base64") })
        }
        this.send({ type: "input_audio_buffer.commit" })
      } catch (e) {
        this.fail(e instanceof Error ? e : new Error(String(e)))
      }
    })
  }

  private send(msg: unknown): void {
    this.ws.send(JSON.stringify(msg))
  }

  private onMessage(ev: any): void {
    let msg: any
    try {
      msg = JSON.parse(String(ev?.data ?? ev))
    } catch {
      return
    }
    const type = String(msg?.type ?? "")
    const w = this.waiting
    if (type === "error") {
      this.fail(new Error(msg?.error?.message ?? "realtime error"))
      return
    }
    if (!w) return
    if (type === "input_audio_buffer.committed") {
      if (!w.itemId) w.itemId = msg.item_id
      return
    }
    if (type === "conversation.item.input_audio_transcription.completed") {
      // Only this pass's item: a completion for another one cannot be ours.
      if (w.itemId && msg.item_id && msg.item_id !== w.itemId) return
      this.waiting = null
      w.resolve(transcriptOf(msg))
    }
  }

  private fail(err: Error): void {
    if (!this.dead) this.dead = err
    const w = this.waiting
    this.waiting = null
    w?.reject(err)
  }
}

function transcriptOf(msg: any): string {
  if (typeof msg?.transcript === "string") return msg.transcript.trim()
  for (const c of msg?.item?.content ?? []) {
    if (typeof c?.transcript === "string") return c.transcript.trim()
  }
  return ""
}

/** Timing of [DictationConnections]; a parameter so tests can shrink it. */
export interface DictationConnectionTiming {
  /** Close a connection nobody has used for this long (a discarded or abandoned recording). */
  idleMs: number
  /** Retire a connection this old, even in use; the next pass opens a fresh one. */
  maxAgeMs: number
}

const DEFAULT_TIMING: DictationConnectionTiming = { idleMs: 60_000, maxAgeMs: 15 * 60_000 }

interface Entry {
  conn: Promise<RealtimeConnection>
  openedAt: number
  idle: ReturnType<typeof setTimeout> | null
}

/** The open connection of each dictation in progress, keyed by the client's dictation id. */
export class DictationConnections {
  private readonly entries = new Map<string, Entry>()

  constructor(
    private readonly timing: DictationConnectionTiming = DEFAULT_TIMING,
    private readonly now: () => number = Date.now,
  ) {}

  /**
   * The open connection of dictation [key], opening one with [open] when there is none (or the
   * last one died or aged out). Concurrent callers share one opening.
   */
  async acquire(key: string, open: () => Promise<RealtimeConnection>): Promise<RealtimeConnection> {
    let e = this.entries.get(key)
    if (e && this.now() - e.openedAt > this.timing.maxAgeMs) {
      this.release(key)
      e = undefined
    }
    if (e) {
      const conn = await e.conn.catch(() => null)
      if (conn?.alive && this.entries.get(key) === e) {
        this.touch(key, e)
        return conn
      }
      if (this.entries.get(key) === e) this.release(key)
    }
    const entry: Entry = { conn: open(), openedAt: this.now(), idle: null }
    this.entries.set(key, entry)
    // A failed open must not stay cached: the next pass tries again.
    entry.conn.catch(() => { if (this.entries.get(key) === entry) this.entries.delete(key) })
    this.touch(key, entry)
    return entry.conn
  }

  /** Close dictation [key]'s connection (Stop, a failure, an idle timeout). */
  release(key: string): void {
    const e = this.entries.get(key)
    if (!e) return
    this.entries.delete(key)
    if (e.idle) clearTimeout(e.idle)
    e.conn.then((c) => c.close(), () => {})
  }

  get size(): number {
    return this.entries.size
  }

  private touch(key: string, e: Entry): void {
    if (e.idle) clearTimeout(e.idle)
    e.idle = setTimeout(() => { if (this.entries.get(key) === e) this.release(key) }, this.timing.idleMs)
    ;(e.idle as any)?.unref?.()
  }
}
