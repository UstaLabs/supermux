// Local whisper.cpp STT engine — universal fallback (and selectable primary).
// Wraps the low-level `transcribeAudio` helper so the rest of the pipeline only
// speaks `SttEngine`.

import { existsSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import { transcribeAudio, type TranscribeOpts } from "../whisper"
import type { SpawnFn, SttEngine, SttResult, SttTranscribeOpts } from "../stt-types"

const log = makeLogger("stt-whisper")

const DEFAULT_MODEL = process.env.MUX_WHISPER_MODEL ?? join(homedir(), ".cache", "whisper-models", "ggml-base.bin")
const WHISPER_BIN = process.env.MUX_WHISPER_BIN ?? join(homedir(), ".local", "bin", "whisper-cli")

export interface WhisperEngineOpts {
  spawn?: SpawnFn
  /** Override availability probe (tests). */
  isAvailable?: () => boolean
  /** Injected low-level transcribe (tests). */
  transcribeAudio?: (audioPath: string, opts: TranscribeOpts) => Promise<{ text: string }>
  modelPath?: string
  binPath?: string
}

export function whisperEngine(opts: WhisperEngineOpts = {}): SttEngine {
  const modelDefault = opts.modelPath ?? DEFAULT_MODEL
  const bin = opts.binPath ?? WHISPER_BIN
  const run = opts.transcribeAudio ?? transcribeAudio

  return {
    name: "whisper",
    prefersCleanup: true,

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      // Bin is required; model path may be overridden per-call so only require bin.
      return existsSync(bin)
    },

    async transcribe(audioPath: string, tOpts: SttTranscribeOpts = {}): Promise<SttResult> {
      const model = tOpts.model || modelDefault
      log.info("whisper_transcribe", { audioPath, model, lang: tOpts.lang ?? "auto" })
      const r = await run(audioPath, {
        model,
        lang: tOpts.lang,
        spawn: opts.spawn,
      })
      return {
        text: r.text,
        prefersCleanup: true,
        model,
      }
    },
  }
}
