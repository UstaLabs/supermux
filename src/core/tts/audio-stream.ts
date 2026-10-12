// Read-aloud as ONE progressive MP3 at a URL: the clients hand the URL to the platform's own media
// player (<audio>, AVPlayer, MediaPlayer, ffplay), which starts on the first bytes — about 1 s after
// the tap, since the codex endpoint streams its MP3 as it synthesizes.
//
// Synthesis runs once per stream, into a buffer; every GET replays the buffer from byte 0 and then
// follows it live. That matters because players open a URL more than once (Safari/AVPlayer probe
// with a Range request first, then reconnect), and none of them may trigger a second synthesis.
//
// Codex pieces are raw MP3 frames (no ID3/Xing header — checked 2026-10-10), so concatenating the
// pieces of a long message is itself a valid MP3.

import { randomBytes } from "crypto"
import { makeLogger } from "../../shared/log"
import { plainTextForSpeech, splitForTts } from "./plain-text"
import { CODEX_PRONUNCIATION_MAX_CHARS, type TtsEngine, type TtsSpeakOpts } from "./tts-types"

const log = makeLogger("tts-audio")

/** Append-only bytes with any number of readers that replay from the start and follow live. */
export class ByteRelay {
  private parts: Uint8Array[] = []
  private ended = false
  private error: unknown = undefined
  private wake: (() => void)[] = []

  get done(): boolean {
    return this.ended
  }

  push(chunk: Uint8Array): void {
    if (this.ended || !chunk.byteLength) return
    this.parts.push(chunk)
    this.notify()
  }

  end(error?: unknown): void {
    if (this.ended) return
    this.ended = true
    this.error = error
    this.notify()
  }

  async *read(): AsyncGenerator<Uint8Array> {
    let i = 0
    while (true) {
      while (i < this.parts.length) yield this.parts[i++]!
      if (this.ended) {
        if (this.error !== undefined) throw this.error
        return
      }
      await new Promise<void>((r) => this.wake.push(r))
    }
  }

  private notify(): void {
    const w = this.wake
    this.wake = []
    for (const r of w) r()
  }
}

/**
 * Synthesize [chunks] in order into [out]. Piece n+1 starts when piece n starts streaming and is
 * buffered on the side, so it is already there when n ends — no gap between pieces.
 */
async function pump(engine: TtsEngine, chunks: string[], out: ByteRelay, opts: TtsSpeakOpts): Promise<void> {
  const start = (text: string): ByteRelay => {
    const relay = new ByteRelay()
    ;(async () => {
      const s = await engine.speakStream!(text, opts)
      for await (const c of s.body) relay.push(c)
    })().then(() => relay.end(), (e) => relay.end(e ?? new Error("tts piece failed")))
    return relay
  }
  try {
    let current = start(chunks[0]!)
    for (let i = 0; i < chunks.length; i++) {
      const next = i + 1 < chunks.length ? start(chunks[i + 1]!) : null
      for await (const c of current.read()) out.push(c)
      if (!next) break
      current = next
    }
    out.end()
  } catch (e) {
    out.end(e)
  }
}

interface Entry {
  relay: ByteRelay
  abort: AbortController
  readers: number
  createdAt: number
  idleTimer?: ReturnType<typeof setTimeout>
}

export interface TtsAudioStreamsOpts {
  /** Abandon synthesis when nobody has been listening for this long (the user tapped stop). */
  idleMs?: number
  /** Forget a stream this long after it was started. */
  ttlMs?: number
  /** At most this many streams are kept; the oldest is dropped first. */
  max?: number
}

export class TtsAudioStreams {
  private entries = new Map<string, Entry>()
  private readonly idleMs: number
  private readonly ttlMs: number
  private readonly max: number

  constructor(opts: TtsAudioStreamsOpts = {}) {
    this.idleMs = opts.idleMs ?? 20_000
    this.ttlMs = opts.ttlMs ?? 10 * 60_000
    this.max = opts.max ?? 8
  }

  /**
   * Start synthesizing [text] (markdown is flattened) and return the stream id. Returns at once —
   * the player's GET races the first synthesis bytes, so no round trip is spent waiting here.
   */
  start(engine: TtsEngine, text: string, opts: Omit<TtsSpeakOpts, "signal"> = {}): string {
    if (!engine.speakStream) throw new Error(`tts engine ${engine.name} cannot stream`)
    const plain = plainTextForSpeech(text)
    if (!plain) throw new Error("tts: empty text after flatten")
    this.sweep()

    const id = randomBytes(18).toString("base64url")
    const abort = new AbortController()
    const entry: Entry = { relay: new ByteRelay(), abort, readers: 0, createdAt: Date.now() }
    this.entries.set(id, entry)
    const chunks = splitForTts(plain, CODEX_PRONUNCIATION_MAX_CHARS)
    log.info("tts_audio_start", { engine: engine.name, chars: plain.length, pieces: chunks.length })
    void pump(engine, chunks, entry.relay, { ...opts, signal: abort.signal }).then(() => {
      if (entry.relay.done) this.clearIdle(entry)
    })
    // Nobody ever connected (client crashed, or the player failed before its GET).
    this.armIdle(entry)
    return id
  }

  /** The MP3 bytes for [id] from the start, following live; null when unknown or expired. */
  open(id: string): ReadableStream<Uint8Array> | null {
    const entry = this.entries.get(id)
    if (!entry) return null
    entry.readers++
    this.clearIdle(entry)
    const it = entry.relay.read()
    let released = false
    const release = () => {
      if (released) return
      released = true
      entry.readers--
      if (entry.readers === 0) this.armIdle(entry)
    }
    return new ReadableStream<Uint8Array>({
      async pull(controller) {
        try {
          const n = await it.next()
          if (n.done) {
            release()
            controller.close()
          } else {
            controller.enqueue(n.value)
          }
        } catch (e) {
          release()
          controller.error(e)
        }
      },
      cancel() {
        release()
        void it.return(undefined)
      },
    })
  }

  private armIdle(entry: Entry): void {
    this.clearIdle(entry)
    if (entry.relay.done) return
    entry.idleTimer = setTimeout(() => {
      if (entry.readers === 0 && !entry.relay.done) {
        log.info("tts_audio_abandoned", {})
        entry.abort.abort()
      }
    }, this.idleMs)
  }

  private clearIdle(entry: Entry): void {
    if (entry.idleTimer) clearTimeout(entry.idleTimer)
    entry.idleTimer = undefined
  }

  private sweep(): void {
    const now = Date.now()
    for (const [id, e] of this.entries) {
      if (now - e.createdAt > this.ttlMs) this.drop(id, e)
    }
    while (this.entries.size >= this.max) {
      const [id, e] = this.entries.entries().next().value!
      this.drop(id, e)
    }
  }

  private drop(id: string, e: Entry): void {
    this.clearIdle(e)
    if (!e.relay.done) e.abort.abort()
    this.entries.delete(id)
  }
}
