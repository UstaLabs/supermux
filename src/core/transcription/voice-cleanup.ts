// Voice cleanup orchestration. Turns a rough speech-to-text draft into the user's
// intended message via the direct-API agent adapter layer (src/core/agent-api).
//
// Strategy: pick the configured engine (default Codex — the officially sanctioned
// ChatGPT-subscription path), and if it is unavailable or fails, fall back to the
// cursor-agent CLI one-shot (reliable under the systemd broker). THROWS only if BOTH
// the selected engine and the cursor-cli fallback fail → the caller keeps the raw
// draft. Returns the cleaned text + which engine produced it.

import { makeLogger } from "../../shared/log"
import {
  FALLBACK_ENGINE,
  VOICE_CLEANUP_ENGINE,
  VOICE_CLEANUP_MODEL as ENV_VOICE_CLEANUP_MODEL,
  select,
  type Engine,
} from "../agent-api/index"
import { type CleanupInput, buildCleanupPrompt } from "../agent-api/prompt"
import { DEFAULT_TIMEOUT_MS, type FetchFn, type ReadFileFn } from "../agent-api/types"
import type { RunFn } from "../agent-api/adapters/cursor-cli"

const log = makeLogger("voice-cleanup")

// Re-exported from the canonical agent-api modules so existing importers (main.ts,
// the voice-cleanup tests) keep a stable entry point.
export { type CleanupInput, buildCleanupPrompt }
export { VOICE_CLEANUP_ENGINE } from "../agent-api/index"

// For logging/visibility: the model the cleanup uses when none is configured.
export const VOICE_CLEANUP_MODEL = ENV_VOICE_CLEANUP_MODEL ?? ""

export interface CleanupOpts {
  // The engine to try first; defaults to VOICE_CLEANUP_ENGINE (env || "codex").
  engine?: Engine | string
  // Override the model passed to the engine; defaults to VOICE_CLEANUP_MODEL.
  model?: string
  timeoutMs?: number
  // Injectables for tests / the broker — forwarded into the selected adapters.
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  run?: RunFn
}

// Cleaned text + the engine name that produced it ("none" for an empty draft).
export interface CleanupResult {
  text: string
  engine: string
}

// Selected engine first, cursor-cli fallback. THROWS only if BOTH fail.
export async function cleanupDraft(input: CleanupInput, opts: CleanupOpts = {}): Promise<CleanupResult> {
  if (!input.draft.trim()) return { text: "", engine: "none" }

  const prompt = buildCleanupPrompt(input)
  const model = opts.model ?? (VOICE_CLEANUP_MODEL || undefined)
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const seam = { fetchFn: opts.fetchFn, readFileFn: opts.readFileFn, run: opts.run }
  const engine = opts.engine ?? VOICE_CLEANUP_ENGINE

  // PRIMARY: the configured engine, when its creds (and any opt-in gate) are present.
  if (engine !== FALLBACK_ENGINE) {
    const primary = select(engine, seam)
    if (primary.isAvailable()) {
      try {
        const text = (await primary.complete(prompt, { model, timeoutMs })).trim()
        if (text) return { text, engine: primary.name }
        log.warn("voice_cleanup_empty", { engine: primary.name })
      } catch (e) {
        log.warn("voice_cleanup_primary_failed", { engine: primary.name, err: String(e) })
      }
    } else {
      log.info("voice_cleanup_primary_unavailable", { engine: primary.name })
    }
  }

  // FALLBACK: cursor-cli one-shot. If this throws, the caller keeps the raw draft.
  const fallback = select(FALLBACK_ENGINE, seam)
  const text = (await fallback.complete(prompt, { model, timeoutMs })).trim()
  return { text, engine: fallback.name }
}
