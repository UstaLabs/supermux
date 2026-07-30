// TTS orchestrator: pick engine, flatten markdown, chunk long text for codex.

import { makeLogger } from "../../shared/log"
import { codexPronunciationEngine, type CodexPronunciationEngineOpts } from "./engines/codex-pronunciation"
import { plainTextForSpeech, splitForTts } from "./plain-text"
import {
  CODEX_PRONUNCIATION_MAX_CHARS,
  DEFAULT_TTS_ENGINE,
  TTS_ENGINES,
  type TtsEngine,
  type TtsEngineName,
  type TtsResult,
  type TtsSpeakOpts,
} from "./tts-types"

const log = makeLogger("tts")

export {
  TTS_ENGINES,
  DEFAULT_TTS_ENGINE,
  CODEX_PRONUNCIATION_MAX_CHARS,
  plainTextForSpeech,
  splitForTts,
  type TtsEngineName,
  type TtsResult,
  type TtsSpeakOpts,
}

export interface SelectTtsOpts {
  codex?: CodexPronunciationEngineOpts
  overrides?: Record<string, TtsEngine>
}

const registry: Record<Exclude<TtsEngineName, "platform">, (o: SelectTtsOpts) => TtsEngine> = {
  codex: (o) => codexPronunciationEngine(o.codex),
}

export function selectTts(engine: TtsEngineName | string, opts: SelectTtsOpts = {}): TtsEngine | null {
  if (engine === "platform") return null // client-side only
  if (opts.overrides?.[engine]) return opts.overrides[engine]!
  if (engine === "codex") return registry.codex(opts)
  log.warn("tts_unknown_engine", { engine })
  return null
}

export const VOICE_TTS_ENGINE: TtsEngineName = ((): TtsEngineName => {
  const e = process.env.MUX_VOICE_TTS_ENGINE
  return e && (TTS_ENGINES as readonly string[]).includes(e) ? (e as TtsEngineName) : DEFAULT_TTS_ENGINE
})()

export interface RunTtsOpts extends TtsSpeakOpts {
  engine?: TtsEngineName | string
  select?: SelectTtsOpts
  /** When true (default), flatten markdown before synthesis. */
  flattenMarkdown?: boolean
}

export interface TtsStreamChunk extends TtsResult {
  /** 0-based index of this audio piece. */
  index: number
  /** Total number of pieces for this speak request. */
  total: number
}

function prepareTts(text: string, opts: RunTtsOpts): {
  engine: TtsEngine
  chunks: string[]
  plain: string
} {
  const want = opts.engine ?? VOICE_TTS_ENGINE
  if (want === "platform") {
    throw new Error("tts engine is platform — speak on the client")
  }
  const engine = selectTts(want, opts.select ?? {})
  if (!engine) throw new Error(`tts engine unavailable: ${want}`)
  if (!engine.isAvailable()) throw new Error(`tts engine unavailable: ${engine.name}`)

  const plain = opts.flattenMarkdown === false ? text.trim() : plainTextForSpeech(text)
  if (!plain) throw new Error("tts: empty text after flatten")

  const chunks =
    engine.name === "codex"
      ? splitForTts(plain, CODEX_PRONUNCIATION_MAX_CHARS)
      : [plain]

  return { engine, chunks, plain }
}

/**
 * Stream server-side TTS: yield each audio piece as soon as it is ready.
 * Pipelines one-ahead synthesis so chunk N+1 is already synthesizing while
 * chunk N is being sent to the client (and can start playing).
 */
export async function* runTtsStream(
  text: string,
  opts: RunTtsOpts = {},
): AsyncGenerator<TtsStreamChunk, void, unknown> {
  const { engine, chunks, plain } = prepareTts(text, opts)
  if (chunks.length > 1) {
    log.info("tts_stream", { engine: engine.name, chunks: chunks.length, chars: plain.length })
  }

  // Start first synth immediately; always keep the next one in flight after yield.
  let inflight = engine.speak(chunks[0]!, opts)
  for (let i = 0; i < chunks.length; i++) {
    if (i + 1 < chunks.length) {
      // Kick off N+1 before awaiting N's network/send path fully drains.
      const next = engine.speak(chunks[i + 1]!, opts)
      const r = await inflight
      yield {
        audio: r.audio,
        mime: r.mime || "audio/mpeg",
        engine: r.engine || engine.name,
        chunked: chunks.length > 1,
        index: i,
        total: chunks.length,
      }
      inflight = next
    } else {
      const r = await inflight
      yield {
        audio: r.audio,
        mime: r.mime || "audio/mpeg",
        engine: r.engine || engine.name,
        chunked: chunks.length > 1,
        index: i,
        total: chunks.length,
      }
    }
  }
}

/**
 * Buffer the full stream into one audio blob (tests / non-streaming callers).
 */
export async function runTts(text: string, opts: RunTtsOpts = {}): Promise<TtsResult> {
  const parts: Uint8Array[] = []
  let mime = "audio/mpeg"
  let engine = "unknown"
  let chunked = false
  for await (const c of runTtsStream(text, opts)) {
    parts.push(c.audio)
    mime = c.mime || mime
    engine = c.engine
    chunked = !!c.chunked || c.total > 1
  }
  if (parts.length === 1) {
    return { audio: parts[0]!, mime, engine, chunked }
  }
  const total = parts.reduce((n, p) => n + p.byteLength, 0)
  const audio = new Uint8Array(total)
  let off = 0
  for (const p of parts) {
    audio.set(p, off)
    off += p.byteLength
  }
  return { audio, mime, engine, chunked: true }
}
