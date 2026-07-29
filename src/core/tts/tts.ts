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

/**
 * Run server-side TTS. Throws if engine is platform (client should speak) or
 * unavailable/fails. Chunks long text for codex pronunciation limit.
 */
export async function runTts(text: string, opts: RunTtsOpts = {}): Promise<TtsResult> {
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

  if (chunks.length === 1) {
    return engine.speak(chunks[0]!, opts)
  }

  log.info("tts_chunked", { engine: engine.name, chunks: chunks.length, chars: plain.length })
  const parts: Uint8Array[] = []
  let mime = "audio/mpeg"
  for (const c of chunks) {
    const r = await engine.speak(c, opts)
    parts.push(r.audio)
    mime = r.mime || mime
  }
  const total = parts.reduce((n, p) => n + p.byteLength, 0)
  const audio = new Uint8Array(total)
  let off = 0
  for (const p of parts) {
    audio.set(p, off)
    off += p.byteLength
  }
  return { audio, mime, engine: engine.name, chunked: true }
}
