/** Read-aloud backends. `platform` is client-native OS TTS (no broker call). */
export const TTS_ENGINES = ["platform", "codex"] as const
export type TtsEngineName = (typeof TTS_ENGINES)[number]

export const DEFAULT_TTS_ENGINE: TtsEngineName = "platform"

/** ChatGPT pronunciation endpoint hard limit (live-probed ~1200 chars). */
export const CODEX_PRONUNCIATION_MAX_CHARS = 1200

export interface TtsSpeakOpts {
  /** ISO language hint (en, tr, …). Optional. */
  lang?: string
  /** Speech rate (ChatGPT pronunciation uses 1.0 default). */
  speed?: number
  signal?: AbortSignal
}

export interface TtsResult {
  /** Audio bytes (e.g. MP3). */
  audio: Uint8Array
  mime: string
  engine: string
  /** True when text was split into multiple synthesis calls. */
  chunked?: boolean
}

export interface TtsEngine {
  name: string
  isAvailable(): boolean
  speak(text: string, opts?: TtsSpeakOpts): Promise<TtsResult>
}
