// Speech-to-text engine interface. Mirrors the agent-api cleanup adapter seam:
// each engine is a small, injectable unit selected by name so the broker can
// swap whisper / codex-realtime / future backends without touching the HTTP
// endpoint or client composers.

export interface SttTranscribeOpts {
  /** Engine-specific model id or path (e.g. ggml-base.bin path for whisper). */
  model?: string
  /** BCP-47 / whisper language code, or "auto". */
  lang?: string
  signal?: AbortSignal
}

export interface SttResult {
  text: string
  /**
   * When true, the voice pipeline should run the LLM cleanup pass on `text`.
   * Rough local models (whisper base) prefer cleanup; high-quality cloud STT
   * (e.g. gpt-4o-transcribe) usually does not.
   */
  prefersCleanup: boolean
  /** Model id/path the engine actually used, when known. */
  model?: string
}

/**
 * One STT backend. Implementations must be unit-testable (inject spawn/fetch/etc.
 * at construction) and must not assume a live network unless that is their path.
 */
export interface SttEngine {
  readonly name: string
  /** Default cleanup preference for this engine family (overridable per result). */
  readonly prefersCleanup: boolean
  /** Creds/binaries present — no network call. */
  isAvailable(): boolean
  /** Transcribe a local audio file path. THROWS on hard failure. */
  transcribe(audioPath: string, opts?: SttTranscribeOpts): Promise<SttResult>
}

/** Subprocess seam used by local engines (whisper + ffmpeg). */
export type SpawnFn = (cmd: string, args: string[]) => { exited: Promise<number> }

/** Registry allowlist of STT engine names (light module so settings can import it). */
export const STT_ENGINES = ["codex-realtime", "whisper"] as const
export type SttEngineName = (typeof STT_ENGINES)[number]
/** Universal local fallback when the primary engine is offline or fails. */
export const FALLBACK_STT_ENGINE: SttEngineName = "whisper"
/** Product default when neither env nor app-config selects an engine. */
export const DEFAULT_STT_ENGINE: SttEngineName = "codex-realtime"
