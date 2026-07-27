// STT engine registry + orchestrator.
//
// Callers (main.ts /transcribe) pick an engine by name; unknown/unavailable
// engines fall back to local whisper so voice never hard-fails solely because
// a cloud backend is offline. Adding a new engine = one file under engines/ +
// a registry entry here.

import { makeLogger } from "../../shared/log"
import { codexRealtimeEngine, type CodexRealtimeEngineOpts } from "./engines/codex-realtime"
import { whisperEngine, type WhisperEngineOpts } from "./engines/whisper"
import {
  DEFAULT_STT_ENGINE,
  FALLBACK_STT_ENGINE,
  STT_ENGINES,
  type SpawnFn,
  type SttEngine,
  type SttEngineName,
  type SttResult,
  type SttTranscribeOpts,
} from "./stt-types"

const log = makeLogger("stt")

/** Re-exported so callers can `import { STT_ENGINES } from "./stt"`. */
export { STT_ENGINES, FALLBACK_STT_ENGINE, DEFAULT_STT_ENGINE, type SttEngineName }
// Future engines (not yet registered): "openai-batch" | …

export interface SelectSttOpts {
  /** Whisper-specific injectables (spawn, availability). */
  whisper?: WhisperEngineOpts
  /** Codex Realtime injectables (fetch, auth, WebSocket). */
  codexRealtime?: CodexRealtimeEngineOpts
  /**
   * Optional full engine instances keyed by name. Used by tests and by
   * future hosts that want to inject a pre-built engine without registering
   * it globally. Checked before the built-in registry (exact name match).
   */
  overrides?: Record<string, SttEngine>
}

const registry: Record<SttEngineName, (o: SelectSttOpts) => SttEngine> = {
  "codex-realtime": (o) => codexRealtimeEngine(o.codexRealtime),
  whisper: (o) => whisperEngine(o.whisper),
}

/**
 * Resolve an engine string to an `SttEngine`. Unknown names degrade to whisper
 * so a bad config row never breaks transcription.
 */
export function selectStt(engine: SttEngineName | string, opts: SelectSttOpts = {}): SttEngine {
  if (opts.overrides?.[engine]) return opts.overrides[engine]!
  const name = (STT_ENGINES as readonly string[]).includes(engine) ? (engine as SttEngineName) : FALLBACK_STT_ENGINE
  if (name !== engine) log.warn("stt_unknown_engine", { engine, using: name })
  // Prefer an override for the resolved name too (tests inject "whisper").
  if (opts.overrides?.[name]) return opts.overrides[name]!
  return registry[name](opts)
}

/** Env default; app-config may override at call time. */
export const VOICE_STT_ENGINE: SttEngineName = ((): SttEngineName => {
  const e = process.env.MUX_VOICE_STT_ENGINE
  return e && (STT_ENGINES as readonly string[]).includes(e) ? (e as SttEngineName) : DEFAULT_STT_ENGINE
})()

export interface RunSttOpts extends SttTranscribeOpts {
  /** Preferred engine; defaults to VOICE_STT_ENGINE. */
  engine?: SttEngineName | string
  /** Injectables forwarded to `selectStt`. */
  select?: SelectSttOpts
  /**
   * When true (default), fall back to whisper if the primary engine is
   * unavailable or throws. Set false in tests that assert hard failure.
   */
  fallback?: boolean
}

export interface RunSttResult extends SttResult {
  /** Engine that produced the text. */
  engine: string
  /** True when a secondary engine was used after primary failure/unavailability. */
  fellBack?: boolean
}

/**
 * Run STT with optional fallback to whisper. THROWS only when every attempted
 * engine fails (caller may then surface an error to the client).
 */
export async function runStt(audioPath: string, opts: RunSttOpts = {}): Promise<RunSttResult> {
  const want = opts.engine ?? VOICE_STT_ENGINE
  const selectOpts = opts.select ?? {}
  const allowFallback = opts.fallback !== false
  const tOpts: SttTranscribeOpts = { model: opts.model, lang: opts.lang, signal: opts.signal }

  const primary = selectStt(want, selectOpts)
  const tryOne = async (engine: SttEngine): Promise<RunSttResult> => {
    const r = await engine.transcribe(audioPath, tOpts)
    return {
      text: r.text,
      prefersCleanup: r.prefersCleanup,
      model: r.model,
      engine: engine.name,
    }
  }

  if (primary.isAvailable()) {
    try {
      return await tryOne(primary)
    } catch (e) {
      log.warn("stt_primary_failed", { engine: primary.name, err: String(e) })
      if (!allowFallback || primary.name === FALLBACK_STT_ENGINE) throw e
    }
  } else {
    log.info("stt_primary_unavailable", { engine: primary.name })
    if (!allowFallback || primary.name === FALLBACK_STT_ENGINE) {
      throw new Error(`stt engine unavailable: ${primary.name}`)
    }
  }

  const fallback = selectStt(FALLBACK_STT_ENGINE, selectOpts)
  if (!fallback.isAvailable()) throw new Error(`stt fallback unavailable: ${fallback.name}`)
  const r = await tryOne(fallback)
  return { ...r, fellBack: true }
}

// Re-exports for callers that only need the types.
export type { SpawnFn, SttEngine, SttResult, SttTranscribeOpts }
